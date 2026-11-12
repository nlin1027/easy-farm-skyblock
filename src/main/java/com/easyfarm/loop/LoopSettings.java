package com.easyfarm.loop;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

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
 * { "playMode": "hold", "backgroundPlay": true }
 * }</pre>
 * Files from before play modes existed have {@code "holdAttackToRun": true/false}, which maps to {@code hold} / {@code keybind}.
 */
public final class LoopSettings {
	private static final Logger LOGGER = LoggerFactory.getLogger("easyfarm");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** What makes a started loop run. */
	public enum PlayMode {
		/** Runs only while left click is held; letting go pauses it in place. */
		HOLD,
		/** The loop key (F9 by default) toggles between playing and paused. A started loop plays straight away. */
		KEYBIND,
		/** Either works: hold left click, or toggle with the loop key. */
		BOTH;

		public PlayMode next() {
			return values()[(this.ordinal() + 1) % values().length];
		}

		public boolean allowsHold() {
			return this != KEYBIND;
		}

		public boolean allowsKeybind() {
			return this != HOLD;
		}
	}

	private static PlayMode playMode = PlayMode.HOLD;
	private static boolean backgroundPlay = true;

	private LoopSettings() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("easyfarm").resolve("loop-settings.json");
	}

	public static PlayMode playMode() {
		return playMode;
	}

	public static void setPlayMode(PlayMode mode) {
		playMode = mode;
		save();
	}

	/**
	 * Whether a loop that is playing keeps playing while the game window is not focused (alt-tabbed or minimized), instead
	 * of Minecraft's "pause on lost focus" opening the pause menu and stopping it. Only applies to loops started with the
	 * keybind: holding left click cannot be done while the window is unfocused.
	 */
	public static boolean backgroundPlay() {
		return backgroundPlay;
	}

	public static void setBackgroundPlay(boolean value) {
		backgroundPlay = value;
		save();
	}

	public static void load() {
		Path file = file();
		if (!Files.isRegularFile(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			JsonElement mode = json.get("playMode");
			JsonElement legacyHold = json.get("holdAttackToRun");
			if (mode != null) {
				playMode = PlayMode.valueOf(mode.getAsString().toUpperCase(Locale.ROOT));
			} else if (legacyHold != null) {
				playMode = legacyHold.getAsBoolean() ? PlayMode.HOLD : PlayMode.KEYBIND;
			}
			JsonElement background = json.get("backgroundPlay");
			if (background != null) {
				backgroundPlay = background.getAsBoolean();
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not read {}, using defaults: {}", file.getFileName(), e.toString());
		}
	}

	private static void save() {
		Path file = file();
		JsonObject json = new JsonObject();
		json.addProperty("playMode", playMode.name().toLowerCase(Locale.ROOT));
		json.addProperty("backgroundPlay", backgroundPlay);
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
