package com.easyfarm.gui.tab;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.StringJoiner;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;

import com.easyfarm.gui.HudStyle;
import com.easyfarm.recording.Recording;

/** Scrollable list of saved recordings, styled to match the HUD panel rather than vanilla's dirt-style lists. */
final class RecordingList extends ObjectSelectionList<RecordingList.Entry> {
	private static final int ROW_HEIGHT = 24;
	private static final DateTimeFormatter DATE_FORMAT =
			DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault());

	private final Runnable onSelectionChanged;

	RecordingList(Minecraft minecraft, int width, int height, List<Recording> recordings, @Nullable String selectedId,
			Runnable onSelectionChanged) {
		super(minecraft, width, height, 0, ROW_HEIGHT);
		this.onSelectionChanged = onSelectionChanged;
		this.centerListVertically = false;

		for (Recording recording : recordings) {
			Entry entry = new Entry(recording);
			this.addEntry(entry);
			if (recording.id().equals(selectedId)) {
				super.setSelected(entry);
			}
		}
	}

	/** Lays the list out at its final position; entries only follow when told to, so the tab calls this after layout. */
	void place(int x, int y, int width, int height) {
		this.updateSizeAndPosition(width, height, x, y);
	}

	@Override
	public void setSelected(@Nullable Entry entry) {
		super.setSelected(entry);
		this.onSelectionChanged.run();
	}

	public @Nullable Recording selectedRecording() {
		Entry selected = this.getSelected();
		return selected == null ? null : selected.recording;
	}

	@Override
	public int getRowLeft() {
		return this.getX() + 2;
	}

	@Override
	public int getRowWidth() {
		return this.width - this.scrollbarWidth() - 6;
	}

	@Override
	protected int scrollBarX() {
		return this.getRight() - this.scrollbarWidth() - 1;
	}

	@Override
	protected void extractListBackground(GuiGraphicsExtractor graphics) {
		graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), HudStyle.LIST_BACKGROUND);
	}

	@Override
	protected void extractListSeparators(GuiGraphicsExtractor graphics) {
		graphics.outline(this.getX(), this.getY(), this.getWidth(), this.getHeight(), HudStyle.SEPARATOR);
	}

	@Override
	protected void extractSelection(GuiGraphicsExtractor graphics, Entry entry, int outlineColor) {
		int x0 = entry.getX();
		int y0 = entry.getY();
		int x1 = x0 + entry.getWidth();
		int y1 = y0 + entry.getHeight();
		graphics.fill(x0, y0, x1, y1, HudStyle.ROW_SELECTED);
		graphics.fill(x0, y0, x0 + 2, y1, HudStyle.ACCENT);
	}

	private static final int MAX_KEY_LABELS = 6;

	final class Entry extends ObjectSelectionList.Entry<Entry> {
		private final Recording recording;
		private final Component details;
		private final Component date;

		Entry(Recording recording) {
			this.recording = recording;

			StringJoiner keys = new StringJoiner(" ");
			List<String> labels = recording.inputLabels();
			for (int i = 0; i < labels.size() && i < MAX_KEY_LABELS; i++) {
				keys.add(labels.get(i));
			}
			if (labels.size() > MAX_KEY_LABELS) {
				keys.add("+" + (labels.size() - MAX_KEY_LABELS));
			}
			this.details = Component.translatable("easyfarm.recordings.details",
					Recording.formatDuration(recording.lengthTicks()), recording.pressCount(), keys.toString());
			this.date = Component.literal(DATE_FORMAT.format(recording.createdAt()));
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			if (hovered && RecordingList.this.getSelected() != this) {
				graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), HudStyle.ROW_HOVERED);
			}

			Font font = RecordingList.this.minecraft.font;
			int x = this.getContentX() + 4;
			int right = this.getContentRight() - 2;
			int dateWidth = font.width(this.date);

			graphics.text(font, this.recording.name(), x, this.getContentY() + 1, HudStyle.TEXT, false);
			graphics.text(font, this.date, right - dateWidth, this.getContentY() + 1, HudStyle.TEXT_MUTED, false);
			graphics.text(font, this.details, x, this.getContentY() + 1 + font.lineHeight + 2, HudStyle.TEXT_MUTED, false);
		}

		@Override
		public Component getNarration() {
			return Component.translatable("narrator.select", this.recording.name());
		}
	}
}
