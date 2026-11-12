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

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import com.easyfarm.EasyFarmClient;
import com.easyfarm.loop.LoopPlayer;

/**
 * Captures every keyboard key, every mouse button and the camera, and saves the result with {@link RecordingStore}.
 *
 * <p>Keys and buttons are read from the physical keyboard and mouse ({@link PhysicalInput}), once per client tick, not from
 * the key bindings, so every key is captured whether or not Minecraft binds it. Camera movement is recorded as the change
 * in yaw/pitch since the previous sample, several times per tick. Nothing is sent anywhere.
 *
 * <p>Recording pauses while any screen is open (chat, inventory, the HUD): typing and menu clicks never end up in a
 * recording. Escape, the chat and command keys, and this mod's record and loop keys are never recorded.
 *
 * <p>Start and stop from the Record tab, or from anywhere with the "Start/stop recording" key (F8 by default).
 */
public final class Recorder {
	private static final Logger LOGGER = LoggerFactory.getLogger("easyfarm");

	/** Hard cap so a forgotten recording does not grow forever: 30 minutes. */
	public static final int MAX_TICKS = 30 * 60 * Recording.TICKS_PER_SECOND;

	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(EasyFarmClient.MOD_ID, "main"));

	/** Every GLFW keyboard key code worth polling (polling an unknown code makes GLFW log an error). */
	private static final int[] KEY_CODES = buildKeyCodes();
	private static final int MOUSE_BUTTONS = 8;

	/** Camera samples per game tick. Vanilla ticks at 20/s; 1.75 samples per tick is 35/s. */
	public static final float LOOK_SAMPLES_PER_TICK = 1.75f;
	private static final float LOOK_SAMPLE_GAP = 1.0f / LOOK_SAMPLES_PER_TICK;

	private static @Nullable KeyMapping toggleKey;

	private static boolean recording;
	private static Instant startedAt = Instant.EPOCH;
	private static int tick;
	private static final List<Recording.Event> events = new ArrayList<>();
	private static final boolean[] keyHeld = new boolean[GLFW.GLFW_KEY_LAST + 1];
	private static final boolean[] mouseHeld = new boolean[MOUSE_BUTTONS];
	private static float lastYaw;
	private static float lastPitch;
	private static boolean haveLook;
	/** Timeline position of the last camera sample. */
	private static float lastLookTime;
	private static float startYaw;
	private static float startPitch;

	private static String activeName = "";
	/** What is typed in the Record tab's name box; survives the HUD being rebuilt or closed. */
	private static String draftName = "";

	private static @Nullable Component lastResult;

	private Recorder() {
	}

	private static int[] buildKeyCodes() {
		List<Integer> codes = new ArrayList<>();
		for (int code = GLFW.GLFW_KEY_SPACE; code <= GLFW.GLFW_KEY_LAST; code++) {
			boolean valid = code == 32 || code == 39 || (code >= 44 && code <= 57) || code == 59 || code == 61
					|| (code >= 65 && code <= 93) || code == 96 || code == 161 || code == 162
					|| (code >= 256 && code <= 269) || (code >= 280 && code <= 284)
					|| (code >= 290 && code <= 314) || (code >= 320 && code <= 348);
			if (valid) {
				codes.add(code);
			}
		}
		return codes.stream().mapToInt(Integer::intValue).toArray();
	}

	public static void register() {
		toggleKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.easyfarm.toggle_recording", GLFW.GLFW_KEY_F8, CATEGORY));
		ClientTickEvents.START_CLIENT_TICK.register(Recorder::onStartTick);
		LevelRenderEvents.START_MAIN.register(context -> onFrame(Minecraft.getInstance()));
	}

	/** True for this mod's own bindings, which loop playback leaves alone. */
	public static boolean isOwnMapping(KeyMapping mapping) {
		return mapping == toggleKey || LoopPlayer.isOwnMapping(mapping);
	}

	private static void onStartTick(Minecraft minecraft) {
		if (toggleKey != null) {
			while (toggleKey.consumeClick()) {
				if (recording) {
					stop();
				} else {
					Component error = start(draftName);
					if (error != null && minecraft.player != null) {
						minecraft.player.sendOverlayMessage(error);
					}
				}
			}
		}

		if (!recording) {
			return;
		}

		// Left the world: keep what was captured so far.
		LocalPlayer player = minecraft.player;
		if (player == null) {
			stop();
			return;
		}

		if (minecraft.gui.screen() != null || minecraft.isPaused()) {
			// Forget the camera so a screen's mouse movement is not counted as a turn.
			haveLook = false;
			return;
		}

		if (!haveLook) {
			lastYaw = player.getYRot();
			lastPitch = player.getXRot();
			lastLookTime = tick;
			haveLook = true;
		}
		sampleCamera(player);
		sampleInput(minecraft);
		tick++;

		if (tick >= MAX_TICKS) {
			stop();
		}
	}

	private static void sampleCamera(LocalPlayer player) {
		float yaw = player.getYRot();
		float pitch = player.getXRot();
		float dYaw = yaw - lastYaw;
		float dPitch = pitch - lastPitch;
		if (Math.abs(dYaw) > 1e-4f || Math.abs(dPitch) > 1e-4f) {
			events.add(Recording.Event.look(tick, 0, dYaw, dPitch));
		}
		lastYaw = yaw;
		lastPitch = pitch;
		lastLookTime = tick;
	}

	/**
	 * Extra camera samples between ticks, taken once per rendered frame whenever enough of a tick has passed since the last
	 * sample. {@code tick} was already advanced for the tick that just ran, so the frame sits at {@code tick - 1 + partial}.
	 */
	private static void onFrame(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!recording || player == null || !haveLook || tick == 0
				|| minecraft.gui.screen() != null || minecraft.isPaused()) {
			return;
		}
		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float time = tick - 1 + Mth.clamp(partial, 0.0f, 0.999f);
		if (time - lastLookTime < LOOK_SAMPLE_GAP) {
			return;
		}
		float yaw = player.getYRot();
		float pitch = player.getXRot();
		float dYaw = yaw - lastYaw;
		float dPitch = pitch - lastPitch;
		if (Math.abs(dYaw) > 1e-4f || Math.abs(dPitch) > 1e-4f) {
			int whole = (int) Math.floor(time);
			events.add(Recording.Event.look(whole, time - whole, dYaw, dPitch));
		}
		lastYaw = yaw;
		lastPitch = pitch;
		lastLookTime = time;
	}

	private static void sampleInput(Minecraft minecraft) {
		for (int code : KEY_CODES) {
			boolean down = PhysicalInput.key(code) && !isExcluded(minecraft, code);
			if (down != keyHeld[code]) {
				keyHeld[code] = down;
				events.add(Recording.Event.key(tick, code, down));
			}
		}
		for (int button = 0; button < MOUSE_BUTTONS; button++) {
			boolean down = PhysicalInput.mouse(button) && !isExcludedMouse(button);
			if (down != mouseHeld[button]) {
				mouseHeld[button] = down;
				events.add(Recording.Event.mouse(tick, button, down));
			}
		}
	}

	/** Keys that must never be replayed: they would open screens or control the recorder or the loop player itself. */
	private static boolean isExcluded(Minecraft minecraft, int code) {
		if (code == GLFW.GLFW_KEY_ESCAPE) {
			return true;
		}
		InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(code);
		return isOwnKey(key) || minecraft.options.keyChat.matches(key) || minecraft.options.keyCommand.matches(key);
	}

	/** The record key can be rebound to a mouse button, which must not be recorded either. */
	private static boolean isExcludedMouse(int button) {
		return isOwnKey(InputConstants.Type.MOUSE.getOrCreate(button));
	}

	private static boolean isOwnKey(InputConstants.Key key) {
		return (toggleKey != null && !toggleKey.isUnbound() && toggleKey.saveString().equals(key.getName()))
				|| LoopPlayer.isToggleKey(key);
	}

	/**
	 * Starts a new recording under the given name.
	 *
	 * @return {@code null} on success, otherwise the reason nothing started
	 */
	public static @Nullable Component start(String name) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return Component.translatable("easyfarm.record.error.no_world");
		}
		if (recording) {
			return Component.translatable("easyfarm.record.error.already_recording", activeName);
		}
		Component nameError = nameError(name);
		if (nameError != null) {
			return nameError;
		}

		// Never let a recording capture a loop's own input.
		LoopPlayer.stop();

		recording = true;
		activeName = name.strip();
		draftName = "";
		startedAt = Instant.now();
		tick = 0;
		events.clear();
		Arrays.fill(keyHeld, false);
		Arrays.fill(mouseHeld, false);
		// Remember the exact camera angle so every playback can turn back to it first.
		startYaw = minecraft.player.getYRot();
		startPitch = minecraft.player.getXRot();
		lastYaw = startYaw;
		lastPitch = startPitch;
		lastLookTime = 0;
		haveLook = true;
		lastResult = null;

		minecraft.player.sendOverlayMessage(Component.translatable("easyfarm.record.overlay.started", activeName, toggleKeyName()));
		return null;
	}

	/** Why this name cannot be used for a new recording, or {@code null} if it can. */
	public static @Nullable Component nameError(String name) {
		return switch (RecordingStore.checkName(name)) {
			case OK -> null;
			case EMPTY -> Component.translatable("easyfarm.record.error.name_empty");
			case TOO_LONG -> Component.translatable("easyfarm.record.error.name_too_long", RecordingStore.MAX_NAME_LENGTH);
			case TAKEN -> Component.translatable("easyfarm.record.error.name_taken", name.strip());
		};
	}

	public static String draftName() {
		return draftName;
	}

	public static void setDraftName(String name) {
		draftName = name;
	}

	/** Name of the recording in progress (empty when idle). */
	public static String activeName() {
		return recording ? activeName : "";
	}

	/** The key currently bound to start/stop recording, as the player would see it in Controls. */
	public static Component toggleKeyName() {
		return toggleKey == null ? Component.literal("?") : toggleKey.getTranslatedKeyMessage();
	}

	/**
	 * Stops the current recording and saves it, unless nothing was pressed or turned.
	 *
	 * @return the saved recording, or {@code null} if nothing was saved (not recording, nothing happened, or a write error)
	 */
	public static @Nullable Recording stop() {
		if (!recording) {
			return null;
		}
		recording = false;
		Recording saved = null;

		// Close everything still held so playback always ends with nothing pressed, but remember what it was: a loop can
		// choose to keep exactly those inputs pressed through the buffer after this recording.
		List<Recording.Input> heldAtEnd = new ArrayList<>();
		for (int code : KEY_CODES) {
			if (keyHeld[code]) {
				keyHeld[code] = false;
				heldAtEnd.add(new Recording.Input(Recording.Type.KEY, code));
				events.add(Recording.Event.key(tick, code, false));
			}
		}
		for (int button = 0; button < MOUSE_BUTTONS; button++) {
			if (mouseHeld[button]) {
				mouseHeld[button] = false;
				heldAtEnd.add(new Recording.Input(Recording.Type.MOUSE, button));
				events.add(Recording.Event.mouse(tick, button, false));
			}
		}

		if (events.isEmpty()) {
			lastResult = Component.translatable("easyfarm.record.result.empty");
			draftName = activeName; // let the player try again under the same name
		} else {
			try {
				saved = RecordingStore.saveNew(activeName, startedAt, tick, startYaw, startPitch, events, heldAtEnd);
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

	/** Key and mouse presses captured so far. */
	public static int pressCount() {
		int presses = 0;
		for (Recording.Event event : events) {
			if (event.type() != Recording.Type.LOOK && event.down()) {
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
