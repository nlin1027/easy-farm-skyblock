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
import java.util.List;
import java.util.Locale;

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
 *   "version": 2,
 *   "name": "Wheat farm",
 *   "createdAt": "2026-10-31T14:02:11Z",
 *   "lengthTicks": 240,
 *   "startYaw": 90.0, "startPitch": 12.5,
 *   "events": [
 *     { "tick": 0, "type": "key", "code": 87, "down": true },
 *     { "tick": 3, "type": "mouse", "code": 0, "down": true },
 *     { "tick": 3, "frac": 0.57, "type": "look", "yaw": 1.5, "pitch": -0.25 }
 *   ],
 *   "heldAtEnd": [ { "type": "key", "code": 87 } ]
 * }
 * }</pre>
 * A file that cannot be read is skipped and logged, never deleted, so a hand edit gone wrong does not lose data.
 */
public final class RecordingStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("easyfarm");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int FORMAT_VERSION = 2;
	private static final String EXTENSION = ".json";
	private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());
	public static final int MAX_NAME_LENGTH = 32;

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
	 * Saves a freshly captured recording under a new id.
	 *
	 * @param name a name that passed {@link #checkName}
	 * @return the recording as saved
	 */
	public static Recording saveNew(String name, Instant createdAt, int lengthTicks, float startYaw, float startPitch,
			List<Recording.Event> events, List<Recording.Input> heldAtEnd) throws IOException {
		Path dir = directory();
		Files.createDirectories(dir);

		String baseId = ID_FORMAT.format(createdAt);
		String id = baseId;
		for (int i = 2; Files.exists(dir.resolve(id + EXTENSION)); i++) {
			id = baseId + "_" + i;
		}

		Recording recording = new Recording(id, name.strip(), createdAt, lengthTicks, startYaw, startPitch, events, heldAtEnd);
		write(recording);
		return recording;
	}

	/** Why a recording name is not usable. */
	public enum NameCheck {
		OK, EMPTY, TOO_LONG, TAKEN
	}

	/** Names are trimmed, 1 to {@link #MAX_NAME_LENGTH} characters, and unique ignoring case. */
	public static NameCheck checkName(String name) {
		String trimmed = name.strip();
		if (trimmed.isEmpty()) {
			return NameCheck.EMPTY;
		}
		if (trimmed.length() > MAX_NAME_LENGTH) {
			return NameCheck.TOO_LONG;
		}
		return find(trimmed) != null ? NameCheck.TAKEN : NameCheck.OK;
	}

	/** The recording with this name (ignoring case), or {@code null}. */
	public static Recording find(String name) {
		String wanted = name.strip().toLowerCase(Locale.ROOT);
		for (Recording recording : list()) {
			if (recording.name().toLowerCase(Locale.ROOT).equals(wanted)) {
				return recording;
			}
		}
		return null;
	}

	/** Renames a recording. The new name must have passed {@link #checkName}. */
	public static Recording rename(Recording recording, String newName) throws IOException {
		Recording renamed = new Recording(recording.id(), newName.strip(), recording.createdAt(), recording.lengthTicks(), recording.startYaw(),
				recording.startPitch(), recording.events(), recording.heldAtEnd());
		write(renamed);
		return renamed;
	}

	public static void delete(Recording recording) throws IOException {
		Files.deleteIfExists(directory().resolve(recording.id() + EXTENSION));
	}

	private static void write(Recording recording) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("version", FORMAT_VERSION);
		json.addProperty("name", recording.name());
		json.addProperty("createdAt", recording.createdAt().toString());
		json.addProperty("lengthTicks", recording.lengthTicks());

		if (recording.hasStartLook()) {
			json.addProperty("startYaw", recording.startYaw());
			json.addProperty("startPitch", recording.startPitch());
		}

		JsonArray events = new JsonArray();
		for (Recording.Event event : recording.events()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("tick", event.tick());
			if (event.frac() != 0) {
				entry.addProperty("frac", event.frac());
			}
			entry.addProperty("type", event.type().name().toLowerCase(Locale.ROOT));
			if (event.type() == Recording.Type.LOOK) {
				entry.addProperty("yaw", event.yaw());
				entry.addProperty("pitch", event.pitch());
			} else {
				entry.addProperty("code", event.code());
				entry.addProperty("down", event.down());
			}
			events.add(entry);
		}
		json.add("events", events);

		JsonArray held = new JsonArray();
		for (Recording.Input input : recording.heldAtEnd()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("type", input.type().name().toLowerCase(Locale.ROOT));
			entry.addProperty("code", input.code());
			held.add(entry);
		}
		json.add("heldAtEnd", held);

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
		if (version != 1 && version != FORMAT_VERSION) {
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

		float startYaw = json.has("startYaw") ? json.get("startYaw").getAsFloat() : Float.NaN;
		float startPitch = json.has("startPitch") ? json.get("startPitch").getAsFloat() : Float.NaN;

		List<Recording.Event> events = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray("events")) {
			JsonObject entry = element.getAsJsonObject();
			int tick = entry.get("tick").getAsInt();
			if (tick < 0 || tick > lengthTicks) {
				throw new JsonParseException("event tick " + tick + " outside 0.." + lengthTicks);
			}
			if (version == 1) {
				MovementKey key = MovementKey.byId(entry.get("key").getAsString());
				if (key == null) {
					throw new JsonParseException("unknown key " + entry.get("key"));
				}
				events.add(Recording.Event.key(tick, key.defaultCode(), entry.get("down").getAsBoolean()));
				continue;
			}
			Recording.Type type = Recording.Type.valueOf(entry.get("type").getAsString().toUpperCase(Locale.ROOT));
			events.add(switch (type) {
				case KEY -> Recording.Event.key(tick, entry.get("code").getAsInt(), entry.get("down").getAsBoolean());
				case MOUSE -> Recording.Event.mouse(tick, entry.get("code").getAsInt(), entry.get("down").getAsBoolean());
				case LOOK -> Recording.Event.look(tick, entry.has("frac") ? entry.get("frac").getAsFloat() : 0f,
						entry.get("yaw").getAsFloat(), entry.get("pitch").getAsFloat());
			});
		}
		events.sort(Comparator.comparingDouble(Recording.Event::time));

		List<Recording.Input> heldAtEnd = new ArrayList<>();
		if (version == 1) {
			heldAtEnd.addAll(heldAtEndFromReleases(events, lengthTicks));
		} else if (json.has("heldAtEnd")) {
			for (JsonElement element : json.getAsJsonArray("heldAtEnd")) {
				JsonObject entry = element.getAsJsonObject();
				heldAtEnd.add(new Recording.Input(
						Recording.Type.valueOf(entry.get("type").getAsString().toUpperCase(Locale.ROOT)), entry.get("code").getAsInt()));
			}
		}

		return new Recording(id, name, createdAt, lengthTicks, startYaw, startPitch, events, heldAtEnd);
	}

	/**
	 * Version 1 files did not record what was held when recording stopped, but the recorder released those keys on the very
	 * last tick, so the keys whose last event is a release at {@code lengthTicks} are the ones that were still down.
	 */
	private static List<Recording.Input> heldAtEndFromReleases(List<Recording.Event> events, int lengthTicks) {
		java.util.Map<Recording.Input, Recording.Event> last = new java.util.LinkedHashMap<>();
		for (Recording.Event event : events) {
			if (event.type() != Recording.Type.LOOK) {
				last.put(event.input(), event);
			}
		}
		List<Recording.Input> held = new ArrayList<>();
		for (java.util.Map.Entry<Recording.Input, Recording.Event> entry : last.entrySet()) {
			if (!entry.getValue().down() && entry.getValue().tick() == lengthTicks) {
				held.add(entry.getKey());
			}
		}
		return held;
	}
}
