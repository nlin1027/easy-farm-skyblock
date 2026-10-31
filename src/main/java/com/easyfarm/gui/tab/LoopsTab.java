package com.easyfarm.gui.tab;

import net.minecraft.network.chat.Component;

/** Put a recording on loop. */
public final class LoopsTab extends HudTab {
	public LoopsTab() {
		super(Component.translatable("easyfarm.tab.loops"));
		this.addHeading(Component.translatable("easyfarm.loops.heading"));
		this.addText(Component.translatable("easyfarm.loops.empty"));
		this.addMutedText(Component.translatable("easyfarm.loops.empty_hint"));
		this.addComingSoonButton(Component.translatable("easyfarm.loops.start"));
	}
}
