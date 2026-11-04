package com.easyfarm.skyblock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Works out whether the player is in Hypixel SkyBlock's Garden, purely from the sidebar scoreboard and tab list the
 * client has already received. It sends nothing and does not use the Hypixel web API or /locraw.
 *
 * <p>SkyBlock's sidebar title is "SKYBLOCK" and one line names the area ("⏣ The Garden"); the tab list has an
 * "Area: Garden" entry. Both are checked so a change to one of them does not break detection.
 */
public final class SkyblockLocation {
	private static final Pattern SIDEBAR_AREA = Pattern.compile("\\bthe garden\\b");
	private static final Pattern TAB_AREA = Pattern.compile("\\barea:\\s*garden\\b");
	private static final Pattern FORMATTING = Pattern.compile("§.");

	/** Test hook: when non-null, replaces real detection. */
	private static volatile @Nullable Boolean override;

	private SkyblockLocation() {
	}

	public static boolean isInGarden() {
		Boolean forced = override;
		if (forced != null) {
			return forced;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.getConnection() == null) {
			return false;
		}
		Scoreboard scoreboard = client.level.getScoreboard();
		Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (sidebar == null) {
			return false;
		}
		List<String> sidebarLines = new ArrayList<>();
		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
			if (entry.isHidden()) {
				continue;
			}
			// Hypixel puts each line's text in the entry's team prefix/suffix, so format through the team.
			Component name = PlayerTeam.formatNameForTeam(
					scoreboard.getPlayersTeam(entry.owner()), entry.ownerName());
			sidebarLines.add(name.getString());
		}
		List<String> tabLines = new ArrayList<>();
		for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
			Component display = info.getTabListDisplayName();
			if (display != null) {
				tabLines.add(display.getString());
			}
		}
		return isGarden(sidebar.getDisplayName().getString(), sidebarLines, tabLines);
	}

	/** Pure detection on already-extracted text, so it can be tested without a connection. */
	public static boolean isGarden(String sidebarTitle, List<String> sidebarLines, List<String> tabLines) {
		if (!clean(sidebarTitle).contains("skyblock")) {
			return false;
		}
		for (String line : sidebarLines) {
			if (SIDEBAR_AREA.matcher(clean(line)).find()) {
				return true;
			}
		}
		for (String line : tabLines) {
			if (TAB_AREA.matcher(clean(line)).find()) {
				return true;
			}
		}
		return false;
	}

	private static String clean(String text) {
		return FORMATTING.matcher(text).replaceAll("").toLowerCase(Locale.ROOT).trim();
	}

	/** Test hook: force the answer ({@code null} restores real detection). */
	public static void setOverride(@Nullable Boolean value) {
		override = value;
	}
}
