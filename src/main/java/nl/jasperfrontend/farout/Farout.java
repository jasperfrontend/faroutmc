package nl.jasperfrontend.farout;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Farout implements ModInitializer {
	public static final String MOD_ID = "farout";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Overridable for testing: .\gradlew runClientShort passes -Dfarout.target=100 -Dfarout.split=10.
	public static final int TARGET = Integer.getInteger("farout.target", 10_000);
	public static final int SPLIT_INTERVAL = Integer.getInteger("farout.split", 1_000);
	private static final int ACTION_BAR_INTERVAL_TICKS = 10;

	private static final Map<UUID, Run> RUNS = new HashMap<>();

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.clientboundPlay().register(RunStatePayload.TYPE, RunStatePayload.CODEC);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			LiteralArgumentBuilder<CommandSourceStack> start = Commands.literal("start");
			for (Heading heading : Heading.values()) {
				start.then(Commands.literal(heading.id())
					.executes(ctx -> start(ctx.getSource().getPlayerOrException(), heading)));
			}
			dispatcher.register(Commands.literal("farout")
				.then(start)
				.then(Commands.literal("stop")
					.executes(ctx -> stop(ctx.getSource().getPlayerOrException()))));
		});

		ServerTickEvents.END_SERVER_TICK.register(Farout::tick);

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				Run run = endRun(player);
				if (run != null) {
					int progress = run.heading.progress(run.origin, player.blockPosition());
					player.sendSystemMessage(Component.literal(
						"Run failed at " + Format.blocks(progress) + " blocks (" + Format.time(run.ticks) + ")"
					).withStyle(ChatFormatting.RED));
				}
			}
		});

		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> {
			if (to.dimension() != Level.OVERWORLD && endRun(player) != null) {
				player.sendSystemMessage(Component.literal("Run cancelled: left the overworld")
					.withStyle(ChatFormatting.RED));
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> RUNS.remove(handler.getPlayer().getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> RUNS.clear());
	}

	private static int start(ServerPlayer player, Heading heading) {
		if (RUNS.containsKey(player.getUUID())) {
			player.sendSystemMessage(Component.literal("A run is already active. Use /farout stop first.")
				.withStyle(ChatFormatting.RED));
			return 0;
		}

		BlockPos origin = teleportToSpawn(player, heading.yaw);
		RUNS.put(player.getUUID(), new Run(heading, origin));
		sendRunState(player, true);
		player.sendSystemMessage(Component.literal(
			"Farout started: head " + heading.label + " for " + Format.blocks(TARGET) + " blocks."));
		return 1;
	}

	private static int stop(ServerPlayer player) {
		if (endRun(player) == null) {
			player.sendSystemMessage(Component.literal("No active run."));
			return 0;
		}

		player.sendOverlayMessage(Component.empty());
		teleportToSpawn(player, null);
		player.sendSystemMessage(Component.literal("Run stopped."));
		return 1;
	}

	/** Removes the player's run, if any, and tells their client to stop drawing run HUD. */
	private static Run endRun(ServerPlayer player) {
		Run run = RUNS.remove(player.getUUID());
		if (run != null) {
			sendRunState(player, false);
		}
		return run;
	}

	private static void sendRunState(ServerPlayer player, boolean active) {
		if (ServerPlayNetworking.canSend(player, RunStatePayload.TYPE)) {
			ServerPlayNetworking.send(player, new RunStatePayload(active));
		}
	}

	/** Teleports to a safe spot at world spawn and returns that block position. */
	private static BlockPos teleportToSpawn(ServerPlayer player, Float yaw) {
		ServerLevel overworld = player.level().getServer().overworld();
		LevelData.RespawnData spawn = overworld.getRespawnData();
		// Vanilla's new-player placement: loads the chunks and searches the spawn radius for a safe spot.
		BlockPos pos = player.adjustSpawnLocation(overworld, spawn.pos());

		player.teleportTo(overworld, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Set.of(),
			yaw != null ? yaw : spawn.yaw(), 0f, true);
		return pos;
	}

	private static void tick(MinecraftServer server) {
		Iterator<Map.Entry<UUID, Run>> it = RUNS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Run> entry = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				it.remove();
				continue;
			}

			Run run = entry.getValue();
			run.ticks++;
			int progress = run.heading.progress(run.origin, player.blockPosition());

			while (progress >= run.nextSplit && run.nextSplit < TARGET) {
				player.sendSystemMessage(Component.literal(
					"Split " + Format.blocks(run.nextSplit) + ": " + Format.time(run.ticks)
				).withStyle(ChatFormatting.GRAY));
				run.nextSplit += SPLIT_INTERVAL;
			}

			if (progress >= TARGET) {
				it.remove();
				sendRunState(player, false);
				finish(server, player, run);
				continue;
			}

			if (run.ticks % ACTION_BAR_INTERVAL_TICKS == 0) {
				player.sendOverlayMessage(Component.literal(counter(run.heading, progress, run.ticks)));
			}
		}
	}

	private static String counter(Heading heading, int progress, long ticks) {
		return "Farout · " + heading.label + " · " + Format.blocks(progress) + " / " + Format.blocks(TARGET)
			+ " · " + Format.time(ticks);
	}

	private static void finish(MinecraftServer server, ServerPlayer player, Run run) {
		String result = Format.blocks(TARGET) + " blocks in " + Format.time(run.ticks);

		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 100, 20));
		player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(result)));
		player.connection.send(new ClientboundSetTitleTextPacket(
			Component.literal("FAROUT!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
		player.sendOverlayMessage(Component.literal(counter(run.heading, TARGET, run.ticks)));
		player.sendSystemMessage(Component.literal("FAROUT! " + result).withStyle(ChatFormatting.GOLD));
		// Player#playSound excludes the player itself on the server side, so send the packet directly.
		player.connection.send(new ClientboundSoundPacket(
			BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE),
			SoundSource.MASTER, player.getX(), player.getY(), player.getZ(), 1f, 1f,
			player.getRandom().nextLong()));

		try {
			ResultsLog.append(run.heading, run.ticks, server.overworld().getSeed());
		} catch (IOException e) {
			LOGGER.error("Could not write results.csv", e);
			player.sendSystemMessage(Component.literal("Could not write results.csv: " + e.getMessage())
				.withStyle(ChatFormatting.RED));
		}
	}
}
