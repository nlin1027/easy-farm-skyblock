package com.easyfarm.loop;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.easyfarm.recording.Recording;

/**
 * An ordered chain of recordings that is played over and over.
 *
 * <p>Buffers are not stored as steps: they are implied after <em>every</em> recording, which covers both rules at once
 * (a buffer between any two recordings, and a buffer at the end of the loop before it starts over). Because the model
 * has no way to express "no buffer here", a loop without buffers cannot exist, whatever the editor or a hand-edited
 * file does. Each buffer's length is rolled fresh, uniformly from {@code [bufferMinTicks, bufferMaxTicks]}, every
 * single time playback reaches it; see {@link LoopPlayer}.
 *
 * @param id             file name without {@code .json}
 * @param name           display name
 * @param createdAt      when the loop was first saved
 * @param steps          the recording blocks in play order; the same recording may appear more than once
 * @param bufferMinTicks shortest possible buffer, at least {@link #MIN_BUFFER_TICKS}
 * @param bufferMaxTicks longest possible buffer, always greater than the minimum so the roll is never fixed
 * @param showInInventory whether the loop gets a play/stop button next to the inventory's EF button
 */
public record Loop(String id, String name, Instant createdAt, List<Step> steps, int bufferMinTicks, int bufferMaxTicks,
		boolean showInInventory) {
	/** One tick (0.05 s): a buffer can be short, but it can never be skipped. */
	public static final int MIN_BUFFER_TICKS = 1;
	/** Ten minutes. */
	public static final int MAX_BUFFER_TICKS = 10 * 60 * Recording.TICKS_PER_SECOND;

	/**
	 * One recording block of a loop.
	 *
	 * @param recordingId   the {@link Recording#id() recording} to play
	 * @param holdLastKeys  keep the keys and buttons that were still held when the recording ended pressed through the
	 *                      random buffer that follows, until the next recording starts
	 */
	public record Step(String recordingId, boolean holdLastKeys) {
		public Step withHoldLastKeys(boolean hold) {
			return new Step(this.recordingId, hold);
		}
	}

	public Loop {
		steps = List.copyOf(steps);
		if (!isValidBuffer(bufferMinTicks, bufferMaxTicks)) {
			throw new IllegalArgumentException("invalid buffer range " + bufferMinTicks + ".." + bufferMaxTicks);
		}
	}

	public Loop withShowInInventory(boolean show) {
		return new Loop(this.id, this.name, this.createdAt, this.steps, this.bufferMinTicks, this.bufferMaxTicks, show);
	}

	/** The recording ids in play order. */
	public List<String> recordingIds() {
		return this.steps.stream().map(Step::recordingId).toList();
	}

	/** Steps for these recordings, none of them holding keys through its buffer. */
	public static List<Step> plainSteps(List<String> recordingIds) {
		return recordingIds.stream().map(id -> new Step(id, false)).toList();
	}

	/** The minimum is at least one tick and the maximum is strictly larger, so every roll has at least two outcomes. */
	public static boolean isValidBuffer(int minTicks, int maxTicks) {
		return minTicks >= MIN_BUFFER_TICKS && maxTicks > minTicks && maxTicks <= MAX_BUFFER_TICKS;
	}

	/** {@code 0.5–2.0s} style label for a buffer range. */
	public static String formatBufferRange(int minTicks, int maxTicks) {
		return formatSeconds(minTicks) + "–" + formatSeconds(maxTicks) + "s";
	}

	/** Seconds with up to two decimals (ticks are 0.05 s), without trailing zeros beyond one decimal: 0.5, 1.25, 3.0. */
	public static String formatSeconds(int ticks) {
		String text = String.format(Locale.ROOT, "%.2f", ticks / (double) Recording.TICKS_PER_SECOND);
		return text.endsWith("0") ? text.substring(0, text.length() - 1) : text;
	}

	public String bufferLabel() {
		return formatBufferRange(this.bufferMinTicks, this.bufferMaxTicks);
	}
}
