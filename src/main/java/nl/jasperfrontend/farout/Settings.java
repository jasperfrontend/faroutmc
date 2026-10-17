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
	static final String AIRTIME_HEIGHT = "airtimeHeight";
	static final String AIRTIME_SECONDS = "airtimeSeconds";

	static final int DEFAULT_PATH_WIDTH = 7;
	static final int DEFAULT_LEEWAY_BLOCKS = 200;
	static final int DEFAULT_AIRTIME_HEIGHT = 50;
	static final int DEFAULT_AIRTIME_SECONDS = 30;

	/** Width in blocks of the lane you must stay in, centred on the start line. */
	final int pathWidth;
	/** Blocks you may walk outside the lane per run; use more and you're struck down. */
	final int leewayBlocks;
	/** Blocks above the start area the player hangs during airtime; 0 skips airtime. */
	final int airtimeHeight;
	/** How long airtime lasts before the drop. */
	final int airtimeSeconds;

	private Settings(int pathWidth, int leewayBlocks, int airtimeHeight, int airtimeSeconds) {
		this.pathWidth = pathWidth;
		this.leewayBlocks = leewayBlocks;
		this.airtimeHeight = airtimeHeight;
		this.airtimeSeconds = airtimeSeconds;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("farout").resolve("settings.properties");
	}

	/** Reads the file, writing it with defaults first if it doesn't exist yet so it's easy to find. */
	static Settings load() {
		Properties props = read();
		Settings settings = new Settings(
			readInt(props, PATH_WIDTH, DEFAULT_PATH_WIDTH, 1),
			readInt(props, LEEWAY_BLOCKS, DEFAULT_LEEWAY_BLOCKS, 0),
			readInt(props, AIRTIME_HEIGHT, DEFAULT_AIRTIME_HEIGHT, 0),
			readInt(props, AIRTIME_SECONDS, DEFAULT_AIRTIME_SECONDS, 1));
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
			case PATH_WIDTH -> new Settings(value, leewayBlocks, airtimeHeight, airtimeSeconds);
			case LEEWAY_BLOCKS -> new Settings(pathWidth, value, airtimeHeight, airtimeSeconds);
			case AIRTIME_HEIGHT -> new Settings(pathWidth, leewayBlocks, value, airtimeSeconds);
			case AIRTIME_SECONDS -> new Settings(pathWidth, leewayBlocks, airtimeHeight, value);
			default -> throw new IllegalArgumentException("Unknown setting " + name);
		};
	}

	void save() throws IOException {
		Properties props = new Properties();
		props.setProperty(PATH_WIDTH, Integer.toString(pathWidth));
		props.setProperty(LEEWAY_BLOCKS, Integer.toString(leewayBlocks));
		props.setProperty(AIRTIME_HEIGHT, Integer.toString(airtimeHeight));
		props.setProperty(AIRTIME_SECONDS, Integer.toString(airtimeSeconds));
		Path file = file();
		Files.createDirectories(file.getParent());
		try (Writer writer = Files.newBufferedWriter(file)) {
			props.store(writer, "Farout settings: " + PATH_WIDTH + " = lane width in blocks, "
				+ LEEWAY_BLOCKS + " = blocks you may walk outside it per run, "
			+ AIRTIME_HEIGHT + " = blocks above the start area you hang before the drop (0 = no airtime), "
			+ AIRTIME_SECONDS + " = seconds of airtime before the drop");
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
