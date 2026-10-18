package nl.jasperfrontend.farout;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.Level;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Every session, from /farout start to coming home, happens in its own temporary copy of the overworld:
 * same seed, same generator, so the same terrain, with the start area built at its spawn. The copy is
 * deleted when the run ends, so nothing built, broken or looted on one run is still there on the next,
 * and the player's own world is never touched. Fantasy deletes any copy still open when the server stops.
 */
public final class RunWorlds {
	private static final String PREFIX = "run_";

	/** The world of each player's current session. */
	private static final Map<UUID, RuntimeLevelHandle> OPEN = new HashMap<>();
	/** Worlds of runs that ended, waiting for everyone to leave so they can be deleted. */
	private static final List<Closing> CLOSING = new ArrayList<>();

	/** sendHomeAt: server tick from which anyone still alive in there is taken home. */
	private record Closing(RuntimeLevelHandle handle, long sendHomeAt) {
	}

	private RunWorlds() {
	}

	/** Whether this is one of Farout's run worlds; works on the client too. */
	public static boolean isRunWorld(ResourceKey<Level> dimension) {
		Identifier id = dimension.identifier();
		return id.getNamespace().equals(Farout.MOD_ID) && id.getPath().startsWith(PREFIX);
	}

	/** Opens a fresh copy of the overworld for the player's session, closing their last one if it's still open. */
	static ServerLevel open(ServerPlayer player) {
		close(player.getUUID(), 0);
		RuntimeLevelHandle handle = create(player.level().getServer());
		OPEN.put(player.getUUID(), handle);
		return handle.asLevel();
	}

	/** The world of the player's current session, or null if they have none. */
	static ServerLevel level(UUID player) {
		RuntimeLevelHandle handle = OPEN.get(player);
		return handle == null ? null : handle.asLevel();
	}

	/**
	 * A temporary world generated exactly like the overworld, with its game rules and difficulty. It keeps
	 * its own clock, starting at sunrise, so a run never changes the time in the player's own world.
	 */
	static RuntimeLevelHandle create(MinecraftServer server) {
		ServerLevel overworld = server.overworld();
		RuntimeLevelConfig config = new RuntimeLevelConfig()
			.setSeed(overworld.getSeed())
			.setGenerator(overworld.getChunkSource().getGenerator())
			.setDimensionType(overworld.dimensionTypeRegistration())
			.setMirrorOverworldGameRules(true)
			.setMirrorOverworldDifficulty(true)
			.setClockTime(WorldClocks.OVERWORLD, 0);
		// Random, so a new run never collides with the last one's world while that's still being deleted.
		String key = PREFIX + Long.toHexString(ThreadLocalRandom.current().nextLong()).toLowerCase(Locale.ROOT);
		return Fantasy.get(server).openTemporaryLevel(Identifier.fromNamespaceAndPath(Farout.MOD_ID, key), config);
	}

	/**
	 * Marks the player's run world for deletion. It goes once nobody's in it; anyone still alive in there
	 * after graceTicks is taken home. The dead stay until they respawn.
	 */
	static void close(UUID player, int graceTicks) {
		RuntimeLevelHandle handle = OPEN.remove(player);
		if (handle != null) {
			CLOSING.add(new Closing(handle, handle.asLevel().getServer().getTickCount() + graceTicks));
		}
	}

	static void tick(MinecraftServer server) {
		Iterator<Closing> it = CLOSING.iterator();
		while (it.hasNext()) {
			Closing closing = it.next();
			ServerLevel level = closing.handle.asLevel();
			if (server.getTickCount() >= closing.sendHomeAt) {
				for (ServerPlayer player : List.copyOf(level.players())) {
					if (player.isAlive()) {
						Farout.sendHome(player);
					}
				}
			}
			if (level.players().isEmpty()) {
				closing.handle.delete();
				it.remove();
			}
		}
	}

	/** On server stop: Fantasy deletes the worlds themselves. */
	static void forgetAll() {
		OPEN.clear();
		CLOSING.clear();
	}
}
