package nl.jasperfrontend.farout.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import nl.jasperfrontend.farout.Farout;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeds the player chose to keep after a run, with every direction they've run each one in, in
 * config/farout/saved-seeds.json. No limit: an entry is a few bytes. Kept on the client, so the list is
 * the player's own whichever world or server they ran on. Seeds are strings so they survive being read
 * by anything that can't hold a 64-bit number, like JavaScript.
 */
public final class SavedSeeds {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** directions: {@link nl.jasperfrontend.farout.Heading#id()}s, in the order they were first run. */
	public record Entry(String seed, List<String> directions) {
	}

	private SavedSeeds() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("farout").resolve("saved-seeds.json");
	}

	/** Oldest first; an unreadable file reads as empty, and is only overwritten by the next save. */
	public static List<Entry> load() {
		Path file = file();
		if (Files.notExists(file)) {
			return new ArrayList<>();
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			List<Entry> entries = GSON.fromJson(reader, new TypeToken<List<Entry>>() { }.getType());
			return entries == null ? new ArrayList<>() : new ArrayList<>(entries);
		} catch (IOException | JsonParseException e) {
			Farout.LOGGER.error("Could not read saved seeds", e);
			return new ArrayList<>();
		}
	}

	private static void save(List<Entry> entries) throws IOException {
		Path file = file();
		Files.createDirectories(file.getParent());
		try (Writer writer = Files.newBufferedWriter(file)) {
			GSON.toJson(entries, writer);
		}
	}

	public static boolean contains(String seed) {
		return load().stream().anyMatch(e -> e.seed.equals(seed));
	}

	/** Saves the seed with this direction, or adds the direction to it; returns false if both were already there. */
	public static boolean add(String seed, String direction) throws IOException {
		List<Entry> entries = load();
		for (int i = 0; i < entries.size(); i++) {
			Entry entry = entries.get(i);
			if (entry.seed.equals(seed)) {
				if (entry.directions.contains(direction)) {
					return false;
				}
				List<String> directions = new ArrayList<>(entry.directions);
				directions.add(direction);
				entries.set(i, new Entry(seed, directions));
				save(entries);
				return true;
			}
		}
		entries.add(new Entry(seed, List.of(direction)));
		save(entries);
		return true;
	}

	public static void remove(String seed) throws IOException {
		List<Entry> entries = load();
		if (entries.removeIf(e -> e.seed.equals(seed))) {
			save(entries);
		}
	}
}
