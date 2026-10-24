package nl.jasperfrontend.farout;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
	/** How long you get to take in the finish before you're taken home. */
	private static final int FINISH_GRACE_TICKS = 100;

	private static final Map<UUID, Run> RUNS = new HashMap<>();
	/** Players standing in the start area, picking a direction; value is the hub centre. */
	private static final Map<UUID, BlockPos> IN_HUB = new HashMap<>();
	/** The hub centre in each player's session world, from /farout start until their run begins. */
	private static final Map<UUID, BlockPos> HUBS = new HashMap<>();

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.clientboundPlay().register(RunStatePayload.TYPE, RunStatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LanePayload.TYPE, LanePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SeedOfferPayload.TYPE, SeedOfferPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(OpenMenuPayload.TYPE, OpenMenuPayload.CODEC);
		Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, Identifier.fromNamespaceAndPath(MOD_ID, "power_up"),
			PowerUpRecipe.SERIALIZER);
		PowerUps.register();
		Stash.register();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			LiteralArgumentBuilder<CommandSourceStack> start = Commands.literal("start")
				.executes(ctx -> enterHub(ctx.getSource().getPlayerOrException(), null));
			for (Heading heading : Heading.values()) {
				start.then(Commands.literal(heading.id())
					.executes(ctx -> start(ctx.getSource().getPlayerOrException(), heading)));
			}
			LiteralArgumentBuilder<CommandSourceStack> settings = Commands.literal("settings")
				.executes(ctx -> showSettings(ctx.getSource()));
			for (String name : Settings.NAMES) {
				String unit = switch (name) {
					case Settings.AIRTIME_SECONDS -> "seconds";
					case Settings.BONUS_CHEST -> "enabled";
					default -> "blocks";
				};
				settings.then(Commands.literal(name)
					.then(Commands.argument(unit, IntegerArgumentType.integer(Settings.min(name), Settings.max(name)))
						.executes(ctx -> setSetting(ctx.getSource(), name, IntegerArgumentType.getInteger(ctx, unit)))));
			}
			dispatcher.register(Commands.literal("farout")
				.then(start)
				.then(settings)
				.then(Commands.literal("here")
					.executes(ctx -> here(ctx.getSource().getPlayerOrException())))
				.then(Commands.literal("menu")
					.executes(ctx -> openMenu(ctx.getSource().getPlayerOrException())))
				.then(Commands.literal("stop")
					.executes(ctx -> stop(ctx.getSource().getPlayerOrException()))));
		});

		ServerTickEvents.END_SERVER_TICK.register(Farout::tick);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
			!(entity instanceof ServerPlayer player && source.is(DamageTypeTags.IS_FALL) && Airtime.protects(player)));

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				PowerUps.clear(player);
				Run run = endRun(player);
				if (run != null) {
					int progress = run.heading.progress(run.origin, player.blockPosition());
					player.sendSystemMessage(Component.literal(
						"Run failed at " + Format.blocks(progress) + " blocks (" + Format.time(run.ticks) + ")"
					).withStyle(ChatFormatting.RED));
				}
				// Whatever they died with stays in the run's world; AFTER_RESPAWN takes them home.
				endSession(player);
			}
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			// Respawning in the run's world (a bed there) is handled by RunWorlds, which sends them home from it.
			if (Stash.has(newPlayer) && !RunWorlds.isRunWorld(newPlayer.level().dimension())) {
				sendHome(newPlayer);
			}
		});

		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> {
			// Moving into a run world is how every session starts; only leaving one ends it.
			if (!RunWorlds.isRunWorld(from.dimension()) || RunWorlds.isRunWorld(to.dimension())) {
				return;
			}
			if (endRun(player) != null) {
				player.sendSystemMessage(Component.literal("Run cancelled: left the run's world")
					.withStyle(ChatFormatting.RED));
			}
			endSession(player);
			// Farout's own way home takes the stash before it moves you; anything else (a /tp, another mod)
			// still gets you your own things back.
			if (Stash.has(player)) {
				sendHome(player);
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			PowerUps.unlockRecipes(handler.getPlayer());
			// Still stashed: the server went down mid-session without saying goodbye, and the run world with it.
			if (Stash.has(handler.getPlayer())) {
				sendHome(handler.getPlayer());
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.getPlayer();
			PowerUps.clear(player);
			RUNS.remove(player.getUUID());
			Airtime.cancel(player);
			IN_HUB.remove(player.getUUID());
			HUBS.remove(player.getUUID());
			BonusChest.forget(player);
			RunWorlds.close(player.getUUID(), 0);
			// Saved after this, so they log back in where they started, with their own things.
			if (Stash.has(player) || RunWorlds.isRunWorld(player.level().dimension())) {
				sendHome(player);
			}
			SplitBoard.forget(player.getUUID());
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			// Before the players are saved and Fantasy deletes the run worlds: everyone home, with their own things.
			for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
				if (Stash.has(player) || RunWorlds.isRunWorld(player.level().dimension())) {
					Airtime.cancel(player);
					sendHome(player);
				}
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			RUNS.clear();
			IN_HUB.clear();
			HUBS.clear();
			Airtime.forgetAll();
			SplitBoard.forgetAll();
			PowerUps.clearAll();
			BonusChest.forgetAll();
			RunWorlds.forgetAll();
		});
	}

	/**
	 * /farout here: the start area where the player stands instead of at spawn. The run's copy of the world is
	 * generated from the seed, so the ground there is the same as in the overworld or any run world, but not
	 * in the Nether or the End.
	 */
	private static int here(ServerPlayer player) {
		ResourceKey<Level> dimension = player.level().dimension();
		if (dimension != Level.OVERWORLD && !RunWorlds.isRunWorld(dimension)) {
			player.sendSystemMessage(Component.literal("Runs happen in a copy of the overworld, so /farout here only works there.")
				.withStyle(ChatFormatting.RED));
			return 0;
		}
		return enterHub(player, player.blockPosition());
	}

	/**
	 * /farout start: stash the player, open a fresh copy of the world with the start area at its spawn (or at
	 * spot, for /farout here), and drop them into its centre to pick a lane, after a few seconds of airtime
	 * high above it to scout the directions. Nothing of this touches the player's own world.
	 */
	private static int enterHub(ServerPlayer player, BlockPos spot) {
		if (RUNS.containsKey(player.getUUID())) {
			player.sendSystemMessage(Component.literal("A run is already active. Use /farout stop first.")
				.withStyle(ChatFormatting.RED));
			return 0;
		}

		Airtime.cancel(player);
		leaveHub(player);
		// Starting again from the hub or right after a finish keeps the stash from the first start.
		Stash.save(player);
		Settings settings = Settings.load();
		ServerLevel level = RunWorlds.open(player);
		BlockPos centre = SpawnHub.centre(level, spot != null ? spot : level.getServer().overworld().getRespawnData().pos());
		SpawnHub.build(level, centre);
		SpawnHub.clearEntities(level, centre);
		SpawnHub.labels(level, centre);
		BonusChest.forget(player);
		if (settings.bonusChest == 1) {
			BonusChest.place(player, level, centre);
		}
		HUBS.put(player.getUUID(), centre);
		resetToFreshSpawn(player);
		SplitBoard.clear(player);

		if (settings.airtimeHeight == 0) {
			// On top of the bonus chest, if there is one, as Airtime would have dropped them.
			player.teleportTo(level, centre.getX() + 0.5, centre.getY() + (BonusChest.placed(player) ? 1 : 0),
				centre.getZ() + 0.5, Set.of(), Heading.NORTH.yaw, 0f, true);
			IN_HUB.put(player.getUUID(), centre);
			sendRunState(player, true);
			player.sendSystemMessage(Component.literal("Walk through a portal to pick your direction."));
			return 1;
		}
		// Up there they're not in the hub yet; tickHub takes them in once they land in the centre.
		Airtime.start(player, level, centre, settings.airtimeHeight, settings.airtimeSeconds);
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

		Airtime.cancel(player);
		leaveHub(player);
		BonusChest.forget(player);
		Stash.save(player);
		BlockPos origin = teleportToSpawn(player, RunWorlds.open(player), heading.yaw);
		beginRun(player, heading, origin, heading.lateral(origin), null);
		return 1;
	}

	/** /farout menu: the settings screen and saved seeds, which live on the client. */
	private static int openMenu(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, OpenMenuPayload.TYPE)) {
			player.sendSystemMessage(Component.literal("The settings screen needs Farout installed on your side too.")
				.withStyle(ChatFormatting.RED));
			return 0;
		}
		ServerPlayNetworking.send(player, OpenMenuPayload.INSTANCE);
		return 1;
	}

	private static int showSettings(CommandSourceStack source) {
		Settings settings = Settings.load();
		source.sendSystemMessage(Component.literal("Farout settings (config/farout/settings.properties):"));
		for (String name : Settings.NAMES) {
			source.sendSystemMessage(Component.literal("  " + name + ": " + settings.get(name)
				+ " (default " + Settings.defaultValue(name) + ")"));
		}
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

	/**
	 * laneCentre: the sideways block coordinate the lane is centred on. hub: the start area's centre, or null
	 * for /farout start &lt;direction&gt;, which has none.
	 */
	private static void beginRun(ServerPlayer player, Heading heading, BlockPos origin, int laneCentre, BlockPos hub) {
		// Start like a brand-new world: a freshly spawned player, clear skies, at the time a new world begins.
		// The time is the run world's own clock; weather is one for the whole server, so that clears at home too.
		HUBS.remove(player.getUUID());
		// Bonus chest loot comes through the reset; anything else picked up in the start area doesn't.
		Map<Integer, ItemStack> loot = BonusChest.release(player);
		resetToFreshSpawn(player);
		loot.forEach(player.getInventory()::setItem);
		MinecraftServer server = player.level().getServer();
		CommandSourceStack source = server.createCommandSourceStack().withLevel(player.level()).withSuppressedOutput();
		server.getCommands().performPrefixedCommand(source, "time set 0");
		server.getCommands().performPrefixedCommand(source, "weather clear");

		Settings settings = Settings.load();
		Lane lane = new Lane(heading, laneCentre, settings.pathWidth, settings.leewayBlocks, player);
		// A start area away from spawn is from /farout here, and that course keeps its own personal bests.
		BlockPos spot = hub != null ? spot(server, hub) : null;
		// The bonus chest saves so much time that runs with it are a category of their own.
		boolean bonusChest = BonusChest.placed(player);
		long[] pb = PersonalBests.load(heading, player.level().getSeed(), spot, bonusChest);
		RUNS.put(player.getUUID(),
			new Run(heading, player.level(), origin, hub != null ? hub : origin, spot, bonusChest, lane, pb));
		sendRunState(player, true);
		if (ServerPlayNetworking.canSend(player, LanePayload.TYPE)) {
			ServerPlayNetworking.send(player, lane.payload());
		}
		SplitBoard.show(player, heading);
		player.sendSystemMessage(Component.literal(
			"Farout started: head " + heading.label + " for " + Format.blocks(TARGET) + " blocks. Stay in the "
				+ settings.pathWidth + "-wide lane; " + Format.blocks(settings.leewayBlocks) + " blocks of leeway."));
		if (pb == null) {
			player.sendSystemMessage(Component.literal("No personal best for " + heading.label
				+ (spot != null ? " from this spot" : "") + (bonusChest ? " with the bonus chest" : "")
				+ " on this seed yet.").withStyle(ChatFormatting.GRAY));
		}
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

	/** /farout stop: gives up whatever part of the session they're in (Airtime, hub, run, finish) and goes home. */
	private static int stop(ServerPlayer player) {
		boolean hadBoard = SplitBoard.clear(player);
		Run run = endRun(player);
		if (run == null && !Stash.has(player) && !RunWorlds.isRunWorld(player.level().dimension())) {
			// After a finish, death or cancel the splits stay on screen until the next start or this.
			player.sendSystemMessage(Component.literal(hadBoard ? "Splits cleared." : "No active run."));
			return hadBoard ? 1 : 0;
		}

		endSession(player);
		player.sendOverlayMessage(Component.empty());
		sendHome(player);
		player.sendSystemMessage(Component.literal((run != null ? "Run stopped. " : "")
			+ "Back where you started, with your own things."));
		return 1;
	}

	/** Ends the player's session short of the run itself: Airtime, the hub, and the world, deleted once they're out. */
	private static void endSession(ServerPlayer player) {
		Airtime.cancel(player);
		leaveHub(player);
		HUBS.remove(player.getUUID());
		BonusChest.forget(player);
		RunWorlds.close(player.getUUID(), 0);
	}

	/**
	 * Back where the player was before /farout start, with everything they had then (see {@link Stash}).
	 * Without a stash, the overworld's spawn.
	 */
	static void sendHome(ServerPlayer player) {
		if (!Stash.restore(player)) {
			ServerLevel overworld = player.level().getServer().overworld();
			LevelData.RespawnData spawn = overworld.getRespawnData();
			BlockPos pos = player.adjustSpawnLocation(overworld, spawn.pos());
			player.teleportTo(overworld, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Set.of(), spawn.yaw(), 0f, true);
		}
	}

	/** Removes the player's run, if any, and tells their client to stop drawing run HUD. */
	private static Run endRun(ServerPlayer player) {
		Run run = RUNS.remove(player.getUUID());
		if (run != null) {
			sendRunState(player, false);
			retire(player, run, 0);
		}
		return run;
	}

	/**
	 * Every run that ends in play comes through here: its world is deleted once the player is out of it
	 * (taken home after graceTicks if they're still alive in there), and the client offers to save the seed.
	 */
	private static void retire(ServerPlayer player, Run run, int graceTicks) {
		RunWorlds.close(player.getUUID(), graceTicks);
		if (ServerPlayNetworking.canSend(player, SeedOfferPayload.TYPE)) {
			ServerPlayNetworking.send(player, new SeedOfferPayload(run.level.getSeed(), run.heading.id()));
		}
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

	/** Teleports to a safe spot at the overworld's spawn, in the given run world, and returns that block position. */
	private static BlockPos teleportToSpawn(ServerPlayer player, ServerLevel level, float yaw) {
		LevelData.RespawnData spawn = player.level().getServer().overworld().getRespawnData();
		// Vanilla's new-player placement: loads the chunks and searches the spawn radius for a safe spot.
		BlockPos pos = player.adjustSpawnLocation(level, spawn.pos());

		player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Set.of(), yaw, 0f, true);
		return pos;
	}

	private static void tick(MinecraftServer server) {
		Airtime.tick(server);
		tickHub(server);
		// After tickHub: a run that just started reset their health, and the greedy go straight back to half a heart.
		BonusChest.tick(server);
		PowerUps.tick(server);
		RunWorlds.tick(server);

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
				retire(player, run, FINISH_GRACE_TICKS);
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
				retire(player, run, 0);
				continue;
			}

			if (leewayUsed || run.ticks % ACTION_BAR_INTERVAL_TICKS == 0) {
				player.sendOverlayMessage(counter(progress, run.ticks, run.lane, run.lane.inside(player)));
			}
		}
	}

	/** Starts the run for anyone who stepped through a portal; shows the portals to everyone still choosing. */
	private static void tickHub(MinecraftServer server) {
		// Landed from Airtime, or walked back into the middle: the portals arm.
		for (Map.Entry<UUID, BlockPos> hub : HUBS.entrySet()) {
			UUID id = hub.getKey();
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null && player.level() == RunWorlds.level(id) && !RUNS.containsKey(id)
				&& !IN_HUB.containsKey(id) && SpawnHub.inCentre(hub.getValue(), player.blockPosition())) {
				IN_HUB.put(id, hub.getValue());
				sendRunState(player, true);
			}
		}
		if (server.getTickCount() % 20 == 0) {
			for (Map.Entry<UUID, BlockPos> entry : IN_HUB.entrySet()) {
				ServerLevel level = RunWorlds.level(entry.getKey());
				if (level != null) {
					RecipeBoard.cycle(level, entry.getValue(), server.getTickCount() / 20);
				}
			}
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
			if (!SpawnHub.isNear(centre, player.blockPosition()) || player.level() != RunWorlds.level(entry.getKey())) {
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
				beginRun(player, heading, player.blockPosition(), heading.lateral(centre), centre);
				player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 30, 10));
				player.connection.send(new ClientboundSetSubtitleTextPacket(Component.empty()));
				player.connection.send(new ClientboundSetTitleTextPacket(
					Component.literal(heading.label).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)));
				continue;
			}

			long time = server.getTickCount();
			if (time % 5 == 0) {
				SpawnHub.particles(player.level(), player, centre);
			}
			if (time % ACTION_BAR_INTERVAL_TICKS == 0) {
				player.sendOverlayMessage(Component.literal("Walk through a portal to start"));
			}
		}
	}

	/** The hub centre if it's a /farout here one, or null if it's at spawn: a here at spawn is the same course. */
	private static BlockPos spot(MinecraftServer server, BlockPos centre) {
		BlockPos spawn = server.overworld().getRespawnData().pos();
		return centre.getX() == spawn.getX() && centre.getZ() == spawn.getZ() ? null : centre;
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
		player.sendSystemMessage(Component.literal("Heading home in " + FINISH_GRACE_TICKS / 20 + " seconds.")
			.withStyle(ChatFormatting.GRAY));
		if (newPb) {
			player.sendSystemMessage(Component.literal("New personal best!")
				.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			try {
				PersonalBests.save(run.heading, run.level.getSeed(), run.spot, run.bonusChest, run.splits);
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
			ResultsLog.append(run.heading, run.ticks, server.overworld().getSeed(), run.start, run.bonusChest);
		} catch (IOException e) {
			LOGGER.error("Could not write results.csv", e);
			player.sendSystemMessage(Component.literal("Could not write results.csv: " + e.getMessage())
				.withStyle(ChatFormatting.RED));
		}
	}
}
