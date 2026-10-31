package com.easyfarm.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

import com.easyfarm.loop.Loop;
import com.easyfarm.loop.LoopPlayer;
import com.easyfarm.loop.LoopStore;

/**
 * Adds the "EF" button next to the player inventory (survival and creative), and next to it a reset button and one
 * button per loop marked "show in inventory" in the Loops tab.
 *
 * <p>A loop button selects that loop to play (switching away from any other) or, if it is already the selected one,
 * stops it; the playing loop's button is shown green with a ▶. These are plain client-side widgets; clicking one only
 * changes local state, which sends nothing to the server. They sit in a column left of the panel and follow it every
 * frame, because the survival panel slides sideways (and the recipe book appears to its left) when the book is toggled.
 */
public final class InventoryButton {
	private static final int SIZE = 20;
	private static final int GAP = 4;
	private static final int ROW_GAP = 2;
	private static final int LOOP_BUTTON_MAX_WIDTH = 90;
	private static final String PLAYING_PREFIX = "▶ ";
	/** RecipeBookComponent's private xOffset when the book sits beside the panel. */
	private static final int RECIPE_BOOK_X_OFFSET = 86;
	/** The recipe book's category tabs start this far left of the book itself. */
	private static final int RECIPE_BOOK_TAB_OVERHANG = 30;

	private InventoryButton() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			// Potion effects are drawn to the right of both panels, so the buttons go in a column on the left.
			if (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen) {
				attach((AbstractContainerScreen<?>) screen);
			}
		});
	}

	private static void attach(AbstractContainerScreen<?> screen) {
		Font font = Minecraft.getInstance().font;
		Button hud = Button.builder(Component.translatable("easyfarm.button.open"), pressed -> EasyFarmScreen.open())
				.size(SIZE, SIZE)
				.tooltip(Tooltip.create(Component.translatable("easyfarm.button.open.tooltip")))
				.build();

		List<Button> extras = new ArrayList<>();
		List<LoopButton> loopButtons = new ArrayList<>();
		List<Loop> shown = LoopStore.list().stream().filter(Loop::showInInventory).toList();
		Button reset = null;
		if (!shown.isEmpty()) {
			Component resetLabel = Component.translatable("easyfarm.button.reset");
			reset = Button.builder(resetLabel, pressed -> LoopPlayer.reset())
					.size(Math.max(SIZE, font.width(resetLabel) + 10), SIZE)
					.tooltip(Tooltip.create(Component.translatable("easyfarm.button.reset.tooltip")))
					.build();
			extras.add(reset);

			for (Loop loop : shown) {
				// Sized for the "▶ " version so the button does not change width when its loop starts or stops.
				int width = Math.min(LOOP_BUTTON_MAX_WIDTH, font.width(PLAYING_PREFIX + loop.name()) + 10);
				Button button = Button.builder(Component.literal(loop.name()), pressed -> LoopPlayer.toggle(loop))
						.size(width, SIZE)
						.tooltip(Tooltip.create(Component.translatable("easyfarm.button.loop.tooltip", loop.name())))
						.build();
				extras.add(button);
				loopButtons.add(new LoopButton(button, loop));
			}
		}

		Screens.getWidgets(screen).add(hud);
		Screens.getWidgets(screen).addAll(extras);

		Button resetButton = reset;
		Runnable update = () -> {
			place(hud, extras, screen);
			if (resetButton != null) {
				resetButton.active = LoopPlayer.isRunning();
			}
			for (LoopButton loopButton : loopButtons) {
				loopButton.refresh(font);
			}
		};
		update.run();

		// Screen events are reset on every re-init, so this cannot pile up duplicates.
		ScreenEvents.beforeExtract(screen).register((s, graphics, mouseX, mouseY, tickProgress) -> update.run());
	}

	/** A loop's button; its label shows whether that loop is the one playing. */
	private record LoopButton(Button button, Loop loop) {
		void refresh(Font font) {
			boolean playing = LoopPlayer.isPlaying(this.loop);
			String text = (playing ? PLAYING_PREFIX : "") + this.loop.name();
			text = font.plainSubstrByWidth(text, this.button.getWidth() - 8);
			Component label = playing ? Component.literal(text).withStyle(ChatFormatting.GREEN) : Component.literal(text);
			if (!label.equals(this.button.getMessage())) {
				this.button.setMessage(label);
			}
		}
	}

	/**
	 * A column on the left of the panel: EF level with the panel's top edge, the others stacked below it, all
	 * right-aligned. When the survival recipe book is open beside the panel, the column moves left of the book instead.
	 */
	private static void place(Button hud, List<Button> extras, AbstractContainerScreen<?> screen) {
		int widest = hud.getWidth();
		for (Button button : extras) {
			widest = Math.max(widest, button.getWidth());
		}
		int right = leftEdge(screen) - GAP;
		if (right - widest < GAP) {
			// A narrow window with the recipe book open leaves no room on the left, and buttons laid over the book would
			// lose their clicks to it. Use a row above the panel until the book closes or the window widens.
			placeAbove(hud, extras, screen);
			return;
		}
		hud.setPosition(right - SIZE, screen.topPos + GAP);
		int y = hud.getY();
		for (Button button : extras) {
			y += SIZE + ROW_GAP;
			button.setPosition(right - button.getWidth(), y);
		}
	}

	/** Fallback: a row above the panel, EF right-aligned with it and the others running leftwards. */
	private static void placeAbove(Button hud, List<Button> extras, AbstractContainerScreen<?> screen) {
		int y = Math.max(GAP, screen.topPos - SIZE - GAP);
		hud.setPosition(screen.leftPos + screen.imageWidth - SIZE, y);
		int x = hud.getX();
		for (Button button : extras) {
			x -= ROW_GAP + button.getWidth();
			button.setPosition(x, y);
		}
	}

	/** Leftmost x of what the inventory draws: the panel, or the open recipe book and its category tabs. */
	private static int leftEdge(AbstractContainerScreen<?> screen) {
		// An open recipe book (with room to sit beside the panel) pushes the panel right of centre; that is the only
		// thing that moves it, so an off-centre panel means the book is there.
		boolean bookBeside = screen instanceof InventoryScreen && screen.leftPos != (screen.width - screen.imageWidth) / 2;
		if (!bookBeside) {
			return screen.leftPos;
		}
		// RecipeBookComponent draws the book at (width - 147) / 2 - 86, with its tabs sticking out 30 further left.
		int bookLeft = (screen.width - RecipeBookComponent.IMAGE_WIDTH) / 2 - RECIPE_BOOK_X_OFFSET;
		return bookLeft - RECIPE_BOOK_TAB_OVERHANG;
	}
}
