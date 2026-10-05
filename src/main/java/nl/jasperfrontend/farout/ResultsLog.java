package nl.jasperfrontend.farout;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

final class ResultsLog {
	private static final String HEADER = "date,direction,time,seed\n";

	private ResultsLog() {
	}

	static void append(Heading heading, long ticks, long seed) throws IOException {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("farout").resolve("results.csv");
		Files.createDirectories(file.getParent());
		if (Files.notExists(file)) {
			Files.writeString(file, HEADER);
		}
		String line = String.join(",",
			LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
			heading.id(),
			Format.time(ticks),
			Long.toString(seed)) + "\n";
		Files.writeString(file, line, StandardOpenOption.APPEND);
	}
}
