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
 * course again. bonusChest is "yes" for a run with the bonus chest in its start area, "no" otherwise.
 * Older files get the columns they're missing the first time they're read or written: an empty start for
 * runs from before 1.0.24, and "no" bonus chest for runs from before 1.0.26, when there wasn't one yet.
 */
public final class ResultsLog {
	private static final String[] COLUMNS = {"date", "direction", "time", "seed", "start", "bonusChest"};
	/** What each column is filled with in rows from before it existed. */
	private static final String[] MISSING = {"", "", "", "", "", "no"};
	private static final String HEADER = String.join(",", COLUMNS);

	/** start is "x y z", or empty for runs from before 1.0.24. */
	public record Result(String date, String direction, String time, String seed, String start, boolean bonusChest) {
	}

	private ResultsLog() {
	}

	static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("farout").resolve("results.csv");
	}

	static void append(Heading heading, long ticks, long seed, BlockPos start, boolean bonusChest) throws IOException {
		append(file(), heading, ticks, seed, start, bonusChest);
	}

	static void append(Path file, Heading heading, long ticks, long seed, BlockPos start, boolean bonusChest)
		throws IOException {
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
			start.getX() + " " + start.getY() + " " + start.getZ(),
			bonusChest ? "yes" : "no") + "\n";
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
				if (cells.length == COLUMNS.length) {
					results.add(new Result(cells[0], cells[1], cells[2], cells[3], cells[4].trim(),
						cells[5].trim().equals("yes")));
				}
			}
		} catch (IOException e) {
			Farout.LOGGER.error("Could not read results.csv", e);
		}
		return results;
	}

	/** Gives a file from an older version the columns it's missing, filled in for every run already in it. */
	private static void migrate(Path file) throws IOException {
		List<String> lines = Files.readAllLines(file);
		if (lines.isEmpty() || lines.getFirst().equals(HEADER)) {
			return;
		}
		int has = lines.getFirst().split(",", -1).length;
		if (has >= COLUMNS.length || !HEADER.startsWith(lines.getFirst() + ",")) {
			Farout.LOGGER.warn("Leaving results.csv with an unknown header as it is: {}", lines.getFirst());
			return;
		}
		StringBuilder padding = new StringBuilder();
		for (int i = has; i < COLUMNS.length; i++) {
			padding.append(',').append(MISSING[i]);
		}
		List<String> migrated = new ArrayList<>(lines.size());
		migrated.add(HEADER);
		for (String line : lines.subList(1, lines.size())) {
			if (!line.isBlank()) {
				migrated.add(line + padding);
			}
		}
		Files.write(file, migrated);
	}
}
