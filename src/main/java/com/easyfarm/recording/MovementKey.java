package com.easyfarm.recording;

import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The seven movement keys that the first recording format (version 1) stored by name. Only used to read those old files:
 * new recordings store raw GLFW key codes for every key. The {@link #id} is what was written to disk, so it never changes.
 */
public enum MovementKey {
	FORWARD("forward", GLFW.GLFW_KEY_W),
	LEFT("left", GLFW.GLFW_KEY_A),
	BACK("back", GLFW.GLFW_KEY_S),
	RIGHT("right", GLFW.GLFW_KEY_D),
	JUMP("jump", GLFW.GLFW_KEY_SPACE),
	SNEAK("sneak", GLFW.GLFW_KEY_LEFT_SHIFT),
	SPRINT("sprint", GLFW.GLFW_KEY_LEFT_CONTROL);

	private final String id;
	private final int defaultCode;

	MovementKey(String id, int defaultCode) {
		this.id = id;
		this.defaultCode = defaultCode;
	}

	public String id() {
		return this.id;
	}

	/** The key these movements are bound to by default. */
	public int defaultCode() {
		return this.defaultCode;
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
