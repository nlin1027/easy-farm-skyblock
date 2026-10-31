package com.easyfarm.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.level.GameType;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import com.easyfarm.gui.EasyFarmScreen;
import com.easyfarm.recording.MovementKey;
import com.easyfarm.recording.Recorder;
import com.easyfarm.recording.Recording;
import com.easyfarm.recording.RecordingStore;

/**
 * Drives the real client: opens the HUD with {@code /ef} and with the inventory button (creative and survival),
 * switches tabs by keyboard and mouse, closes it again and screenshots every state to
 * {@code build/run/clientGameTest/screenshots}.
 */
public class EasyFarmClientGameTest implements FabricClientGameTest {
	/** Messages the server sent us. A command the server received and rejected always produces one. */
	private static final List<String> SERVER_MESSAGES = new CopyOnWriteArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		// Overlay (action bar) messages are the mod's own feedback; a rejected command shows up as a chat message.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				SERVER_MESSAGES.add(message.getString());
			}
		});

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			commandsStayLocal(context);
			commandOpensHud(context);

			clearRecordings(context);
			recordsMovementAndSaves(context);
			stoppingWithoutMovementSavesNothing(context);
			deletesRecording(context);
			subcommandsWork(context);

			world.getServer().runCommand("gamemode creative @a");
			context.waitFor(client -> client.gameMode != null && client.gameMode.getPlayerMode() == GameType.CREATIVE);
			inventoryButtonOpensHud(context, "creative", CreativeModeInventoryScreen.class);

			world.getServer().runCommand("gamemode survival @a");
			context.waitFor(client -> client.gameMode != null && client.gameMode.getPlayerMode() == GameType.SURVIVAL);
			inventoryButtonOpensHud(context, "survival", InventoryScreen.class);
			buttonFollowsRecipeBook(context);
		}
	}

	/**
	 * {@code /ef} must never be sent to the server, not even with junk after it. The control proves the method works: a
	 * command the client does not handle is forwarded, and the server visibly answers it.
	 */
	private static void commandsStayLocal(ClientGameTestContext context) {
		SERVER_MESSAGES.clear();
		runChatCommand(context, "easyfarm_control_not_a_command");
		context.waitFor(client -> !SERVER_MESSAGES.isEmpty());
		context.waitTicks(5);

		SERVER_MESSAGES.clear();
		runChatCommand(context, "ef junk");
		context.waitTicks(20);
		check(SERVER_MESSAGES.isEmpty(), "'/ef junk' was forwarded to the server, which answered: " + SERVER_MESSAGES);
		check(currentScreen(context) == null, "'/ef junk' must not open the HUD");
	}

	/** {@code /ef} typed in chat: the HUD must stay open after the chat screen closes itself. */
	private static void commandOpensHud(ClientGameTestContext context) {
		SERVER_MESSAGES.clear();
		runChatCommand(context, "ef");
		context.waitForScreen(EasyFarmScreen.class);
		context.waitTicks(20);
		check(SERVER_MESSAGES.isEmpty(), "'/ef' was forwarded to the server, which answered: " + SERVER_MESSAGES);
		check(selectedTab(context) == 0, "The HUD should open on the Record tab");
		context.takeScreenshot("command-1-record-tab");

		switchTabWithKeyboard(context, GLFW.GLFW_KEY_2);
		check(selectedTab(context) == 1, "Ctrl+2 should select the Recordings tab");
		context.takeScreenshot("command-2-recordings-tab");

		switchTabWithKeyboard(context, GLFW.GLFW_KEY_3);
		check(selectedTab(context) == 2, "Ctrl+3 should select the Loops tab");
		context.takeScreenshot("command-3-loops-tab");

		clickTab(context, 0);
		check(selectedTab(context) == 0, "Clicking the Record tab should select it");
		clickTab(context, 1);
		check(selectedTab(context) == 1, "Clicking the Recordings tab should select it");

		// Opened from chat, so closing returns to the game rather than to a leftover chat box.
		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitFor(client -> client.gui.screen() == null);
	}

	private static void clearRecordings(ClientGameTestContext context) {
		context.runOnClient(client -> {
			for (Recording recording : RecordingStore.list()) {
				try {
					RecordingStore.delete(recording);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		});
	}

	/**
	 * Start from the Record tab, walk forward and jump, stop with the hotkey: one recording must be saved with both keys,
	 * every press matched by a release, and it must show up on the Recordings tab.
	 */
	private static void recordsMovementAndSaves(ClientGameTestContext context) {
		runChatCommand(context, "ef");
		context.waitForScreen(EasyFarmScreen.class);
		context.waitTicks(5);
		context.takeScreenshot("record-1-idle");

		// A name is required: the button stays disabled until one is typed.
		check(!isButtonActive(context, "Start recording"), "'Start recording' must be disabled without a name");
		context.takeScreenshot("record-1b-name-required");
		typeInNameBox(context, "Wheat farm");
		context.takeScreenshot("record-1c-named");
		clickButton(context, "Start recording");
		context.waitFor(client -> client.gui.screen() == null);
		check(context.computeOnClient(client -> Recorder.isRecording()), "Clicking 'Start recording' did not start recording");

		context.getInput().holdKeyFor(options -> options.keyUp, 20);
		context.getInput().holdKeyFor(options -> options.keyJump, 5);
		context.waitTicks(10);

		context.getInput().pressKey(GLFW.GLFW_KEY_F8);
		context.waitFor(client -> !Recorder.isRecording());

		List<Recording> recordings = context.computeOnClient(client -> RecordingStore.list());
		check(recordings.size() == 1, "Expected 1 saved recording, found " + recordings.size());
		Recording recording = recordings.getFirst();
		check(recording.name().equals("Wheat farm"), "The recording should be called 'Wheat farm', but is '" + recording.name() + "'");
		check(recording.keysUsed().containsAll(List.of(MovementKey.FORWARD, MovementKey.JUMP)),
				"The recording should contain forward and jump, but has " + recording.keysUsed());
		check(recording.lengthTicks() >= 30, "The recording is too short: " + recording.lengthTicks() + " ticks");
		long presses = recording.events().stream().filter(Recording.Event::down).count();
		long releases = recording.events().size() - presses;
		check(presses == releases, "Every press needs a release: " + presses + " presses, " + releases + " releases");

		runChatCommand(context, "ef");
		context.waitForScreen(EasyFarmScreen.class);
		switchTabWithKeyboard(context, GLFW.GLFW_KEY_2);
		context.takeScreenshot("record-2-saved-in-recordings-tab");
		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitFor(client -> client.gui.screen() == null);
	}

	/** Start with the hotkey, open the HUD (which pauses recording) and stop without moving: nothing may be saved. */
	private static void stoppingWithoutMovementSavesNothing(ClientGameTestContext context) {
		// Without a name the hotkey must not start anything.
		context.getInput().pressKey(GLFW.GLFW_KEY_F8);
		context.waitTicks(5);
		check(!context.computeOnClient(client -> Recorder.isRecording()), "F8 started a recording without a name");

		context.runOnClient(client -> Recorder.setDraftName("Empty run"));
		context.getInput().pressKey(GLFW.GLFW_KEY_F8);
		context.waitFor(client -> Recorder.isRecording());
		context.waitTicks(10);

		runChatCommand(context, "ef");
		context.waitForScreen(EasyFarmScreen.class);
		context.waitTicks(5);
		check(selectedTab(context) == EasyFarmScreen.TAB_RECORD, "The HUD should open on the Record tab");
		context.takeScreenshot("record-3-recording-in-progress");

		clickButton(context, "Stop and save");
		context.waitFor(client -> !Recorder.isRecording());
		context.waitTicks(5);
		context.takeScreenshot("record-4-nothing-saved");
		check(selectedTab(context) == EasyFarmScreen.TAB_RECORD, "An empty recording should leave the HUD on the Record tab");
		int count = context.computeOnClient(client -> RecordingStore.list().size());
		check(count == 1, "An empty recording was saved anyway: " + count + " recordings");
	}

	/** Select the recording, then Delete twice (the first click only arms the button). */
	private static void deletesRecording(ClientGameTestContext context) {
		switchTabWithKeyboard(context, GLFW.GLFW_KEY_2);
		int[] firstRow = context.computeOnClient(client -> {
			for (GuiEventListener child : client.gui.screen().children()) {
				if (child instanceof AbstractWidget widget && widget.getClass().getSimpleName().equals("RecordingList")) {
					return new int[] {widget.getX() + 40, widget.getY() + 12};
				}
			}
			throw new AssertionError("The Recordings tab has no list");
		});
		clickAtGuiPosition(context, firstRow[0], firstRow[1]);
		context.waitTicks(2);

		clickButton(context, "Delete");
		context.waitTicks(2);
		check(context.computeOnClient(client -> RecordingStore.list().size()) == 1, "One click on Delete must not delete yet");
		clickButton(context, "Click again to delete");
		context.waitTicks(5);
		context.takeScreenshot("record-5-after-delete");
		check(context.computeOnClient(client -> RecordingStore.list().isEmpty()), "The recording was not deleted");

		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitFor(client -> client.gui.screen() == null);
	}

	/** Every subcommand of /ef, run through the real chat box. */
	private static void subcommandsWork(ClientGameTestContext context) {
		SERVER_MESSAGES.clear();
		runChatCommand(context, "ef help");
		context.waitTicks(10);
		check(context.computeOnClient(client -> client.gui.screen() == null), "'/ef help' must not open a screen");

		runChatCommand(context, "ef record \"Cmd run\"");
		context.waitFor(client -> Recorder.isRecording());
		check("Cmd run".equals(context.computeOnClient(client -> Recorder.activeName())), "Wrong name for '/ef record'");
		context.getInput().holdKeyFor(options -> options.keyUp, 10);
		context.waitTicks(3);
		context.takeScreenshot("command-4-recording-from-command");
		runChatCommand(context, "ef stop");
		context.waitFor(client -> !Recorder.isRecording());
		check(context.computeOnClient(client -> RecordingStore.find("cmd RUN") != null), "'/ef stop' did not save 'Cmd run'");

		runChatCommand(context, "ef status");
		runChatCommand(context, "ef list");
		runChatCommand(context, "ef rename \"Cmd run\" Renamed");
		context.waitTicks(5);
		check(context.computeOnClient(client -> RecordingStore.find("Renamed") != null && RecordingStore.find("Cmd run") == null),
				"'/ef rename' did not rename");
		runChatCommand(context, "ef delete Renamed");
		context.waitTicks(5);
		check(context.computeOnClient(client -> RecordingStore.list().isEmpty()), "'/ef delete' did not delete");

		runChatCommand(context, "ef open recordings");
		context.waitForScreen(EasyFarmScreen.class);
		context.waitTicks(5);
		context.takeScreenshot("command-5-open-recordings");
		check(selectedTab(context) == EasyFarmScreen.TAB_RECORDINGS, "'/ef open recordings' should show the Recordings tab");
		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitFor(client -> client.gui.screen() == null);

		check(SERVER_MESSAGES.isEmpty(), "A subcommand was forwarded to the server: " + SERVER_MESSAGES);
	}

	private static boolean isButtonActive(ClientGameTestContext context, String label) {
		return context.computeOnClient(client -> {
			for (AbstractWidget widget : Screens.getWidgets(client.gui.screen())) {
				if (widget instanceof Button button && button.getMessage().getString().equals(label)) {
					return button.active;
				}
			}
			throw new AssertionError("No '" + label + "' button");
		});
	}

	private static void typeInNameBox(ClientGameTestContext context, String name) {
		int[] center = context.computeOnClient(client -> {
			for (AbstractWidget widget : Screens.getWidgets(client.gui.screen())) {
				if (widget instanceof EditBox box) {
					return new int[] {box.getX() + box.getWidth() / 2, box.getY() + box.getHeight() / 2};
				}
			}
			throw new AssertionError("The Record tab has no name box");
		});
		clickAtGuiPosition(context, center[0], center[1]);
		context.getInput().typeChars(name);
		context.waitTicks(2);
	}

	private static void clickButton(ClientGameTestContext context, String label) {
		int[] center = context.computeOnClient(client -> {
			for (AbstractWidget widget : Screens.getWidgets(client.gui.screen())) {
				if (widget instanceof Button button && button.visible && button.getMessage().getString().equals(label)) {
					check(button.active, "Button '" + label + "' is disabled");
					return new int[] {button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2};
				}
			}
			throw new AssertionError("No '" + label + "' button on " + client.gui.screen().getClass().getSimpleName());
		});
		clickAtGuiPosition(context, center[0], center[1]);
		context.waitTick();
	}

	private static void inventoryButtonOpensHud(ClientGameTestContext context, String label, Class<? extends Screen> inventoryClass) {
		context.getInput().pressKey(options -> options.keyInventory);
		context.waitForScreen(inventoryClass);
		context.waitTicks(5);
		context.takeScreenshot(label + "-1-inventory-with-button");

		clickHudButton(context);
		context.waitForScreen(EasyFarmScreen.class);
		context.waitTicks(5);
		context.takeScreenshot(label + "-2-hud-from-button");

		// Opened from the inventory, so closing returns to the inventory.
		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitForScreen(inventoryClass);
		context.waitTicks(5);
		context.takeScreenshot(label + "-3-back-in-inventory");

		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitFor(client -> client.gui.screen() == null);
	}

	/** Toggling the survival recipe book slides the panel; the button has to slide with it. */
	private static void buttonFollowsRecipeBook(ClientGameTestContext context) {
		context.getInput().pressKey(options -> options.keyInventory);
		context.waitForScreen(InventoryScreen.class);
		context.waitTicks(5);

		int[] before = buttonPosition(context);
		// The recipe book button sits at (leftPos + 104, height / 2 - 22) and is 20x18; click its middle.
		int[] recipeButton = context.computeOnClient(client -> {
			InventoryScreen screen = (InventoryScreen) client.gui.screen();
			return new int[] {screen.leftPos + 104 + 10, screen.height / 2 - 22 + 9};
		});
		clickAtGuiPosition(context, recipeButton[0], recipeButton[1]);
		context.waitTicks(5);
		context.takeScreenshot("survival-4-recipe-book-open");

		int[] after = buttonPosition(context);
		check(after[0] != before[0], "The HUD button did not follow the inventory panel when the recipe book opened: x stayed " + before[0]);

		clickHudButton(context);
		context.waitForScreen(EasyFarmScreen.class);
		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitForScreen(InventoryScreen.class);
		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitFor(client -> client.gui.screen() == null);
	}

	private static void runChatCommand(ClientGameTestContext context, String command) {
		// The command key opens chat with "/" already typed.
		context.getInput().pressKey(options -> options.keyCommand);
		context.waitForScreen(ChatScreen.class);
		context.getInput().typeChars(command);
		context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
	}

	/**
	 * The harness always builds key events with no modifier bits, so Ctrl+digit cannot be simulated through its input API
	 * (the real game gets the modifier from GLFW). Hand the screen a Ctrl+digit event directly instead.
	 */
	private static void switchTabWithKeyboard(ClientGameTestContext context, int digitKey) {
		context.runOnClient(client -> {
			boolean handled = client.gui.screen().keyPressed(new KeyEvent(digitKey, 0, InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER));
			check(handled, "The HUD ignored a Ctrl+digit key press");
		});
		context.waitTicks(5);
	}

	private static void clickTab(ClientGameTestContext context, int index) {
		int[] center = context.computeOnClient(client -> {
			for (GuiEventListener child : client.gui.screen().children()) {
				if (child instanceof TabNavigationBar bar) {
					AbstractWidget tab = (AbstractWidget) bar.children().get(index);
					return new int[] {tab.getX() + tab.getWidth() / 2, tab.getY() + tab.getHeight() / 2};
				}
			}
			throw new AssertionError("The HUD has no tab bar");
		});
		clickAtGuiPosition(context, center[0], center[1]);
		context.waitTicks(5);
	}

	private static int selectedTab(ClientGameTestContext context) {
		return context.computeOnClient(client -> ((EasyFarmScreen) client.gui.screen()).getSelectedTab());
	}

	private static Screen currentScreen(ClientGameTestContext context) {
		return context.computeOnClient(client -> client.gui.screen());
	}

	private static int[] buttonPosition(ClientGameTestContext context) {
		return context.computeOnClient(client -> {
			Button button = findHudButton(client.gui.screen());
			return new int[] {button.getX(), button.getY()};
		});
	}

	private static void clickHudButton(ClientGameTestContext context) {
		int[] center = context.computeOnClient(client -> {
			Button button = findHudButton(client.gui.screen());
			return new int[] {button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2};
		});
		clickAtGuiPosition(context, center[0], center[1]);
	}

	private static void clickAtGuiPosition(ClientGameTestContext context, int guiX, int guiY) {
		int scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
		context.getInput().setCursorPos((guiX + 0.5) * scale, (guiY + 0.5) * scale);
		context.waitTick();
		context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
	}

	private static Button findHudButton(Screen screen) {
		for (AbstractWidget widget : Screens.getWidgets(screen)) {
			if (widget instanceof Button button && button.getMessage().getString().equals("EF")) {
				return button;
			}
		}
		throw new AssertionError("No Easy Farm button on " + screen.getClass().getSimpleName());
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
