package com.easyfarm.gui.tab;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import com.easyfarm.gui.EasyFarmScreen;
import com.easyfarm.recording.Recorder;
import com.easyfarm.recording.Recording;

/** Start and stop capturing movement keys. */
public final class RecordTab extends HudTab {
	public RecordTab(EasyFarmScreen screen) {
		super(Component.translatable("easyfarm.tab.record"));

		if (Recorder.isRecording()) {
			this.addHeading(Component.translatable("easyfarm.record.heading.recording"));
			this.addText(Component.translatable("easyfarm.record.progress",
					Recording.formatDuration(Recorder.elapsedTicks()), Recorder.pressCount()));
			this.addMutedText(Component.translatable("easyfarm.record.paused_hint"));
			this.addButton(Component.translatable("easyfarm.record.stop"), button -> {
				Recording saved = Recorder.stop();
				if (saved != null) {
					// Show the new recording straight away.
					RecordingsTab.focus(saved.id());
					screen.showTab(EasyFarmScreen.TAB_RECORDINGS);
				} else {
					screen.refresh();
				}
			});
		} else {
			this.addHeading(Component.translatable("easyfarm.record.heading"));
			this.addMutedText(Component.translatable("easyfarm.record.description"));
			this.addButton(Component.translatable("easyfarm.record.start"), button -> {
				// Close every screen (not just the HUD) so the player can move straight away.
				Recorder.start();
				Minecraft.getInstance().gui.setScreen(null);
			});
			this.addMutedText(Component.translatable("easyfarm.record.key_hint", Recorder.toggleKeyName()));

			Component lastResult = Recorder.lastResult();
			if (lastResult != null) {
				this.addText(lastResult);
			}
		}
	}
}
