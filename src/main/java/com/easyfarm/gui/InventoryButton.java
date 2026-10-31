package com.easyfarm.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

/**
 * Adds the "EF" button next to the player inventory (survival and creative).
 *
 * <p>The button is a plain client-side widget; clicking it only swaps the open screen, which sends nothing to the server.
 * It follows the inventory panel every frame because the survival panel slides sideways when the recipe book is toggled.
 */
public final class InventoryButton {
	private static final int SIZE = 20;
	private static final int GAP = 4;

	private InventoryButton() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			// Potion effects are drawn to the right of both panels and the recipe book to the left of the survival one, so
			// the survival button goes above the panel. The creative panel has tab rows above and below, so it goes left.
			if (screen instanceof InventoryScreen inventory) {
				attach(inventory, Placement.ABOVE_RIGHT);
			} else if (screen instanceof CreativeModeInventoryScreen creative) {
				attach(creative, Placement.LEFT);
			}
		});
	}

	private static void attach(AbstractContainerScreen<?> screen, Placement placement) {
		Button button = Button.builder(Component.translatable("easyfarm.button.open"), pressed -> EasyFarmScreen.open())
				.size(SIZE, SIZE)
				.tooltip(Tooltip.create(Component.translatable("easyfarm.button.open.tooltip")))
				.build();
		placement.apply(button, screen);
		Screens.getWidgets(screen).add(button);

		// Screen events are reset on every re-init, so this cannot pile up duplicates.
		ScreenEvents.beforeExtract(screen).register((s, graphics, mouseX, mouseY, tickProgress) -> placement.apply(button, screen));
	}

	private enum Placement {
		/** Above the panel, right-aligned with it. */
		ABOVE_RIGHT {
			@Override
			void apply(Button button, AbstractContainerScreen<?> screen) {
				button.setX(screen.leftPos + screen.imageWidth - SIZE);
				button.setY(Math.max(GAP, screen.topPos - SIZE - GAP));
			}
		},
		/** Left of the panel, level with its top edge. */
		LEFT {
			@Override
			void apply(Button button, AbstractContainerScreen<?> screen) {
				button.setX(screen.leftPos - SIZE - GAP);
				button.setY(screen.topPos + GAP);
			}
		};

		abstract void apply(Button button, AbstractContainerScreen<?> screen);
	}
}
