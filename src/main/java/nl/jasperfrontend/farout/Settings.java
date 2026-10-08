package nl.jasperfrontend.farout;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Player-tweakable settings in config/farout/settings.properties. Edit the file by hand or use
 * /farout settings &lt;name&gt; &lt;value&gt;; either way it's read again at the start of every run.
 */
final class Settings {
	static final String PATH_WIDTH = "pathWidth";
	static final String LEEWAY_BLOCKS = "leewayBlocks";

	static final int DEFAULT_PATH_WIDTH = 5;
	static final int DEFAULT_LEEWAY_BLOCKS = 100;

	/** Width in blocks of the lane you must stay in, centred on the start line. */
	final int pathWidth;
	/** Blocks you may walk outside the lane per run; use more and you're struck down. */
	final int leewayBlocks;

	private Settings(int pathWidth, int leewayBlocks) {
		this.pathWidth = pathWidth;
		this.leewayBlocks = leewayBlocks;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("farout").resolve("settings.properties");
	}

	/** Reads the file, writing it with defaults first if it doesn't exist yet so it's easy to find. */
	static Settings load() {
		Properties props = read();
		Settings settings = new Settings(
			readInt(props, PATH_WIDTH, DEFAULT_PATH_WIDTH, 1),
			readInt(props, LEEWAY_BLOCKS, DEFAULT_LEEWAY_BLOCKS, 0));
		if (Files.notExists(file())) {
			try {
				settings.save();
			} catch (IOException e) {
				Farout.LOGGER.error("Could not write default settings", e);
			}
		}
		return settings;
	}

	Settings with(String name, int value) {
		return switch (name) {
			case PATH_WIDTH -> new Settings(value, leewayBlocks);
			case LEEWAY_BLOCKS -> new Settings(pathWidth, value);
			default -> throw new IllegalArgumentException("Unknown setting " + name);
		};
	}

	void save() throws IOException {
		Properties props = new Properties();
		props.setProperty(PATH_WIDTH, Integer.toString(pathWidth));
		props.setProperty(LEEWAY_BLOCKS, Integer.toString(leewayBlocks));
		Path file = file();
		Files.createDirectories(file.getParent());
		try (Writer writer = Files.newBufferedWriter(file)) {
			props.store(writer, "Farout settings: " + PATH_WIDTH + " = lane width in blocks, "
				+ LEEWAY_BLOCKS + " = blocks you may walk outside it per run");
		}
	}

	private static int readInt(Properties props, String key, int fallback, int min) {
		String value = props.getProperty(key);
		if (value == null) {
			return fallback;
		}
		try {
			return Math.max(min, Integer.parseInt(value.trim()));
		} catch (NumberFormatException e) {
			Farout.LOGGER.warn("Ignoring malformed setting {}={}, using {}", key, value, fallback);
			return fallback;
		}
	}

	private static Properties read() {
		Properties props = new Properties();
		Path file = file();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				props.load(reader);
			} catch (IOException e) {
				Farout.LOGGER.error("Could not read settings", e);
			}
		}
		return props;
	}
}
