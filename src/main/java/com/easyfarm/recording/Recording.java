package com.easyfarm.recording;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A saved run of movement input.
 *
 * <p>Input is stored as press/release events on a game-tick timeline (20 ticks per second), which is exactly what playback
 * needs: at tick {@code t}, set each key from the events for {@code t}. Every key pressed during the recording is released
 * again by {@link #lengthTicks()}, so replaying a recording never leaves a key stuck down.
 *
 * @param id          file name without {@code .json}; unique, never shown to the player
 * @param name        display name
 * @param createdAt   when recording started
 * @param lengthTicks how long the recording lasts
 * @param events      in tick order
 */
public record Recording(String id, String name, Instant createdAt, int lengthTicks, List<Event> events) {
	public static final int TICKS_PER_SECOND = 20;

	public Recording {
		events = List.copyOf(events);
	}

	/** A key going down or up on a given tick, counted from the start of the recording. */
	public record Event(int tick, MovementKey key, boolean down) {
	}

	public Set<MovementKey> keysUsed() {
		Set<MovementKey> keys = EnumSet.noneOf(MovementKey.class);
		for (Event event : this.events) {
			keys.add(event.key());
		}
		return keys;
	}

	public int pressCount() {
		int presses = 0;
		for (Event event : this.events) {
			if (event.down()) {
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
