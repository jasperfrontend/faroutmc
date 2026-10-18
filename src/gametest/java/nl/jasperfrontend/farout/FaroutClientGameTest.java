package nl.jasperfrontend.farout;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import nl.jasperfrontend.farout.client.FaroutSettingsScreen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * .\gradlew runClientGameTest: plays the run-world loop in a real client, in a normal (not superflat)
 * world, on the 100-block course. Screenshots land in build/run/clientGameTest/screenshots. Not in CI:
 * it needs a display.
 */
public class FaroutClientGameTest implements FabricClientGameTest {
	private static final String SEED = "8675309";

	@Override
	public void runTest(ClientGameTestContext context) {
		Path saved = FabricLoader.getInstance().getConfigDir().resolve("farout").resolve("saved-seeds.json");
		try {
			Files.deleteIfExists(saved);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		try (TestSingleplayerContext singleplayer = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(settings -> settings.setSeed(SEED))
			.create()) {
			TestServerContext server = singleplayer.getServer();
			server.runCommand("farout settings airtimeHeight 0");

			// Through the hub: walking across the north portal moves you into a fresh run world.
			server.runCommand("execute as @a run farout start");
			context.waitTicks(5);
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				BlockPos centre = SpawnHub.centre(s.overworld());
				player.teleportTo(s.overworld(), centre.getX() + 0.5, centre.getY(), centre.getZ() - 4.5, Set.of(),
					Heading.NORTH.yaw, 0f, true);
			});
			context.waitFor(minecraft -> minecraft.level != null && RunWorlds.isRunWorld(minecraft.level.dimension())
				&& minecraft.screen == null);
			context.waitTicks(40);
			context.takeScreenshot("farout-1-run-world-hub");
			ResourceKey<Level> first = server.computeOnServer(s -> {
				ServerPlayer player = player(s);
				check(RunWorlds.isRunWorld(player.level().dimension()), "not in a run world after crossing the portal");
				check(player.level().getSeed() == Long.parseLong(SEED), "run world has seed " + player.level().getSeed());
				// Something to leave behind: it must not be there on the next run.
				player.level().setBlock(player.blockPosition().above(2), Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
				return player.level().dimension();
			});

			// Giving up: back to the hub, the world goes, and you're asked about the seed.
			server.runCommand("execute as @a run farout stop");
			context.waitForScreen(ConfirmScreen.class);
			context.takeScreenshot("farout-2-save-seed-prompt");
			context.clickScreenButton("Save seed");
			context.waitFor(minecraft -> minecraft.screen == null);
			context.waitTicks(5);
			server.runOnServer(s -> {
				check(player(s).level() == s.overworld(), "not back in the overworld after /farout stop");
				check(s.getLevel(first) == null, "run world still loaded after /farout stop");
			});
			String json;
			try {
				json = Files.readString(saved);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			check(json.contains("\"" + SEED + "\"") && json.contains("north"), "saved-seeds.json is " + json);

			// Same seed, same direction, untouched: the gold block from last time is gone.
			server.runCommand("execute as @a run farout start north");
			context.waitFor(minecraft -> minecraft.level != null && RunWorlds.isRunWorld(minecraft.level.dimension()));
			server.runOnServer(s -> {
				ServerPlayer player = player(s);
				check(player.level().dimension() != first, "second run reused the first run's world");
				BlockPos centre = SpawnHub.centre(s.overworld());
				ServerLevel level = player.level();
				for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-12, -2, -12), centre.offset(12, 6, 12))) {
					check(!level.getBlockState(pos).is(Blocks.GOLD_BLOCK), "gold block from the last run is still at " + pos);
				}
			});

			// Dying ends it too; the seed's already saved, so there's no prompt, and you respawn at home.
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
			server.runOnServer(s -> check(s.levelKeys().stream().noneMatch(RunWorlds::isRunWorld), "a run world is still loaded"));

			// Finishing (100 blocks here): a few seconds to take it in, then home, and the seed, already saved,
			// just gets East added without asking.
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
			context.takeScreenshot("farout-5-finish");
			check(context.computeOnClient(minecraft -> RunWorlds.isRunWorld(minecraft.level.dimension())),
				"sent home straight after finishing, without the grace period");
			context.waitFor(minecraft -> !RunWorlds.isRunWorld(minecraft.level.dimension()), 200);
			context.waitTicks(10);
			check(context.computeOnClient(minecraft -> minecraft.screen) == null, "a prompt showed for an already saved seed");
			String afterFinish;
			try {
				afterFinish = Files.readString(saved);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			check(afterFinish.contains("east"), "East wasn't added to the saved seed: " + afterFinish);
			server.runOnServer(s -> check(s.levelKeys().stream().noneMatch(RunWorlds::isRunWorld), "a run world is still loaded"));

			server.runCommand("execute as @a run farout menu");
			context.waitForScreen(FaroutSettingsScreen.class);
			context.takeScreenshot("farout-4-settings");
			context.getInput().setCursorPos(context.computeOnClient(m -> m.getWindow().getScreenWidth() / 2.0),
				context.computeOnClient(m -> m.getWindow().getScreenHeight() / 2.0));
			context.getInput().scroll(-10);
			context.waitTicks(2);
			context.takeScreenshot("farout-6-saved-seeds");
			context.clickScreenButton("gui.cancel");
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
