package nl.jasperfrontend.farout;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.MixinEnvironment;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

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
