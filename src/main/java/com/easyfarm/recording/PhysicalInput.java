package com.easyfarm.recording;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;

/**
 * The physical keyboard and mouse, read straight from GLFW rather than through the key bindings. The recorder captures
 * from here (so every key counts, bound or not), and loop playback reads "is left click held" from here (because playback
 * itself drives the attack binding). Nothing is sent anywhere.
 *
 * <p>Tests replace the source with a fake one, since the test harness cannot change what GLFW reports.
 */
public final class PhysicalInput {
	/** Where key and button state comes from. */
	public interface Source {
		boolean key(int code);

		boolean mouse(int button);
	}

	private static final Source GLFW_SOURCE = new Source() {
		@Override
		public boolean key(int code) {
			return GLFW.glfwGetKey(Minecraft.getInstance().getWindow().handle(), code) == GLFW.GLFW_PRESS;
		}

		@Override
		public boolean mouse(int button) {
			return GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().handle(), button) == GLFW.GLFW_PRESS;
		}
	};

	private static Source source = GLFW_SOURCE;

	private PhysicalInput() {
	}

	public static boolean key(int code) {
		return source.key(code);
	}

	public static boolean mouse(int button) {
		return source.mouse(button);
	}

	/** Test hook: use {@code fake} instead of GLFW (pass {@code null} to go back). */
	public static void override(Source fake) {
		source = fake == null ? GLFW_SOURCE : fake;
	}
}
