package nl.jasperfrontend.farout;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Every finished run, in config/farout/results.csv. start is the centre of the start area as "x y z" (or
 * where the run started, for /farout start &lt;direction&gt;), so a /tp there and /farout here runs the same
 * course again. Files from before 1.0.24 have no start column; they get one, left empty for the old rows,
 * the first time they're read or written.
 */
public final class ResultsLog {
	private static final String OLD_HEADER = "date,direction,time,seed";
	private static final String HEADER = OLD_HEADER + ",start";

	/** start is "x y z", or empty for runs from before 1.0.24. */
	public record Result(String date, String direction, String time, String seed, String start) {
	}

	private ResultsLog() {
	}

	static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("farout").resolve("results.csv");
	}

	static void append(Heading heading, long ticks, long seed, BlockPos start) throws IOException {
		append(file(), heading, ticks, seed, start);
	}

	static void append(Path file, Heading heading, long ticks, long seed, BlockPos start) throws IOException {
		Files.createDirectories(file.getParent());
		if (Files.notExists(file)) {
			Files.writeString(file, HEADER + "\n");
		}
		migrate(file);
		String line = String.join(",",
			LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
			heading.id(),
			Format.time(ticks),
			Long.toString(seed),
			start.getX() + " " + start.getY() + " " + start.getZ()) + "\n";
		Files.writeString(file, line, StandardOpenOption.APPEND);
	}

	/** Oldest first; an unreadable file reads as empty. */
	public static List<Result> load() {
		return load(file());
	}

	static List<Result> load(Path file) {
		List<Result> results = new ArrayList<>();
		if (Files.notExists(file)) {
			return results;
		}
		try {
			migrate(file);
			List<String> lines = Files.readAllLines(file);
			for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
				String[] cells = line.split(",", -1);
				if (cells.length == 5) {
					results.add(new Result(cells[0], cells[1], cells[2], cells[3], cells[4].trim()));
				}
			}
		} catch (IOException e) {
			Farout.LOGGER.error("Could not read results.csv", e);
		}
		return results;
	}

	/** Gives a file from before 1.0.24 the start column, empty for every run already in it. */
	private static void migrate(Path file) throws IOException {
		List<String> lines = Files.readAllLines(file);
		if (lines.isEmpty() || !lines.getFirst().equals(OLD_HEADER)) {
			return;
		}
		List<String> migrated = new ArrayList<>(lines.size());
		migrated.add(HEADER);
		for (String line : lines.subList(1, lines.size())) {
			if (!line.isBlank()) {
				migrated.add(line + ",");
			}
		}
		Files.write(file, migrated);
	}
}
