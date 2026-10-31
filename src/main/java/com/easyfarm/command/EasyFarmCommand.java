package com.easyfarm.command;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import com.easyfarm.gui.EasyFarmScreen;
import com.easyfarm.recording.Recorder;
import com.easyfarm.recording.Recording;
import com.easyfarm.recording.RecordingStore;

/**
 * Registers {@code /ef} and its subcommands.
 *
 * <p>These are Fabric <em>client</em> commands: {@code ClientPacketListener#sendCommand} is intercepted before any packet is
 * built, so neither {@code /ef} nor a malformed {@code /ef <junk>} is ever sent to the server.
 *
 * <p>Names with spaces are written in quotes: {@code /ef record "wheat farm"}.
 */
public final class EasyFarmCommand {
	private static final SuggestionProvider<FabricClientCommandSource> RECORDING_NAMES = (context, builder) -> {
		for (Recording recording : RecordingStore.list()) {
			String name = recording.name();
			String suggestion = name.matches("[A-Za-z0-9_.+-]+") ? name : "\"" + name + "\"";
			if (suggestion.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
				builder.suggest(suggestion);
			}
		}
		return builder.buildFuture();
	};

	/** One line of {@code /ef help}. */
	private record Help(String usage, String descriptionKey) {
	}

	private static final List<Help> HELP = List.of(
			new Help("/ef", "easyfarm.command.help.open"),
			new Help("/ef open [record|recordings|loops]", "easyfarm.command.help.open_tab"),
			new Help("/ef record <name>", "easyfarm.command.help.record"),
			new Help("/ef stop", "easyfarm.command.help.stop"),
			new Help("/ef status", "easyfarm.command.help.status"),
			new Help("/ef list", "easyfarm.command.help.list"),
			new Help("/ef rename <name> <new name>", "easyfarm.command.help.rename"),
			new Help("/ef delete <name>", "easyfarm.command.help.delete"),
			new Help("/ef help", "easyfarm.command.help.help"));

	private EasyFarmCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
			LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("ef")
					.executes(context -> {
						EasyFarmScreen.open();
						return Command.SINGLE_SUCCESS;
					});

			root.then(ClientCommands.literal("help").executes(EasyFarmCommand::help));

			root.then(ClientCommands.literal("open")
					.executes(context -> {
						EasyFarmScreen.open();
						return Command.SINGLE_SUCCESS;
					})
					.then(openTab("record", EasyFarmScreen.TAB_RECORD))
					.then(openTab("recordings", EasyFarmScreen.TAB_RECORDINGS))
					.then(openTab("loops", EasyFarmScreen.TAB_LOOPS)));

			root.then(ClientCommands.literal("record")
					.then(ClientCommands.argument("name", StringArgumentType.string()).executes(EasyFarmCommand::record)));
			root.then(ClientCommands.literal("stop").executes(EasyFarmCommand::stop));
			root.then(ClientCommands.literal("status").executes(EasyFarmCommand::status));
			root.then(ClientCommands.literal("list").executes(EasyFarmCommand::list));

			root.then(ClientCommands.literal("delete")
					.then(ClientCommands.argument("name", StringArgumentType.string())
							.suggests(RECORDING_NAMES)
							.executes(EasyFarmCommand::delete)));
			root.then(ClientCommands.literal("rename")
					.then(ClientCommands.argument("name", StringArgumentType.string())
							.suggests(RECORDING_NAMES)
							.then(ClientCommands.argument("newName", StringArgumentType.string()).executes(EasyFarmCommand::rename))));

			dispatcher.register(root);
		});
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> openTab(String literal, int tab) {
		return ClientCommands.literal(literal).executes(context -> {
			EasyFarmScreen.open(tab);
			return Command.SINGLE_SUCCESS;
		});
	}

	private static int help(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		source.sendFeedback(Component.translatable("easyfarm.command.help.header").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		for (Help line : HELP) {
			MutableComponent usage = Component.literal(line.usage()).withStyle(ChatFormatting.YELLOW);
			source.sendFeedback(usage.append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.translatable(line.descriptionKey()).withStyle(ChatFormatting.GRAY)));
		}
		source.sendFeedback(Component.translatable("easyfarm.command.help.footer").withStyle(ChatFormatting.DARK_GRAY));
		return Command.SINGLE_SUCCESS;
	}

	private static int record(CommandContext<FabricClientCommandSource> context) {
		Component error = Recorder.start(StringArgumentType.getString(context, "name"));
		if (error != null) {
			context.getSource().sendError(error);
			return 0;
		}
		// Recorder already shows "Recording ..." on the action bar.
		return Command.SINGLE_SUCCESS;
	}

	private static int stop(CommandContext<FabricClientCommandSource> context) {
		if (!Recorder.isRecording()) {
			context.getSource().sendError(Component.translatable("easyfarm.command.error.not_recording"));
			return 0;
		}
		Recorder.stop();
		Component result = Recorder.lastResult();
		if (result != null) {
			context.getSource().sendFeedback(result);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int status(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		if (Recorder.isRecording()) {
			source.sendFeedback(Component.translatable("easyfarm.command.status.recording",
					Recorder.activeName(), Recording.formatDuration(Recorder.elapsedTicks()), Recorder.pressCount()));
		} else {
			source.sendFeedback(Component.translatable("easyfarm.command.status.idle"));
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int list(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		List<Recording> recordings = RecordingStore.list();
		if (recordings.isEmpty()) {
			source.sendFeedback(Component.translatable("easyfarm.recordings.empty"));
			return Command.SINGLE_SUCCESS;
		}
		source.sendFeedback(Component.translatable("easyfarm.recordings.heading.count", recordings.size())
				.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		for (Recording recording : recordings) {
			source.sendFeedback(Component.literal(recording.name()).withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(" - " + Recording.formatDuration(recording.lengthTicks())
							+ ", " + recording.pressCount() + " presses").withStyle(ChatFormatting.GRAY)));
		}
		return recordings.size();
	}

	private static int delete(CommandContext<FabricClientCommandSource> context) {
		String name = StringArgumentType.getString(context, "name");
		Recording recording = RecordingStore.find(name);
		if (recording == null) {
			context.getSource().sendError(Component.translatable("easyfarm.command.error.not_found", name));
			return 0;
		}
		try {
			RecordingStore.delete(recording);
		} catch (IOException e) {
			context.getSource().sendError(Component.translatable("easyfarm.command.error.io", e.getMessage()));
			return 0;
		}
		context.getSource().sendFeedback(Component.translatable("easyfarm.command.deleted", recording.name()));
		return Command.SINGLE_SUCCESS;
	}

	private static int rename(CommandContext<FabricClientCommandSource> context) {
		String name = StringArgumentType.getString(context, "name");
		String newName = StringArgumentType.getString(context, "newName");
		Recording recording = RecordingStore.find(name);
		if (recording == null) {
			context.getSource().sendError(Component.translatable("easyfarm.command.error.not_found", name));
			return 0;
		}
		// Changing only the capitalisation of the same recording is fine, so skip the "taken" check then.
		boolean sameRecording = recording.name().equalsIgnoreCase(newName.strip());
		Component error = sameRecording ? null : Recorder.nameError(newName);
		if (error == null && (newName.isBlank() || newName.strip().length() > RecordingStore.MAX_NAME_LENGTH)) {
			error = Recorder.nameError(newName);
		}
		if (error != null) {
			context.getSource().sendError(error);
			return 0;
		}
		try {
			RecordingStore.rename(recording, newName);
		} catch (IOException e) {
			context.getSource().sendError(Component.translatable("easyfarm.command.error.io", e.getMessage()));
			return 0;
		}
		context.getSource().sendFeedback(Component.translatable("easyfarm.command.renamed", recording.name(), newName.strip()));
		return Command.SINGLE_SUCCESS;
	}
}
