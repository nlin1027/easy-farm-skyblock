package com.easyfarm.command;

import com.mojang.brigadier.Command;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

import com.easyfarm.gui.EasyFarmScreen;

/**
 * Registers {@code /ef}.
 *
 * <p>This is a Fabric <em>client</em> command: {@code ClientPacketListener#sendCommand} is intercepted before any packet is
 * built, so neither {@code /ef} nor a malformed {@code /ef <junk>} is ever sent to the server.
 */
public final class EasyFarmCommand {
	private EasyFarmCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
				dispatcher.register(ClientCommands.literal("ef").executes(context -> {
					EasyFarmScreen.open();
					return Command.SINGLE_SUCCESS;
				})));
	}
}
