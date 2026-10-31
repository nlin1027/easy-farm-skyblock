package com.easyfarm.gui;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import com.easyfarm.gui.tab.HudTab;
import com.easyfarm.gui.tab.LoopsTab;
import com.easyfarm.gui.tab.RecordTab;
import com.easyfarm.gui.tab.RecordingsTab;
import com.easyfarm.recording.Recorder;
import com.easyfarm.recording.Recording;

/**
 * The Easy Farm HUD: a floating panel with a tab per feature (record, review, loop).
 *
 * <p>It is a plain client-side screen. It does not pause the game, so a recording or loop keeps running while it is open.
 */
public class EasyFarmScreen extends Screen {
	public static final int TAB_RECORD = 0;
	public static final int TAB_RECORDINGS = 1;
	public static final int TAB_LOOPS = 2;

	private static final int PANEL_WIDTH = 320;
	private static final int PANEL_HEIGHT = 240;
	private static final int PANEL_MARGIN = 10;
	private static final int PADDING = 8;
	private static final int HEADER_HEIGHT = 22;
	private static final int TAB_BAR_HEIGHT = 24;
	private static final int FOOTER_HEIGHT = 32;
	private static final int DONE_WIDTH = 100;
	private static final int STATUS_DOT_SIZE = 5;

	private final @Nullable Screen parent;
	private final TabManager tabManager;
	private @Nullable TabNavigationBar tabBar;
	private List<HudTab> tabs = List.of();
	private int selectedTab;

	private int panelLeft;
	private int panelTop;
	private int panelWidth;
	private int panelHeight;

	public EasyFarmScreen(@Nullable Screen parent) {
		super(Component.translatable("easyfarm.title"));
		this.parent = parent;
		this.tabManager = new TabManager(
				widget -> this.addRenderableWidget(widget),
				this::removeWidget,
				tab -> this.selectedTab = Math.max(0, this.tabs.indexOf(tab)),
				tab -> { });
	}

	/**
	 * Opens the HUD on top of whatever screen is showing. Shared by {@code /ef} and the inventory button.
	 *
	 * <p>The work is always queued with {@link Minecraft#schedule}, never run inline (unlike {@code execute}, which runs
	 * inline when no task is active). That matters for {@code /ef}: {@code ChatScreen} calls {@code setScreen(null)} right
	 * after running the command, so opening the HUD synchronously would have chat close it again immediately.
	 */
	public static void open() {
		open(-1);
	}

	/** Like {@link #open()}, but starts on the given tab ({@code TAB_*}); -1 keeps the default. */
	public static void open(int tab) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.schedule(() -> {
			Screen current = minecraft.gui.screen();
			if (minecraft.player == null || current instanceof EasyFarmScreen) {
				return;
			}

			// Closing the HUD should drop back to the inventory it was opened from, but not to a leftover chat box.
			EasyFarmScreen screen = new EasyFarmScreen(current instanceof ChatScreen ? null : current);
			minecraft.gui.setScreen(screen);
			if (tab >= 0) {
				// After setScreen: building the tab bar during init resets the selection to the first tab.
				screen.showTab(tab);
			}
		});
	}

	@Override
	protected void init() {
		this.panelWidth = Math.min(PANEL_WIDTH, this.width - 2 * PANEL_MARGIN);
		this.panelHeight = Math.min(PANEL_HEIGHT, this.height - 2 * PANEL_MARGIN);
		this.panelLeft = (this.width - this.panelWidth) / 2;
		this.panelTop = (this.height - this.panelHeight) / 2;

		// Tabs are views, so they are rebuilt on every init (resize) and the selection is restored by index.
		this.tabs = List.of(new RecordTab(this), new RecordingsTab(this), new LoopsTab());

		int innerWidth = this.panelWidth - 2 * PADDING;
		int tabWidth = innerWidth / this.tabs.size();
		int tabBarTop = this.panelTop + HEADER_HEIGHT;

		TabNavigationBar.Builder tabBarBuilder = TabNavigationBar.builder(
				this.tabManager, this.panelLeft + PADDING, tabBarTop, tabWidth * this.tabs.size(), TAB_BAR_HEIGHT);
		for (HudTab tab : this.tabs) {
			tabBarBuilder.addTab(new PanelTabButton(this.tabManager, tab, tabWidth, TAB_BAR_HEIGHT), tab);
		}
		TabNavigationBar tabBar = tabBarBuilder.build();
		this.tabBar = this.addRenderableWidget(tabBar);

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.panelLeft + (this.panelWidth - DONE_WIDTH) / 2, this.panelTop + this.panelHeight - FOOTER_HEIGHT + 6, DONE_WIDTH, 20)
				.build());

		tabBar.selectTab(this.selectedTab, false);

		int contentTop = tabBarTop + TAB_BAR_HEIGHT + 4;
		int contentBottom = this.panelTop + this.panelHeight - FOOTER_HEIGHT;
		this.tabManager.setTabArea(new ScreenRectangle(this.panelLeft + PADDING, contentTop, innerWidth, contentBottom - contentTop));
	}

	/** Index of the tab being shown (0 = Record, 1 = Recordings, 2 = Loops). */
	public int getSelectedTab() {
		return this.selectedTab;
	}

	/** Switches to a tab, rebuilding every tab so they all show the latest state (recordings, recorder status). */
	public void showTab(int index) {
		this.selectedTab = index;
		this.rebuildWidgets();
	}

	/** Rebuilds the tabs in place after their data changed, keeping the current tab. */
	public void refresh() {
		this.rebuildWidgets();
	}

	/**
	 * Ctrl+1..3 and Ctrl+Tab switch tabs. The tab bar only sees key presses while it has focus, so, like vanilla's own
	 * tabbed screens, the screen has to hand them over itself.
	 */
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.tabBar != null && this.tabBar.keyPressed(event)) {
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	/** Keep the world running while the HUD is open, like the inventory does. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Use the light in-game dimming instead of the full menu background. */
	@Override
	public boolean isInGameUi() {
		return true;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);

		int right = this.panelLeft + this.panelWidth;
		int bottom = this.panelTop + this.panelHeight;
		graphics.fill(this.panelLeft, this.panelTop, right, bottom, HudStyle.PANEL_BACKGROUND);
		graphics.outline(this.panelLeft, this.panelTop, this.panelWidth, this.panelHeight, HudStyle.PANEL_BORDER);
		graphics.fill(this.panelLeft, this.panelTop, right, this.panelTop + 2, HudStyle.ACCENT);

		int headerBottom = this.panelTop + HEADER_HEIGHT;
		graphics.fill(this.panelLeft + 1, headerBottom - 1, right - 1, headerBottom, HudStyle.SEPARATOR);
		int footerTop = bottom - FOOTER_HEIGHT;
		graphics.fill(this.panelLeft + 1, footerTop, right - 1, footerTop + 1, HudStyle.SEPARATOR);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);

		int textY = this.panelTop + 7;
		graphics.text(this.font, this.title, this.panelLeft + PADDING, textY, HudStyle.TEXT, true);

		// Looping will add its own state here once that feature exists.
		boolean recording = Recorder.isRecording();
		Component status = recording
				? Component.translatable("easyfarm.status.recording", Recording.formatDuration(Recorder.elapsedTicks()))
				: Component.translatable("easyfarm.status.idle");
		int statusX = this.panelLeft + this.panelWidth - PADDING - this.font.width(status);
		graphics.text(this.font, status, statusX, textY, recording ? HudStyle.TEXT : HudStyle.TEXT_MUTED, false);
		int dotX = statusX - STATUS_DOT_SIZE - 4;
		int dotY = textY + (this.font.lineHeight - STATUS_DOT_SIZE) / 2;
		graphics.fill(dotX, dotY, dotX + STATUS_DOT_SIZE, dotY + STATUS_DOT_SIZE, recording ? HudStyle.STATUS_RECORDING : HudStyle.STATUS_IDLE);
	}
}
