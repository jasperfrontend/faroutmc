package nl.jasperfrontend.farout;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Split times of the best finished run per direction and seed, in config/farout/personal-bests.properties.
 * Per seed because terrain makes or breaks a run: a PB set across an ocean says nothing about a mountain range.
 * Keyed by target and split interval too, so test runs (runClientShort) never mix with real ones.
 * Keys from before 1.0.23 have no seed; they stay in the file but are no longer read.
 * Runs from /farout here are kept per spot as well, with its x and z on the end of the key: another start is
 * another course. Runs with the bonus chest get ".bonus" on the end: it saves so much time that they'd
 * leave every run without it behind.
 */
final class PersonalBests {
	private PersonalBests() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("farout").resolve("personal-bests.properties");
	}

	/** spot: the start area's centre for a run from /farout here, null for one from spawn. */
	private static String key(Heading heading, long seed, BlockPos spot, boolean bonusChest) {
		String key = heading.id() + "." + seed + "." + Farout.TARGET + "." + Farout.SPLIT_INTERVAL;
		if (spot != null) {
			key += "." + spot.getX() + "." + spot.getZ();
		}
		return bonusChest ? key + ".bonus" : key;
	}

	/** Split ticks of the personal best (the last entry is the finish), or null if there is none. */
	static long[] load(Heading heading, long seed, BlockPos spot, boolean bonusChest) {
		String key = key(heading, seed, spot, bonusChest);
		String value = read().getProperty(key);
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Arrays.stream(value.split(",")).mapToLong(Long::parseLong).toArray();
		} catch (NumberFormatException e) {
			Farout.LOGGER.warn("Ignoring malformed personal best for {}: {}", key, value);
			return null;
		}
	}

	static void save(Heading heading, long seed, BlockPos spot, boolean bonusChest, List<Long> splits) throws IOException {
		Properties props = read();
		props.setProperty(key(heading, seed, spot, bonusChest), String.join(",", splits.stream().map(String::valueOf).toList()));
		Path file = file();
		Files.createDirectories(file.getParent());
		try (Writer writer = Files.newBufferedWriter(file)) {
			props.store(writer, "Farout personal bests: split times in ticks (20 per second), last one is the finish");
		}
	}

	private static Properties read() {
		Properties props = new Properties();
		Path file = file();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				props.load(reader);
			} catch (IOException e) {
				Farout.LOGGER.error("Could not read personal bests", e);
			}
		}
		return props;
	}
}
