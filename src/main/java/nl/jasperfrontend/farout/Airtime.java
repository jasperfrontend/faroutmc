package nl.jasperfrontend.farout;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Abilities;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Airtime: after /farout start the player hangs high above the start area with the world frozen, to scout
 * which way looks best, then drops into the centre of it. Hovering is flight at speed 0, so they can look
 * around (and zoom) but not move.
 */
final class Airtime {
	/** Fall damage protection from the moment of the drop. */
	private static final int PROTECT_TICKS = 10 * 20;
	/** How far the player may drift (blocks) before being put back over the centre. */
	private static final double DRIFT = 0.3;

	private static final Map<UUID, State> AIRBORNE = new HashMap<>();
	/** Whether Airtime froze the game, so a freeze someone set with /tick stays put. */
	private static boolean weFroze;

	private static final class State {
		final double x, y, z;
		final boolean mayfly;
		final float flyingSpeed;
		int hoverLeft;
		int protectLeft = PROTECT_TICKS;
		/** Still on the way down; X and Z stay pinned over the centre until they land. */
		boolean falling = true;

		State(double x, double y, double z, int seconds, Abilities abilities) {
			this.hoverLeft = seconds * 20;
			this.x = x;
			this.y = y;
			this.z = z;
			this.mayfly = abilities.mayfly;
			this.flyingSpeed = abilities.getFlyingSpeed();
		}

		boolean hovering() {
			return hoverLeft > 0;
		}
	}

	private Airtime() {
	}

	/** Lifts the player {@code height} blocks above the hub centre and freezes the game until they drop. */
	static void start(ServerPlayer player, BlockPos centre, int height, int seconds) {
		cancel(player);
		ServerLevel overworld = player.level().getServer().overworld();
		double y = Math.min(centre.getY() + height, overworld.getMaxY() - 2);
		State state = new State(centre.getX() + 0.5, y, centre.getZ() + 0.5, seconds,
			player.getAbilities());
		AIRBORNE.put(player.getUUID(), state);

		player.teleportTo(overworld, state.x, state.y, state.z, Set.of(), Heading.NORTH.yaw, 0f, true);
		hover(player, true, state);

		ServerTickRateManager ticks = player.level().getServer().tickRateManager();
		if (!ticks.isFrozen()) {
			ticks.setFrozen(true);
			weFroze = true;
		}
	}

	/** Whether the player is up there or still covered by the drop's fall damage protection. */
	static boolean protects(ServerPlayer player) {
		return AIRBORNE.containsKey(player.getUUID());
	}

	/** Ends airtime early (stop, death, another start), giving back normal flight; returns whether there was any. */
	static boolean cancel(ServerPlayer player) {
		State state = AIRBORNE.remove(player.getUUID());
		if (state == null) {
			return false;
		}
		if (state.hovering()) {
			hover(player, false, state);
		}
		unfreezeIfDone(player.level().getServer());
		return true;
	}

	static void forget(MinecraftServer server, UUID id) {
		AIRBORNE.remove(id);
		unfreezeIfDone(server);
	}

	static void forgetAll() {
		AIRBORNE.clear();
		weFroze = false;
	}

	static void tick(MinecraftServer server) {
		Iterator<Map.Entry<UUID, State>> it = AIRBORNE.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, State> entry = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			State state = entry.getValue();
			if (player == null) {
				it.remove();
				continue;
			}

			if (state.hovering()) {
				tickHover(player, state);
				if (!state.hovering()) {
					drop(player, state);
				}
			} else {
				tickFall(player, state);
				if (--state.protectLeft <= 0) {
					it.remove();
				}
			}
		}
		unfreezeIfDone(server);
	}

	private static void tickHover(ServerPlayer player, State state) {
		// Double-tapping jump turns flight off; hold them up regardless, and back where they were.
		if (!player.getAbilities().flying) {
			hover(player, true, state);
		}
		if (drifted(player, state) || Math.abs(player.getY() - state.y) > DRIFT) {
			player.teleportTo(player.level().getServer().overworld(), state.x, state.y, state.z,
				Relative.ROTATION, 0f, 0f, false);
		}

		int ticksLeft = state.hoverLeft--;
		if (ticksLeft % 20 == 0) {
			int seconds = ticksLeft / 20;
			player.sendOverlayMessage(Component.literal("Airtime: " + seconds + "s").withStyle(ChatFormatting.AQUA));
			if (seconds <= 3) {
				sound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 1f);
			}
		}
	}

	private static void drop(ServerPlayer player, State state) {
		hover(player, false, state);
		player.sendOverlayMessage(Component.literal("Drop!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		sound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 2f);
		player.sendSystemMessage(Component.literal(
			"Walk through the portal of the direction you picked to start your run."));
	}

	private static void tickFall(ServerPlayer player, State state) {
		if (!state.falling) {
			return;
		}
		if (player.onGround()) {
			state.falling = false;
			return;
		}
		// Steering while falling could miss the centre; keep the fall straight down, speed and view intact.
		if (drifted(player, state)) {
			player.teleportTo(player.level().getServer().overworld(), state.x, 0, state.z,
				Relative.union(Relative.ROTATION, Set.of(Relative.Y, Relative.DELTA_Y)), 0f, 0f, false);
		}
	}

	private static boolean drifted(ServerPlayer player, State state) {
		return Math.abs(player.getX() - state.x) > DRIFT || Math.abs(player.getZ() - state.z) > DRIFT;
	}

	/** On: flight at speed 0, which holds the player still. Off: flight as it was before. */
	private static void hover(ServerPlayer player, boolean on, State state) {
		Abilities abilities = player.getAbilities();
		abilities.mayfly = on || state.mayfly;
		abilities.flying = on;
		abilities.setFlyingSpeed(on ? 0f : state.flyingSpeed);
		player.onUpdateAbilities();
	}

	private static void unfreezeIfDone(MinecraftServer server) {
		if (weFroze && AIRBORNE.values().stream().noneMatch(State::hovering)) {
			server.tickRateManager().setFrozen(false);
			weFroze = false;
		}
	}

	private static void sound(ServerPlayer player, SoundEvent sound, float pitch) {
		// Player#playSound excludes the player itself on the server side, so send the packet directly.
		player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
			SoundSource.MASTER, player.getX(), player.getY(), player.getZ(), 1f, pitch,
			player.getRandom().nextLong()));
	}
}
