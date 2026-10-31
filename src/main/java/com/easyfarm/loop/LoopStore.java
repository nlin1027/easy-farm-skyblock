package com.easyfarm.loop;

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

import org.jspecify.annotations.Nullable;
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
 * Reads and writes loops as one JSON file each in {@code config/easyfarm/loops/}, next to the recordings folder.
 *
 * <p>File format (version 1):
 * <pre>{@code
 * {
 *   "version": 1,
 *   "name": "Loop 1",
 *   "createdAt": "2026-10-31T14:02:11Z",
 *   "buffer": { "minTicks": 10, "maxTicks": 40 },
 *   "recordings": [ "2026-10-31_10-22-13", "2026-10-31_10-25-40" ]
 * }
 * }</pre>
 * Recordings are referenced by id, so a loop shows them as missing (and refuses to play) if one is deleted. There is
 * nowhere to switch buffers off: see {@link Loop}. A file that cannot be read is skipped and logged, never deleted.
 */
public final class LoopStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("easyfarm");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int FORMAT_VERSION = 1;
	private static final String EXTENSION = ".json";
	private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());
	private static final Pattern DEFAULT_NAME = Pattern.compile("Loop (\\d+)");

	private LoopStore() {
	}

	public static Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve("easyfarm").resolve("loops");
	}

	/** All readable loops, newest first. */
	public static List<Loop> list() {
		Path dir = directory();
		List<Loop> loops = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return loops;
		}

		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + EXTENSION)) {
			for (Path file : files) {
				try {
					loops.add(read(file));
				} catch (IOException | RuntimeException e) {
					LOGGER.warn("Skipping unreadable loop {}: {}", file.getFileName(), e.toString());
				}
			}
		} catch (IOException e) {
			LOGGER.error("Could not list loops in {}", dir, e);
		}

		loops.sort(Comparator.comparing(Loop::createdAt).reversed());
		return loops;
	}

	public static @Nullable Loop get(String id) {
		for (Loop loop : list()) {
			if (loop.id().equals(id)) {
				return loop;
			}
		}
		return null;
	}

	/** Saves a brand-new loop under a fresh id. */
	public static Loop saveNew(String name, List<String> recordingIds, int bufferMinTicks, int bufferMaxTicks) throws IOException {
		Path dir = directory();
		Files.createDirectories(dir);

		Instant now = Instant.now();
		String baseId = ID_FORMAT.format(now);
		String id = baseId;
		for (int i = 2; Files.exists(dir.resolve(id + EXTENSION)); i++) {
			id = baseId + "_" + i;
		}

		Loop loop = new Loop(id, name, now, recordingIds, bufferMinTicks, bufferMaxTicks);
		write(loop);
		return loop;
	}

	/** Overwrites an existing loop (same id). */
	public static void save(Loop loop) throws IOException {
		Files.createDirectories(directory());
		write(loop);
	}

	public static void delete(Loop loop) throws IOException {
		Files.deleteIfExists(directory().resolve(loop.id() + EXTENSION));
	}

	public static String nextDefaultName() {
		Set<Integer> taken = new HashSet<>();
		for (Loop loop : list()) {
			Matcher matcher = DEFAULT_NAME.matcher(loop.name());
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
		return "Loop " + n;
	}

	private static void write(Loop loop) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("version", FORMAT_VERSION);
		json.addProperty("name", loop.name());
		json.addProperty("createdAt", loop.createdAt().toString());

		JsonObject buffer = new JsonObject();
		buffer.addProperty("minTicks", loop.bufferMinTicks());
		buffer.addProperty("maxTicks", loop.bufferMaxTicks());
		json.add("buffer", buffer);

		JsonArray recordings = new JsonArray();
		for (String recordingId : loop.recordingIds()) {
			recordings.add(recordingId);
		}
		json.add("recordings", recordings);

		// Write to a temporary file first so a crash mid-write never leaves a half-written loop behind.
		Path target = directory().resolve(loop.id() + EXTENSION);
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

	private static Loop read(Path file) throws IOException {
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
		Instant createdAt;
		try {
			createdAt = Instant.parse(json.get("createdAt").getAsString());
		} catch (DateTimeParseException e) {
			throw new JsonParseException("bad createdAt", e);
		}

		JsonObject buffer = json.getAsJsonObject("buffer");
		int minTicks = buffer.get("minTicks").getAsInt();
		int maxTicks = buffer.get("maxTicks").getAsInt();
		if (!Loop.isValidBuffer(minTicks, maxTicks)) {
			// Refuse rather than "fix": silently changing the range would change how the loop behaves.
			throw new JsonParseException("invalid buffer range " + minTicks + ".." + maxTicks);
		}

		List<String> recordingIds = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray("recordings")) {
			recordingIds.add(element.getAsString());
		}

		return new Loop(id, name, createdAt, recordingIds, minTicks, maxTicks);
	}
}
