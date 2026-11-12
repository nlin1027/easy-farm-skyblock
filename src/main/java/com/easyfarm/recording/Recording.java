package com.easyfarm.recording;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * A saved run of input: every keyboard key, every mouse button and the camera, on a game-tick timeline (20 ticks per
 * second).
 *
 * <p>Playback applies the key and mouse events for tick {@code t} at tick {@code t}, and turns the camera along the
 * recorded samples. Every key or button pressed during the recording is released again by {@link #lengthTicks()}, so
 * replaying a recording never leaves anything stuck down, except on purpose: {@link #heldAtEnd()} remembers what was
 * still held when recording stopped, which a loop can keep pressed through the buffer that follows.
 *
 * @param id          file name without {@code .json}; unique, never shown to the player
 * @param name        display name
 * @param createdAt   when recording started
 * @param lengthTicks how long the recording lasts
 * @param startYaw    exact camera yaw when recording began ({@link Float#NaN} if unknown)
 * @param startPitch  exact camera pitch when recording began ({@link Float#NaN} if unknown)
 * @param events      in time order
 * @param heldAtEnd   the keys and buttons that were still down when recording stopped
 */
public record Recording(String id, String name, Instant createdAt, int lengthTicks, float startYaw, float startPitch,
		List<Event> events, List<Input> heldAtEnd) {
	public static final int TICKS_PER_SECOND = 20;

	public Recording {
		events = List.copyOf(events);
		heldAtEnd = List.copyOf(heldAtEnd);
	}

	public enum Type {
		/** A keyboard key (GLFW key code) going down or up. */
		KEY,
		/** A mouse button going down or up. */
		MOUSE,
		/** The camera turning: yaw/pitch are the change since the previous camera sample (several per tick). */
		LOOK
	}

	/** A key or mouse button, without a state. */
	public record Input(Type type, int code) {
		public InputConstants.Key key() {
			return (this.type == Type.MOUSE ? InputConstants.Type.MOUSE : InputConstants.Type.KEYSYM).getOrCreate(this.code);
		}
	}

	/** One thing that happened on a tick. code/down apply to KEY and MOUSE, yaw/pitch to LOOK. */
	public record Event(int tick, float frac, Type type, int code, boolean down, float yaw, float pitch) {
		public static Event key(int tick, int code, boolean down) {
			return new Event(tick, 0, Type.KEY, code, down, 0, 0);
		}

		public static Event mouse(int tick, int button, boolean down) {
			return new Event(tick, 0, Type.MOUSE, button, down, 0, 0);
		}

		/** A camera sample part-way through a tick: {@code frac} is in [0, 1). */
		public static Event look(int tick, float frac, float yaw, float pitch) {
			return new Event(tick, frac, Type.LOOK, 0, false, yaw, pitch);
		}

		/** Position on the timeline in ticks, including the sub-tick fraction used by camera samples. */
		public float time() {
			return this.tick + this.frac;
		}

		public Input input() {
			return new Input(this.type, this.code);
		}
	}

	/** False for recordings made before the camera was recorded; playback then leaves the camera alone. */
	public boolean hasStartLook() {
		return !Float.isNaN(this.startYaw) && !Float.isNaN(this.startPitch);
	}

	/** Whether playback should take over the camera: the recording turned it, or knows where it started. */
	public boolean controlsCamera() {
		if (this.hasStartLook()) {
			return true;
		}
		for (Event event : this.events) {
			if (event.type() == Type.LOOK) {
				return true;
			}
		}
		return false;
	}

	/** Every distinct key and button pressed, in the order first pressed. */
	public Set<Input> inputsUsed() {
		Set<Input> inputs = new LinkedHashSet<>();
		for (Event event : this.events) {
			if (event.type() != Type.LOOK) {
				inputs.add(event.input());
			}
		}
		return inputs;
	}

	/** Short names of every key and button pressed ("W", "Space", "Left Button"). */
	public List<String> inputLabels() {
		List<String> labels = new ArrayList<>();
		for (Input input : this.inputsUsed()) {
			labels.add(input.key().getDisplayName().getString());
		}
		return labels;
	}

	public boolean usesInput(Type type, int code) {
		for (Event event : this.events) {
			if (event.type() == type && event.code() == code) {
				return true;
			}
		}
		return false;
	}

	/** Key and button presses (camera samples do not count). */
	public int pressCount() {
		int presses = 0;
		for (Event event : this.events) {
			if (event.type() != Type.LOOK && event.down()) {
				presses++;
			}
		}
		return presses;
	}

	/** {@code m:ss}, or {@code h:mm:ss} for very long recordings. */
	public static String formatDuration(int ticks) {
		int totalSeconds = ticks / TICKS_PER_SECOND;
		int hours = totalSeconds / 3600;
		int minutes = totalSeconds / 60 % 60;
		int seconds = totalSeconds % 60;
		return hours > 0
				? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
				: String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
	}
}
