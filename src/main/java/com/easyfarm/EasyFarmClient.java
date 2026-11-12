package com.easyfarm;

import net.fabricmc.api.ClientModInitializer;

import com.easyfarm.command.EasyFarmCommand;
import com.easyfarm.gui.InventoryButton;
import com.easyfarm.loop.LoopPlayer;
import com.easyfarm.recording.Recorder;

/**
 * Client entrypoint. Easy Farm is purely client-side: it registers no network channel or payload, has no mixins, and
 * sends no packet of its own. Its only outbound traffic is a loop's chat/command blocks, which go through the game's own
 * chat methods exactly as if typed, so they carry nothing that identifies the mod. It only reads what the client already
 * shows (the scoreboard and tab list, to know whether the player is in the Garden).
 */
public final class EasyFarmClient implements ClientModInitializer {
	public static final String MOD_ID = "easyfarm";

	@Override
	public void onInitializeClient() {
		EasyFarmCommand.register();
		InventoryButton.register();
		Recorder.register();
		LoopPlayer.register();
	}
}
