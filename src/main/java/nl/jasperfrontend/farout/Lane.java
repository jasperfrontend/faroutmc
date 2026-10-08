package nl.jasperfrontend.farout;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The straight lane a run must follow: pathWidth blocks wide, centred on the start line. Every block
 * walked outside it uses up a block of leeway; use up more than the run started with and you're struck down.
 */
final class Lane {
	private static final int EDGE_INTERVAL_TICKS = 10;
	private static final int EDGE_BEHIND = 4;
	private static final int EDGE_AHEAD = 20;
	private static final int EDGE_SPACING = 2;
	/** How far the ground ahead may differ from the player's feet before the edge markers stay at feet height. */
	private static final int EDGE_MAX_DROP = 6;

	private final Heading heading;
	/** Lateral coordinates of the lane's two edges; the player's position must stay between them. */
	private final double low;
	private final double high;
	final int leewayBlocks;
	/** Blocks walked outside the lane so far. */
	private double used;
	private double lastX;
	private double lastZ;

	/** centre: the block column the lane is centred on, e.g. the hub's centre or the start position. */
	Lane(Heading heading, int centre, int pathWidth, int leewayBlocks, ServerPlayer player) {
		this.heading = heading;
		this.low = centre + 0.5 - pathWidth / 2.0;
		this.high = centre + 0.5 + pathWidth / 2.0;
		this.leewayBlocks = leewayBlocks;
		this.lastX = player.getX();
		this.lastZ = player.getZ();
	}

	boolean inside(ServerPlayer player) {
		double lateral = heading.lateral(player.getX(), player.getZ());
		return lateral >= low && lateral <= high;
	}

	/** Whole leeway blocks left; negative once it's all gone. */
	int left() {
		return leewayBlocks - (int) Math.floor(used);
	}

	boolean exhausted() {
		return used > leewayBlocks;
	}

	/**
	 * Charges the distance moved since last tick if the player is outside the lane, and plays a tick of
	 * feedback for every whole leeway block used. Returns whether any leeway was used this tick.
	 */
	boolean tick(ServerPlayer player, long runTicks) {
		double dx = player.getX() - lastX;
		double dz = player.getZ() - lastZ;
		lastX = player.getX();
		lastZ = player.getZ();

		if (runTicks % EDGE_INTERVAL_TICKS == 0) {
			showEdges(player);
		}
		if (inside(player)) {
			return false;
		}

		int before = left();
		used += Math.sqrt(dx * dx + dz * dz);
		if (left() < before && !exhausted()) {
			consumed(player);
		}
		return left() < before;
	}

	/** A dull note that drops in pitch as leeway runs out, plus a puff of smoke at your feet. */
	private void consumed(ServerPlayer player) {
		float fraction = leewayBlocks == 0 ? 0f : (float) left() / leewayBlocks;
		player.connection.send(new ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_BASS, SoundSource.MASTER,
			player.getX(), player.getY(), player.getZ(), 0.5f, 0.5f + fraction, player.getRandom().nextLong()));
		player.level().sendParticles(player, ParticleTypes.SMOKE, false, true,
			player.getX(), player.getY() + 0.1, player.getZ(), 4, 0.2, 0.05, 0.2, 0.01);
	}

	/** Faint sparkles along both lane edges around the player, shown only to them. */
	private void showEdges(ServerPlayer player) {
		ServerLevel level = player.level();
		for (int d = -EDGE_BEHIND; d <= EDGE_AHEAD; d += EDGE_SPACING) {
			edge(level, player, low, d);
			edge(level, player, high, d);
		}
	}

	private void edge(ServerLevel level, ServerPlayer player, double lateral, int forward) {
		double x = heading.stepX == 0 ? lateral : player.getX() + heading.stepX * forward;
		double z = heading.stepZ == 0 ? lateral : player.getZ() + heading.stepZ * forward;
		// Follow the ground over hills, but stay at feet height underground or over big drops.
		double y = player.getY();
		BlockPos column = BlockPos.containing(x, y, z);
		if (level.isLoaded(column)) {
			int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
			if (Math.abs(ground - y) <= EDGE_MAX_DROP) {
				y = ground;
			}
		}
		level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, false, true, x, y + 0.2, z, 1, 0, 0, 0, 0);
	}

	/** Out of leeway: a lightning bolt (looks and sounds only, no fire) and instant death. */
	static void strike(ServerPlayer player) {
		ServerLevel level = player.level();
		LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
		if (bolt != null) {
			bolt.setVisualOnly(true);
			bolt.snapTo(player.getX(), player.getY(), player.getZ());
			level.addFreshEntity(bolt);
		}
		// An extra crack right on top of the player, on top of the bolt's own thunder.
		player.connection.send(new ClientboundSoundPacket(
			BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.LIGHTNING_BOLT_IMPACT),
			SoundSource.MASTER, player.getX(), player.getY(), player.getZ(), 1f, 1f, player.getRandom().nextLong()));
		player.kill(level);
	}
}
