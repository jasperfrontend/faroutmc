package nl.jasperfrontend.farout;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.MixinEnvironment;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Run on a real dedicated server by .\gradlew runGameTest. Booting at all proves the mod loads: its
 * registries, recipe serializer and datapack JSON. The tests below check what that can't.
 */
public class FaroutGameTests {
	/** Hill height in blocks above the hub floor, north of the hub. */
	private static final int HILL = 5;
	/** Hub builds reach well past the 8x8x8 test structure; keep neighbouring tests clear of each other. */
	private static final int PADDING = 64;

	/** Loads every mixin target, so an injection point that moved in a Minecraft update fails here, not in play. */
	@GameTest
	public void mixinsApply(GameTestHelper helper) {
		MixinEnvironment.getCurrentEnvironment().audit();
		helper.succeed();
	}

	@GameTest
	public void powerUpRecipesLoad(GameTestHelper helper) {
		for (String id : new String[] {"cant_hurt_me", "morning_coffee", "mountain_hop", "power_drill"}) {
			ResourceKey<net.minecraft.world.item.crafting.Recipe<?>> key = ResourceKey.create(Registries.RECIPE,
				Identifier.fromNamespaceAndPath(Farout.MOD_ID, id));
			check(helper, helper.getLevel().getServer().getRecipeManager().byKey(key).isPresent(),
				"recipe farout:" + id + " is not loaded");
		}
		helper.succeed();
	}

	/**
	 * A results.csv from before 1.0.24 gets the start and bonusChest columns once, and keeps its runs; new runs
	 * record their start and bonus chest.
	 */
	@GameTest
	public void resultsLogMigrates(GameTestHelper helper) {
		try {
			Path file = Files.createTempDirectory("farout").resolve("results.csv");
			Files.writeString(file, "date,direction,time,seed\n2026-10-17T20:00:00,north,08:12.4,8675309\n");
			ResultsLog.append(file, Heading.EAST, 9000, 42, new BlockPos(300, 64, -12), true);
			ResultsLog.append(file, Heading.WEST, 9100, 42, new BlockPos(-5, 70, 8), false);

			List<String> lines = Files.readAllLines(file);
			check(helper, lines.size() == 4 && lines.getFirst().equals("date,direction,time,seed,start,bonusChest")
				&& lines.get(1).equals("2026-10-17T20:00:00,north,08:12.4,8675309,,no"),
				"results.csv after migrating: " + lines);
			List<ResultsLog.Result> results = ResultsLog.load(file);
			check(helper, results.size() == 3, "read back " + results.size() + " runs: " + results);
			check(helper, results.get(0).direction().equals("north") && results.get(0).start().isEmpty()
				&& !results.get(0).bonusChest(), "old run after migrating: " + results.get(0));
			check(helper, results.get(1).start().equals("300 64 -12") && results.get(2).start().equals("-5 70 8"),
				"starts read back as " + results.get(1).start() + " and " + results.get(2).start());
			check(helper, results.get(1).bonusChest() && !results.get(2).bonusChest(),
				"bonus chests read back as " + results.get(1).bonusChest() + " and " + results.get(2).bonusChest());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		helper.succeed();
	}

	/** A results.csv from 1.0.24 or 1.0.25 keeps its starts and gets "no" bonus chest, which didn't exist yet. */
	@GameTest
	public void resultsLogMigratesStarts(GameTestHelper helper) {
		try {
			Path file = Files.createTempDirectory("farout").resolve("results.csv");
			Files.writeString(file, "date,direction,time,seed,start\n2026-10-22T20:00:00,south,07:55.0,42,1 2 3\n");
			List<ResultsLog.Result> results = ResultsLog.load(file);
			check(helper, results.size() == 1 && results.getFirst().start().equals("1 2 3") && !results.getFirst().bonusChest(),
				"run from 1.0.25 after migrating: " + results);
			check(helper, Files.readAllLines(file).get(1).equals("2026-10-22T20:00:00,south,07:55.0,42,1 2 3,no"),
				"results.csv after migrating: " + Files.readAllLines(file));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		helper.succeed();
	}

	/**
	 * Loot taken from the bonus chest comes through the run's start as plain items that stack with what you find;
	 * the diamond stays put and so does the player's health.
	 */
	@GameTest
	public void bonusChestLootComesThroughPlain(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
		BonusChest.place(player, level, pos);
		ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(pos);
		player.getInventory().setItem(0, chest.removeItemNoUpdate(1));
		player.getInventory().setItem(5, chest.removeItemNoUpdate(5));
		player.getInventory().setItem(6, new ItemStack(Items.OAK_LOG));

		BonusChest.tick(level.getServer());
		Map<Integer, ItemStack> loot = BonusChest.release(player);
		float health = player.getHealth();
		boolean chestStands = level.getBlockState(pos).is(Blocks.CHEST);
		BonusChest.forget(player);
		level.getServer().getPlayerList().remove(player);

		check(helper, loot.keySet().equals(Set.of(0, 5)), "loot kept from slots " + loot.keySet() + ", expected 0 and 5");
		check(helper, ItemStack.isSameItemSameComponents(loot.get(0), new ItemStack(Items.DIAMOND_PICKAXE)),
			"pickaxe came through as " + loot.get(0) + " " + loot.get(0).getComponentsPatch());
		check(helper, loot.get(5).getCount() == 64 && ItemStack.isSameItemSameComponents(loot.get(5), new ItemStack(Items.DIRT)),
			"dirt came through as " + loot.get(5) + " " + loot.get(5).getComponentsPatch());
		check(helper, ItemStack.isSameItemSameComponents(chest.getItem(7), new ItemStack(Items.COOKED_BEEF)),
			"steak left in the chest is still marked: " + chest.getItem(7).getComponentsPatch());
		check(helper, BonusChest.isDiamond(chest.getItem(13)), "the diamond isn't in the middle of the chest: " + chest.getItem(13));
		check(helper, chestStands && health == player.getMaxHealth(), "taking the loot set off the trap");
		helper.succeed();
	}

	/** Taking the diamond blows the chest up with what's left in it, empties the inventory, and keeps you on half a heart. */
	@GameTest
	public void bonusChestDiamondBlowsUp(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
		BonusChest.place(player, level, pos);
		ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(pos);
		player.getInventory().setItem(0, chest.removeItemNoUpdate(1));
		player.getInventory().setItem(7, new ItemStack(Items.OAK_LOG, 12));
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
		player.getInventory().setItem(1, chest.removeItemNoUpdate(13));

		BonusChest.tick(level.getServer());
		float health = player.getHealth();
		boolean inventoryEmpty = player.getInventory().isEmpty();
		player.heal(10f);
		player.setAbsorptionAmount(4f);
		BonusChest.tick(level.getServer());
		float healed = player.getHealth();
		float absorption = player.getAbsorptionAmount();
		BonusChest.forget(player);
		level.getServer().getPlayerList().remove(player);

		check(helper, health == 1f, "health after taking the diamond is " + health + ", expected half a heart");
		check(helper, inventoryEmpty, "the inventory wasn't emptied when the chest blew up");
		check(helper, level.getBlockState(pos).isAir(), "the chest is still there: " + level.getBlockState(pos));
		check(helper, level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4)).isEmpty(),
			"the chest spilled its loot when it blew up");
		check(helper, healed == 1f && absorption == 0f, "healed back up to " + healed + " + " + absorption + " absorption");
		helper.succeed();
	}

	/** A lane that ends in a hillside carries on as stairs up to the top, and only there. */
	@GameTest(padding = PADDING)
	public void hubLaneClimbsOutOfAHill(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = terrain(helper);
		SpawnHub.build(level, c);
		assertExit(helper, level, c);

		// /farout start rebuilds the hub every time; that must not dig the stairs any further.
		SpawnHub.build(level, c);
		assertExit(helper, level, c);
		helper.succeed();
	}

	@GameTest(padding = PADDING)
	public void hubClearsOverhangs(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = terrain(helper);
		BlockPos overhang = c.above(7);
		level.setBlock(overhang, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
		SpawnHub.build(level, c);
		check(helper, level.getBlockState(overhang).isAir(), "overhang above the hub centre was left in place");
		helper.succeed();
	}

	/** Mobs and dropped items in the hub and on its exit stairs are removed; the field next to it is left alone. */
	@GameTest(padding = PADDING)
	public void hubClearsEntities(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = terrain(helper);
		int reach = SpawnHub.REACH;
		Entity grazing = cow(level, c.offset(2, 0, 2));
		Entity outside = cow(level, c.offset(reach + 3, 0, reach + 3));
		ItemEntity seeds = new ItemEntity(level, c.getX() + 0.5, c.getY() + HILL + 1, c.getZ() - reach - HILL + 0.5,
			new ItemStack(Items.WHEAT_SEEDS));
		level.addFreshEntity(seeds);

		SpawnHub.build(level, c);
		SpawnHub.clearEntities(level, c);
		check(helper, grazing.isRemoved(), "cow in the hub centre was left in place");
		check(helper, seeds.isRemoved(), "seeds on the north exit stairs were left in place");
		check(helper, !outside.isRemoved(), "cow outside the hub was removed too");
		outside.discard();
		helper.succeed();
	}

	/**
	 * A run world has the overworld's terrain as generated, without anything changed in the overworld since,
	 * and it's gone once deleted.
	 */
	@GameTest(maxTicks = 200)
	public void runWorldCopiesTheOverworld(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel overworld = server.overworld();
		// Far from the test structures, so the overworld here is still as generated.
		BlockPos column = new BlockPos(3000, 0, -3000);
		overworld.getChunk(column);
		BlockPos changed = column.atY(overworld.getMaxY() - 1);
		overworld.setBlock(changed, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);

		RuntimeLevelHandle handle = RunWorlds.create(server);
		ServerLevel copy = handle.asLevel();
		check(helper, copy.getSeed() == overworld.getSeed(), "run world seed " + copy.getSeed() + " is not the overworld's");
		copy.getChunk(column);
		for (int y = overworld.getMinY(); y < overworld.getMaxY() - 1; y++) {
			BlockPos pos = column.atY(y);
			check(helper, copy.getBlockState(pos).equals(overworld.getBlockState(pos)),
				"run world differs at " + pos + ": " + copy.getBlockState(pos) + ", overworld has " + overworld.getBlockState(pos));
		}
		check(helper, !copy.getBlockState(changed).is(Blocks.GOLD_BLOCK), "a block placed in the overworld showed up in the run world");
		overworld.setBlock(changed, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);

		handle.delete();
		helper.succeedWhen(() -> check(helper, server.getLevel(copy.dimension()) == null, "run world still loaded after delete"));
	}

	/** A run world's clock runs on the server, not just in the client's sky, so beds work there at night. */
	@GameTest(maxTicks = 100)
	public void runWorldClockRuns(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		RuntimeLevelHandle handle = RunWorlds.create(server);
		ServerLevel copy = handle.asLevel();
		Holder<WorldClock> clock = server.registryAccess().getOrThrow(WorldClocks.OVERWORLD);
		long start = copy.clockManager().getTotalTicks(clock);
		helper.runAfterDelay(40, () -> {
			long now = copy.clockManager().getTotalTicks(clock);
			handle.delete();
			check(helper, now > start, "run world clock stood still at " + now + " for 40 ticks");
			helper.succeed();
		});
	}

	/** Morning Coffee only brews in the minute after waking up. */
	@GameTest
	public void morningCoffeeNeedsWakingUp(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		CraftingMenu menu = new CraftingMenu(0, player.getInventory(), ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(BlockPos.ZERO)));
		// R R / RCR / RBR: buttons around a bed, a torch below it.
		Item[] grid = {Items.OAK_BUTTON, Items.AIR, Items.OAK_BUTTON, Items.OAK_BUTTON, Items.RED_BED, Items.OAK_BUTTON,
			Items.OAK_BUTTON, Items.TORCH, Items.OAK_BUTTON};
		for (int i = 0; i < grid.length; i++) {
			menu.getSlot(1 + i).set(new ItemStack(grid[i]));
		}
		ItemStack before = menu.getSlot(0).getItem();

		PowerUps.openCoffeeWindow(player);
		menu.slotsChanged(menu.getSlot(1).container);
		ItemStack after = menu.getSlot(0).getItem();
		PowerUps.clear(player);
		helper.getLevel().getServer().getPlayerList().remove(player);

		check(helper, before.isEmpty(), "Morning Coffee crafted without waking up: " + before);
		check(helper, Power.of(after) == Power.MORNING_COFFEE, "no Morning Coffee right after waking up, got " + after);
		helper.succeed();
	}

	private static Entity cow(ServerLevel level, BlockPos pos) {
		Entity cow = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
		cow.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		level.addFreshEntity(cow);
		return cow;
	}

	/**
	 * Flat stone ground with a {@link #HILL}-high plateau starting right past the end of the north lane;
	 * returns the hub centre (feet level) on it.
	 */
	private static BlockPos terrain(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos c = helper.absolutePos(new BlockPos(4, 1, 4));
		int reach = SpawnHub.REACH;
		for (int dx = -reach - 4; dx <= reach + 4; dx++) {
			for (int dz = -reach - 20; dz <= reach + 4; dz++) {
				for (int dy = -1; dy <= 16; dy++) {
					boolean ground = dy == -1 || (dz < -reach && dy < HILL);
					level.setBlock(c.offset(dx, dy, dz),
						ground ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
			}
		}
		return c;
	}

	private static void assertExit(GameTestHelper helper, ServerLevel level, BlockPos c) {
		int reach = SpawnHub.REACH;
		for (int side = -3; side <= 3; side++) {
			for (int step = 1; step <= HILL; step++) {
				BlockPos stair = c.offset(side, step - 1, -reach - step);
				BlockState state = level.getBlockState(stair);
				check(helper, state.is(Blocks.STONE_BRICK_STAIRS) && state.getValue(StairBlock.FACING) == Direction.NORTH,
					"north exit step " + step + " (side " + side + ") is " + state + ", expected north-facing stairs");
				check(helper, level.getBlockState(stair.above()).isAir() && level.getBlockState(stair.above(2)).isAir(),
					"no headroom above north exit step " + step + " (side " + side + ")");
			}
			// On top of the hill: plain ground again, no stairs dug into it.
			BlockPos past = c.offset(side, HILL - 1, -reach - HILL - 1);
			check(helper, level.getBlockState(past).is(Blocks.STONE),
				"stairs carried on past the hilltop at side " + side + ": " + level.getBlockState(past));
		}
		// The other lanes end on flat ground and need no stairs.
		check(helper, !level.getBlockState(c.offset(reach + 1, 0, 0)).is(Blocks.STONE_BRICK_STAIRS),
			"east lane got stairs on flat ground");
		check(helper, !level.getBlockState(c.offset(0, 0, reach + 1)).is(Blocks.STONE_BRICK_STAIRS),
			"south lane got stairs on flat ground");
	}

	private static void check(GameTestHelper helper, boolean condition, String message) {
		helper.assertTrue(condition, Component.literal(message));
	}
}
