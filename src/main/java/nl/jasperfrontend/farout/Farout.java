package nl.jasperfrontend.farout;

import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
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
	/** Players standing in the start area, picking a direction; value is the hub centre. */
	private static final Map<UUID, BlockPos> IN_HUB = new HashMap<>();
	/** Centre of the built hub, so its portals work without a command (e.g. after dying); null if none. */
	private static BlockPos hubCentre;
	private static boolean hubLookedUp;

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.clientboundPlay().register(RunStatePayload.TYPE, RunStatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LanePayload.TYPE, LanePayload.CODEC);
		Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, Identifier.fromNamespaceAndPath(MOD_ID, "power_up"),
			PowerUpRecipe.SERIALIZER);
		PowerUps.register();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			LiteralArgumentBuilder<CommandSourceStack> start = Commands.literal("start")
				.executes(ctx -> enterHub(ctx.getSource().getPlayerOrException()));
			for (Heading heading : Heading.values()) {
				start.then(Commands.literal(heading.id())
					.executes(ctx -> start(ctx.getSource().getPlayerOrException(), heading)));
			}
			LiteralArgumentBuilder<CommandSourceStack> settings = Commands.literal("settings")
				.executes(ctx -> showSettings(ctx.getSource()));
			settings.then(Commands.literal(Settings.PATH_WIDTH)
				.then(Commands.argument("blocks", IntegerArgumentType.integer(1, 64))
					.executes(ctx -> setSetting(ctx.getSource(), Settings.PATH_WIDTH,
						IntegerArgumentType.getInteger(ctx, "blocks")))));
			settings.then(Commands.literal(Settings.LEEWAY_BLOCKS)
				.then(Commands.argument("blocks", IntegerArgumentType.integer(0, 100_000))
					.executes(ctx -> setSetting(ctx.getSource(), Settings.LEEWAY_BLOCKS,
						IntegerArgumentType.getInteger(ctx, "blocks")))));
			settings.then(Commands.literal(Settings.AIRTIME_HEIGHT)
				.then(Commands.argument("blocks", IntegerArgumentType.integer(0, 300))
					.executes(ctx -> setSetting(ctx.getSource(), Settings.AIRTIME_HEIGHT,
						IntegerArgumentType.getInteger(ctx, "blocks")))));
			settings.then(Commands.literal(Settings.AIRTIME_SECONDS)
				.then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
					.executes(ctx -> setSetting(ctx.getSource(), Settings.AIRTIME_SECONDS,
						IntegerArgumentType.getInteger(ctx, "seconds")))));
			dispatcher.register(Commands.literal("farout")
				.then(start)
				.then(settings)
				.then(Commands.literal("stop")
					.executes(ctx -> stop(ctx.getSource().getPlayerOrException()))));
		});

		ServerTickEvents.END_SERVER_TICK.register(Farout::tick);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
			!(entity instanceof ServerPlayer player && source.is(DamageTypeTags.IS_FALL) && Airtime.protects(player)));

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				Airtime.cancel(player);
				leaveHub(player);
				PowerUps.clear(player);
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
			if (to.dimension() != Level.OVERWORLD) {
				Airtime.cancel(player);
				leaveHub(player);
			}
			if (to.dimension() != Level.OVERWORLD && endRun(player) != null) {
				player.sendSystemMessage(Component.literal("Run cancelled: left the overworld")
					.withStyle(ChatFormatting.RED));
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			PowerUps.unlockRecipes(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			PowerUps.clear(handler.getPlayer());
			RUNS.remove(handler.getPlayer().getUUID());
			IN_HUB.remove(handler.getPlayer().getUUID());
			Airtime.forget(server, handler.getPlayer().getUUID());
			SplitBoard.forget(handler.getPlayer().getUUID());
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			RUNS.clear();
			IN_HUB.clear();
			hubCentre = null;
			hubLookedUp = false;
			Airtime.forgetAll();
			SplitBoard.forgetAll();
			PowerUps.clearAll();
		});
	}

	/**
	 * /farout start: build the start area at spawn and drop the player into its centre to pick a lane, after
	 * a few seconds of airtime high above it to scout the directions.
	 */
	private static int enterHub(ServerPlayer player) {
		if (RUNS.containsKey(player.getUUID())) {
			player.sendSystemMessage(Component.literal("A run is already active. Use /farout stop first.")
				.withStyle(ChatFormatting.RED));
			return 0;
		}

		ServerLevel overworld = player.level().getServer().overworld();
		BlockPos centre = SpawnHub.centre(overworld);
		SpawnHub.build(overworld, centre);
		SpawnHub.clearEntities(overworld, centre);
		SpawnHub.labels(overworld, centre);
		hubCentre = centre;
		hubLookedUp = true;
		player.getInventory().clearContent();
		SplitBoard.clear(player);

		Settings settings = Settings.load();
		if (settings.airtimeHeight == 0) {
			player.teleportTo(overworld, centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5, Set.of(),
				Heading.NORTH.yaw, 0f, true);
			IN_HUB.put(player.getUUID(), centre);
			sendRunState(player, true);
			player.sendSystemMessage(Component.literal("Walk through a portal to pick your direction."));
			return 1;
		}
		// Up there they're not in the hub yet; tickHub takes them in once they land in the centre.
		leaveHub(player);
		Airtime.start(player, centre, settings.airtimeHeight, settings.airtimeSeconds);
		player.sendSystemMessage(Component.literal("Airtime! ").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
			.append(Component.literal("You've got " + settings.airtimeSeconds + " seconds up here with the world on pause. "
				+ "Look around and pick the direction you want to go, then drop into the start area.")
				.withStyle(ChatFormatting.WHITE)));
		return 1;
	}

	/** /farout start &lt;direction&gt;: skip the start area and start straight away from spawn. */
	private static int start(ServerPlayer player, Heading heading) {
		if (RUNS.containsKey(player.getUUID())) {
			player.sendSystemMessage(Component.literal("A run is already active. Use /farout stop first.")
				.withStyle(ChatFormatting.RED));
			return 0;
		}

		IN_HUB.remove(player.getUUID());
		Airtime.cancel(player);
		BlockPos origin = teleportToSpawn(player, heading.yaw);
		beginRun(player, heading, origin, heading.lateral(origin));
		return 1;
	}

	private static int showSettings(CommandSourceStack source) {
		Settings settings = Settings.load();
		source.sendSystemMessage(Component.literal("Farout settings (config/farout/settings.properties):"));
		source.sendSystemMessage(Component.literal("  " + Settings.PATH_WIDTH + ": " + settings.pathWidth
			+ " (default " + Settings.DEFAULT_PATH_WIDTH + ")"));
		source.sendSystemMessage(Component.literal("  " + Settings.LEEWAY_BLOCKS + ": " + settings.leewayBlocks
			+ " (default " + Settings.DEFAULT_LEEWAY_BLOCKS + ")"));
		source.sendSystemMessage(Component.literal("  " + Settings.AIRTIME_HEIGHT + ": " + settings.airtimeHeight
			+ " (default " + Settings.DEFAULT_AIRTIME_HEIGHT + ")"));
		source.sendSystemMessage(Component.literal("  " + Settings.AIRTIME_SECONDS + ": " + settings.airtimeSeconds
			+ " (default " + Settings.DEFAULT_AIRTIME_SECONDS + ")"));
		return 1;
	}

	private static int setSetting(CommandSourceStack source, String name, int value) {
		try {
			Settings.load().with(name, value).save();
		} catch (IOException e) {
			LOGGER.error("Could not write settings", e);
			source.sendSystemMessage(Component.literal("Could not save settings: " + e.getMessage())
				.withStyle(ChatFormatting.RED));
			return 0;
		}
		source.sendSystemMessage(Component.literal(name + " set to " + value + ", from your next run on."));
		return 1;
	}

	/** laneCentre: the sideways block coordinate the lane is centred on. */
	private static void beginRun(ServerPlayer player, Heading heading, BlockPos origin, int laneCentre) {
		// Start like a brand-new world: a freshly spawned player, clear skies, at the time a new world begins.
		resetToFreshSpawn(player);
		MinecraftServer server = player.level().getServer();
		CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(source, "time set 0");
		server.getCommands().performPrefixedCommand(source, "weather clear");

		Settings settings = Settings.load();
		Lane lane = new Lane(heading, laneCentre, settings.pathWidth, settings.leewayBlocks, player);
		RUNS.put(player.getUUID(), new Run(heading, origin, lane, PersonalBests.load(heading)));
		sendRunState(player, true);
		if (ServerPlayNetworking.canSend(player, LanePayload.TYPE)) {
			ServerPlayNetworking.send(player, lane.payload());
		}
		SplitBoard.show(player, heading);
		player.sendSystemMessage(Component.literal(
			"Farout started: head " + heading.label + " for " + Format.blocks(TARGET) + " blocks. Stay in the "
				+ settings.pathWidth + "-wide lane; " + Format.blocks(settings.leewayBlocks) + " blocks of leeway."));
	}

	/** Everything back to how a new player spawns into a new world, so nothing carries over between runs. */
	private static void resetToFreshSpawn(ServerPlayer player) {
		player.getInventory().clearContent();
		player.getEnderChestInventory().clearContent();
		PowerUps.clear(player);
		player.removeAllEffects();
		player.setHealth(player.getMaxHealth());
		player.setAbsorptionAmount(0f);
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(5f);
		player.setExperienceLevels(0);
		player.setExperiencePoints(0);
		player.clearFire();
		player.setTicksFrozen(0);
		player.setAirSupply(player.getMaxAirSupply());
		player.resetFallDistance();
		player.setArrowCount(0);
		player.setStingerCount(0);
	}

	/** Takes the player out of the start area, if they're in it; returns whether they were. */
	private static boolean leaveHub(ServerPlayer player) {
		if (IN_HUB.remove(player.getUUID()) == null) {
			return false;
		}
		sendRunState(player, false);
		return true;
	}

	private static int stop(ServerPlayer player) {
		boolean hadBoard = SplitBoard.clear(player);
		if (Airtime.cancel(player)) {
			// Down to the start area rather than leaving them hanging, or falling, wherever they were.
			leaveHub(player);
			ServerLevel overworld = player.level().getServer().overworld();
			BlockPos centre = SpawnHub.centre(overworld);
			player.teleportTo(overworld, centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5, Set.of(),
				Heading.NORTH.yaw, 0f, true);
			player.resetFallDistance();
			player.sendOverlayMessage(Component.empty());
			player.sendSystemMessage(Component.literal("Airtime cancelled."));
			return 1;
		}
		if (leaveHub(player)) {
			player.sendOverlayMessage(Component.empty());
			player.sendSystemMessage(Component.literal("Left the start area."));
			return 1;
		}
		if (endRun(player) == null) {
			// After a finish, death or cancel the splits stay on screen until the next start or this.
			player.sendSystemMessage(Component.literal(hadBoard ? "Splits cleared." : "No active run."));
			return hadBoard ? 1 : 0;
		}

		player.sendOverlayMessage(Component.empty());
		PowerUps.clear(player);
		// Everything carried was gathered during the run, so it goes with it (armour and offhand too).
		player.getInventory().clearContent();
		// Back to the middle of the start area; vanilla's spawn search could land on a lane wall.
		ServerLevel overworld = player.level().getServer().overworld();
		BlockPos centre = SpawnHub.centre(overworld);
		player.teleportTo(overworld, centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5, Set.of(),
			Heading.NORTH.yaw, 0f, true);
		player.sendSystemMessage(Component.literal("Run stopped. Inventory cleared."));
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
		// Every way a run ends comes through here, so the lane fog always clears.
		if (!active && ServerPlayNetworking.canSend(player, LanePayload.TYPE)) {
			ServerPlayNetworking.send(player, LanePayload.NONE);
		}
	}

	/** Teleports to a safe spot at world spawn and returns that block position. */
	private static BlockPos teleportToSpawn(ServerPlayer player, float yaw) {
		ServerLevel overworld = player.level().getServer().overworld();
		LevelData.RespawnData spawn = overworld.getRespawnData();
		// Vanilla's new-player placement: loads the chunks and searches the spawn radius for a safe spot.
		BlockPos pos = player.adjustSpawnLocation(overworld, spawn.pos());

		player.teleportTo(overworld, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Set.of(), yaw, 0f, true);
		return pos;
	}

	private static void tick(MinecraftServer server) {
		Airtime.tick(server);
		tickHub(server);
		PowerUps.tick(server);

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
				run.splits.add(run.ticks);
				Long delta = run.deltaAtLastSplit();
				player.sendSystemMessage(Component.literal(
					"Split " + Format.blocks(run.nextSplit) + ": " + Format.time(run.ticks)
				).withStyle(ChatFormatting.GRAY).append(SplitBoard.delta(delta)));
				SplitBoard.split(player, run.nextSplit, run.ticks, delta, false);
				run.nextSplit += SPLIT_INTERVAL;
			}

			if (progress >= TARGET) {
				it.remove();
				sendRunState(player, false);
				finish(server, player, run);
				continue;
			}

			boolean leewayUsed = run.lane.tick(player, run.ticks);
			if (run.lane.exhausted()) {
				it.remove();
				sendRunState(player, false);
				player.sendOverlayMessage(Component.literal("Out of leeway!").withStyle(ChatFormatting.RED));
				player.sendSystemMessage(Component.literal(
					"Out of leeway! Run failed at " + Format.blocks(progress) + " blocks (" + Format.time(run.ticks) + ")"
				).withStyle(ChatFormatting.RED));
				Lane.strike(player);
				continue;
			}

			if (leewayUsed || run.ticks % ACTION_BAR_INTERVAL_TICKS == 0) {
				player.sendOverlayMessage(counter(progress, run.ticks, run.lane, run.lane.inside(player)));
			}
		}
	}

	/** Starts the run for anyone who stepped through a portal; shows the portals to everyone still choosing. */
	private static void tickHub(MinecraftServer server) {
		ServerLevel overworld = server.overworld();
		if (!hubLookedUp) {
			// Once per server start: a hub built in an earlier session should work straight away too.
			hubLookedUp = true;
			BlockPos centre = SpawnHub.centre(overworld);
			hubCentre = SpawnHub.isBuilt(overworld, centre) ? centre : null;
		}
		if (hubCentre != null) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				UUID id = player.getUUID();
				if (player.level() == overworld && !RUNS.containsKey(id) && !IN_HUB.containsKey(id)
					&& SpawnHub.inCentre(hubCentre, player.blockPosition())) {
					IN_HUB.put(id, hubCentre);
					sendRunState(player, true);
				}
			}
		}
		if (hubCentre != null && !IN_HUB.isEmpty() && server.getTickCount() % 20 == 0) {
			RecipeBoard.cycle(overworld, hubCentre, server.getTickCount() / 20);
		}

		Iterator<Map.Entry<UUID, BlockPos>> it = IN_HUB.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, BlockPos> entry = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				it.remove();
				continue;
			}

			BlockPos centre = entry.getValue();
			if (!SpawnHub.isNear(centre, player.blockPosition()) || player.level() != overworld) {
				// Left without using a portal (teleported, climbed out): stand down until they're back in the centre.
				it.remove();
				sendRunState(player, false);
				player.sendOverlayMessage(Component.empty());
				continue;
			}
			Heading heading = SpawnHub.crossed(centre, player.blockPosition());
			if (heading != null) {
				it.remove();
				// The run counts from the portal line, so the timer and splits start right here.
				// The lane runs down the middle of the hub lane they walked into, wherever they crossed the portal.
				beginRun(player, heading, player.blockPosition(), heading.lateral(centre));
				player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 30, 10));
				player.connection.send(new ClientboundSetSubtitleTextPacket(Component.empty()));
				player.connection.send(new ClientboundSetTitleTextPacket(
					Component.literal(heading.label).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)));
				continue;
			}

			long time = server.getTickCount();
			if (time % 5 == 0) {
				SpawnHub.particles(server.overworld(), player, centre);
			}
			if (time % ACTION_BAR_INTERVAL_TICKS == 0) {
				player.sendOverlayMessage(Component.literal("Walk through a portal to start"));
			}
		}
	}

	private static String counter(int progress, long ticks) {
		return Format.blocks(progress) + " / " + Format.blocks(TARGET) + " · " + Format.time(ticks);
	}

	/** The counter plus leeway left: white in the lane, red while outside it (and using it up). */
	private static Component counter(int progress, long ticks, Lane lane, boolean inside) {
		ChatFormatting color = inside ? ChatFormatting.WHITE : ChatFormatting.RED;
		return Component.literal(counter(progress, ticks) + " · ")
			.append(Component.literal("leeway " + Format.blocks(lane.left())).withStyle(color));
	}

	private static void finish(MinecraftServer server, ServerPlayer player, Run run) {
		String result = Format.blocks(TARGET) + " blocks in " + Format.time(run.ticks);
		run.splits.add(run.ticks);
		Long delta = run.deltaAtLastSplit();
		boolean newPb = run.pb == null || delta != null && delta < 0;

		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 100, 20));
		player.connection.send(new ClientboundSetSubtitleTextPacket(
			Component.literal(result).append(SplitBoard.delta(delta))));
		player.connection.send(new ClientboundSetTitleTextPacket(
			Component.literal("FAROUT!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
		player.sendOverlayMessage(Component.literal(counter(TARGET, run.ticks)));
		SplitBoard.split(player, TARGET, run.ticks, delta, true);
		player.sendSystemMessage(Component.literal("FAROUT! " + result).withStyle(ChatFormatting.GOLD)
			.append(SplitBoard.delta(delta)));
		if (newPb) {
			player.sendSystemMessage(Component.literal("New personal best!")
				.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			try {
				PersonalBests.save(run.heading, run.splits);
			} catch (IOException e) {
				LOGGER.error("Could not write personal bests", e);
				player.sendSystemMessage(Component.literal("Could not save personal best: " + e.getMessage())
					.withStyle(ChatFormatting.RED));
			}
		}
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
