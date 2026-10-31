package com.easyfarm.gui.tab;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;

import com.easyfarm.gui.HudStyle;
import com.easyfarm.loop.Loop;
import com.easyfarm.loop.LoopPlayer;
import com.easyfarm.recording.Recording;

/** Scrollable list of saved loops, styled like the HUD panel. */
final class LoopList extends ObjectSelectionList<LoopList.Entry> {
	private static final int ROW_HEIGHT = 24;
	private static final int MISSING_TEXT = 0xFFFF7070;

	private final Runnable onSelectionChanged;

	LoopList(Minecraft minecraft, List<Loop> loops, Map<String, Recording> recordings, @Nullable String selectedId,
			Runnable onSelectionChanged) {
		super(minecraft, 100, 50, 0, ROW_HEIGHT);
		this.onSelectionChanged = onSelectionChanged;
		this.centerListVertically = false;

		for (Loop loop : loops) {
			Entry entry = new Entry(loop, recordings);
			this.addEntry(entry);
			if (loop.id().equals(selectedId)) {
				super.setSelected(entry);
			}
		}
	}

	void place(int x, int y, int width, int height) {
		this.updateSizeAndPosition(width, height, x, y);
	}

	@Override
	public void setSelected(@Nullable Entry entry) {
		super.setSelected(entry);
		this.onSelectionChanged.run();
	}

	public @Nullable Loop selectedLoop() {
		Entry selected = this.getSelected();
		return selected == null ? null : selected.loop;
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

	final class Entry extends ObjectSelectionList.Entry<Entry> {
		private final Loop loop;
		private final Component details;
		private final boolean missing;

		Entry(Loop loop, Map<String, Recording> recordings) {
			this.loop = loop;

			int missingCount = 0;
			int totalTicks = 0;
			for (String id : loop.recordingIds()) {
				Recording recording = recordings.get(id);
				if (recording == null) {
					missingCount++;
				} else {
					totalTicks += recording.lengthTicks();
				}
			}
			this.missing = missingCount > 0;

			if (this.missing) {
				this.details = Component.translatable("easyfarm.loops.details.missing", missingCount);
			} else {
				// Average cycle: every recording plus one average buffer after each.
				int averageBuffer = (loop.bufferMinTicks() + loop.bufferMaxTicks()) / 2;
				int cycleTicks = totalTicks + averageBuffer * loop.recordingIds().size();
				this.details = Component.translatable("easyfarm.loops.details",
						loop.recordingIds().size(), loop.bufferLabel(), Recording.formatDuration(cycleTicks));
			}
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			if (hovered && LoopList.this.getSelected() != this) {
				graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), HudStyle.ROW_HOVERED);
			}

			Font font = LoopList.this.minecraft.font;
			int x = this.getContentX() + 4;
			int right = this.getContentRight() - 2;

			Loop playing = LoopPlayer.currentLoop();
			if (playing != null && playing.id().equals(this.loop.id())) {
				Component badge = Component.translatable("easyfarm.loops.badge.playing");
				graphics.text(font, badge, right - font.width(badge), this.getContentY() + 1, HudStyle.ACCENT, false);
			}

			graphics.text(font, this.loop.name(), x, this.getContentY() + 1, HudStyle.TEXT, false);
			graphics.text(font, this.details, x, this.getContentY() + 1 + font.lineHeight + 2,
					this.missing ? MISSING_TEXT : HudStyle.TEXT_MUTED, false);
		}

		@Override
		public Component getNarration() {
			return Component.translatable("narrator.select", this.loop.name());
		}
	}
}
