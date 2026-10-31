package com.easyfarm.recording;

import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;

/**
 * The movement keys Easy Farm records. They are tracked through the player's key bindings rather than raw keyboard keys,
 * so a player who moved "forward" to another key still gets it recorded, and playback can drive the same bindings.
 *
 * <p>The {@link #id} is what goes into the JSON file, so it must never change once released.
 */
public enum MovementKey {
	FORWARD("forward", "W", options -> options.keyUp),
	LEFT("left", "A", options -> options.keyLeft),
	BACK("back", "S", options -> options.keyDown),
	RIGHT("right", "D", options -> options.keyRight),
	JUMP("jump", "Space", options -> options.keyJump),
	SNEAK("sneak", "Shift", options -> options.keyShift),
	SPRINT("sprint", "Ctrl", options -> options.keySprint);

	private final String id;
	private final String shortLabel;
	private final Function<Options, KeyMapping> mapping;

	MovementKey(String id, String shortLabel, Function<Options, KeyMapping> mapping) {
		this.id = id;
		this.shortLabel = shortLabel;
		this.mapping = mapping;
	}

	/** Stable name used in recording files. */
	public String id() {
		return this.id;
	}

	/** The default key, used as a compact label in the HUD. */
	public String shortLabel() {
		return this.shortLabel;
	}

	public KeyMapping mapping(Options options) {
		return this.mapping.apply(options);
	}

	public static @Nullable MovementKey byId(String id) {
		for (MovementKey key : values()) {
			if (key.id.equals(id)) {
				return key;
			}
		}
		return null;
	}
}
