package nl.jasperfrontend.farout;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Locale;

/**
 * The start area at world spawn: a plus-shaped hub with a lane per direction. A purple carpet line
 * where each lane leaves the centre square is the portal; stepping onto it commits to that direction.
 *
 * <pre>
 *        | N |
 *     ---+   +---
 *      W   c   E      centre square: |dx|, |dz| <= HALF
 *     ---+   +---     lanes: HALF wide either side, LANE long
 *        | S |        portal line: the first lane row, at distance HALF + 1
 * </pre>
 * Where the hub sits in a dip, each lane carries on as a staircase up to the surface, so no direction
 * starts with digging out.
 */
final class SpawnHub {
	private static final int HALF = 3;
	private static final int LANE = 8;
	private static final int CLEAR_HEIGHT = 4;
	private static final int WALL_HEIGHT = 3;
	private static final int PORTAL = HALF + 1;
	static final int REACH = HALF + LANE;
	/** Longest exit staircase; terrain still rising after this many steps is left as it is. */
	private static final int MAX_EXIT_STEPS = 48;

	/** Marks every entity the hub summons, so a rebuild can remove the old ones first. */
	static final String HUB_TAG = "farout_hub";
	// Roughly eye level (1.62) plus room for the 2x-scaled text, which grows upwards from its anchor.
	private static final double LABEL_HEIGHT = 1.7;

	private static final BlockState FLOOR = Blocks.STONE_BRICKS.defaultBlockState();
	private static final BlockState WALL = Blocks.POLISHED_ANDESITE.defaultBlockState();
	private static final BlockState CARPET = Blocks.PURPLE_CARPET.defaultBlockState();
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private SpawnHub() {
	}

	/** The hub centre (feet level) above world spawn. */
	static BlockPos centre(ServerLevel level) {
		BlockPos spawn = level.getRespawnData().pos();
		return new BlockPos(spawn.getX(), surface(level, spawn.getX(), spawn.getZ()), spawn.getZ());
	}

	/** (Re)builds the hub around the centre, so a damaged hub is repaired on every start. */
	static void build(ServerLevel level, BlockPos c) {
		for (int dx = -REACH; dx <= REACH; dx++) {
			for (int dz = -REACH; dz <= REACH; dz++) {
				int ax = Math.abs(dx);
				int az = Math.abs(dz);
				boolean open = (ax <= HALF && az <= REACH) || (az <= HALF && ax <= REACH);
				boolean wall = (ax == PORTAL && az >= PORTAL) || (az == PORTAL && ax >= PORTAL);
				if (!open && !wall) {
					continue;
				}

				set(level, c.offset(dx, -1, dz), FLOOR);
				for (int dy = 0; dy < CLEAR_HEIGHT; dy++) {
					BlockState state = AIR;
					if (wall && dy < WALL_HEIGHT) {
						state = WALL;
					} else if (dy == 0 && isPortal(ax, az)) {
						state = CARPET;
					}
					set(level, c.offset(dx, dy, dz), state);
				}
				if (open) {
					// No overhang left hanging over the hub, whatever the hill above it.
					int top = surface(level, c.getX() + dx, c.getZ() + dz);
					for (int y = c.getY() + CLEAR_HEIGHT; y < top; y++) {
						set(level, new BlockPos(c.getX() + dx, y, c.getZ() + dz), AIR);
					}
				}
			}
		}
		for (Heading heading : Heading.values()) {
			exit(level, c, heading);
		}
	}

	/**
	 * Carries a lane on past its end as stairs cut into the terrain, one block up per row, until the
	 * ground ahead is no higher than where the stairs got to. Flat or falling ground gets no stairs.
	 */
	private static void exit(ServerLevel level, BlockPos c, Heading heading) {
		BlockState stair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
			.setValue(StairBlock.FACING, Direction.fromYRot(heading.yaw));
		int feet = c.getY();
		for (int step = 1; step <= MAX_EXIT_STEPS; step++) {
			int[] surface = new int[2 * HALF + 1];
			int highest = Integer.MIN_VALUE;
			for (int side = -HALF; side <= HALF; side++) {
				BlockPos column = exitColumn(c, heading, step, side);
				surface[side + HALF] = surface(level, column.getX(), column.getZ());
				highest = Math.max(highest, surface[side + HALF]);
			}
			if (highest <= feet) {
				return;
			}
			for (int side = -HALF; side <= HALF; side++) {
				BlockPos column = exitColumn(c, heading, step, side);
				set(level, column.atY(feet), stair);
				// Clear to the sky, with headroom for whoever stands on this step.
				int top = Math.max(surface[side + HALF], feet + 3);
				for (int y = feet + 1; y < top; y++) {
					set(level, column.atY(y), AIR);
				}
			}
			feet++;
		}
	}

	/** The column {@code step} rows past the end of a lane, {@code side} blocks off its middle. */
	private static BlockPos exitColumn(BlockPos c, Heading heading, int step, int side) {
		int forward = REACH + step;
		return new BlockPos(
			c.getX() + heading.stepX * forward + (heading.stepX == 0 ? side : 0),
			0,
			c.getZ() + heading.stepZ * forward + (heading.stepZ == 0 ? side : 0));
	}

	/** Feet level on top of the ground at this column, ignoring leaves. */
	private static int surface(ServerLevel level, int x, int z) {
		level.getChunk(x >> 4, z >> 4); // load it, or the heightmap reads as empty
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	/**
	 * Floating direction names above each portal line, and the power-up recipes in the corners: vanilla
	 * display entities, summoned with the /summon command because TextDisplay's setters are private. Old
	 * ones are removed first so rebuilds don't stack.
	 */
	static void labels(ServerLevel level, BlockPos c) {
		CommandSourceStack source = level.getServer().createCommandSourceStack()
			.withLevel(level).withSuppressedOutput();
		Commands commands = level.getServer().getCommands();
		commands.performPrefixedCommand(source, "kill @e[tag=" + HUB_TAG + "]");

		double x = c.getX() + 0.5;
		double y = c.getY() + LABEL_HEIGHT;
		double z = c.getZ() + 0.5;
		label(commands, source, Heading.NORTH, x, y, z - PORTAL);
		label(commands, source, Heading.SOUTH, x, y, z + PORTAL);
		label(commands, source, Heading.EAST, x + PORTAL, y, z);
		label(commands, source, Heading.WEST, x - PORTAL, y, z);
		RecipeBoard.build(commands, source, c);
	}

	private static void label(Commands commands, CommandSourceStack source, Heading heading, double x, double y, double z) {
		commands.performPrefixedCommand(source, String.format(Locale.ROOT,
			"summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"%s\"],billboard:\"center\","
				+ "text:{text:\"%s\",color:\"light_purple\",bold:true},"
				+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
				+ "translation:[0f,0f,0f],scale:[2f,2f,2f]}}",
			x, y, z, HUB_TAG, heading.label.toUpperCase(Locale.ROOT)));
	}

	private static boolean isPortal(int ax, int az) {
		return (az == PORTAL && ax <= HALF) || (ax == PORTAL && az <= HALF);
	}

	private static void set(ServerLevel level, BlockPos pos, BlockState state) {
		level.setBlock(pos, state, Block.UPDATE_CLIENTS);
	}

	/** Whether a hub exists around this centre (its north portal carpet is in place). */
	static boolean isBuilt(ServerLevel level, BlockPos c) {
		return level.getBlockState(c.offset(0, 0, -PORTAL)).is(Blocks.PURPLE_CARPET);
	}

	/** Standing in the open centre square, where the portals get armed. */
	static boolean inCentre(BlockPos c, BlockPos player) {
		return Math.abs(player.getX() - c.getX()) <= HALF && Math.abs(player.getZ() - c.getZ()) <= HALF
			&& Math.abs(player.getY() - c.getY()) <= 2;
	}

	/** Still somewhere in or right around the hub; anything further means they left another way. */
	static boolean isNear(BlockPos c, BlockPos player) {
		return Math.abs(player.getX() - c.getX()) <= REACH + 2 && Math.abs(player.getZ() - c.getZ()) <= REACH + 2
			&& Math.abs(player.getY() - c.getY()) <= CLEAR_HEIGHT;
	}

	/** The direction whose portal the player has stepped through, or null while they're still in the centre. */
	static Heading crossed(BlockPos c, BlockPos player) {
		int dx = player.getX() - c.getX();
		int dz = player.getZ() - c.getZ();
		if (Math.max(Math.abs(dx), Math.abs(dz)) < PORTAL) {
			return null;
		}
		if (Math.abs(dz) >= Math.abs(dx)) {
			return dz < 0 ? Heading.NORTH : Heading.SOUTH;
		}
		return dx > 0 ? Heading.EAST : Heading.WEST;
	}

	/** Portal particles hanging over each carpet line, shown only to this player. */
	static void particles(ServerLevel level, ServerPlayer player, BlockPos c) {
		double x = c.getX() + 0.5;
		double y = c.getY() + 1.0;
		double z = c.getZ() + 0.5;
		double spread = HALF * 0.6;
		send(level, player, x, y, z - PORTAL, spread, 0);
		send(level, player, x, y, z + PORTAL, spread, 0);
		send(level, player, x + PORTAL, y, z, 0, spread);
		send(level, player, x - PORTAL, y, z, 0, spread);
	}

	private static void send(ServerLevel level, ServerPlayer player, double x, double y, double z, double dx, double dz) {
		level.sendParticles(player, ParticleTypes.PORTAL, false, true, x, y, z, 12, dx, 0.6, dz, 0.2);
	}
}
