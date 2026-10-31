package com.easyfarm.gui.tab;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;

/**
 * One page of the HUD. A tab is only a view: the screen builds fresh tab instances every time it is (re)initialised,
 * so anything that has to survive a window resize belongs in a model object, not in the tab.
 */
public abstract class HudTab implements Tab {
	private static final int TEXT_MAX_WIDTH = 240;
	private static final int BUTTON_WIDTH = 140;

	private final Component title;
	protected final Font font = Minecraft.getInstance().font;
	protected final LinearLayout layout = LinearLayout.vertical().spacing(6);

	protected HudTab(Component title) {
		this.title = title;
	}

	protected void addHeading(Component text) {
		this.layout.addChild(new StringWidget(text.copy().withStyle(ChatFormatting.BOLD), this.font), LayoutSettings::alignHorizontallyCenter);
	}

	protected void addText(Component text) {
		this.layout.addChild(new StringWidget(text, this.font), LayoutSettings::alignHorizontallyCenter);
	}

	protected void addMutedText(Component text) {
		this.layout.addChild(
				new MultiLineTextWidget(text.copy().withStyle(ChatFormatting.GRAY), this.font).setMaxWidth(TEXT_MAX_WIDTH).setCentered(true),
				LayoutSettings::alignHorizontallyCenter);
	}

	protected Button addButton(Component label, Button.OnPress onPress) {
		Button button = Button.builder(label, onPress).width(BUTTON_WIDTH).build();
		this.layout.addChild(button, settings -> settings.alignHorizontallyCenter().paddingTop(6));
		return button;
	}

	/** A button for a feature that is not built yet: visible so the layout is final, but disabled. */
	protected void addComingSoonButton(Component label) {
		Button button = Button.builder(label, pressed -> { })
				.width(BUTTON_WIDTH)
				.tooltip(Tooltip.create(Component.translatable("easyfarm.coming_soon")))
				.build();
		button.active = false;
		this.layout.addChild(button, settings -> settings.alignHorizontallyCenter().paddingTop(6));
	}

	@Override
	public Component getTabTitle() {
		return this.title;
	}

	@Override
	public Component getTabExtraNarration() {
		return Component.empty();
	}

	@Override
	public void visitChildren(Consumer<AbstractWidget> childrenConsumer) {
		this.layout.visitWidgets(childrenConsumer);
	}

	@Override
	public void doLayout(ScreenRectangle rectangle) {
		this.layout.arrangeElements();
		FrameLayout.alignInRectangle(this.layout, rectangle, 0.5F, 0.15F);
	}

	@Override
	public LinearLayout getLayout() {
		return this.layout;
	}
}
