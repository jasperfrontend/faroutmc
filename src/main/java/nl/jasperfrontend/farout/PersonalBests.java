package nl.jasperfrontend.farout;

import net.fabricmc.loader.api.FabricLoader;

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
 */
final class PersonalBests {
	private PersonalBests() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("farout").resolve("personal-bests.properties");
	}

	private static String key(Heading heading, long seed) {
		return heading.id() + "." + seed + "." + Farout.TARGET + "." + Farout.SPLIT_INTERVAL;
	}

	/** Split ticks of the personal best (the last entry is the finish), or null if there is none. */
	static long[] load(Heading heading, long seed) {
		String value = read().getProperty(key(heading, seed));
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Arrays.stream(value.split(",")).mapToLong(Long::parseLong).toArray();
		} catch (NumberFormatException e) {
			Farout.LOGGER.warn("Ignoring malformed personal best for {}: {}", key(heading, seed), value);
			return null;
		}
	}

	static void save(Heading heading, long seed, List<Long> splits) throws IOException {
		Properties props = read();
		props.setProperty(key(heading, seed), String.join(",", splits.stream().map(String::valueOf).toList()));
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
