package nl.jasperfrontend.farout;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Player-tweakable settings in config/farout/settings.properties. Edit the file by hand, use
 * /farout settings &lt;name&gt; &lt;value&gt; or the settings screen; it's read again at the start of every run.
 */
public final class Settings {
	public static final String PATH_WIDTH = "pathWidth";
	public static final String LEEWAY_BLOCKS = "leewayBlocks";
	public static final String AIRTIME_HEIGHT = "airtimeHeight";
	public static final String AIRTIME_SECONDS = "airtimeSeconds";
	/** Every setting, in the order they're listed. */
	public static final String[] NAMES = {PATH_WIDTH, LEEWAY_BLOCKS, AIRTIME_HEIGHT, AIRTIME_SECONDS};

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

	/** Lowest value the command and the settings screen accept. */
	public static int min(String name) {
		return switch (name) {
			case PATH_WIDTH, AIRTIME_SECONDS -> 1;
			case LEEWAY_BLOCKS, AIRTIME_HEIGHT -> 0;
			default -> throw new IllegalArgumentException("Unknown setting " + name);
		};
	}

	/** Highest value the command and the settings screen accept. */
	public static int max(String name) {
		return switch (name) {
			case PATH_WIDTH -> 64;
			case LEEWAY_BLOCKS -> 100_000;
			case AIRTIME_HEIGHT, AIRTIME_SECONDS -> 300;
			default -> throw new IllegalArgumentException("Unknown setting " + name);
		};
	}

	public static int defaultValue(String name) {
		return switch (name) {
			case PATH_WIDTH -> DEFAULT_PATH_WIDTH;
			case LEEWAY_BLOCKS -> DEFAULT_LEEWAY_BLOCKS;
			case AIRTIME_HEIGHT -> DEFAULT_AIRTIME_HEIGHT;
			case AIRTIME_SECONDS -> DEFAULT_AIRTIME_SECONDS;
			default -> throw new IllegalArgumentException("Unknown setting " + name);
		};
	}

	public int get(String name) {
		return switch (name) {
			case PATH_WIDTH -> pathWidth;
			case LEEWAY_BLOCKS -> leewayBlocks;
			case AIRTIME_HEIGHT -> airtimeHeight;
			case AIRTIME_SECONDS -> airtimeSeconds;
			default -> throw new IllegalArgumentException("Unknown setting " + name);
		};
	}

	/** Reads the file, writing it with defaults first if it doesn't exist yet so it's easy to find. */
	public static Settings load() {
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

	public Settings with(String name, int value) {
		return switch (name) {
			case PATH_WIDTH -> new Settings(value, leewayBlocks, airtimeHeight, airtimeSeconds);
			case LEEWAY_BLOCKS -> new Settings(pathWidth, value, airtimeHeight, airtimeSeconds);
			case AIRTIME_HEIGHT -> new Settings(pathWidth, leewayBlocks, value, airtimeSeconds);
			case AIRTIME_SECONDS -> new Settings(pathWidth, leewayBlocks, airtimeHeight, value);
			default -> throw new IllegalArgumentException("Unknown setting " + name);
		};
	}

	public void save() throws IOException {
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
