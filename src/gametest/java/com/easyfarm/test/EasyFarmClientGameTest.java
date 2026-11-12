package com.easyfarm.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import com.easyfarm.skyblock.SkyblockLocation;
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
import com.easyfarm.gui.loop.LoopBuilderWidget;
import com.easyfarm.gui.loop.LoopEditorScreen;
import com.easyfarm.gui.tab.LoopList;
import com.easyfarm.loop.Loop;
import com.easyfarm.loop.LoopPlayer;
import com.easyfarm.loop.LoopSettings;
import com.easyfarm.loop.LoopStore;
import com.easyfarm.recording.PhysicalInput;
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

	/**
	 * Keys and buttons the recorder and the loop player see as physically held. The harness cannot change what GLFW
	 * reports, so the mod's {@link PhysicalInput} is pointed at these sets for the whole test.
	 */
	private static final Set<Integer> FAKE_KEYS = ConcurrentHashMap.newKeySet();
	private static final Set<Integer> FAKE_MOUSE = ConcurrentHashMap.newKeySet();

	@Override
	public void runTest(ClientGameTestContext context) {
		// Overlay (action bar) messages are the mod's own feedback; a rejected command shows up as a chat message.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				SERVER_MESSAGES.add(message.getString());
			}
		});

		PhysicalInput.override(new PhysicalInput.Source() {
			@Override
			public boolean key(int code) {
				return FAKE_KEYS.contains(code);
			}

			@Override
			public boolean mouse(int button) {
				return FAKE_MOUSE.contains(button);
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

			createTestRecordings(context);
			editorBuildsLoop(context);
			buffersAreRolledEveryTime(context);
			holdLeftClickToRun(context);
			keybindPlaysAndPauses(context);
			holdLastKeysThroughBuffer(context);
			oldFilesStillLoad(context);

			gardenDetection(context);
			SkyblockLocation.setOverride(true);

			world.getServer().runCommand("gamemode creative @a");
			context.waitFor(client -> client.gameMode != null && client.gameMode.getPlayerMode() == GameType.CREATIVE);
			inventoryButtonOpensHud(context, "creative", CreativeModeInventoryScreen.class);

			world.getServer().runCommand("gamemode survival @a");
			context.waitFor(client -> client.gameMode != null && client.gameMode.getPlayerMode() == GameType.SURVIVAL);
			inventoryButtonOpensHud(context, "survival", InventoryScreen.class);
			buttonFollowsRecipeBook(context);
			inventoryLoopButtons(context);
		}
	}

	/** Detection text rules, and the inventory buttons being hidden (but /ef still working) outside the Garden. */
	private static void gardenDetection(ClientGameTestContext context) {
		List<String> garden = List.of("§7⏣ §aThe Garden", "Plot: §bNone");
		check(SkyblockLocation.isGarden("§e§lSKYBLOCK", garden, List.of()), "sidebar 'The Garden' not detected");
		check(SkyblockLocation.isGarden("SKYBLOCK", List.of("⏣ Hub"), List.of("§bArea: §7Garden")), "tab 'Area: Garden' not detected");
		check(!SkyblockLocation.isGarden("SKYBLOCK", List.of("⏣ Hub"), List.of("Area: Hub")), "Hub detected as Garden");
		check(!SkyblockLocation.isGarden("Other Server", garden, List.of()), "non-SkyBlock sidebar accepted");
		check(!context.computeOnClient(client -> SkyblockLocation.isInGarden()), "singleplayer must not count as the Garden");

		context.getInput().pressKey(options -> options.keyInventory);
		context.waitForScreen(InventoryScreen.class);
		context.waitTicks(5);
		check(findVisibleHudButton(context) == null, "EF button must be hidden outside the Garden");
		context.getInput().pressKey(options -> options.keyInventory);
		context.waitFor(client -> client.gui.screen() == null);
	}

	private static Button findVisibleHudButton(ClientGameTestContext context) {
		return context.computeOnClient(client -> {
			Button button = findHudButton(client.gui.screen());
			return button != null && button.visible ? button : null;
		});
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

		// W, an unbound key (Q is drop, but Z is not bound to anything), the right mouse button and a camera turn.
		FAKE_KEYS.add(GLFW.GLFW_KEY_W);
		context.waitTicks(10);
		FAKE_KEYS.add(GLFW.GLFW_KEY_Z);
		FAKE_MOUSE.add(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		context.waitTicks(5);
		FAKE_MOUSE.remove(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		FAKE_KEYS.remove(GLFW.GLFW_KEY_Z);
		context.runOnClient(client -> client.player.setYRot(client.player.getYRot() + 30.0f));
		context.waitTicks(10);
		FAKE_KEYS.remove(GLFW.GLFW_KEY_W);
		context.waitTicks(5);

		context.getInput().pressKey(GLFW.GLFW_KEY_F8);
		context.waitFor(client -> !Recorder.isRecording());

		List<Recording> recordings = context.computeOnClient(client -> RecordingStore.list());
		check(recordings.size() == 1, "Expected 1 saved recording, found " + recordings.size());
		Recording recording = recordings.getFirst();
		check(recording.name().equals("Wheat farm"), "The recording should be called 'Wheat farm', but is '" + recording.name() + "'");
		check(recording.usesInput(Recording.Type.KEY, GLFW.GLFW_KEY_W), "The recording should contain W");
		check(recording.usesInput(Recording.Type.KEY, GLFW.GLFW_KEY_Z), "The recording should contain the unbound key Z");
		check(recording.usesInput(Recording.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT), "The recording should contain the right mouse button");
		check(!recording.usesInput(Recording.Type.KEY, GLFW.GLFW_KEY_F8), "The record key must not be recorded");
		check(recording.lengthTicks() >= 30, "The recording is too short: " + recording.lengthTicks() + " ticks");
		List<Recording.Event> inputs = recording.events().stream().filter(e -> e.type() != Recording.Type.LOOK).toList();
		long presses = inputs.stream().filter(Recording.Event::down).count();
		long releases = inputs.size() - presses;
		check(presses == releases, "Every press needs a release: " + presses + " presses, " + releases + " releases");
		double turned = recording.events().stream().filter(e -> e.type() == Recording.Type.LOOK).mapToDouble(Recording.Event::yaw).sum();
		check(Math.abs(turned - 30.0) < 0.5, "The camera turn should add up to 30 degrees, but is " + turned);
		check(recording.hasStartLook(), "The recording should remember the starting camera angle");
		check(recording.heldAtEnd().isEmpty(), "Nothing was held at the end, but heldAtEnd is " + recording.heldAtEnd());

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

	/** Two short recordings written straight to disk: "Forward" (W for 6 ticks) and "Left" (A for 4 ticks). */
	private static void createTestRecordings(ClientGameTestContext context) {
		clearRecordings(context);
		context.runOnClient(client -> {
			try {
				for (Loop loop : LoopStore.list()) {
					LoopStore.delete(loop);
				}
				// Saved oldest first; the palette lists newest first, so "Left" is row 0.
				RecordingStore.saveNew("Forward", Instant.parse("2026-01-01T00:00:00Z"), 6, Float.NaN, Float.NaN,
						List.of(Recording.Event.key(0, GLFW.GLFW_KEY_W, true), Recording.Event.key(6, GLFW.GLFW_KEY_W, false)),
						List.of(new Recording.Input(Recording.Type.KEY, GLFW.GLFW_KEY_W)));
				RecordingStore.saveNew("Left", Instant.parse("2026-01-01T00:01:00Z"), 4, Float.NaN, Float.NaN,
						List.of(Recording.Event.key(0, GLFW.GLFW_KEY_A, true), Recording.Event.key(4, GLFW.GLFW_KEY_A, false)),
						List.of(new Recording.Input(Recording.Type.KEY, GLFW.GLFW_KEY_A)));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	/** Click both recordings in, drag the second above the first, check validation, then save. */
	private static void editorBuildsLoop(ClientGameTestContext context) {
		runChatCommand(context, "ef");
		context.waitForScreen(EasyFarmScreen.class);
		switchTabWithKeyboard(context, GLFW.GLFW_KEY_3);
		context.takeScreenshot("loops-1-empty");
		clickButton(context, "New loop");
		context.waitForScreen(LoopEditorScreen.class);
		context.waitTicks(2);
		context.takeScreenshot("loops-2-editor-empty");

		List<String> ids = context.computeOnClient(client -> RecordingStore.list().stream().map(Recording::id).toList());
		String left = ids.get(0);
		String forward = ids.get(1);

		clickAtGuiPosition(context, builderPoint(context, b -> b.paletteRowCenter(0)));
		context.waitTicks(2);
		clickAtGuiPosition(context, builderPoint(context, b -> b.paletteRowCenter(1)));
		context.waitTicks(2);
		check(editorSequence(context).equals(List.of(left, forward)), "Clicking palette rows should append: " + editorSequence(context));

		// Drag block 2 (forward) into the gap above block 1.
		drag(context, builderPoint(context, b -> b.blockCenter(1)), builderPoint(context, b -> b.gapPoint(0)));
		check(editorSequence(context).equals(List.of(forward, left)), "Dragging should reorder: " + editorSequence(context));

		// Add a third block by dragging from the palette, then remove it with its ×.
		drag(context, builderPoint(context, b -> b.paletteRowCenter(0)), builderPoint(context, b -> b.gapPoint(2)));
		check(editorSequence(context).size() == 3, "Dragging from the palette should add a block: " + editorSequence(context));
		clickAtGuiPosition(context, builderPoint(context, b -> b.removeButtonCenter(2)));
		context.waitTicks(2);
		check(editorSequence(context).equals(List.of(forward, left)), "× should remove the block: " + editorSequence(context));

		// The Hold toggle flips only its own block, and flips back.
		clickAtGuiPosition(context, builderPoint(context, b -> b.holdToggleCenter(0)));
		context.waitTicks(2);
		check(editorHolds(context).equals(List.of(true, false)), "Clicking Hold on block 1 should turn it on: " + editorHolds(context));
		context.takeScreenshot("loops-2b-editor-hold-on");
		clickAtGuiPosition(context, builderPoint(context, b -> b.holdToggleCenter(0)));
		context.waitTicks(2);
		check(editorHolds(context).equals(List.of(false, false)), "Clicking Hold again should turn it off: " + editorHolds(context));

		// Equal bounds would make the buffer fixed, which is never allowed.
		setBufferRange(context, "0.5", "0.5");
		clickButton(context, "Save loop");
		context.waitTicks(2);
		check(currentScreen(context) instanceof LoopEditorScreen, "A loop with min == max must not save");
		context.takeScreenshot("loops-3-editor-range-error");

		setBufferRange(context, "0.05", "0.5");
		context.waitTicks(2);
		context.takeScreenshot("loops-4-editor-filled");
		clickButton(context, "Save loop");
		context.waitForScreen(EasyFarmScreen.class);
		context.waitTicks(2);
		context.takeScreenshot("loops-5-saved");

		List<Loop> loops = context.computeOnClient(client -> LoopStore.list());
		check(loops.size() == 1, "Expected 1 saved loop, found " + loops.size());
		Loop loop = loops.getFirst();
		check(loop.recordingIds().equals(List.of(forward, left)), "Saved order is wrong: " + loop.recordingIds());
		check(loop.bufferMinTicks() == 1 && loop.bufferMaxTicks() == 10,
				"Buffer should be 1..10 ticks, was " + loop.bufferMinTicks() + ".." + loop.bufferMaxTicks());
	}

	/**
	 * Run the loop for many cycles: every buffer (between recordings and at the end) must get its own roll inside the
	 * range, and the rolls must actually vary. Then stop with F9 and check no key is left held.
	 */
	private static void buffersAreRolledEveryTime(ClientGameTestContext context) {
		context.runOnClient(client -> LoopSettings.setPlayMode(LoopSettings.PlayMode.KEYBIND));
		context.runOnClient(client -> ((EasyFarmScreen) client.gui.screen()).refresh());
		context.waitTicks(2);
		clickButton(context, "Start");
		context.waitFor(client -> client.gui.screen() == null);
		check(context.computeOnClient(client -> LoopPlayer.isRunning()), "Clicking Start did not start the loop");

		List<Integer> rolls = new ArrayList<>();
		int endBuffers = 0;
		int middleBuffers = 0;
		boolean sawForward = false;
		boolean sawLeft = false;
		int lastRollCount = 0;
		for (int tick = 0; tick < 600 && rolls.size() < 24; tick++) {
			context.waitTick();
			Object[] state = context.computeOnClient(client -> new Object[] {
					LoopPlayer.buffersRolled(), LoopPlayer.currentBufferTicks(), LoopPlayer.describePosition().getString(),
					client.options.keyUp.isDown(), client.options.keyLeft.isDown()});
			sawForward |= (boolean) state[3];
			sawLeft |= (boolean) state[4];
			int rollCount = (int) state[0];
			if (rollCount != lastRollCount) {
				check(rollCount == lastRollCount + 1, "Buffers were skipped or double-rolled: " + lastRollCount + " -> " + rollCount);
				lastRollCount = rollCount;
				rolls.add((int) state[1]);
				if (((String) state[2]).startsWith("end buffer")) {
					endBuffers++;
				} else {
					middleBuffers++;
				}
			}
		}
		context.takeScreenshot("loops-6-playing");

		check(rolls.size() >= 24, "Too few buffers in 600 ticks: " + rolls.size());
		check(rolls.stream().allMatch(ticks -> ticks >= 1 && ticks <= 10), "A buffer was rolled outside 1..10 ticks: " + rolls);
		check(rolls.stream().distinct().count() >= 4, "Buffers are not random enough: " + rolls);
		check(endBuffers >= 10 && middleBuffers >= 10, "Expected both middle and end buffers: " + middleBuffers + " middle, " + endBuffers + " end");
		check(sawForward && sawLeft, "Playback never pressed the recorded keys (forward " + sawForward + ", left " + sawLeft + ")");

		context.runOnClient(client -> LoopPlayer.stop());
		context.waitTick();
		boolean anyHeld = context.computeOnClient(client -> client.options.keyUp.isDown() || client.options.keyLeft.isDown());
		check(!anyHeld, "Stopping the loop left a movement key held");
	}

	/** In hold mode the loop only advances while left click is held, and resumes in place after a release. */
	private static void holdLeftClickToRun(ClientGameTestContext context) {
		context.runOnClient(client -> LoopSettings.setPlayMode(LoopSettings.PlayMode.HOLD));
		context.getInput().pressKey(GLFW.GLFW_KEY_F9);
		context.waitFor(client -> LoopPlayer.isRunning());
		context.waitTicks(10);
		check(context.computeOnClient(client -> LoopPlayer.isWaiting() && LoopPlayer.buffersRolled() == 0
				&& !client.options.keyUp.isDown()), "Without left click the loop must stay paused at the start");

		FAKE_MOUSE.add(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(30);
		int rolledWhileHeld = context.computeOnClient(client -> LoopPlayer.buffersRolled());
		check(rolledWhileHeld > 0, "Holding left click did not run the loop");

		FAKE_MOUSE.remove(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
		String pausedAt = context.computeOnClient(client -> LoopPlayer.buffersRolled() + "/" + LoopPlayer.describePosition().getString());
		check(context.computeOnClient(client -> LoopPlayer.isWaiting()), "Releasing left click should pause the loop");
		check(!context.computeOnClient(client -> client.options.keyUp.isDown() || client.options.keyLeft.isDown()),
				"Pausing must let go of the movement keys");
		context.waitTicks(20);
		String stillAt = context.computeOnClient(client -> LoopPlayer.buffersRolled() + "/" + LoopPlayer.describePosition().getString());
		check(pausedAt.equals(stillAt), "The loop moved on while paused: " + pausedAt + " -> " + stillAt);

		FAKE_MOUSE.add(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(30);
		FAKE_MOUSE.remove(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		check(context.computeOnClient(client -> LoopPlayer.buffersRolled()) > rolledWhileHeld, "The loop did not resume");

		// In hold mode the loop key stops the loop (there is nothing to toggle).
		context.getInput().pressKey(GLFW.GLFW_KEY_F9);
		context.waitFor(client -> !LoopPlayer.isRunning());
	}

	/**
	 * Keybind mode: a started loop plays without left click, the loop key pauses and resumes it in place, and while it
	 * plays the game's "pause on lost focus" is switched off (that is what lets it keep going when tabbed out) and put back
	 * afterwards.
	 */
	private static void keybindPlaysAndPauses(ClientGameTestContext context) {
		context.runOnClient(client -> {
			LoopSettings.setPlayMode(LoopSettings.PlayMode.KEYBIND);
			LoopSettings.setBackgroundPlay(true);
			client.options.pauseOnLostFocus = true;
		});
		context.getInput().pressKey(GLFW.GLFW_KEY_F9);
		context.waitFor(client -> LoopPlayer.isRunning());
		context.waitTicks(30);
		check(context.computeOnClient(client -> LoopPlayer.isPlaying() && LoopPlayer.buffersRolled() > 0),
				"A keybind-mode loop should play without holding left click");
		check(context.computeOnClient(client -> LoopPlayer.isBackgroundPlayActive() && !client.options.pauseOnLostFocus),
				"While playing, 'pause on lost focus' should be off so the loop survives tabbing out");

		context.getInput().pressKey(GLFW.GLFW_KEY_F9);
		context.waitTicks(3);
		check(context.computeOnClient(client -> LoopPlayer.isRunning() && LoopPlayer.isWaiting()), "The loop key should pause the loop");
		check(context.computeOnClient(client -> client.options.pauseOnLostFocus && !LoopPlayer.isBackgroundPlayActive()),
				"Pausing should give 'pause on lost focus' back");
		String pausedAt = context.computeOnClient(client -> LoopPlayer.buffersRolled() + "/" + LoopPlayer.describePosition().getString());
		context.waitTicks(20);
		check(pausedAt.equals(context.computeOnClient(client -> LoopPlayer.buffersRolled() + "/" + LoopPlayer.describePosition().getString())),
				"The loop moved on while paused");
		check(!context.computeOnClient(client -> client.options.keyUp.isDown() || client.options.keyLeft.isDown()),
				"Pausing must let go of the movement keys");

		context.getInput().pressKey(GLFW.GLFW_KEY_F9);
		context.waitTicks(30);
		check(context.computeOnClient(client -> LoopPlayer.isPlaying() && LoopPlayer.buffersRolled() > 0),
				"The loop key should resume the loop");

		// With the setting off, the game's option is left alone.
		context.runOnClient(client -> LoopSettings.setBackgroundPlay(false));
		context.waitTicks(3);
		check(context.computeOnClient(client -> client.options.pauseOnLostFocus), "Background play off must leave 'pause on lost focus' on");

		context.runOnClient(client -> {
			LoopPlayer.stop();
			LoopSettings.setBackgroundPlay(true);
		});
		context.waitTicks(2);
		check(context.computeOnClient(client -> client.options.pauseOnLostFocus), "'pause on lost focus' was not restored");
		context.runOnClient(client -> client.options.pauseOnLostFocus = false);
	}

	/**
	 * A block with "Hold" on keeps its recording's last keys pressed through the buffer after it; a block without it
	 * releases everything. Built through the editor's data model and saved like a real loop.
	 */
	private static void holdLastKeysThroughBuffer(ClientGameTestContext context) {
		List<String> ids = context.computeOnClient(client -> RecordingStore.list().stream().map(Recording::id).toList());
		String left = ids.get(0);
		String forward = ids.get(1);
		Loop saved = context.computeOnClient(client -> {
			try {
				Loop created = LoopStore.saveNew("Hold test",
						List.of(new Loop.Step(forward, true), new Loop.Step(left, false)), 12, 16);
				return LoopStore.get(created.id());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		check(saved.steps().get(0).holdLastKeys() && !saved.steps().get(1).holdLastKeys(), "The hold flags did not survive saving: " + saved.steps());

		context.runOnClient(client -> {
			LoopSettings.setPlayMode(LoopSettings.PlayMode.KEYBIND);
			LoopPlayer.start(saved);
		});

		int heldMiddleTicks = 0;
		int releasedEndTicks = 0;
		boolean sawMiddle = false;
		for (int tick = 0; tick < 400 && (heldMiddleTicks < 30 || releasedEndTicks < 10); tick++) {
			context.waitTick();
			Object[] state = context.computeOnClient(client -> new Object[] {
					LoopPlayer.describePosition().getString(), client.options.keyUp.isDown(), client.options.keyLeft.isDown()});
			String position = (String) state[0];
			if (position.startsWith("buffer ")) {
				sawMiddle = true;
				heldMiddleTicks++;
				check((boolean) state[1], "Forward must stay held through the buffer after a Hold block (" + position + ")");
				check(!(boolean) state[2], "Only the last keys of that recording are held, not left");
			} else if (position.startsWith("end buffer ")) {
				releasedEndTicks++;
				check(!(boolean) state[1] && !(boolean) state[2], "A block without Hold must release everything in its buffer (" + position + ")");
			}
		}
		check(sawMiddle && heldMiddleTicks >= 30 && releasedEndTicks >= 10,
				"Did not observe both buffers (held " + heldMiddleTicks + ", released " + releasedEndTicks + ")");

		context.runOnClient(client -> LoopPlayer.stop());
		context.waitTick();
		check(!context.computeOnClient(client -> client.options.keyUp.isDown()), "Stopping left the held key pressed");
		context.runOnClient(client -> {
			try {
				LoopStore.delete(saved);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			LoopSettings.setPlayMode(LoopSettings.PlayMode.HOLD);
		});
	}

	/** Version 1 recording and loop files (written by the first release) must still load. */
	private static void oldFilesStillLoad(ClientGameTestContext context) {
		context.runOnClient(client -> {
			try {
				java.nio.file.Path recordings = RecordingStore.directory();
				java.nio.file.Files.createDirectories(recordings);
				java.nio.file.Files.writeString(recordings.resolve("legacy-1.json"), """
						{ "version": 1, "name": "Legacy", "createdAt": "2025-01-01T00:00:00Z", "lengthTicks": 8,
						  "events": [ { "tick": 0, "key": "forward", "down": true }, { "tick": 8, "key": "forward", "down": false } ] }
						""");
				java.nio.file.Path loops = LoopStore.directory();
				java.nio.file.Files.createDirectories(loops);
				java.nio.file.Files.writeString(loops.resolve("legacy-loop.json"), """
						{ "version": 1, "name": "Legacy loop", "createdAt": "2025-01-01T00:00:00Z",
						  "buffer": { "minTicks": 5, "maxTicks": 9 }, "recordings": [ "legacy-1" ] }
						""");
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		Recording legacy = context.computeOnClient(client -> RecordingStore.list().stream().filter(r -> r.id().equals("legacy-1")).findFirst().orElse(null));
		check(legacy != null, "A version 1 recording did not load");
		check(legacy.usesInput(Recording.Type.KEY, GLFW.GLFW_KEY_W), "The legacy 'forward' key should map to W");
		check(legacy.heldAtEnd().contains(new Recording.Input(Recording.Type.KEY, GLFW.GLFW_KEY_W)), "The legacy final release should count as held at the end");
		Loop legacyLoop = context.computeOnClient(client -> LoopStore.get("legacy-loop"));
		check(legacyLoop != null && legacyLoop.recordingIds().equals(List.of("legacy-1")) && !legacyLoop.steps().get(0).holdLastKeys(),
				"A version 1 loop did not load");
		context.runOnClient(client -> {
			try {
				RecordingStore.delete(legacy);
				LoopStore.delete(legacyLoop);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	/**
	 * Tick "show in inventory" on the loop, then from the survival inventory: its button sits left of the panel, plays it,
	 * stops it, and the Reset button and {@code /ef loop reset} both send it back to cycle 1.
	 */
	private static void inventoryLoopButtons(ClientGameTestContext context) {
		context.runOnClient(client -> LoopSettings.setPlayMode(LoopSettings.PlayMode.KEYBIND));

		runChatCommand(context, "ef open loops");
		context.waitForScreen(EasyFarmScreen.class);
		context.waitTicks(5);
		int[] checkbox = context.computeOnClient(client -> {
			for (AbstractWidget widget : Screens.getWidgets(client.gui.screen())) {
				if (widget instanceof LoopList list) {
					return list.checkboxCenter(0);
				}
			}
			throw new AssertionError("The Loops tab has no list");
		});
		clickAtGuiPosition(context, checkbox);
		context.waitTicks(2);
		check(context.computeOnClient(client -> LoopStore.list().getFirst().showInInventory()), "The checkmark did not save 'show in inventory'");
		context.takeScreenshot("loops-7-show-in-inventory-checked");
		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitFor(client -> client.gui.screen() == null);

		context.getInput().pressKey(options -> options.keyInventory);
		context.waitForScreen(InventoryScreen.class);
		context.waitTicks(5);
		context.takeScreenshot("survival-5-loop-buttons-book-open");
		// The previous test left the recipe book open, and this window is too narrow for the buttons beside it, so they
		// fall back to the row above the panel. They must never sit on the book, which would swallow their clicks.
		boolean clearOfBook = context.computeOnClient(client -> {
			InventoryScreen screen = (InventoryScreen) client.gui.screen();
			// The book is drawn at (width - 147) / 2 - 86 and its tabs stick out 30 further left.
			int bookTabsLeft = (screen.width - 147) / 2 - 86 - 30;
			for (AbstractWidget widget : Screens.getWidgets(screen)) {
				String label = widget.getMessage().getString();
				if (widget instanceof Button && (label.equals("EF") || label.equals("Reset") || label.endsWith("Loop 1"))
						&& widget.getBottom() > screen.topPos && widget.getRight() > bookTabsLeft) {
					return false;
				}
			}
			return true;
		});
		check(clearOfBook, "With the recipe book open, an Easy Farm button overlaps the book");

		// Close the recipe book: now everything belongs in a column left of the panel.
		int[] recipeButton = context.computeOnClient(client -> {
			InventoryScreen screen = (InventoryScreen) client.gui.screen();
			return new int[] {screen.leftPos + 104 + 10, screen.height / 2 - 22 + 9};
		});
		clickAtGuiPosition(context, recipeButton);
		context.waitTicks(5);
		context.takeScreenshot("survival-6-loop-buttons-left");
		boolean allLeft = context.computeOnClient(client -> {
			InventoryScreen screen = (InventoryScreen) client.gui.screen();
			for (AbstractWidget widget : Screens.getWidgets(screen)) {
				String label = widget.getMessage().getString();
				if (widget instanceof Button && (label.equals("EF") || label.equals("Reset") || label.endsWith("Loop 1"))
						&& widget.getRight() > screen.leftPos) {
					return false;
				}
			}
			return true;
		});
		check(allLeft, "An Easy Farm button is not on the left side of the inventory");

		clickButton(context, "Loop 1");
		context.waitTicks(2);
		check(context.computeOnClient(client -> LoopPlayer.isRunning() && "Loop 1".equals(LoopPlayer.currentLoop().name())),
				"The inventory loop button did not select the loop");
		context.takeScreenshot("survival-5-inventory-loop-playing");
		clickButton(context, "▶ Loop 1");
		context.waitTicks(2);
		check(!context.computeOnClient(client -> LoopPlayer.isRunning()), "Clicking the playing loop's button should stop it");
		clickButton(context, "Loop 1");
		context.waitTicks(2);

		// Let it run into a later cycle, then reset with the inventory button.
		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitFor(client -> client.gui.screen() == null);
		context.waitFor(client -> LoopPlayer.cycle() >= 2);
		// While the loop plays every keystroke except the loop key is discarded: the inventory key does nothing.
		context.getInput().pressKey(options -> options.keyInventory);
		context.waitTicks(5);
		check(currentScreen(context) == null, "The inventory key must be discarded while a loop plays");
		// Pausing with the loop key gives the keyboard back.
		context.getInput().pressKey(GLFW.GLFW_KEY_F9);
		context.waitTicks(3);
		context.getInput().pressKey(options -> options.keyInventory);
		context.waitForScreen(InventoryScreen.class);
		context.waitTicks(2);
		check(context.computeOnClient(client -> LoopPlayer.cycle() >= 2), "The loop should be past cycle 1 before resetting");
		clickButton(context, "Reset");
		context.waitTicks(2);
		check(context.computeOnClient(client -> LoopPlayer.cycle() == 1 && LoopPlayer.describePosition().getString().startsWith("Forward (1/")),
				"Reset did not reset the loop to its first recording");

		// And again with the command.
		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitFor(client -> client.gui.screen() == null);
		context.getInput().pressKey(GLFW.GLFW_KEY_F9);
		context.waitFor(client -> LoopPlayer.cycle() >= 2);
		context.getInput().pressKey(GLFW.GLFW_KEY_F9);
		context.waitTicks(3);
		SERVER_MESSAGES.clear();
		runChatCommand(context, "ef loop reset");
		context.waitFor(client -> client.gui.screen() == null);
		check(context.computeOnClient(client -> LoopPlayer.cycle() == 1), "'/ef loop reset' did not reset the loop");
		check(SERVER_MESSAGES.isEmpty(), "'/ef loop reset' was forwarded to the server: " + SERVER_MESSAGES);

		context.runOnClient(client -> LoopPlayer.stop());
		context.waitFor(client -> !LoopPlayer.isRunning());
		context.runOnClient(client -> LoopSettings.setPlayMode(LoopSettings.PlayMode.HOLD));
	}

	private static LoopBuilderWidget builder(Screen screen) {
		for (AbstractWidget widget : Screens.getWidgets(screen)) {
			if (widget instanceof LoopBuilderWidget builder) {
				return builder;
			}
		}
		throw new AssertionError("No loop builder on " + screen.getClass().getSimpleName());
	}

	private static int[] builderPoint(ClientGameTestContext context, java.util.function.Function<LoopBuilderWidget, int[]> point) {
		return context.computeOnClient(client -> point.apply(builder(client.gui.screen())));
	}

	@SuppressWarnings("unchecked")
	private static List<String> editorSequence(ClientGameTestContext context) {
		return context.computeOnClient(client -> {
			try {
				java.lang.reflect.Field field = LoopEditorScreen.class.getDeclaredField("sequence");
				field.setAccessible(true);
				return ((List<Loop.Step>) field.get(client.gui.screen())).stream().map(Loop.Step::recordingId).toList();
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	@SuppressWarnings("unchecked")
	private static List<Boolean> editorHolds(ClientGameTestContext context) {
		return context.computeOnClient(client -> {
			try {
				java.lang.reflect.Field field = LoopEditorScreen.class.getDeclaredField("sequence");
				field.setAccessible(true);
				return ((List<Loop.Step>) field.get(client.gui.screen())).stream().map(Loop.Step::holdLastKeys).toList();
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	/** The editor's two seconds boxes are the 2nd and 3rd edit boxes (after the name). */
	private static void setBufferRange(ClientGameTestContext context, String min, String max) {
		context.runOnClient(client -> {
			List<EditBox> boxes = new ArrayList<>();
			for (AbstractWidget widget : Screens.getWidgets(client.gui.screen())) {
				if (widget instanceof EditBox box) {
					boxes.add(box);
				}
			}
			boxes.get(1).setValue(min);
			boxes.get(2).setValue(max);
		});
	}

	private static void drag(ClientGameTestContext context, int[] from, int[] to) {
		int scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
		context.getInput().setCursorPos((from[0] + 0.5) * scale, (from[1] + 0.5) * scale);
		context.waitTick();
		context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTick();
		// Move in steps so the widget sees a real drag, then let go over the target.
		for (int step = 1; step <= 5; step++) {
			double x = from[0] + (to[0] - from[0]) * step / 5.0;
			double y = from[1] + (to[1] - from[1]) * step / 5.0;
			context.getInput().setCursorPos((x + 0.5) * scale, (y + 0.5) * scale);
			context.waitTick();
		}
		context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	private static void clickAtGuiPosition(ClientGameTestContext context, int[] point) {
		clickAtGuiPosition(context, point[0], point[1]);
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
		FAKE_KEYS.add(GLFW.GLFW_KEY_W);
		context.waitTicks(10);
		FAKE_KEYS.remove(GLFW.GLFW_KEY_W);
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
