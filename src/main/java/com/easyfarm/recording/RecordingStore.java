package com.easyfarm.recording;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Reads and writes recordings as one JSON file each in {@code config/easyfarm/recordings/}.
 *
 * <p>File format (version 1):
 * <pre>{@code
 * {
 *   "version": 1,
 *   "name": "Recording 1",
 *   "createdAt": "2026-10-31T14:02:11Z",
 *   "lengthTicks": 240,
 *   "events": [
 *     { "tick": 0, "key": "forward", "down": true },
 *     { "tick": 37, "key": "forward", "down": false }
 *   ]
 * }
 * }</pre>
 * A file that cannot be read is skipped and logged, never deleted, so a hand edit gone wrong does not lose data.
 */
public final class RecordingStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("easyfarm");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int FORMAT_VERSION = 1;
	private static final String EXTENSION = ".json";
	private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());
	private static final Pattern DEFAULT_NAME = Pattern.compile("Recording (\\d+)");

	private RecordingStore() {
	}

	public static Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve("easyfarm").resolve("recordings");
	}

	/** All readable recordings, newest first. */
	public static List<Recording> list() {
		Path dir = directory();
		List<Recording> recordings = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return recordings;
		}

		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + EXTENSION)) {
			for (Path file : files) {
				try {
					recordings.add(read(file));
				} catch (IOException | RuntimeException e) {
					LOGGER.warn("Skipping unreadable recording {}: {}", file.getFileName(), e.toString());
				}
			}
		} catch (IOException e) {
			LOGGER.error("Could not list recordings in {}", dir, e);
		}

		recordings.sort(Comparator.comparing(Recording::createdAt).reversed());
		return recordings;
	}

	/**
	 * Saves a freshly captured recording under a new id and the next free "Recording N" name.
	 *
	 * @return the recording as saved
	 */
	public static Recording saveNew(Instant createdAt, int lengthTicks, List<Recording.Event> events) throws IOException {
		Path dir = directory();
		Files.createDirectories(dir);

		String baseId = ID_FORMAT.format(createdAt);
		String id = baseId;
		for (int i = 2; Files.exists(dir.resolve(id + EXTENSION)); i++) {
			id = baseId + "_" + i;
		}

		Recording recording = new Recording(id, nextDefaultName(), createdAt, lengthTicks, events);
		write(recording);
		return recording;
	}

	public static void delete(Recording recording) throws IOException {
		Files.deleteIfExists(directory().resolve(recording.id() + EXTENSION));
	}

	private static String nextDefaultName() {
		Set<Integer> taken = new HashSet<>();
		for (Recording recording : list()) {
			Matcher matcher = DEFAULT_NAME.matcher(recording.name());
			if (matcher.matches()) {
				try {
					taken.add(Integer.parseInt(matcher.group(1)));
				} catch (NumberFormatException ignored) {
					// Absurdly large number; it cannot collide with anything we would pick.
				}
			}
		}

		int n = 1;
		while (taken.contains(n)) {
			n++;
		}
		return "Recording " + n;
	}

	private static void write(Recording recording) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("version", FORMAT_VERSION);
		json.addProperty("name", recording.name());
		json.addProperty("createdAt", recording.createdAt().toString());
		json.addProperty("lengthTicks", recording.lengthTicks());

		JsonArray events = new JsonArray();
		for (Recording.Event event : recording.events()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("tick", event.tick());
			entry.addProperty("key", event.key().id());
			entry.addProperty("down", event.down());
			events.add(entry);
		}
		json.add("events", events);

		// Write to a temporary file first so a crash mid-write never leaves a half-written recording behind.
		Path target = directory().resolve(recording.id() + EXTENSION);
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
			GSON.toJson(json, writer);
		}
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static Recording read(Path file) throws IOException {
		JsonObject json;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			json = JsonParser.parseReader(reader).getAsJsonObject();
		}

		int version = json.get("version").getAsInt();
		if (version != FORMAT_VERSION) {
			throw new JsonParseException("unsupported format version " + version);
		}

		String fileName = file.getFileName().toString();
		String id = fileName.substring(0, fileName.length() - EXTENSION.length());
		String name = json.get("name").getAsString();
		int lengthTicks = json.get("lengthTicks").getAsInt();
		Instant createdAt;
		try {
			createdAt = Instant.parse(json.get("createdAt").getAsString());
		} catch (DateTimeParseException e) {
			throw new JsonParseException("bad createdAt", e);
		}

		List<Recording.Event> events = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray("events")) {
			JsonObject entry = element.getAsJsonObject();
			int tick = entry.get("tick").getAsInt();
			MovementKey key = MovementKey.byId(entry.get("key").getAsString());
			if (key == null) {
				throw new JsonParseException("unknown key " + entry.get("key"));
			}
			if (tick < 0 || tick > lengthTicks) {
				throw new JsonParseException("event tick " + tick + " outside 0.." + lengthTicks);
			}
			events.add(new Recording.Event(tick, key, entry.get("down").getAsBoolean()));
		}
		events.sort(Comparator.comparingInt(Recording.Event::tick));

		return new Recording(id, name, createdAt, lengthTicks, events);
	}
}
