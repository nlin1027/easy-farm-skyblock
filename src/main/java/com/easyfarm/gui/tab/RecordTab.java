package com.easyfarm.gui.tab;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.network.chat.Component;

import com.easyfarm.gui.EasyFarmScreen;
import com.easyfarm.recording.Recorder;
import com.easyfarm.recording.Recording;
import com.easyfarm.recording.RecordingStore;

/** Name a recording, then start and stop capturing movement keys. */
public final class RecordTab extends HudTab {
	private static final int NAME_BOX_WIDTH = 180;

	public RecordTab(EasyFarmScreen screen) {
		super(Component.translatable("easyfarm.tab.record"));

		if (Recorder.isRecording()) {
			this.addHeading(Component.translatable("easyfarm.record.heading.recording"));
			this.addText(Component.literal(Recorder.activeName()));
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

			EditBox nameBox = new EditBox(this.font, NAME_BOX_WIDTH, 20, Component.translatable("easyfarm.record.name"));
			nameBox.setMaxLength(RecordingStore.MAX_NAME_LENGTH + 8); // a little slack so "too long" can be reported
			nameBox.setHint(Component.translatable("easyfarm.record.name.hint").withStyle(ChatFormatting.DARK_GRAY));
			nameBox.setValue(Recorder.draftName());
			this.layout.addChild(nameBox, LayoutSettings::alignHorizontallyCenter);

			// One line that shows the key tip, or the reason the name is not usable.
			Component hint = Component.translatable("easyfarm.record.key_hint", Recorder.toggleKeyName()).withStyle(ChatFormatting.GRAY);
			StringWidget nameError = new StringWidget(hint, this.font);
			nameError.setWidth(240);
			this.layout.addChild(nameError, LayoutSettings::alignHorizontallyCenter);

			Button start = this.addButton(Component.translatable("easyfarm.record.start"), button -> {
				if (Recorder.start(nameBox.getValue()) == null) {
					// Close every screen (not just the HUD) so the player can move straight away.
					Minecraft.getInstance().gui.setScreen(null);
				}
			});

			Runnable validate = () -> {
				String value = nameBox.getValue();
				Component error = Recorder.nameError(value);
				start.active = error == null;
				boolean show = error != null && !value.isBlank();
				nameError.setMessage(show ? error.copy().withStyle(ChatFormatting.RED) : hint);
			};
			nameBox.setResponder(value -> {
				Recorder.setDraftName(value);
				validate.run();
			});
			validate.run();

			Component lastResult = Recorder.lastResult();
			if (lastResult != null) {
				this.addText(lastResult);
			}
		}
	}
}
