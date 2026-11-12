package com.easyfarm.loop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

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
import com.easyfarm.recording.PhysicalInput;
import com.easyfarm.recording.Recorder;
import com.easyfarm.recording.Recording;
import com.easyfarm.recording.RecordingStore;

/**
 * Plays a {@link Loop}: recording, buffer, recording, buffer, ... then starts over, until stopped.
 *
 * <p><b>Buffers.</b> After every recording, including the last one before the loop wraps around, playback waits out a
 * buffer whose length is drawn from the loop's range at the moment the buffer is reached. Nothing is cached or
 * precomputed: every buffer encounter calls {@link #rollBuffer()} once, so two buffers in the same cycle, or the same
 * buffer in two cycles, are independent draws. Normally a buffer holds no inputs. A block marked "hold last keys"
 * ({@link Loop.Step#holdLastKeys()}) instead keeps the keys and mouse buttons that were still held when its recording
 * ended pressed through the buffer, until the next recording starts.
 *
 * <p><b>What plays.</b> Every recorded keyboard key and mouse button, and the camera. Keys and buttons are applied at the
 * start of each client tick, before the game reads input, by pressing the matching key bindings. The camera is set every
 * rendered frame from the recorded samples so it moves smoothly, and eases back to a recording's starting angle whenever
 * a recording begins. A recording made before the camera was recorded leaves the camera free.
 *
 * <p><b>Starting and pausing.</b> See {@link LoopSettings.PlayMode}: a loop runs while left click is held (read from the
 * physical mouse, never the attack binding, because playback itself drives that binding), and/or while the loop key has
 * toggled it on. Opening any screen pauses playback. It stops on its own when the player leaves the world or a recording
 * starts. Everything stays on the client: only the key bindings, the player's own rotation and (temporarily, while a
 * keybind-started loop runs) the client's own "pause on lost focus" option are touched.
 */
public final class LoopPlayer {
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(EasyFarmClient.MOD_ID, "loops"));

	/** How long the camera takes to ease onto a recording's angle. */
	private static final long BLEND_NANOS = 300_000_000L;

	/** Uniform source for buffer rolls. Only ever used on the client thread. */
	private static final RandomGenerator RANDOM = RandomGenerator.getDefault();

	private static @Nullable KeyMapping toggleKey;

	private static @Nullable Loop loop;
	private static List<Recording> sequence = List.of();
	private static List<Boolean> holdFlags = List.of();
	/** Even steps play {@code sequence.get(step / 2)}; odd steps are the buffer after it. */
	private static int step;
	private static int stepTick;
	private static int eventCursor;
	private static int bufferTicks;
	private static int cycle;
	private static int buffersRolled;

	/** Inputs playback currently holds down. */
	private static final Set<InputConstants.Key> desired = new HashSet<>();
	/** True while playback is advancing (as opposed to paused or waiting for the player). */
	private static boolean active;
	/** True after the loop key (or starting in a keybind mode) switched playback on; cleared by pressing the key again. */
	private static boolean keyPlaying;
	/** The player could not run the loop right now because neither left click nor the loop key says to. */
	private static boolean waiting;

	// Camera: the recording's recorded turn (cumulative) as a curve over time, applied on top of a base angle.
	private static float[] curveTime = {0};
	private static float[] curveYaw = {0};
	private static float[] curvePitch = {0};
	private static float baseYaw;
	private static float basePitch;
	private static boolean controlsCamera;
	/** True until the first tick of a recording has set the base angle and started easing onto it. */
	private static boolean needsStart;
	/** Timeline position of the most recent tick start; frames add the partial tick to it. */
	private static float tickStartTime;
	private static boolean blending;
	private static long blendStart;
	private static float blendYaw;
	private static float blendPitch;

	/** True while this class has switched {@code pauseOnLostFocus} off; {@link #savedPauseOnLostFocus} is the value to restore. */
	private static boolean focusPauseOverridden;
	private static boolean savedPauseOnLostFocus;

	private static @Nullable String lastLoopId;
	private static @Nullable Component lastResult;

	private LoopPlayer() {
	}

	public static void register() {
		LoopSettings.load();
		toggleKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.easyfarm.toggle_loop", GLFW.GLFW_KEY_F9, CATEGORY));
		ClientTickEvents.START_CLIENT_TICK.register(LoopPlayer::onStartTick);
		LevelRenderEvents.START_MAIN.register(context -> onFrame(Minecraft.getInstance()));
	}

	/** True for this mod's loop binding, which playback leaves alone. */
	public static boolean isOwnMapping(KeyMapping mapping) {
		return mapping == toggleKey;
	}

	/** True if {@code key} is whatever the loop key is currently bound to. */
	public static boolean isToggleKey(InputConstants.Key key) {
		return toggleKey != null && !toggleKey.isUnbound() && toggleKey.saveString().equals(key.getName());
	}

	private static boolean wantsPlay() {
		LoopSettings.PlayMode mode = LoopSettings.playMode();
		return (mode.allowsHold() && PhysicalInput.mouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)) || (mode.allowsKeybind() && keyPlaying);
	}

	private static void onStartTick(Minecraft minecraft) {
		if (toggleKey != null) {
			while (toggleKey.consumeClick()) {
				toggleFromHotkey();
			}
		}
		tickPlayback(minecraft);
		syncBackgroundPlay(minecraft);
	}

	/**
	 * Keeps a keybind-started loop running while the window is not focused. Minecraft opens the pause menu half a second
	 * after focus is lost when {@code pauseOnLostFocus} is on, and an open screen stops playback. So that option is
	 * switched off only while a loop is actually running, and put back the moment it is not (stopped, paused, a screen
	 * opened, or the world left), which also means it is never left off while any settings screen could save it.
	 */
	private static void syncBackgroundPlay(Minecraft minecraft) {
		boolean wanted = loop != null && active && keyPlaying && LoopSettings.backgroundPlay();
		if (wanted && !focusPauseOverridden) {
			savedPauseOnLostFocus = minecraft.options.pauseOnLostFocus;
			minecraft.options.pauseOnLostFocus = false;
			focusPauseOverridden = true;
		} else if (!wanted && focusPauseOverridden) {
			minecraft.options.pauseOnLostFocus = savedPauseOnLostFocus;
			focusPauseOverridden = false;
		}
	}

	/** Whether the game's own "pause on lost focus" setting is currently switched off by background play. */
	public static boolean isBackgroundPlayActive() {
		return focusPauseOverridden;
	}

	private static void tickPlayback(Minecraft minecraft) {
		if (loop == null) {
			return;
		}
		LocalPlayer player = minecraft.player;
		if (player == null) {
			stop(Component.translatable("easyfarm.loops.result.left_world"));
			return;
		}
		if (Recorder.isRecording()) {
			// Never let a recording capture the loop's own input.
			stop(Component.translatable("easyfarm.loops.result.recording_started"));
			return;
		}
		if (minecraft.gui.screen() != null || minecraft.isPaused()) {
			// The game released the keys itself; resume from a clean slate.
			active = false;
			return;
		}

		if (!wantsPlay()) {
			// Paused in place: let go of everything, but keep the position (and any buffer already rolled) so playing
			// again carries on exactly where it stopped.
			waiting = true;
			if (active) {
				active = false;
				releaseKeys();
			}
			return;
		}
		waiting = false;
		if (!active) {
			active = true;
			if (step % 2 == 0) {
				if (needsStart) {
					resetBase(player);
					needsStart = false;
				}
				// Resuming: the recording's angle for this position is unchanged, so ease the camera from wherever the
				// player left it back onto that angle.
				startBlend(player, stepTick);
			}
		}

		advance(minecraft, player);
	}

	/** Loop key pressed. */
	private static void toggleFromHotkey() {
		Minecraft minecraft = Minecraft.getInstance();
		if (loop != null) {
			if (LoopSettings.playMode().allowsKeybind()) {
				keyPlaying = !keyPlaying;
				overlay(Component.translatable(keyPlaying ? "easyfarm.loops.overlay.resumed" : "easyfarm.loops.overlay.paused", loop.name()));
			} else {
				stop(Component.translatable("easyfarm.loops.result.stopped"));
			}
			return;
		}
		if (minecraft.player == null) {
			return;
		}

		Loop last = lastLoopId == null ? null : LoopStore.get(lastLoopId);
		if (last == null) {
			overlay(Component.translatable("easyfarm.loops.result.no_last_loop"));
			return;
		}
		start(last);
	}

	/** Called every rendered frame: keeps the camera moving smoothly between ticks. */
	private static void onFrame(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (loop == null || !active || !controlsCamera || step % 2 != 0 || player == null || minecraft.gui.screen() != null
				|| minecraft.isPaused() || Recorder.isRecording() || !wantsPlay()) {
			return;
		}
		float partial = Mth.clamp(minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false), 0.0f, 1.0f);
		applyCamera(player, tickStartTime + partial);
	}

	// ---- camera ----

	private static void resetBase(LocalPlayer player) {
		Recording playing = sequence.get(step / 2);
		if (playing.hasStartLook()) {
			// Start of every recording: turn back to exactly where it began.
			baseYaw = playing.startYaw();
			basePitch = Mth.clamp(playing.startPitch(), -90.0f, 90.0f);
		} else {
			baseYaw = player.getYRot();
			basePitch = player.getXRot();
		}
	}

	/** Makes the camera ease from its current angle onto the recording's angle at {@code time}. */
	private static void startBlend(LocalPlayer player, float time) {
		if (!controlsCamera) {
			return;
		}
		blendYaw = Mth.wrapDegrees(player.getYRot() - targetYaw(time));
		blendPitch = player.getXRot() - targetPitch(time);
		blendStart = System.nanoTime();
		blending = true;
	}

	private static float targetYaw(float time) {
		return baseYaw + interpolate(curveYaw, time);
	}

	private static float targetPitch(float time) {
		return Mth.clamp(basePitch + interpolate(curvePitch, time), -90.0f, 90.0f);
	}

	private static float interpolate(float[] values, float time) {
		int last = curveTime.length - 1;
		if (time <= curveTime[0]) {
			return values[0];
		}
		if (time >= curveTime[last]) {
			return values[last];
		}
		int low = 0;
		int high = last;
		while (high - low > 1) {
			int mid = (low + high) >>> 1;
			if (curveTime[mid] <= time) {
				low = mid;
			} else {
				high = mid;
			}
		}
		float span = curveTime[high] - curveTime[low];
		float f = span <= 0 ? 1.0f : (time - curveTime[low]) / span;
		return values[low] + (values[high] - values[low]) * f;
	}

	private static void applyCamera(LocalPlayer player, float time) {
		float yaw = targetYaw(time);
		float pitch = targetPitch(time);
		if (blending) {
			float f = (System.nanoTime() - blendStart) / (float) BLEND_NANOS;
			if (f >= 1.0f) {
				blending = false;
			} else {
				float ease = f * f * (3.0f - 2.0f * f);
				float remaining = 1.0f - ease;
				yaw += blendYaw * remaining;
				pitch = Mth.clamp(pitch + blendPitch * remaining, -90.0f, 90.0f);
			}
		}
		player.setYRot(yaw);
		player.setXRot(pitch);
		// Old angle equal to the new one, so the renderer shows exactly this angle rather than blending from the last one.
		player.yRotO = yaw;
		player.xRotO = pitch;
	}

	private static void buildCurve(Recording source) {
		List<Recording.Event> looks = new ArrayList<>();
		for (Recording.Event event : source.events()) {
			if (event.type() == Recording.Type.LOOK) {
				looks.add(event);
			}
		}
		curveTime = new float[looks.size() + 1];
		curveYaw = new float[looks.size() + 1];
		curvePitch = new float[looks.size() + 1];
		float yaw = 0;
		float pitch = 0;
		for (int i = 0; i < looks.size(); i++) {
			Recording.Event look = looks.get(i);
			yaw += look.yaw();
			pitch += look.pitch();
			curveTime[i + 1] = Math.max(look.time(), curveTime[i]);
			curveYaw[i + 1] = yaw;
			curvePitch[i + 1] = pitch;
		}
		controlsCamera = source.controlsCamera();
	}

	// ---- stepping ----

	/** Plays one tick of the current step and moves on when it is done. */
	private static void advance(Minecraft minecraft, LocalPlayer player) {
		// Discard every keystroke and click the player makes while the loop plays, except the loop key itself, so only the
		// recording's inputs reach the game. (Pausing the loop gives the keyboard back.)
		for (KeyMapping mapping : minecraft.options.keyMappings) {
			if (!isOwnMapping(mapping)) {
				while (mapping.consumeClick()) {
					// drained
				}
			}
		}

		if (step % 2 == 0) {
			Recording recording = sequence.get(step / 2);
			boolean hold = holdFlags.get(step / 2);
			List<Recording.Event> events = recording.events();
			while (eventCursor < events.size() && events.get(eventCursor).tick() <= stepTick) {
				Recording.Event event = events.get(eventCursor++);
				switch (event.type()) {
					case LOOK -> {
						// Camera samples are played from the interpolated curve, not applied one by one.
					}
					case KEY, MOUSE -> {
						boolean finalRelease = hold && !event.down() && event.tick() >= recording.lengthTicks()
								&& recording.heldAtEnd().contains(event.input());
						// A held-through-the-buffer input is simply never released, so there is no one-tick gap.
						if (!finalRelease) {
							press(event.input().key(), event.down());
						}
					}
				}
			}

			if (controlsCamera) {
				tickStartTime = stepTick;
				applyCamera(player, tickStartTime);
			}
			reassertKeys(minecraft);

			if (stepTick >= recording.lengthTicks()) {
				enterBuffer(hold ? recording : null);
			} else {
				stepTick++;
			}
		} else {
			reassertKeys(minecraft);
			stepTick++;
			if (stepTick >= bufferTicks) {
				enterRecording(step + 1);
			}
		}
	}

	/** Re-asserts the held state every tick: release everything, then press exactly what playback holds. */
	private static void reassertKeys(Minecraft minecraft) {
		for (KeyMapping mapping : minecraft.options.keyMappings) {
			if (!isOwnMapping(mapping)) {
				mapping.setDown(false);
			}
		}
		for (InputConstants.Key key : desired) {
			KeyMapping.set(key, true);
		}
	}

	private static void press(InputConstants.Key key, boolean down) {
		if (down) {
			desired.add(key);
			KeyMapping.click(key);
		} else {
			desired.remove(key);
		}
	}

	/**
	 * @param holdFrom the recording that just ended if its block keeps the last keys pressed through the buffer, else null
	 */
	private static void enterBuffer(@Nullable Recording holdFrom) {
		step++;
		stepTick = 0;
		bufferTicks = rollBuffer();
		desired.clear();
		if (holdFrom != null) {
			for (Recording.Input input : holdFrom.heldAtEnd()) {
				desired.add(input.key());
			}
		}
	}

	private static void enterRecording(int nextStep) {
		if (nextStep >= sequence.size() * 2) {
			nextStep = 0;
			cycle++;
		}
		step = nextStep;
		stepTick = 0;
		eventCursor = 0;
		// Whatever the buffer held is let go of here, unless the next recording presses it again on its first tick.
		desired.clear();
		buildCurve(sequence.get(step / 2));
		needsStart = true;
		if (active) {
			// Already running: set up the new recording's camera right away rather than on the next resume.
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				resetBase(player);
				needsStart = false;
				startBlend(player, 0);
			}
		}
	}

	/** A fresh, independent draw for the buffer just reached. Called exactly once per buffer encounter. */
	private static int rollBuffer() {
		Loop current = loop;
		if (current == null) {
			return Loop.MIN_BUFFER_TICKS;
		}
		buffersRolled++;
		return RANDOM.nextInt(current.bufferMinTicks(), current.bufferMaxTicks() + 1);
	}

	/** Lets go of every binding, then hands them back to the physical keyboard/mouse state. */
	private static void releaseKeys() {
		Minecraft minecraft = Minecraft.getInstance();
		for (KeyMapping mapping : minecraft.options.keyMappings) {
			if (!isOwnMapping(mapping)) {
				mapping.setDown(false);
			}
		}
		KeyMapping.setAll();
	}

	private static void releaseAll() {
		if (active || !desired.isEmpty()) {
			releaseKeys();
		}
		desired.clear();
		active = false;
	}

	// ---- starting and stopping ----

	/**
	 * Starts playing a loop. Fails (with {@link #lastResult()} saying why) if a recording is running, the loop is empty,
	 * or one of its recordings no longer exists.
	 */
	public static boolean start(Loop toPlay) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return false;
		}
		if (Recorder.isRecording()) {
			lastResult = Component.translatable("easyfarm.loops.result.busy_recording");
			overlay(lastResult);
			return false;
		}
		if (toPlay.steps().isEmpty()) {
			lastResult = Component.translatable("easyfarm.loops.result.empty");
			overlay(lastResult);
			return false;
		}

		Map<String, Recording> byId = new HashMap<>();
		for (Recording recording : RecordingStore.list()) {
			byId.put(recording.id(), recording);
		}
		List<Recording> resolved = new ArrayList<>();
		List<Boolean> holds = new ArrayList<>();
		int missing = 0;
		for (Loop.Step loopStep : toPlay.steps()) {
			Recording recording = byId.get(loopStep.recordingId());
			if (recording == null) {
				missing++;
			} else {
				resolved.add(recording);
				holds.add(loopStep.holdLastKeys());
			}
		}
		if (missing > 0) {
			lastResult = Component.translatable("easyfarm.loops.result.missing", missing);
			overlay(lastResult);
			return false;
		}

		releaseAll();
		loop = toPlay;
		sequence = List.copyOf(resolved);
		holdFlags = List.copyOf(holds);
		cycle = 1;
		buffersRolled = 0;
		lastLoopId = toPlay.id();
		lastResult = null;
		waiting = false;
		blending = false;
		// The keybind modes start playing straight away; hold mode waits for left click.
		keyPlaying = LoopSettings.playMode().allowsKeybind();
		enterRecording(0);

		overlay(Component.translatable(startedMessageKey(), toPlay.name(), toggleKeyName()));
		return true;
	}

	private static String startedMessageKey() {
		return switch (LoopSettings.playMode()) {
			case HOLD -> "easyfarm.loops.overlay.started_hold";
			case KEYBIND -> "easyfarm.loops.overlay.started_key";
			case BOTH -> "easyfarm.loops.overlay.started_both";
		};
	}

	/**
	 * Sends the running loop back to the start of its first recording, cycle 1. Keys are released first, and any buffer
	 * in progress is dropped; the next buffer reached is rolled fresh as always. Whether playback is paused (menu open,
	 * waiting for left click) is left as it was.
	 *
	 * @return false if no loop is running
	 */
	public static boolean reset() {
		if (loop == null) {
			return false;
		}
		boolean wasActive = active;
		releaseAll();
		cycle = 1;
		active = false;
		enterRecording(0);
		if (wasActive) {
			needsStart = true;
		}
		overlay(Component.translatable("easyfarm.loops.overlay.reset", loop.name()));
		return true;
	}

	/**
	 * The inventory buttons' action: selecting the loop that is already playing stops it; selecting any other loop
	 * switches playback to it (only one loop plays at a time).
	 *
	 * @return whether {@code toToggle} is playing afterwards
	 */
	public static boolean toggle(Loop toToggle) {
		if (loop != null && loop.id().equals(toToggle.id())) {
			stop();
			return false;
		}
		return start(toToggle);
	}

	/** Whether this loop (by id) is the one playing. */
	public static boolean isPlaying(Loop candidate) {
		return loop != null && loop.id().equals(candidate.id());
	}

	/** Stops playback and releases every key it was holding. */
	public static void stop() {
		stop(Component.translatable("easyfarm.loops.result.stopped"));
	}

	private static void stop(Component reason) {
		if (loop == null) {
			return;
		}
		releaseAll();
		loop = null;
		waiting = false;
		keyPlaying = false;
		blending = false;
		sequence = List.of();
		holdFlags = List.of();
		lastResult = reason;
		Minecraft minecraft = Minecraft.getInstance();
		if (focusPauseOverridden) {
			minecraft.options.pauseOnLostFocus = savedPauseOnLostFocus;
			focusPauseOverridden = false;
		}
		overlay(reason);
	}

	private static void overlay(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.sendOverlayMessage(message);
		}
	}

	// ---- state for the UI and tests ----

	public static boolean isRunning() {
		return loop != null;
	}

	public static @Nullable Loop currentLoop() {
		return loop;
	}

	/** 1-based count of trips through the loop. */
	public static int cycle() {
		return cycle;
	}

	/** Running, but paused because neither left click nor the loop key says to play. */
	public static boolean isWaiting() {
		return loop != null && waiting;
	}

	/** Actually advancing right now (not paused, not waiting, no menu open). */
	public static boolean isPlaying() {
		return loop != null && active;
	}

	/** Whether the loop key has switched playback on. */
	public static boolean isKeyPlaying() {
		return loop != null && keyPlaying;
	}

	public static boolean inBuffer() {
		return loop != null && step % 2 == 1;
	}

	/** Length of the buffer currently being waited out (only meaningful while {@link #inBuffer()}). */
	public static int currentBufferTicks() {
		return bufferTicks;
	}

	/** How many buffers have been rolled since the loop started. */
	public static int buffersRolled() {
		return buffersRolled;
	}

	/** Short description of where playback is, e.g. "Recording 2 (2/3)" or "Buffer 1.35s". */
	public static Component describePosition() {
		if (loop == null) {
			return Component.empty();
		}
		if (step % 2 == 1) {
			boolean endBuffer = step == sequence.size() * 2 - 1;
			return Component.translatable(endBuffer ? "easyfarm.loops.position.end_buffer" : "easyfarm.loops.position.buffer",
					Loop.formatSeconds(bufferTicks));
		}
		return Component.translatable("easyfarm.loops.position.recording",
				sequence.get(step / 2).name(), step / 2 + 1, sequence.size());
	}

	public static @Nullable Component lastResult() {
		return lastResult;
	}

	public static Component toggleKeyName() {
		return toggleKey == null ? Component.literal("?") : toggleKey.getTranslatedKeyMessage();
	}
}
