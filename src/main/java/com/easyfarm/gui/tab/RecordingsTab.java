package com.easyfarm.gui.tab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import com.easyfarm.gui.EasyFarmScreen;
import com.easyfarm.recording.Recording;
import com.easyfarm.recording.RecordingStore;

/** Browse and delete saved recordings. */
public final class RecordingsTab extends HudTab {
	private static final Logger LOGGER = LoggerFactory.getLogger("easyfarm");
	private static final int SPACING = 6;
	private static final int BUTTON_HEIGHT = 20;
	private static final int ROW_BUTTON_WIDTH = 110;

	/** Selected recording, kept across rebuilds (resize, tab switches) since tabs themselves are throwaway views. */
	private static @Nullable String selectedId;

	private final EasyFarmScreen screen;
	private final @Nullable RecordingList list;
	private final @Nullable Button deleteButton;
	private @Nullable String armedForDelete;

	public RecordingsTab(EasyFarmScreen screen) {
		super(Component.translatable("easyfarm.tab.recordings"));
		this.screen = screen;

		List<Recording> recordings = RecordingStore.list();
		if (recordings.isEmpty()) {
			this.list = null;
			this.deleteButton = null;
			this.addHeading(Component.translatable("easyfarm.recordings.heading"));
			this.addText(Component.translatable("easyfarm.recordings.empty"));
			this.addMutedText(Component.translatable("easyfarm.recordings.empty_hint"));
			return;
		}

		this.addHeading(Component.translatable("easyfarm.recordings.heading.count", recordings.size()));
		this.list = this.layout.addChild(
				new RecordingList(Minecraft.getInstance(), 100, 50, recordings, selectedId, this::onSelectionChanged),
				LayoutSettings::alignHorizontallyCenter);

		LinearLayout buttons = LinearLayout.horizontal().spacing(SPACING);
		this.deleteButton = buttons.addChild(Button.builder(Component.translatable("easyfarm.recordings.delete"), button -> this.delete())
				.width(ROW_BUTTON_WIDTH)
				.build());
		buttons.addChild(Button.builder(Component.translatable("easyfarm.recordings.open_folder"), button -> openFolder())
				.width(ROW_BUTTON_WIDTH)
				.tooltip(Tooltip.create(Component.translatable("easyfarm.recordings.open_folder.tooltip")))
				.build());
		this.layout.addChild(buttons, LayoutSettings::alignHorizontallyCenter);

		this.updateDeleteButton();
	}

	/** Makes the Recordings tab select this recording the next time it is built. */
	public static void focus(String recordingId) {
		selectedId = recordingId;
	}

	@Override
	public void doLayout(ScreenRectangle rectangle) {
		if (this.list == null) {
			super.doLayout(rectangle);
			return;
		}

		// The list takes whatever height the heading and button row leave over.
		int listHeight = Math.max(30, rectangle.height() - this.font.lineHeight - BUTTON_HEIGHT - 2 * SPACING);
		this.list.setSize(rectangle.width(), listHeight);
		this.layout.arrangeElements();
		FrameLayout.alignInRectangle(this.layout, rectangle, 0.5F, 0.0F);
		this.list.place(this.list.getX(), this.list.getY(), rectangle.width(), listHeight);
	}

	private void onSelectionChanged() {
		Recording selected = this.list == null ? null : this.list.selectedRecording();
		selectedId = selected == null ? null : selected.id();
		this.armedForDelete = null;
		this.updateDeleteButton();
	}

	private void updateDeleteButton() {
		if (this.deleteButton == null || this.list == null) {
			return;
		}
		this.deleteButton.active = this.list.selectedRecording() != null;
		this.deleteButton.setMessage(Component.translatable("easyfarm.recordings.delete"));
	}

	/** Deleting takes two clicks: the first arms the button, the second deletes. */
	private void delete() {
		Recording selected = this.list == null ? null : this.list.selectedRecording();
		if (selected == null || this.deleteButton == null) {
			return;
		}

		if (!selected.id().equals(this.armedForDelete)) {
			this.armedForDelete = selected.id();
			this.deleteButton.setMessage(Component.translatable("easyfarm.recordings.delete.confirm"));
			return;
		}

		try {
			RecordingStore.delete(selected);
		} catch (IOException e) {
			LOGGER.error("Could not delete recording {}", selected.id(), e);
		}
		selectedId = null;
		this.screen.refresh();
	}

	private static void openFolder() {
		Path dir = RecordingStore.directory();
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			LOGGER.error("Could not create {}", dir, e);
			return;
		}
		Util.getPlatform().openPath(dir);
	}
}
