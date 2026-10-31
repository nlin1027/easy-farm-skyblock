package com.easyfarm;

import net.fabricmc.api.ClientModInitializer;

import com.easyfarm.command.EasyFarmCommand;
import com.easyfarm.gui.InventoryButton;
import com.easyfarm.loop.LoopPlayer;
import com.easyfarm.recording.Recorder;

/**
 * Client entrypoint. Easy Farm is purely client-side: it never registers a network channel,
 * sends a packet or reads server data, so there is nothing here for a server to observe.
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
