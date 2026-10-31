package com.easyfarm.gui.tab;

import net.minecraft.network.chat.Component;

/** Start and stop capturing keystrokes. */
public final class RecordTab extends HudTab {
	public RecordTab() {
		super(Component.translatable("easyfarm.tab.record"));
		this.addHeading(Component.translatable("easyfarm.record.heading"));
		this.addMutedText(Component.translatable("easyfarm.record.description"));
		this.addComingSoonButton(Component.translatable("easyfarm.record.start"));
	}
}
