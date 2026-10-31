package com.easyfarm.loop;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Loop playback settings, saved to {@code config/easyfarm/loop-settings.json}:
 * <pre>{@code
 * { "holdAttackToRun": true }
 * }</pre>
 */
public final class LoopSettings {
	private static final Logger LOGGER = LoggerFactory.getLogger("easyfarm");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Play only while attack (left click by default) is held; otherwise stay paused in place. On by default. */
	private static boolean holdAttackToRun = true;

	private LoopSettings() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("easyfarm").resolve("loop-settings.json");
	}

	public static boolean holdAttackToRun() {
		return holdAttackToRun;
	}

	public static void setHoldAttackToRun(boolean value) {
		holdAttackToRun = value;
		save();
	}

	public static void load() {
		Path file = file();
		if (!Files.isRegularFile(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			JsonElement hold = json.get("holdAttackToRun");
			if (hold != null) {
				holdAttackToRun = hold.getAsBoolean();
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not read {}, using defaults: {}", file.getFileName(), e.toString());
		}
	}

	private static void save() {
		Path file = file();
		JsonObject json = new JsonObject();
		json.addProperty("holdAttackToRun", holdAttackToRun);
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(json, writer);
			}
		} catch (IOException e) {
			LOGGER.error("Could not save {}", file, e);
		}
	}
}
