package com.easyfarm.loop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import com.easyfarm.EasyFarmClient;
import com.easyfarm.recording.MovementKey;
import com.easyfarm.recording.Recorder;
import com.easyfarm.recording.Recording;
import com.easyfarm.recording.RecordingStore;

/**
 * Plays a {@link Loop}: recording, buffer, recording, buffer, ... then starts over, until stopped.
 *
 * <p><b>Buffers.</b> After every recording, including the last one before the loop wraps around, playback holds no keys
 * for a buffer whose length is drawn from the loop's range at the moment the buffer is reached. Nothing is cached or
 * precomputed: every buffer encounter calls {@link #rollBuffer()} once, so two buffers in the same cycle, or the same
 * buffer in two cycles, are independent draws.
 *
 * <p><b>Input.</b> Playback drives the player's own movement key bindings at the start of each client tick, before the
 * game reads them. It pauses while any screen is open (the game releases movement keys then) and stops on its own when
 * the player leaves the world or a recording starts. Nothing is sent to the server beyond the movement the keys cause.
 */
public final class LoopPlayer {
	private static final MovementKey[] KEYS = MovementKey.values();
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(EasyFarmClient.MOD_ID, "loops"));

	/** Uniform source for buffer rolls. Only ever used on the client thread. */
	private static final RandomGenerator RANDOM = RandomGenerator.getDefault();

	private static @Nullable KeyMapping toggleKey;

	private static @Nullable Loop loop;
	private static List<Recording> sequence = List.of();
	/** Even steps play {@code sequence.get(step / 2)}; odd steps are the buffer after it. */
	private static int step;
	private static int stepTick;
	private static int eventCursor;
	private static int bufferTicks;
	private static int cycle;
	private static int buffersRolled;
	private static final boolean[] desired = new boolean[KEYS.length];

	/** True while "hold attack to run" is on and attack is not held. */
	private static boolean waitingForAttack;

	private static @Nullable String lastLoopId;
	private static @Nullable Component lastResult;

	private LoopPlayer() {
	}

	public static void register() {
		LoopSettings.load();
		toggleKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.easyfarm.toggle_loop", GLFW.GLFW_KEY_F9, CATEGORY));
		ClientTickEvents.START_CLIENT_TICK.register(LoopPlayer::onStartTick);
	}

	private static void onStartTick(Minecraft minecraft) {
		if (toggleKey != null) {
			while (toggleKey.consumeClick()) {
				toggleFromHotkey();
			}
		}

		if (loop == null) {
			return;
		}

		if (minecraft.player == null) {
			stop(Component.translatable("easyfarm.loops.result.left_world"));
			return;
		}
		if (Recorder.isRecording()) {
			// Never let a recording capture the loop's own input.
			stop(Component.translatable("easyfarm.loops.result.recording_started"));
			return;
		}
		if (minecraft.gui.screen() != null || minecraft.isPaused()) {
			return;
		}

		if (LoopSettings.holdAttackToRun() && !minecraft.options.keyAttack.isDown()) {
			// Paused in place: let go of the movement keys, but keep the position (and any buffer already rolled) so
			// holding attack again carries on exactly where it stopped.
			if (!waitingForAttack) {
				waitingForAttack = true;
				releaseHeldKeys(minecraft.options);
			}
			return;
		}
		waitingForAttack = false;

		advance(minecraft.options);
	}

	/** Lets go of every movement key playback is holding without forgetting that it was holding them. */
	private static void releaseHeldKeys(Options options) {
		for (int i = 0; i < KEYS.length; i++) {
			if (desired[i]) {
				setKey(KEYS[i].mapping(options), false);
			}
		}
	}

	private static void toggleFromHotkey() {
		if (loop != null) {
			stop(Component.translatable("easyfarm.loops.result.stopped"));
			return;
		}

		Loop last = lastLoopId == null ? null : LoopStore.get(lastLoopId);
		if (last == null) {
			overlay(Component.translatable("easyfarm.loops.result.no_last_loop"));
			return;
		}
		start(last);
	}

	/** Plays one tick of the current step and moves on when it is done. */
	private static void advance(Options options) {
		if (step % 2 == 0) {
			Recording recording = sequence.get(step / 2);
			List<Recording.Event> events = recording.events();
			while (eventCursor < events.size() && events.get(eventCursor).tick() <= stepTick) {
				Recording.Event event = events.get(eventCursor++);
				desired[event.key().ordinal()] = event.down();
			}
			apply(options);

			if (stepTick >= recording.lengthTicks()) {
				// Recordings always end with every key released; make sure of it anyway before the buffer.
				Arrays.fill(desired, false);
				apply(options);
				enterBuffer();
			} else {
				stepTick++;
			}
		} else {
			apply(options);
			stepTick++;
			if (stepTick >= bufferTicks) {
				enterRecording(step + 1);
			}
		}
	}

	private static void enterBuffer() {
		step++;
		stepTick = 0;
		bufferTicks = rollBuffer();
	}

	private static void enterRecording(int nextStep) {
		if (nextStep >= sequence.size() * 2) {
			nextStep = 0;
			cycle++;
		}
		step = nextStep;
		stepTick = 0;
		eventCursor = 0;
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

	/** Sets each movement key binding to the state playback wants, toggle-sneak/sprint included. */
	private static void apply(Options options) {
		for (int i = 0; i < KEYS.length; i++) {
			setKey(KEYS[i].mapping(options), desired[i]);
		}
	}

	private static void setKey(KeyMapping mapping, boolean down) {
		if (mapping.isDown() == down) {
			return;
		}
		mapping.setDown(down);
		// A toggle binding (toggle sneak/sprint) ignores "up" and flips on "down"; one more press lands on the target.
		if (mapping.isDown() != down) {
			mapping.setDown(!down);
		}
	}

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
		if (toPlay.recordingIds().isEmpty()) {
			lastResult = Component.translatable("easyfarm.loops.result.empty");
			overlay(lastResult);
			return false;
		}

		Map<String, Recording> byId = new HashMap<>();
		for (Recording recording : RecordingStore.list()) {
			byId.put(recording.id(), recording);
		}
		List<Recording> resolved = new ArrayList<>();
		int missing = 0;
		for (String id : toPlay.recordingIds()) {
			Recording recording = byId.get(id);
			if (recording == null) {
				missing++;
			} else {
				resolved.add(recording);
			}
		}
		if (missing > 0) {
			lastResult = Component.translatable("easyfarm.loops.result.missing", missing);
			overlay(lastResult);
			return false;
		}

		if (loop != null) {
			releaseAll(minecraft.options);
		}
		loop = toPlay;
		sequence = List.copyOf(resolved);
		Arrays.fill(desired, false);
		cycle = 1;
		buffersRolled = 0;
		enterRecording(0);
		lastLoopId = toPlay.id();
		lastResult = null;
		waitingForAttack = false;

		overlay(Component.translatable(
				LoopSettings.holdAttackToRun() ? "easyfarm.loops.overlay.started_hold" : "easyfarm.loops.overlay.started",
				toPlay.name(), toggleKeyName()));
		return true;
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
		releaseAll(Minecraft.getInstance().options);
		cycle = 1;
		enterRecording(0);
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
		releaseAll(Minecraft.getInstance().options);
		loop = null;
		waitingForAttack = false;
		sequence = List.of();
		lastResult = reason;
		overlay(reason);
	}

	private static void releaseAll(Options options) {
		for (int i = 0; i < KEYS.length; i++) {
			if (desired[i]) {
				desired[i] = false;
				setKey(KEYS[i].mapping(options), false);
			}
		}
	}

	private static void overlay(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.sendOverlayMessage(message);
		}
	}

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

	/** Running, but paused because "hold attack to run" is on and attack is not held. */
	public static boolean isWaitingForAttack() {
		return loop != null && waitingForAttack;
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
