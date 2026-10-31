package com.easyfarm.gui.loop;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import com.easyfarm.gui.HudStyle;
import com.easyfarm.recording.Recording;

/**
 * The loop builder: saved recordings on the left, the loop's blocks on the right.
 *
 * <p>Recordings are added by dragging them into the loop column (or clicking them to append), reordered by dragging,
 * and removed with their × or by dragging them out of the column. Buffer blocks are drawn after every recording,
 * including an end buffer that is there even before anything is added: they are part of the loop's structure, not
 * items, so there is nothing to drag, delete or reorder about them.
 */
public final class LoopBuilderWidget extends AbstractWidget {
	private static final int HEADER_HEIGHT = 13;
	private static final int PALETTE_ROW = 20;
	private static final int BLOCK = 20;
	private static final int BUFFER = 14;
	private static final int SLOT = BLOCK + BUFFER;
	private static final int FOOTER = 14;
	private static final int PAD = 2;
	private static final int COLUMN_GAP = 8;
	private static final int REMOVE_WIDTH = 14;
	private static final int DRAG_THRESHOLD = 3;
	private static final double SCROLL_STEP = 12;

	private static final int BLOCK_COLOR = 0xFF2F3F2F;
	private static final int MISSING_COLOR = 0xFF4A2626;
	private static final int BUFFER_COLOR = 0xFF2E2A1C;
	private static final int BUFFER_TEXT = 0xFFE0C068;
	private static final int REMOVE_HOVERED = 0xFFFF6060;
	private static final int GHOST_COLOR = 0xCC3A4F3A;

	private final Font font;
	private final List<Recording> available;
	private final Map<String, Recording> byId = new HashMap<>();
	private final List<String> sequence;
	private final Supplier<Component> bufferLabel;
	private final Runnable onChange;

	private double paletteScroll;
	private double sequenceScroll;

	private enum Source { PALETTE, SEQUENCE }

	private @Nullable Source pressSource;
	private int pressIndex;
	private boolean pressOnRemove;
	private double pressX;
	private double pressY;
	private boolean dragging;

	/**
	 * @param sequence    the loop's recording ids; edited in place
	 * @param bufferLabel the buffer range as currently typed, shown on every buffer block
	 * @param onChange    called after the sequence changes
	 */
	public LoopBuilderWidget(Font font, int x, int y, int width, int height, List<Recording> available, List<String> sequence,
			Supplier<Component> bufferLabel, Runnable onChange) {
		super(x, y, width, height, Component.translatable("easyfarm.loops.editor.builder"));
		this.font = font;
		this.available = available;
		this.sequence = sequence;
		this.bufferLabel = bufferLabel;
		this.onChange = onChange;
		for (Recording recording : available) {
			this.byId.put(recording.id(), recording);
		}
	}

	// ---- geometry ----

	private int paletteLeft() {
		return this.getX();
	}

	private int paletteWidth() {
		return (this.width - COLUMN_GAP) * 2 / 5;
	}

	private int sequenceLeft() {
		return this.getX() + this.paletteWidth() + COLUMN_GAP;
	}

	private int sequenceRight() {
		return this.getRight();
	}

	private int listTop() {
		return this.getY() + HEADER_HEIGHT;
	}

	private int listBottom() {
		return this.getBottom();
	}

	private int visibleHeight() {
		return this.listBottom() - this.listTop() - 2 * PAD;
	}

	private int paletteRowY(int index) {
		return this.listTop() + PAD + index * PALETTE_ROW - (int) this.paletteScroll;
	}

	/** Top of slot {@code index}: a recording block followed by its buffer. Slot {@code size()} is just past the end. */
	private int slotY(int index) {
		return this.listTop() + PAD + index * SLOT - (int) this.sequenceScroll;
	}

	private boolean overPalette(double x, double y) {
		return x >= this.paletteLeft() && x < this.paletteLeft() + this.paletteWidth() && y >= this.listTop() && y < this.listBottom();
	}

	private boolean overSequence(double x, double y) {
		return x >= this.sequenceLeft() && x < this.sequenceRight() && y >= this.listTop() && y < this.listBottom();
	}

	private int paletteIndexAt(double y) {
		int index = Mth.floor((y - this.listTop() - PAD + this.paletteScroll) / PALETTE_ROW);
		return index >= 0 && index < this.available.size() ? index : -1;
	}

	/** Index of the recording block under {@code y}, or -1 over a buffer, the footer or empty space. */
	private int blockIndexAt(double y) {
		double relative = y - this.listTop() - PAD + this.sequenceScroll;
		int index = Mth.floor(relative / SLOT);
		if (index < 0 || index >= this.sequence.size()) {
			return -1;
		}
		return relative - index * SLOT < BLOCK ? index : -1;
	}

	/** Where a block dropped at {@code y} would be inserted: the gap nearest to the pointer. */
	private int insertIndexAt(double y) {
		double relative = y - this.listTop() - PAD + this.sequenceScroll;
		return Mth.clamp(Mth.floor(relative / SLOT + 0.5), 0, this.sequence.size());
	}

	private int sequenceContentHeight() {
		return Math.max(1, this.sequence.size()) * SLOT + FOOTER;
	}

	private void clampScroll() {
		this.paletteScroll = Mth.clamp(this.paletteScroll, 0, Math.max(0, this.available.size() * PALETTE_ROW - this.visibleHeight()));
		this.sequenceScroll = Mth.clamp(this.sequenceScroll, 0, Math.max(0, this.sequenceContentHeight() - this.visibleHeight()));
	}

	// ---- input ----

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!this.active || !this.visible || event.button() != 0 || !this.isMouseOver(event.x(), event.y())) {
			return false;
		}

		this.pressSource = null;
		this.dragging = false;
		this.pressX = event.x();
		this.pressY = event.y();

		if (this.overPalette(event.x(), event.y())) {
			int index = this.paletteIndexAt(event.y());
			if (index >= 0) {
				this.pressSource = Source.PALETTE;
				this.pressIndex = index;
				this.pressOnRemove = false;
			}
		} else if (this.overSequence(event.x(), event.y())) {
			int index = this.blockIndexAt(event.y());
			if (index >= 0) {
				this.pressSource = Source.SEQUENCE;
				this.pressIndex = index;
				this.pressOnRemove = event.x() >= this.sequenceRight() - PAD - REMOVE_WIDTH;
			}
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.pressSource != null && !this.pressOnRemove && !this.dragging
				&& Math.abs(event.x() - this.pressX) + Math.abs(event.y() - this.pressY) > DRAG_THRESHOLD) {
			this.dragging = true;
		}
		return this.pressSource != null;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		Source source = this.pressSource;
		this.pressSource = null;
		if (source == null || event.button() != 0) {
			this.dragging = false;
			return false;
		}

		if (this.dragging) {
			this.dragging = false;
			this.drop(source, event.x(), event.y());
		} else if (source == Source.PALETTE) {
			// A plain click appends, for players who would rather not drag.
			this.sequence.add(this.available.get(this.pressIndex).id());
			this.changed();
			this.sequenceScroll = Double.MAX_VALUE;
			this.clampScroll();
		} else if (this.pressOnRemove && this.blockIndexAt(event.y()) == this.pressIndex) {
			this.sequence.remove(this.pressIndex);
			this.changed();
		}
		return true;
	}

	private void drop(Source source, double x, double y) {
		boolean onSequence = this.overSequence(x, y);
		if (source == Source.PALETTE) {
			if (onSequence) {
				this.sequence.add(this.insertIndexAt(y), this.available.get(this.pressIndex).id());
				this.changed();
			}
			return;
		}

		String moved = this.sequence.remove(this.pressIndex);
		if (onSequence) {
			int target = this.insertIndexAt(y);
			if (target > this.pressIndex) {
				target--;
			}
			this.sequence.add(target, moved);
		}
		// Dropped anywhere outside the loop column: the block is removed.
		this.changed();
	}

	private void changed() {
		this.clampScroll();
		this.onChange.run();
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (this.overPalette(x, y)) {
			this.paletteScroll -= scrollY * SCROLL_STEP;
		} else if (this.overSequence(x, y)) {
			this.sequenceScroll -= scrollY * SCROLL_STEP;
		} else {
			return false;
		}
		this.clampScroll();
		return true;
	}

	// ---- rendering ----

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		this.clampScroll();
		int paletteLeft = this.paletteLeft();
		int paletteRight = paletteLeft + this.paletteWidth();
		int sequenceLeft = this.sequenceLeft();
		int sequenceRight = this.sequenceRight();

		graphics.text(this.font, Component.translatable("easyfarm.loops.editor.available"), paletteLeft + 2, this.getY() + 2, HudStyle.TEXT_MUTED, false);
		graphics.text(this.font, Component.translatable("easyfarm.loops.editor.order"), sequenceLeft + 2, this.getY() + 2, HudStyle.TEXT_MUTED, false);

		this.extractColumn(graphics, paletteLeft, paletteRight);
		this.extractColumn(graphics, sequenceLeft, sequenceRight);

		graphics.enableScissor(paletteLeft + 1, this.listTop() + 1, paletteRight - 1, this.listBottom() - 1);
		this.extractPalette(graphics, paletteLeft, paletteRight, mouseX, mouseY);
		graphics.disableScissor();

		graphics.enableScissor(sequenceLeft + 1, this.listTop() + 1, sequenceRight - 1, this.listBottom() - 1);
		this.extractSequence(graphics, sequenceLeft, sequenceRight, mouseX, mouseY);
		graphics.disableScissor();

		if (this.dragging && this.pressSource != null) {
			this.extractGhost(graphics, mouseX, mouseY);
		}
	}

	private void extractColumn(GuiGraphicsExtractor graphics, int left, int right) {
		graphics.fill(left, this.listTop(), right, this.listBottom(), HudStyle.LIST_BACKGROUND);
		graphics.outline(left, this.listTop(), right - left, this.listBottom() - this.listTop(), HudStyle.SEPARATOR);
	}

	private void extractPalette(GuiGraphicsExtractor graphics, int left, int right, int mouseX, int mouseY) {
		if (this.available.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("easyfarm.loops.editor.no_recordings"),
					(left + right) / 2, this.listTop() + 8, HudStyle.TEXT_MUTED);
			return;
		}

		int hovered = !this.dragging && this.overPalette(mouseX, mouseY) ? this.paletteIndexAt(mouseY) : -1;
		for (int i = 0; i < this.available.size(); i++) {
			int y = this.paletteRowY(i);
			if (y + PALETTE_ROW < this.listTop() || y > this.listBottom()) {
				continue;
			}
			Recording recording = this.available.get(i);
			int x0 = left + PAD;
			int x1 = right - PAD;
			graphics.fill(x0, y + 1, x1, y + PALETTE_ROW - 1, i == hovered ? HudStyle.TAB_HOVERED : HudStyle.TAB_IDLE);

			String duration = Recording.formatDuration(recording.lengthTicks());
			int durationWidth = this.font.width(duration);
			int textY = y + (PALETTE_ROW - this.font.lineHeight) / 2 + 1;
			String name = this.font.plainSubstrByWidth(recording.name(), x1 - x0 - durationWidth - 12);
			graphics.text(this.font, name, x0 + 4, textY, HudStyle.TEXT, false);
			graphics.text(this.font, duration, x1 - 4 - durationWidth, textY, HudStyle.TEXT_MUTED, false);
		}
	}

	private void extractSequence(GuiGraphicsExtractor graphics, int left, int right, int mouseX, int mouseY) {
		int x0 = left + PAD;
		int x1 = right - PAD;
		int count = this.sequence.size();
		boolean hoverable = !this.dragging && this.overSequence(mouseX, mouseY);
		int hoveredBlock = hoverable ? this.blockIndexAt(mouseY) : -1;

		if (count == 0) {
			int y = this.slotY(0);
			graphics.outline(x0, y + 1, x1 - x0, BLOCK - 2, HudStyle.PANEL_BORDER);
			graphics.centeredText(this.font, Component.translatable("easyfarm.loops.editor.drop_here"),
					(x0 + x1) / 2, y + (BLOCK - this.font.lineHeight) / 2 + 1, HudStyle.TEXT_MUTED);
			this.extractBuffer(graphics, x0, x1, y + BLOCK, true);
		}

		for (int i = 0; i < count; i++) {
			int y = this.slotY(i);
			if (y + SLOT < this.listTop() || y > this.listBottom()) {
				continue;
			}
			boolean beingDragged = this.dragging && this.pressSource == Source.SEQUENCE && this.pressIndex == i;
			this.extractBlock(graphics, i, x0, x1, y, beingDragged, i == hoveredBlock && mouseX >= x1 - REMOVE_WIDTH);
			this.extractBuffer(graphics, x0, x1, y + BLOCK, i == count - 1);
		}

		int footerY = this.slotY(Math.max(1, count)) + 3;
		graphics.centeredText(this.font, Component.translatable("easyfarm.loops.editor.repeats"), (x0 + x1) / 2, footerY, HudStyle.TEXT_MUTED);

		if (this.dragging && this.overSequence(mouseX, mouseY)) {
			int lineY = this.slotY(this.insertIndexAt(mouseY)) - 1;
			graphics.fill(x0, lineY - 1, x1, lineY + 1, HudStyle.ACCENT);
		}
	}

	private void extractBlock(GuiGraphicsExtractor graphics, int index, int x0, int x1, int y, boolean faded, boolean removeHovered) {
		Recording recording = this.byId.get(this.sequence.get(index));
		int background = recording == null ? MISSING_COLOR : BLOCK_COLOR;
		graphics.fill(x0, y + 1, x1, y + BLOCK - 1, faded ? HudStyle.TAB_IDLE : background);
		graphics.fill(x0, y + 1, x0 + 2, y + BLOCK - 1, recording == null ? REMOVE_HOVERED : HudStyle.ACCENT);

		int textY = y + (BLOCK - this.font.lineHeight) / 2 + 1;
		int textColor = faded ? HudStyle.TEXT_MUTED : HudStyle.TEXT;
		String duration = recording == null ? "" : Recording.formatDuration(recording.lengthTicks());
		int durationWidth = this.font.width(duration);
		String label = (index + 1) + ". " + (recording == null
				? Component.translatable("easyfarm.loops.editor.missing").getString()
				: recording.name());
		label = this.font.plainSubstrByWidth(label, x1 - x0 - durationWidth - REMOVE_WIDTH - 14);
		graphics.text(this.font, label, x0 + 6, textY, textColor, false);
		graphics.text(this.font, duration, x1 - REMOVE_WIDTH - 4 - durationWidth, textY, HudStyle.TEXT_MUTED, false);
		graphics.text(this.font, "×", x1 - REMOVE_WIDTH + 4, textY, removeHovered ? REMOVE_HOVERED : HudStyle.TEXT_MUTED, false);
	}

	private void extractBuffer(GuiGraphicsExtractor graphics, int x0, int x1, int y, boolean end) {
		int inset = 10;
		graphics.fill(x0 + inset, y + 1, x1 - inset, y + BUFFER - 1, BUFFER_COLOR);
		Component label = Component.translatable(end ? "easyfarm.loops.editor.end_buffer" : "easyfarm.loops.editor.buffer",
				this.bufferLabel.get());
		graphics.centeredText(this.font, label, (x0 + x1) / 2, y + (BUFFER - this.font.lineHeight) / 2 + 1, BUFFER_TEXT);
	}

	private void extractGhost(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		String id = this.pressSource == Source.PALETTE
				? this.available.get(this.pressIndex).id()
				: this.sequence.get(this.pressIndex);
		Recording recording = this.byId.get(id);
		String name = recording == null ? Component.translatable("easyfarm.loops.editor.missing").getString() : recording.name();

		int width = Math.min(140, this.sequenceRight() - this.sequenceLeft() - 2 * PAD);
		int x0 = mouseX - 12;
		int y0 = mouseY - BLOCK / 2;
		graphics.nextStratum();
		graphics.fill(x0, y0, x0 + width, y0 + BLOCK, GHOST_COLOR);
		graphics.outline(x0, y0, width, BLOCK, HudStyle.ACCENT);
		graphics.text(this.font, this.font.plainSubstrByWidth(name, width - 10), x0 + 5, y0 + (BLOCK - this.font.lineHeight) / 2 + 1, HudStyle.TEXT, false);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}

	// ---- positions for the client game test ----

	/** GUI coordinates of the middle of palette row {@code index}. */
	public int[] paletteRowCenter(int index) {
		return new int[] {this.paletteLeft() + this.paletteWidth() / 2, this.paletteRowY(index) + PALETTE_ROW / 2};
	}

	/** GUI coordinates of the middle of loop block {@code index} (away from its × button). */
	public int[] blockCenter(int index) {
		return new int[] {(this.sequenceLeft() + this.sequenceRight()) / 2 - REMOVE_WIDTH, this.slotY(index) + BLOCK / 2};
	}

	/** GUI coordinates of the × on loop block {@code index}. */
	public int[] removeButtonCenter(int index) {
		return new int[] {this.sequenceRight() - PAD - REMOVE_WIDTH / 2, this.slotY(index) + BLOCK / 2};
	}

	/** GUI coordinates of the gap before loop block {@code index} (or after the last one), for dropping. */
	public int[] gapPoint(int index) {
		return new int[] {(this.sequenceLeft() + this.sequenceRight()) / 2, this.slotY(index) + 2};
	}
}
