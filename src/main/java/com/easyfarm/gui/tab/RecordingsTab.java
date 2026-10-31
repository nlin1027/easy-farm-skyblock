package com.easyfarm.gui.tab;

import net.minecraft.network.chat.Component;

/** Browse previously recorded keystrokes. */
public final class RecordingsTab extends HudTab {
	public RecordingsTab() {
		super(Component.translatable("easyfarm.tab.recordings"));
		this.addHeading(Component.translatable("easyfarm.recordings.heading"));
		this.addText(Component.translatable("easyfarm.recordings.empty"));
		this.addMutedText(Component.translatable("easyfarm.recordings.empty_hint"));
	}
}
