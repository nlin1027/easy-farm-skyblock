package com.easyfarm.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;

/** A flat tab button that matches the HUD panel (vanilla's tab buttons are drawn for full-width screen headers). */
final class PanelTabButton extends TabButton {
	PanelTabButton(TabManager tabManager, Tab tab, int width, int height) {
		super(tabManager, tab, width, height);
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		boolean selected = this.isSelected();
		boolean highlighted = this.isHoveredOrFocused();

		int background = selected ? HudStyle.TAB_SELECTED : highlighted ? HudStyle.TAB_HOVERED : HudStyle.TAB_IDLE;
		// Stop one pixel short so neighbouring tabs read as separate buttons.
		graphics.fill(this.getX(), this.getY(), this.getRight() - 1, this.getBottom(), background);
		if (selected) {
			graphics.fill(this.getX(), this.getBottom() - 2, this.getRight() - 1, this.getBottom(), HudStyle.ACCENT);
		}
		if (this.isFocused()) {
			graphics.outline(this.getX(), this.getY(), this.getWidth() - 1, this.getHeight(), HudStyle.TEXT);
		}

		Font font = Minecraft.getInstance().font;
		int textColor = selected || highlighted ? HudStyle.TEXT : HudStyle.TEXT_MUTED;
		graphics.centeredText(font, this.getMessage(), this.getX() + (this.getWidth() - 1) / 2, this.getY() + (this.getHeight() - font.lineHeight) / 2, textColor);
		this.handleCursor(graphics);
	}
}
