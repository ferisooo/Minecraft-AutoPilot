package dev.humanautopilot;

import dev.humanautopilot.modules.BoxFillModule;
import dev.humanautopilot.schematic.SchematicMode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.List;

/** The Autopilot menu (numpad 9): pick a mode, see which one is on, pause or turn it off. */
public final class AutopilotScreen extends Screen {
	private static final int PER_PAGE = 6;
	private boolean files;
	/** The Unfinished builds page. */
	private boolean builds;
	private int page;
	private String error;

	public AutopilotScreen() {
		super(Component.literal("Autopilot"));
	}

	/** Opened on the Unfinished builds page (the tests' screenshot). */
	public static AutopilotScreen unfinishedPage() {
		AutopilotScreen s = new AutopilotScreen();
		s.builds = true;
		return s;
	}

	/** Numpad 8 works in here too (a menu swallows the game's keys: it did nothing with this open). */
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (HumanAutopilot.isRecordKey(e)) {
			HumanAutopilot.toggleRecording();
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	protected void init() {
		gamesLabelY = -1;
		int w = 200, x = width / 2 - w / 2, y = 52, h = 20, gap = 24;
		BoxFillModule box = BoxFillModule.get();
		if (builds) {
			buildsPage(x, y, w, h, gap);
			return;
		}
		if (!files) {
			// A colour each (the user: Box Fill red, Build Schematic blue - "continue and unfinished go inside schematic
			// menu" - then Farm and Parkour), all four under the one heading ("mini-games is the title for all 4").
			y += 12;
			gamesLabelY = y - 11;
			boolean boxOn = box.on() && !box.planMode();
			addRenderableWidget(Button.builder(Component.literal((boxOn ? "§a● " : "") + "§cBox Fill"), b -> {
				HumanAutopilot.setBoxFill(true);
				onClose();
			}).bounds(x, y, w, h).build());
			addRenderableWidget(Button.builder(Component.literal((box.planMode() || SchematicMode.get().previewing() ? "§a● " : "") + "§9Build Schematic..."), b -> {
				files = true;
				page = 0;
				error = null;
				rebuildWidgets();
			}).bounds(x, y += gap, w, h).build());
			// StreamToEarn's Farm game: snowball every cell of the field ripe, again after every gift and every win.
			boolean fmOn = HumanAutopilot.isFarmOn();
			addRenderableWidget(Button.builder(Component.literal((fmOn ? "§a● " : "") + "§6Farm game"), b -> {
				if (!fmOn) HumanAutopilot.autopilotOff();
				HumanAutopilot.setFarm(!fmOn);
				onClose();
			}).bounds(x, y += gap, w, h).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
					"Farm game: on or by the farm (or /farm tp) - she snowballs every cell that isn't ripe, shoots chickens, and keeps going after every win."))).build());
			// A parkour mini-game (StreamToEarn's Parkour): up the course round her to the top, and stay there.
			boolean pkOn = HumanAutopilot.isParkourOn();
			addRenderableWidget(Button.builder(Component.literal((pkOn ? "§a● " : "") + "§bParkour"), b -> {
				if (!pkOn) HumanAutopilot.autopilotOff();
				HumanAutopilot.setParkour(!pkOn);
				onClose();
			}).bounds(x, y += gap, w, h).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
					"Parkour: stand at the start of the course - she jumps it to the top, climbs again if knocked off, and holds the top."))).build());
			// StreamToEarn's Ladder game: up the ladder to the top and hold on there while the timer runs down.
			boolean ldOn = HumanAutopilot.isLadderOn();
			addRenderableWidget(Button.builder(Component.literal((ldOn ? "§a● " : "") + "§dLadder game"), b -> {
				if (!ldOn) HumanAutopilot.autopilotOff();
				HumanAutopilot.setLadder(!ldOn);
				onClose();
			}).bounds(x, y += gap, w, h).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
					"Ladder game: by the ladder (or /ladder tp) - she climbs to the top and holds on, back up if pulled or knocked down."))).build());
			// (One Block - OneBlockModule - is left out of the menu: the user dropped that mini-game half way, 2026-10-23.)
			boolean obOn = HumanAutopilot.isOneBlockOn();
			Button pause = addRenderableWidget(Button.builder(Component.literal(box.paused() ? "Resume" : "Pause"), b -> {
				HumanAutopilot.togglePause();
				rebuildWidgets();
			}).bounds(x, y += gap + 8, w, h).build());
			pause.active = box.on();
			Button off = addRenderableWidget(Button.builder(Component.literal("§cOff"), b -> {
				HumanAutopilot.autopilotOff();
				onClose();
			}).bounds(x, y += gap, w, h).build());
			off.active = box.on() || SchematicMode.get().previewing() || pkOn || fmOn || ldOn || obOn;
			addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(x, y + gap + 8, w, h).build());
			return;
		}
		// Carrying on: the build left last, and the list of those left (here now, in Build Schematic, not on the first page).
		// ("Continue building": cut to fit - a name too long for the button scrolls it sideways.)
		int top = 0;
		String saved = SchematicMode.get().savedBuild();
		if (saved != null && !box.planMode()) {
			String label = "Continue building " + saved.replaceFirst("\\.[A-Za-z]+$", "");
			if (font.width(label) > w - 10) {
				while (label.length() > 18 && font.width(label + "..") > w - 10) label = label.substring(0, label.length() - 1);
				label += "..";
			}
			addRenderableWidget(Button.builder(Component.literal("§9" + label), b -> {
				error = SchematicMode.get().resume();
				if (error == null) onClose();
			}).bounds(x, y, w, h).build());
			y += gap;
			top++;
		}
		int left = SchematicMode.get().unfinished().size();
		if (left > 0) {
			addRenderableWidget(Button.builder(Component.literal("§9Unfinished builds (" + left + ")..."), b -> {
				builds = true;
				page = 0;
				error = null;
				rebuildWidgets();
			}).bounds(x, y, w, h).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
					"Builds you started and left for another one: pick one and she goes back to it and carries on."))).build());
			y += gap;
			top++;
		}
		if (top > 0) y += 6;
		int perPage = PER_PAGE - top;
		List<String> names = SchematicMode.get().files();
		int pages = Math.max(1, (names.size() + perPage - 1) / perPage);
		page = Math.clamp(page, 0, pages - 1);
		for (int i = page * perPage; i < Math.min(names.size(), (page + 1) * perPage); i++) {
			String n = names.get(i);
			addRenderableWidget(Button.builder(Component.literal(n), b -> {
				error = SchematicMode.get().choose(n);
				if (error == null) onClose();
			}).bounds(x, y, w, h).build());
			y += gap;
		}
		y = Math.max(y, 52 + gap * PER_PAGE + (top > 0 ? 6 : 0)) + 6;
		Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
			page--;
			rebuildWidgets();
		}).bounds(x, y, 30, h).build());
		prev.active = page > 0;
		addRenderableWidget(Button.builder(Component.literal("Open folder"), b -> Util.getPlatform().openPath(SchematicMode.dir()))
				.bounds(x + 34, y, w - 68, h).build());
		Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
			page++;
			rebuildWidgets();
		}).bounds(x + w - 30, y, 30, h).build());
		next.active = page < pages - 1;
		int half = w / 2 - 2;
		addRenderableWidget(Button.builder(Component.literal("Sink ground: " + (SchematicMode.skipGround ? "§aON" : "§cOFF")), b -> {
			SchematicMode.skipGround = !SchematicMode.skipGround;
			rebuildWidgets();
		}).bounds(x, y + gap, half, h).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				"ON: the schematic's own ground lines up with the world's ground (the house stands on your grass)."
						+ " OFF: the whole thing goes on top of the block you look at, ground and all."))).build());
		addRenderableWidget(Button.builder(Component.literal("Replace: " + (SchematicMode.replace ? "§aON" : "§cOFF")), b -> {
			SchematicMode.replace = !SchematicMode.replace;
			rebuildWidgets();
		}).bounds(x + w - half, y + gap, half, h).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				"ON: blocks in the way get knocked out so it ends up exactly like the schematic (like pasting it)."
						+ " OFF: other blocks are left alone and those spots skipped."))).build());
		addRenderableWidget(Button.builder(Component.literal("Keep going: " + (SchematicMode.keepGoing ? "§aON" : "§cOFF")), b -> {
			SchematicMode.setKeepGoing(!SchematicMode.keepGoing);
			rebuildWidgets();
		}).bounds(x, y + gap * 2, half, h).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				"ON: when a build is done she goes on to the next file in this list (after the last one, back to the first),"
						+ " on open ground beside it. OFF: she stays and guards the one that's done."))).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
			files = false;
			rebuildWidgets();
		}).bounds(x + w - half, y + gap * 2, half, h).build());
	}

	/** The builds started and left for another one: click one to carry on with it, x to take it off the list. */
	private void buildsPage(int x, int y, int w, int h, int gap) {
		List<SchematicMode.Unfinished> left = SchematicMode.get().unfinished();
		int pages = Math.max(1, (left.size() + PER_PAGE - 1) / PER_PAGE);
		page = Math.clamp(page, 0, pages - 1);
		var me = minecraft == null ? null : minecraft.player;
		for (int i = page * PER_PAGE; i < Math.min(left.size(), (page + 1) * PER_PAGE); i++) {
			SchematicMode.Unfinished u = left.get(i);
			String tail = " §7" + (u.percent() >= 0 ? u.percent() + "%" : "?")
					+ (me == null ? "" : ", " + Math.round(Math.sqrt(me.blockPosition().distSqr(u.at()))) + " away");
			String name = u.file().replaceFirst("\\.[A-Za-z]+$", "");
			// (Cut the name down to fit the button.)
			if (font.width(name + tail) > w - 32) {
				while (name.length() > 4 && font.width(name + ".." + tail) > w - 32) name = name.substring(0, name.length() - 1);
				name += "..";
			}
			addRenderableWidget(Button.builder(Component.literal(name + tail), b -> {
				error = SchematicMode.get().resume(u);
				if (error == null) onClose();
			}).bounds(x, y, w - 24, h).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(u.file() + " at "
					+ u.at().toShortString() + ": " + (u.done() >= 0 ? u.done() + " of " + u.total() + " blocks in" : "not counted yet")
					+ ". Click: she goes back to it and carries on."))).build());
			addRenderableWidget(Button.builder(Component.literal("§cx"), b -> {
				SchematicMode.get().forget(u);
				rebuildWidgets();
			}).bounds(x + w - 20, y, 20, h).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
					"Take it off the list (what's built stays in the world)."))).build());
			y += gap;
		}
		y = Math.max(y, 52 + gap * PER_PAGE) + 6;
		Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
			page--;
			rebuildWidgets();
		}).bounds(x, y, 30, h).build());
		prev.active = page > 0;
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
			builds = false;
			files = true; // (back to Build Schematic, where the list is opened from now)
			rebuildWidgets();
		}).bounds(x + 34, y, w - 68, h).build());
		Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
			page++;
			rebuildWidgets();
		}).bounds(x + w - 30, y, 30, h).build());
		next.active = page < pages - 1;
	}

	/** Where the mini-games' heading goes (-1: not on this page). */
	private int gamesLabelY = -1;

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		if (!builds && !files && gamesLabelY >= 0) g.centeredText(font, "§7- Mini-games -", width / 2, gamesLabelY, 0xFFFFFFFF);
		g.centeredText(font, builds ? "Unfinished builds: pick one to carry on" : files ? "Build Schematic: pick a file" : "Autopilot",
				width / 2, 16, 0xFFFFD866);
		String now;
		BoxFillModule box = BoxFillModule.get();
		if (builds) {
			now = "";
		} else if (files) {
			now = SchematicMode.get().files().isEmpty() ? "No files yet: put .schem / .litematic / .schematic files in the folder" : "";
		} else if (SchematicMode.get().previewing()) {
			now = "On: §bpreview of " + SchematicMode.get().fileName();
		} else if (box.planMode()) {
			now = "On: §aBuild Schematic§r " + box.planName() + " " + box.planDone() + "/" + box.planTotal() + (box.paused() ? " §e(paused)" : "");
		} else if (box.on()) {
			now = "On: §aBox Fill" + (box.paused() ? " §e(paused)" : "");
		} else if (HumanAutopilot.isFarmOn()) {
			var f = dev.humanautopilot.modules.FarmModule.get();
			now = "On: §6Farm game§r " + f.ripeNow() + "/" + f.fieldSize() + " ripe, " + f.wins() + " wins";
		} else if (HumanAutopilot.isParkourOn()) {
			now = "On: §bParkour";
		} else if (HumanAutopilot.isLadderOn()) {
			now = "On: §dLadder game";
		} else if (HumanAutopilot.isOneBlockOn()) {
			now = "On: §2One Block";
		} else {
			now = "Everything is §coff";
		}
		g.centeredText(font, now, width / 2, 32, 0xFFFFFFFF);
		if (error != null) g.centeredText(font, "§cCouldn't load it: " + error, width / 2, height - 20, 0xFFFFFFFF);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
