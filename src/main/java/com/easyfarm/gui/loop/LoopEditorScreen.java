package com.easyfarm.gui.loop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import com.easyfarm.gui.HudStyle;
import com.easyfarm.gui.tab.LoopsTab;
import com.easyfarm.loop.Loop;
import com.easyfarm.loop.LoopStore;
import com.easyfarm.recording.Recording;
import com.easyfarm.recording.RecordingStore;

/**
 * Creates or edits a loop: a name, the random buffer range, and the order of recordings (via {@link LoopBuilderWidget}).
 *
 * <p>Editor state lives in this screen's fields rather than its widgets, so resizing the window (which rebuilds the
 * widgets) keeps everything typed so far.
 */
public final class LoopEditorScreen extends Screen {
	private static final Logger LOGGER = LoggerFactory.getLogger("easyfarm");
	private static final int MAX_WIDTH = 440;
	private static final int MARGIN = 10;
	private static final int ROW_HEIGHT = 16;
	private static final int NAME_MAX_LENGTH = 32;
	private static final int SECONDS_BOX_WIDTH = 44;
	private static final String DEFAULT_MIN = "0.5";
	private static final String DEFAULT_MAX = "2.0";

	private final Screen parent;
	private final @Nullable Loop editing;
	private final List<Loop.Step> sequence;
	private final List<Recording> recordings;

	private String name;
	private String minText;
	private String maxText;
	private String messageText = "";
	private @Nullable LoopBuilderWidget builder;
	private @Nullable Component error;

	private int left;
	private int contentWidth;
	private int nameRowY;
	private int bufferRowY;
	private int messageRowY;
	private int bufferLabelX;
	private int toLabelX;
	private int secondsLabelX;

	public LoopEditorScreen(Screen parent, @Nullable Loop editing) {
		super(Component.translatable(editing == null ? "easyfarm.loops.editor.title.new" : "easyfarm.loops.editor.title.edit"));
		this.parent = parent;
		this.editing = editing;
		this.recordings = RecordingStore.list();
		if (editing == null) {
			this.sequence = new ArrayList<>();
			this.name = LoopStore.nextDefaultName();
			this.minText = DEFAULT_MIN;
			this.maxText = DEFAULT_MAX;
		} else {
			this.sequence = new ArrayList<>(editing.steps());
			this.name = editing.name();
			this.minText = Loop.formatSeconds(editing.bufferMinTicks());
			this.maxText = Loop.formatSeconds(editing.bufferMaxTicks());
		}
	}

	@Override
	protected void init() {
		this.contentWidth = Math.min(MAX_WIDTH, this.width - 2 * MARGIN);
		this.left = (this.width - this.contentWidth) / 2;
		this.nameRowY = 22;
		this.bufferRowY = this.nameRowY + ROW_HEIGHT + 6;
		int labelWidth = Math.max(Math.max(this.font.width(Component.translatable("easyfarm.loops.editor.name")),
				this.font.width(Component.translatable("easyfarm.loops.editor.message"))),
				this.font.width(Component.translatable("easyfarm.loops.editor.buffer_range"))) + 6;

		EditBox nameBox = new EditBox(this.font, this.left + labelWidth, this.nameRowY, this.contentWidth - labelWidth, ROW_HEIGHT,
				Component.translatable("easyfarm.loops.editor.name"));
		nameBox.setMaxLength(NAME_MAX_LENGTH);
		nameBox.setValue(this.name);
		nameBox.setResponder(value -> this.name = value);
		this.addRenderableWidget(nameBox);

		this.bufferLabelX = this.left;
		int minX = this.left + labelWidth;
		EditBox minBox = this.secondsBox(minX, this.minText, value -> this.minText = value, "easyfarm.loops.editor.buffer_min");
		this.toLabelX = minX + SECONDS_BOX_WIDTH + 5;
		int maxX = this.toLabelX + this.font.width(Component.translatable("easyfarm.loops.editor.to")) + 5;
		EditBox maxBox = this.secondsBox(maxX, this.maxText, value -> this.maxText = value, "easyfarm.loops.editor.buffer_max");
		this.secondsLabelX = maxX + SECONDS_BOX_WIDTH + 5;
		this.addRenderableWidget(minBox);
		this.addRenderableWidget(maxBox);

		// Message row: text to send as chat, or a command when it starts with a slash.
		this.messageRowY = this.bufferRowY + ROW_HEIGHT + 6;
		int addWidth = 96;
		EditBox messageBox = new EditBox(this.font, this.left + labelWidth, this.messageRowY,
				this.contentWidth - labelWidth - addWidth - 4, ROW_HEIGHT, Component.translatable("easyfarm.loops.editor.message"));
		messageBox.setMaxLength(Loop.Step.MAX_MESSAGE_LENGTH);
		messageBox.setHint(Component.translatable("easyfarm.loops.editor.message.hint"));
		messageBox.setValue(this.messageText);
		messageBox.setResponder(value -> {
			this.messageText = value;
			this.error = null;
		});
		this.addRenderableWidget(messageBox);
		this.addRenderableWidget(Button.builder(Component.translatable("easyfarm.loops.editor.add_message"), button -> this.addMessage())
				.bounds(this.left + this.contentWidth - addWidth, this.messageRowY, addWidth, ROW_HEIGHT)
				.build());

		int builderTop = this.messageRowY + ROW_HEIGHT + 8;
		int footerTop = this.height - 38;
		this.builder = this.addRenderableWidget(new LoopBuilderWidget(this.font, this.left, builderTop, this.contentWidth, footerTop - builderTop - 4,
				this.recordings, this.sequence, this::currentBufferLabel, () -> this.error = null));

		int buttonWidth = 100;
		int buttonsLeft = this.width / 2 - buttonWidth - 3;
		this.addRenderableWidget(Button.builder(Component.translatable("easyfarm.loops.editor.save"), button -> this.save())
				.bounds(buttonsLeft, this.height - 26, buttonWidth, 20)
				.build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
				.bounds(buttonsLeft + buttonWidth + 6, this.height - 26, buttonWidth, 20)
				.build());
	}

	private void addMessage() {
		if (!Loop.Step.isValidMessage(this.messageText)) {
			this.error = Component.translatable("easyfarm.loops.editor.error.message", Loop.Step.MAX_MESSAGE_LENGTH);
			return;
		}
		this.sequence.add(Loop.Step.message(this.messageText.strip()));
		this.messageText = "";
		this.error = null;
		this.rebuildWidgets();
		if (this.builder != null) {
			this.builder.scrollToEnd();
		}
	}

	private EditBox secondsBox(int x, String value, java.util.function.Consumer<String> responder, String narrationKey) {
		EditBox box = new EditBox(this.font, x, this.bufferRowY, SECONDS_BOX_WIDTH, ROW_HEIGHT, Component.translatable(narrationKey));
		box.setMaxLength(6);
		box.setValue(value);
		box.setResponder(text -> {
			responder.accept(text);
			this.error = null;
		});
		return box;
	}

	/** The range as typed, for the buffer blocks; "?" while it is not a valid range. */
	private Component currentBufferLabel() {
		Integer min = parseTicks(this.minText);
		Integer max = parseTicks(this.maxText);
		if (min == null || max == null || !Loop.isValidBuffer(min, max)) {
			return Component.literal("?");
		}
		return Component.literal(Loop.formatBufferRange(min, max));
	}

	/** Seconds as typed ("0.5", "1,25", "2") to ticks, rounded to the nearest tick; null if it is not a number. */
	private static @Nullable Integer parseTicks(String text) {
		try {
			double seconds = Double.parseDouble(text.trim().replace(',', '.'));
			if (!Double.isFinite(seconds) || seconds < 0 || seconds > 100_000) {
				return null;
			}
			return (int) Math.round(seconds * Recording.TICKS_PER_SECOND);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private void save() {
		String trimmedName = this.name.trim();
		if (trimmedName.isEmpty()) {
			this.error = Component.translatable("easyfarm.loops.editor.error.name");
			return;
		}
		if (this.sequence.isEmpty()) {
			this.error = Component.translatable("easyfarm.loops.editor.error.empty");
			return;
		}

		Integer min = parseTicks(this.minText);
		Integer max = parseTicks(this.maxText);
		if (min == null || max == null) {
			this.error = Component.translatable("easyfarm.loops.editor.error.number");
			return;
		}
		if (min < Loop.MIN_BUFFER_TICKS) {
			this.error = Component.translatable("easyfarm.loops.editor.error.min", Loop.formatSeconds(Loop.MIN_BUFFER_TICKS));
			return;
		}
		if (max <= min) {
			// Equal bounds would make every buffer the same length, which is exactly what buffers must never be.
			this.error = Component.translatable("easyfarm.loops.editor.error.range");
			return;
		}
		if (max > Loop.MAX_BUFFER_TICKS) {
			this.error = Component.translatable("easyfarm.loops.editor.error.max", Loop.formatSeconds(Loop.MAX_BUFFER_TICKS));
			return;
		}

		try {
			Loop saved;
			if (this.editing == null) {
				saved = LoopStore.saveNew(trimmedName, this.sequence, min, max);
			} else {
				saved = new Loop(this.editing.id(), trimmedName, this.editing.createdAt(), this.sequence, min, max,
						this.editing.showInInventory());
				LoopStore.save(saved);
			}
			LoopsTab.focus(saved.id());
		} catch (IOException e) {
			LOGGER.error("Could not save loop", e);
			this.error = Component.translatable("easyfarm.loops.editor.error.io");
			return;
		}
		this.onClose();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		int x0 = this.left - 6;
		int x1 = this.left + this.contentWidth + 6;
		graphics.fill(x0, 4, x1, this.height - 4, HudStyle.PANEL_BACKGROUND);
		graphics.outline(x0, 4, x1 - x0, this.height - 8, HudStyle.PANEL_BORDER);
		graphics.fill(x0, 4, x1, 6, HudStyle.ACCENT);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);

		graphics.centeredText(this.font, this.title, this.width / 2, 10, HudStyle.TEXT);

		int labelOffset = (ROW_HEIGHT - this.font.lineHeight) / 2 + 1;
		graphics.text(this.font, Component.translatable("easyfarm.loops.editor.name"), this.left, this.nameRowY + labelOffset, HudStyle.TEXT_MUTED, false);
		graphics.text(this.font, Component.translatable("easyfarm.loops.editor.message"), this.left, this.messageRowY + labelOffset, HudStyle.TEXT_MUTED, false);
		graphics.text(this.font, Component.translatable("easyfarm.loops.editor.buffer_range"), this.bufferLabelX, this.bufferRowY + labelOffset, HudStyle.TEXT_MUTED, false);
		graphics.text(this.font, Component.translatable("easyfarm.loops.editor.to"), this.toLabelX, this.bufferRowY + labelOffset, HudStyle.TEXT_MUTED, false);
		graphics.text(this.font, Component.translatable("easyfarm.loops.editor.seconds_hint"), this.secondsLabelX, this.bufferRowY + labelOffset, HudStyle.TEXT_MUTED, false);

		if (this.error != null) {
			graphics.centeredText(this.font, this.error, this.width / 2, this.height - 37, 0xFFFF6060);
		}
	}
}
