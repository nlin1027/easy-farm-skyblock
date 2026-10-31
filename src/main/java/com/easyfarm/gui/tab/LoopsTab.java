package com.easyfarm.gui.tab;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

import com.easyfarm.gui.EasyFarmScreen;
import com.easyfarm.gui.loop.LoopEditorScreen;
import com.easyfarm.loop.Loop;
import com.easyfarm.loop.LoopPlayer;
import com.easyfarm.loop.LoopSettings;
import com.easyfarm.loop.LoopStore;
import com.easyfarm.recording.Recording;
import com.easyfarm.recording.RecordingStore;

/** Create, edit, delete, start and stop loops. */
public final class LoopsTab extends HudTab {
	private static final Logger LOGGER = LoggerFactory.getLogger("easyfarm");
	private static final int SPACING = 6;
	private static final int BUTTON_HEIGHT = 20;
	private static final int ROW_BUTTON_WIDTH = 70;

	/** Selected loop, kept across rebuilds since tabs are throwaway views. */
	private static @Nullable String selectedId;

	private final EasyFarmScreen screen;
	private final @Nullable LoopList list;
	private @Nullable Button editButton;
	private @Nullable Button deleteButton;
	private @Nullable Button startButton;
	/** Height of everything except the list, so the list can take the rest. */
	private int fixedHeight;
	private @Nullable String armedForDelete;

	public LoopsTab(EasyFarmScreen screen) {
		super(Component.translatable("easyfarm.tab.loops"));
		this.screen = screen;

		List<Loop> loops = LoopStore.list();
		List<Recording> recordings = RecordingStore.list();
		Map<String, Recording> recordingsById = new HashMap<>();
		for (Recording recording : recordings) {
			recordingsById.put(recording.id(), recording);
		}

		Loop playing = LoopPlayer.currentLoop();
		if (playing != null) {
			this.addLine(Component.translatable("easyfarm.loops.heading.playing", playing.name()).withStyle(ChatFormatting.BOLD));
			this.addLine(Component.translatable(
					LoopPlayer.isWaitingForAttack() ? "easyfarm.loops.position.waiting" : "easyfarm.loops.position",
					LoopPlayer.cycle(), LoopPlayer.describePosition()).withStyle(ChatFormatting.GRAY));
		} else {
			this.addLine((loops.isEmpty()
					? Component.translatable("easyfarm.loops.heading")
					: Component.translatable("easyfarm.loops.heading.count", loops.size())).withStyle(ChatFormatting.BOLD));
			Component lastResult = LoopPlayer.lastResult();
			if (lastResult != null) {
				this.addLine(lastResult.copy().withStyle(ChatFormatting.GRAY));
			}
		}

		if (loops.isEmpty()) {
			this.list = null;
			this.addText(Component.translatable("easyfarm.loops.empty"));
			this.addMutedText(Component.translatable("easyfarm.loops.empty_hint"));
			this.addHoldToRunSetting();
			Button create = this.addButton(Component.translatable("easyfarm.loops.new_loop"), button -> this.openEditor(null));
			if (recordings.isEmpty()) {
				create.active = false;
				create.setTooltip(Tooltip.create(Component.translatable("easyfarm.loops.needs_recordings")));
			}
			if (playing != null) {
				this.addButton(Component.translatable("easyfarm.loops.stop"), button -> this.stop());
			}
			return;
		}

		this.list = this.layout.addChild(new LoopList(Minecraft.getInstance(), loops, recordingsById, selectedId, this::onSelectionChanged),
				LayoutSettings::alignHorizontallyCenter);

		this.addHoldToRunSetting();

		LinearLayout buttons = LinearLayout.horizontal().spacing(4);
		Button create = buttons.addChild(this.rowButton("easyfarm.loops.new", button -> this.openEditor(null)));
		create.active = !recordings.isEmpty();
		this.editButton = buttons.addChild(this.rowButton("easyfarm.loops.edit", button -> {
			Loop selected = this.list.selectedLoop();
			if (selected != null) {
				this.openEditor(selected);
			}
		}));
		this.deleteButton = buttons.addChild(this.rowButton("easyfarm.loops.delete", button -> this.delete()));
		this.startButton = buttons.addChild(playing != null
				? this.rowButton("easyfarm.loops.stop", button -> this.stop())
				: this.rowButton("easyfarm.loops.start", button -> this.start()));
		this.layout.addChild(buttons, LayoutSettings::alignHorizontallyCenter);
		this.fixedHeight += BUTTON_HEIGHT + SPACING;

		this.updateButtons();
	}

	/** Makes the Loops tab select this loop the next time it is built. */
	public static void focus(String loopId) {
		selectedId = loopId;
	}

	private void addLine(Component text) {
		this.layout.addChild(new StringWidget(text, this.font), LayoutSettings::alignHorizontallyCenter);
		this.fixedHeight += this.font.lineHeight + SPACING;
	}

	/** "Only run while left click is held": when off, a started loop plays continuously until stopped. */
	private void addHoldToRunSetting() {
		Checkbox checkbox = Checkbox.builder(Component.translatable("easyfarm.loops.setting.hold_attack"), this.font)
				.selected(LoopSettings.holdAttackToRun())
				.tooltip(Tooltip.create(Component.translatable("easyfarm.loops.setting.hold_attack.tooltip")))
				.onValueChange((box, value) -> LoopSettings.setHoldAttackToRun(value))
				.build();
		this.layout.addChild(checkbox, LayoutSettings::alignHorizontallyCenter);
		this.fixedHeight += checkbox.getHeight() + SPACING;
	}

	private Button rowButton(String key, Button.OnPress onPress) {
		return Button.builder(Component.translatable(key), onPress).width(ROW_BUTTON_WIDTH).build();
	}

	@Override
	public void doLayout(ScreenRectangle rectangle) {
		if (this.list == null) {
			super.doLayout(rectangle);
			return;
		}

		int listHeight = Math.max(30, rectangle.height() - this.fixedHeight);
		this.list.setSize(rectangle.width(), listHeight);
		this.layout.arrangeElements();
		FrameLayout.alignInRectangle(this.layout, rectangle, 0.5F, 0.0F);
		this.list.place(this.list.getX(), this.list.getY(), rectangle.width(), listHeight);
	}

	private void onSelectionChanged() {
		Loop selected = this.list == null ? null : this.list.selectedLoop();
		selectedId = selected == null ? null : selected.id();
		this.armedForDelete = null;
		this.updateButtons();
	}

	private void updateButtons() {
		boolean hasSelection = this.list != null && this.list.selectedLoop() != null;
		if (this.editButton != null) {
			this.editButton.active = hasSelection;
		}
		if (this.deleteButton != null) {
			this.deleteButton.active = hasSelection;
			this.deleteButton.setMessage(Component.translatable("easyfarm.loops.delete"));
		}
		if (this.startButton != null && !LoopPlayer.isRunning()) {
			this.startButton.active = hasSelection;
		}
	}

	private void openEditor(@Nullable Loop loop) {
		Minecraft.getInstance().gui.setScreen(new LoopEditorScreen(this.screen, loop));
	}

	private void start() {
		Loop selected = this.list == null ? null : this.list.selectedLoop();
		if (selected == null) {
			return;
		}
		if (LoopPlayer.start(selected)) {
			// Close every screen so playback (which pauses while a menu is open) runs straight away.
			Minecraft.getInstance().gui.setScreen(null);
		} else {
			this.screen.refresh();
		}
	}

	private void stop() {
		LoopPlayer.stop();
		this.screen.refresh();
	}

	/** Deleting takes two clicks: the first arms the button, the second deletes. */
	private void delete() {
		Loop selected = this.list == null ? null : this.list.selectedLoop();
		if (selected == null || this.deleteButton == null) {
			return;
		}

		if (!selected.id().equals(this.armedForDelete)) {
			this.armedForDelete = selected.id();
			this.deleteButton.setMessage(Component.translatable("easyfarm.loops.delete.confirm"));
			return;
		}

		Loop playing = LoopPlayer.currentLoop();
		if (playing != null && playing.id().equals(selected.id())) {
			LoopPlayer.stop();
		}
		try {
			LoopStore.delete(selected);
		} catch (IOException e) {
			LOGGER.error("Could not delete loop {}", selected.id(), e);
		}
		selectedId = null;
		this.screen.refresh();
	}
}
