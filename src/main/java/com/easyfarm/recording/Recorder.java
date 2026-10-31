package com.easyfarm.recording;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import com.easyfarm.EasyFarmClient;

/**
 * Captures the movement keys ({@link MovementKey}) once per client tick and saves the result with {@link RecordingStore}.
 *
 * <p>Only the state of the player's own key bindings is read; nothing is sent anywhere. Recording pauses while any screen
 * is open (the HUD, the inventory, chat, the pause menu): the game releases movement keys then anyway, and it keeps
 * the moment you open the HUD to press "Stop" out of the recording.
 *
 * <p>Start and stop from the Record tab, or from anywhere with the "Start/stop recording" key (F8 by default).
 */
public final class Recorder {
	private static final Logger LOGGER = LoggerFactory.getLogger("easyfarm");

	/** Hard cap so a forgotten recording does not grow forever: 30 minutes. */
	public static final int MAX_TICKS = 30 * 60 * Recording.TICKS_PER_SECOND;

	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(EasyFarmClient.MOD_ID, "main"));
	private static final MovementKey[] KEYS = MovementKey.values();

	private static @Nullable KeyMapping toggleKey;

	private static boolean recording;
	private static Instant startedAt = Instant.EPOCH;
	private static int tick;
	private static final List<Recording.Event> events = new ArrayList<>();
	private static final boolean[] held = new boolean[KEYS.length];

	private static @Nullable Component lastResult;

	private Recorder() {
	}

	public static void register() {
		toggleKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.easyfarm.toggle_recording", GLFW.GLFW_KEY_F8, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(Recorder::onEndTick);
	}

	private static void onEndTick(Minecraft minecraft) {
		if (toggleKey != null) {
			while (toggleKey.consumeClick()) {
				if (recording) {
					stop();
				} else {
					start();
				}
			}
		}

		if (!recording) {
			return;
		}

		// Left the world: keep what was captured so far.
		if (minecraft.player == null) {
			stop();
			return;
		}

		if (minecraft.gui.screen() != null || minecraft.isPaused()) {
			return;
		}

		sample(minecraft.options);
		tick++;

		if (tick >= MAX_TICKS) {
			stop();
		}
	}

	private static void sample(Options options) {
		for (int i = 0; i < KEYS.length; i++) {
			boolean down = KEYS[i].mapping(options).isDown();
			if (down != held[i]) {
				held[i] = down;
				events.add(new Recording.Event(tick, KEYS[i], down));
			}
		}
	}

	/** Starts a new recording. Does nothing outside a world or if one is already running. */
	public static void start() {
		Minecraft minecraft = Minecraft.getInstance();
		if (recording || minecraft.player == null) {
			return;
		}

		recording = true;
		startedAt = Instant.now();
		tick = 0;
		events.clear();
		Arrays.fill(held, false);
		lastResult = null;

		minecraft.player.sendOverlayMessage(Component.translatable("easyfarm.record.overlay.started", toggleKeyName()));
	}

	/** The key currently bound to start/stop recording, as the player would see it in Controls. */
	public static Component toggleKeyName() {
		return toggleKey == null ? Component.literal("?") : toggleKey.getTranslatedKeyMessage();
	}

	/**
	 * Stops the current recording and saves it, unless nothing was pressed.
	 *
	 * @return the saved recording, or {@code null} if nothing was saved (not recording, nothing pressed, or a write error)
	 */
	public static @Nullable Recording stop() {
		if (!recording) {
			return null;
		}
		recording = false;
		Recording saved = null;

		// Close every key that is still down, so playback always ends with nothing held.
		for (int i = 0; i < KEYS.length; i++) {
			if (held[i]) {
				held[i] = false;
				events.add(new Recording.Event(tick, KEYS[i], false));
			}
		}

		if (events.isEmpty()) {
			lastResult = Component.translatable("easyfarm.record.result.empty");
		} else {
			try {
				saved = RecordingStore.saveNew(startedAt, tick, events);
				lastResult = Component.translatable("easyfarm.record.result.saved",
						saved.name(), Recording.formatDuration(saved.lengthTicks()));
			} catch (IOException e) {
				LOGGER.error("Could not save recording", e);
				lastResult = Component.translatable("easyfarm.record.result.failed");
			}
		}
		events.clear();

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && lastResult != null) {
			minecraft.player.sendOverlayMessage(lastResult);
		}
		return saved;
	}

	public static boolean isRecording() {
		return recording;
	}

	/** Ticks captured so far (time spent with a screen open is not counted). */
	public static int elapsedTicks() {
		return tick;
	}

	public static int pressCount() {
		int presses = 0;
		for (Recording.Event event : events) {
			if (event.down()) {
				presses++;
			}
		}
		return presses;
	}

	/** What happened when the last recording stopped, for the Record tab. */
	public static @Nullable Component lastResult() {
		return lastResult;
	}
}
