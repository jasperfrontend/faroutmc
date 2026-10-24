package nl.jasperfrontend.farout;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import nl.jasperfrontend.farout.client.FaroutSettingsScreen;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * .\gradlew runClientGameTest: plays the session loop in a real client, in a normal (not superflat)
 * world, on the 100-block course, and checks the player's own world and things come through untouched.
 * Screenshots land in build/run/clientGameTest/screenshots. Not in CI: it needs a display.
 */
public class FaroutClientGameTest implements FabricClientGameTest {
	private static final String SEED = "8675309";

	@Override
	public void runTest(ClientGameTestContext context) {
		Path saved = FabricLoader.getInstance().getConfigDir().resolve("farout").resolve("saved-seeds.json");
		Path results = FabricLoader.getInstance().getConfigDir().resolve("farout").resolve("results.csv");
		try {
			Files.deleteIfExists(saved);
			// A results.csv from before 1.0.24, which the finish below has to migrate.
			Files.createDirectories(results.getParent());
			Files.write(results, List.of("date,direction,time,seed", "2026-10-17T20:00:00,north,08:12.4," + SEED));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		try (TestSingleplayerContext singleplayer = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(settings -> settings.setSeed(SEED))
			.create()) {
			TestServerContext server = singleplayer.getServer();
			server.runCommand("farout settings airtimeHeight 0");
			server.runCommand("farout settings bonusChest 1");

			// An existing world: something built at spawn, an armour stand, and a player with things of their own.
			// Worked out once: the build raises the ground there, so asking again later would look higher up.
			BlockPos spawn = server.computeOnServer(s -> SpawnHub.centre(s.overworld(), s.overworld().getRespawnData().pos()));
			Vec3 home = server.computeOnServer(s -> {
				ServerLevel overworld = s.overworld();
				// Kept loaded while the player is away, or the armour stand check below couldn't see it either way.
				overworld.setChunkForced(spawn.getX() >> 4, spawn.getZ() >> 4, true);
				overworld.setBlock(spawn.above(2), Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
				ArmorStand stand = EntityType.ARMOR_STAND.create(overworld, EntitySpawnReason.COMMAND);
				stand.snapTo(spawn.getX() + 2.5, spawn.getY(), spawn.getZ() + 2.5);
				stand.addTag("farout_test_stand");
				overworld.addFreshEntity(stand);

				ServerPlayer player = player(s);
				player.teleportTo(overworld, spawn.getX() + 20.5, spawn.getY() + 1, spawn.getZ() + 0.5, Set.of(), 0f, 0f, true);
				player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
				player.getEnderChestInventory().setItem(0, new ItemStack(Items.ELYTRA));
				player.setExperienceLevels(30);
				return player.position();
			});
			context.waitTicks(5);

			// /farout start: the hub is in a fresh run world; the real spawn stays as it was.
			server.runCommand("execute as @a run farout start");
			context.waitFor(minecraft -> minecraft.level != null && RunWorlds.isRunWorld(minecraft.level.dimension())
				&& minecraft.screen == null);
			context.waitTicks(40);
			context.takeScreenshot("farout-1-hub-in-run-world");
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				check(RunWorlds.isRunWorld(player.level().dimension()), "the hub isn't in a run world");
				check(player.level().getSeed() == Long.parseLong(SEED), "run world has seed " + player.level().getSeed());
				check(player.getInventory().isEmpty() && player.experienceLevel == 0, "not a fresh player in the hub");
				assertHomeUntouched(s, spawn);
				// Take the pickaxe from the bonus chest: it has to come along through the portal.
				BlockPos centre = hub(player.level(), s.overworld().getRespawnData().pos());
				check(player.level().getBlockEntity(centre) instanceof ChestBlockEntity, "no bonus chest in the middle of the hub");
				ChestBlockEntity chest = (ChestBlockEntity) player.level().getBlockEntity(centre);
				player.getInventory().setItem(0, chest.removeItemNoUpdate(1));
			});

			// Across the north portal: the run starts, in the same world.
			ResourceKey<Level> first = server.computeOnServer(s -> {
				ServerPlayer player = player(s);
				BlockPos centre = hub(player.level(), s.overworld().getRespawnData().pos());
				player.teleportTo(player.level(), centre.getX() + 0.5, centre.getY(), centre.getZ() - 4.5, Set.of(),
					Heading.NORTH.yaw, 0f, true);
				return player.level().dimension();
			});
			context.waitTicks(5);
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				check(player.level().dimension() == first, "crossing the portal changed worlds");
				check(!player.canUsePortal(false), "portals work in a run world");
				check(ItemStack.isSameItemSameComponents(player.getInventory().getItem(0), new ItemStack(Items.DIAMOND_PICKAXE)),
					"the bonus chest's pickaxe didn't come through the portal as a plain one: " + player.getInventory().getItem(0));
				// Something to leave behind: it must not be there on the next run.
				player.level().setBlock(player.blockPosition().above(2), Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
			});

			// Giving up: home, with their own things, the world goes, and they're asked about the seed.
			server.runCommand("execute as @a run farout stop");
			context.waitForScreen(ConfirmScreen.class);
			context.takeScreenshot("farout-2-save-seed-prompt");
			context.clickScreenButton("Save seed");
			context.waitFor(minecraft -> minecraft.screen == null);
			context.waitTicks(5);
			server.runOnServer(s -> {
				assertHome(s, home, spawn, "/farout stop");
				check(s.getLevel(first) == null, "run world still loaded after /farout stop");
			});
			String json = read(saved);
			check(json.contains("\"" + SEED + "\"") && json.contains("north"), "saved-seeds.json is " + json);

			// Same seed, same direction, untouched: the gold block from last time is gone.
			server.runCommand("execute as @a run farout start north");
			context.waitFor(minecraft -> minecraft.level != null && RunWorlds.isRunWorld(minecraft.level.dimension()));
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				check(player.level().dimension() != first, "second run reused the first run's world");
				BlockPos centre = SpawnHub.centre(s.overworld(), s.overworld().getRespawnData().pos());
				ServerLevel level = player.level();
				for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-12, -2, -12), centre.offset(12, 6, 12))) {
					check(!level.getBlockState(pos).is(Blocks.GOLD_BLOCK), "gold block from the last run is still at " + pos);
				}
			});

			// Dying ends it too: respawn at home with their own things, and no prompt for a seed that's saved.
			// Vanilla keeps you invulnerable until your client has loaded the new world.
			context.waitFor(minecraft -> minecraft.screen == null);
			context.waitTicks(40);
			server.runCommand("kill @a");
			context.waitTicks(20);
			context.takeScreenshot("farout-3-death");
			context.runOnClient(minecraft -> minecraft.player.respawn());
			context.waitFor(minecraft -> minecraft.screen == null && minecraft.player.isAlive()
				&& !RunWorlds.isRunWorld(minecraft.level.dimension()));
			context.waitTicks(10);
			check(context.computeOnClient(minecraft -> minecraft.screen) == null, "a prompt showed for an already saved seed");
			server.runOnServer(s -> {
				assertHome(s, home, spawn, "dying");
				check(s.levelKeys().stream().noneMatch(RunWorlds::isRunWorld), "a run world is still loaded");
			});

			// Finishing (100 blocks here): a few seconds to take it in, then home; the saved seed gets East added.
			server.runCommand("execute as @a run farout start east");
			context.waitFor(minecraft -> minecraft.level != null && RunWorlds.isRunWorld(minecraft.level.dimension())
				&& minecraft.screen == null);
			context.waitTicks(40);
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				player.teleportTo(player.level(), player.getX() + Farout.TARGET + 1, player.getY() + 1, player.getZ(),
					Set.of(), Heading.EAST.yaw, 0f, true);
			});
			context.waitTicks(20);
			context.takeScreenshot("farout-4-finish");
			check(context.computeOnClient(minecraft -> RunWorlds.isRunWorld(minecraft.level.dimension())),
				"sent home straight after finishing, without the grace period");
			context.waitFor(minecraft -> !RunWorlds.isRunWorld(minecraft.level.dimension()), 200);
			context.waitTicks(10);
			check(context.computeOnClient(minecraft -> minecraft.screen) == null, "a prompt showed for an already saved seed");
			check(read(saved).contains("east"), "East wasn't added to the saved seed: " + read(saved));
			List<String> csv = read(results).lines().toList();
			check(csv.size() == 3 && csv.get(0).equals("date,direction,time,seed,start,bonusChest")
				&& csv.get(1).equals("2026-10-17T20:00:00,north,08:12.4," + SEED + ",,no")
				&& csv.get(2).matches(".*,east,.*," + SEED + ",-?\\d+ -?\\d+ -?\\d+,no"),
				"results.csv after finishing: " + csv);
			server.runOnServer(s -> {
				assertHome(s, home, spawn, "finishing");
				check(s.levelKeys().stream().noneMatch(RunWorlds::isRunWorld), "a run world is still loaded");
			});

			// Airtime over a brand-new run world: hang, drop into the hub, then give up from there.
			server.runCommand("farout settings airtimeHeight 50");
			server.runCommand("farout settings airtimeSeconds 12");
			server.runCommand("execute as @a run farout start");
			context.waitFor(minecraft -> minecraft.level != null && RunWorlds.isRunWorld(minecraft.level.dimension())
				&& minecraft.screen == null);
			context.waitTicks(20);
			context.takeScreenshot("farout-5-airtime");
			context.runOnClient(minecraft -> minecraft.player.setXRot(60f));
			context.waitTicks(100);
			context.takeScreenshot("farout-5b-airtime-looking-down");
			context.waitFor(minecraft -> minecraft.player.onGround(), 400);
			context.waitTicks(10);
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				BlockPos centre = hub(player.level(), s.overworld().getRespawnData().pos());
				check(RunWorlds.isRunWorld(player.level().dimension()) && SpawnHub.inCentre(centre, player.blockPosition()),
					"didn't land in the hub after Airtime: " + player.level().dimension() + " " + player.blockPosition());
			});
			server.runCommand("execute as @a run farout stop");
			context.waitFor(minecraft -> !RunWorlds.isRunWorld(minecraft.level.dimension()));
			context.waitTicks(5);
			server.runOnServer(s -> assertHome(s, home, spawn, "/farout stop in the hub"));

			// /farout here, a way off from spawn: the start area is built where they stand, in the run world.
			server.runCommand("farout settings airtimeHeight 0");
			Vec3 lookout = server.computeOnServer(s -> {
				ServerPlayer player = player(s);
				BlockPos pos = SpawnHub.centre(s.overworld(), spawn.offset(300, 0, 0));
				player.teleportTo(s.overworld(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Set.of(), 0f, 0f, true);
				return player.position();
			});
			context.waitTicks(5);
			server.runCommand("execute as @a run farout here");
			context.waitFor(minecraft -> minecraft.level != null && RunWorlds.isRunWorld(minecraft.level.dimension())
				&& minecraft.screen == null);
			context.waitTicks(40);
			context.takeScreenshot("farout-5c-here");
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				BlockPos centre = hub(player.level(), BlockPos.containing(lookout));
				check(RunWorlds.isRunWorld(player.level().dimension()) && SpawnHub.inCentre(centre, player.blockPosition()),
					"/farout here didn't put them in a start area where they stood: " + player.blockPosition());
				check(player.level().getBlockState(centre.offset(0, 0, -4)).is(Blocks.PURPLE_CARPET),
					"no start area where /farout here was typed");
				assertHomeUntouched(s, spawn);
			});
			server.runCommand("execute as @a run farout stop");
			context.waitFor(minecraft -> !RunWorlds.isRunWorld(minecraft.level.dimension()));
			context.waitTicks(5);
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				check(player.level() == s.overworld() && player.position().distanceTo(lookout) < 1,
					"not back where /farout here was typed: " + player.position());
			});

			server.runCommand("execute as @a run farout menu");
			context.waitForScreen(FaroutSettingsScreen.class);
			context.takeScreenshot("farout-6-settings");
			context.getInput().setCursorPos(context.computeOnClient(m -> m.getWindow().getScreenWidth() / 2.0),
				context.computeOnClient(m -> m.getWindow().getScreenHeight() / 2.0));
			context.getInput().scroll(-50);
			context.waitTicks(2);
			context.takeScreenshot("farout-7-saved-seeds-and-runs");
			// The button sits in a list row, where clickScreenButton can't see it: click it with the mouse.
			double[] at = context.computeOnClient(minecraft -> {
				Button button = findButton(minecraft.screen, "Copy /tp");
				check(button != null, "no Copy /tp button in the menu");
				double scale = minecraft.getWindow().getGuiScale();
				return new double[] {(button.getX() + button.getWidth() / 2.0) * scale,
					(button.getY() + button.getHeight() / 2.0) * scale};
			});
			context.getInput().setCursorPos(at[0], at[1]);
			context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			String clipboard = context.computeOnClient(minecraft -> minecraft.keyboardHandler.getClipboard());
			check(clipboard.matches("/tp -?\\d+ -?\\d+ -?\\d+"), "Copy /tp copied " + clipboard);
			context.clickScreenButton("gui.cancel");
		}
	}

	/** The centre of a start area that's been built: the bonus chest in its middle is the ground there now. */
	private static BlockPos hub(ServerLevel level, BlockPos spot) {
		return SpawnHub.centre(level, spot).below();
	}

	/** Back where they stood before /farout start, with their own things, and the real spawn untouched. */
	private static void assertHome(MinecraftServer server, Vec3 home, BlockPos spawn, String after) {
		ServerPlayer player = player(server);
		check(player.level() == server.overworld() && player.position().distanceTo(home) < 1,
			"not back where they started after " + after + ": " + player.level().dimension() + " " + player.position());
		check(player.getInventory().getItem(0).is(Items.DIAMOND_SWORD), "inventory not given back after " + after);
		check(player.getEnderChestInventory().getItem(0).is(Items.ELYTRA), "ender chest not given back after " + after);
		check(player.experienceLevel == 30, "XP not given back after " + after + ": level " + player.experienceLevel);
		assertHomeUntouched(server, spawn);
	}

	private static void assertHomeUntouched(MinecraftServer server, BlockPos spawn) {
		ServerLevel overworld = server.overworld();
		check(overworld.getBlockState(spawn.above(2)).is(Blocks.DIAMOND_BLOCK), "the build at the real spawn is gone");
		check(!overworld.getBlockState(spawn.offset(0, 0, -4)).is(Blocks.PURPLE_CARPET), "a hub was built at the real spawn");
		check(!overworld.getEntitiesOfClass(ArmorStand.class, new net.minecraft.world.phys.AABB(spawn).inflate(8),
			e -> e.entityTags().contains("farout_test_stand")).isEmpty(), "the armour stand at the real spawn was removed");
	}

	/** The button with this label anywhere on screen, rows of a list included; null if there's none. */
	private static Button findButton(GuiEventListener widget, String label) {
		if (widget instanceof Button button && button.getMessage().getString().equals(label)) {
			return button;
		}
		if (widget instanceof ContainerEventHandler container) {
			for (GuiEventListener child : container.children()) {
				Button found = findButton(child, label);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
