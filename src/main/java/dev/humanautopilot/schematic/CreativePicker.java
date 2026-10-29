package dev.humanautopilot.schematic;

import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.mixin.ContainerScreenAccessor;
import dev.humanautopilot.mixin.CreativeScreenAccessor;
import dev.humanautopilot.survival.GuiMouse;
import dev.humanautopilot.util.Human;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Gets a block into the hotbar in creative the way a player does: E, type a bit of its name in the search, move the
 * cursor onto it, press the hotbar number, Escape. Nothing is spawned in for us.
 */
public final class CreativePicker {
	private static final CreativePicker INSTANCE = new CreativePicker();

	public static CreativePicker get() {
		return INSTANCE;
	}

	private enum Step { IDLE, OPEN, WAIT_SCREEN, SEARCH_TAB, CLEAR, TYPE, FIND, HOVER, PRESS, CHECK, CLOSE }

	private Step step = Step.IDLE;
	private Item item;
	private int hotbar;
	private String query = "";
	private int typed, attempts, scrolls;
	private long nextAt, startedAt, stepSince;
	private Slot slot;
	private double jx, jy;
	private final GuiMouse mouse = new GuiMouse();
	/**
	 * Couldn't get these from the menu, each until when (Long.MAX_VALUE: it isn't in the menu at all). A slip - the menu
	 * taking too long, closing under us - is only for a couple of minutes: in the user's fantasy build vines were left
	 * out for good after one fetch was left half done while he flew off for other blocks.
	 */
	private final java.util.Map<Item, Long> failed = new java.util.HashMap<>();
	private long lastCallAt = -1000, openedAt = -1000;
	private final long[] usedAt = new long[9];

	private CreativePicker() {}

	/** Couldn't find this one in the creative menu: the builder leaves its blocks out. */
	public boolean failed(Item it) {
		Long until = failed.get(it);
		return until != null && until > HumanAutopilot.ticks();
	}

	public void forgetFailures() {
		failed.clear();
	}

	/** Worth another go (a different kind of the same item: any potion of night vision, when the long one isn't there). */
	public void unfail(Item it) {
		failed.remove(it);
	}

	/** The builder is using this hotbar slot (so we fill the one used longest ago, not this one). */
	public void used(int s) {
		if (s >= 0 && s < 9) usedAt[s] = HumanAutopilot.ticks();
	}

	/** How many spots round us still need each item (the builder's count, set before a fetch). */
	private java.util.Map<Item, Integer> need = java.util.Map.of();
	/**
	 * While the menu's open anyway: the next blocks needed round here, like a player grabbing a few at once (a trip to
	 * the menu is ~2 s; another search while it's open about half that, and no stop in the building).
	 */
	private final java.util.ArrayDeque<Item> also = new java.util.ArrayDeque<>();
	private final java.util.Set<Integer> filledNow = new java.util.HashSet<>();

	public void needs(java.util.Map<Item, Integer> n) {
		need = n;
	}

	public boolean active() {
		return step != Step.IDLE;
	}

	public void cancel() {
		if (step == Step.IDLE) return;
		step = Step.IDLE;
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof CreativeModeInventoryScreen) mc.setScreen(null);
	}

	/** Our creative menu is open and we're working in it. */
	public boolean ownsScreen(Screen s) {
		return step.ordinal() >= Step.WAIT_SCREEN.ordinal() && s instanceof CreativeModeInventoryScreen;
	}

	/** In the world, with {@code it} not in the hotbar: open the menu (the rest happens in {@link #screenTick}). */
	public String fetch(LocalPlayer p, InputDriver in, Item it) {
		if (step == Step.IDLE || item != it || special != null) {
			special = null;
			specialQuery = null;
		}
		return fetch0(p, in, it);
	}

	/**
	 * One particular stack of an item (a potion of night vision: every potion is the same item), found by searching for
	 * {@code query}.
	 */
	public String fetch(LocalPlayer p, InputDriver in, Item it, String query, java.util.function.Predicate<ItemStack> match) {
		if (step == Step.IDLE || item != it || special == null) {
			special = match;
			specialQuery = query;
			step = Step.IDLE;
		}
		return fetch0(p, in, it);
	}

	public boolean fetchingSpecial() {
		return step != Step.IDLE && special != null;
	}

	private java.util.function.Predicate<ItemStack> special;
	private String specialQuery;

	private boolean matches(ItemStack st) {
		return special != null ? special.test(st) : st.is(item);
	}

	private String fetch0(LocalPlayer p, InputDriver in, Item it) {
		long now = HumanAutopilot.ticks();
		// (Not asked for half a second before the menu was even open - the block it was for got skipped, we flew off:
		// from the start, not on with the old clock.)
		if ((step == Step.OPEN || step == Step.WAIT_SCREEN) && now - lastCallAt > 10) step = Step.IDLE;
		lastCallAt = now;
		if (step == Step.IDLE || item != it) start(p, it, now);
		switch (step) {
			case OPEN -> {
				if (now >= nextAt) {
					in.click(InputDriver.opts().keyInventory);
					step = Step.WAIT_SCREEN;
					stepSince = now;
				}
			}
			case WAIT_SCREEN -> {
				if (now - stepSince > 20) { // didn't open: press it again
					step = Step.OPEN;
					nextAt = now + Human.rangeInt(2, 5);
				}
			}
			default -> {
				// Screen closed under us (or never ours): start over, a few times at most.
				if (++attempts > 3) giveUp("the menu kept closing");
				else step = Step.OPEN;
			}
		}
		return "getting " + name(it) + " from the creative menu";
	}

	private void start(LocalPlayer p, Item it, long now) {
		item = it;
		attempts = 0;
		startedAt = now;
		openedAt = -1000;
		nextAt = now + Human.rangeInt(2, 6); // a beat to decide
		step = Step.OPEN;
		// The first empty hotbar slot, else the one whose block is needed least round here (of those, the one used
		// longest ago). Just the one used longest ago threw out what was wanted again a minute later: in the fantasy
		// building's tree, 136 trips to the menu for 29 kinds of block (spruce wood 11 times).
		hotbar = -1;
		for (int i = 0; i < 9 && hotbar < 0; i++) if (p.getInventory().getItem(i).isEmpty()) hotbar = i;
		if (hotbar < 0) {
			hotbar = 0;
			int least = Integer.MAX_VALUE;
			for (int i = 0; i < 9; i++) {
				int n = keep(p, i, now);
				if (n < least || (n == least && usedAt[i] < usedAt[hotbar])) {
					least = n;
					hotbar = i;
				}
			}
		}
		String n = specialQuery != null ? specialQuery : name(it);
		query = n;
		HumanAutopilot.LOGGER.info("[schem] fetching {} into hotbar slot {}", n, hotbar + 1);
		dev.humanautopilot.modules.BoxFillModule.fetches++;
		also.clear();
		filledNow.clear();
		// (Furniture too, one of a kind: the castle's rooms, 59 kinds, were a trip to the menu for nearly every piece and
		// a quarter of the time in the menu. Each one only into a slot holding something needed less: see nextAlso.)
		if (special == null) need.entrySet().stream()
				.filter(e -> e.getKey() != it && e.getValue() >= 1 && !failed(e.getKey()) && !inHotbar(p, e.getKey()))
				.sorted((a, b) -> b.getValue() - a.getValue()).limit(3).forEach(e -> also.add(e.getKey()));
	}

	/**
	 * The hotbar slot a new block should go into (an empty one, else the one worth keeping least): for picking one off the
	 * build with a middle click, which puts it into the slot in hand.
	 */
	public int slotToFill(LocalPlayer p, java.util.Map<Item, Integer> n) {
		need = n;
		for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).isEmpty()) return i;
		long now = HumanAutopilot.ticks();
		int slot = 0, least = Integer.MAX_VALUE;
		for (int i = 0; i < 9; i++) {
			int k = keep(p, i, now);
			if (k < least || (k == least && usedAt[i] < usedAt[slot])) {
				least = k;
				slot = i;
			}
		}
		return slot;
	}

	/** How much the block in hotbar slot {@code i} is worth keeping: how many spots coming up need it, more if used lately. */
	private int keep(LocalPlayer p, int i, long now) {
		ItemStack st = p.getInventory().getItem(i);
		if (st.isEmpty()) return -1;
		int n = need.getOrDefault(st.getItem(), 0);
		if (n <= 0) return n;
		// Used in the last 10 s and still wanted: the hand's on it (the newest one went straight back out again).
		if (now - usedAt[i] < 200) return n + 2000;
		// (And in the last minute, still wanted round here: kept over a bigger count of something not touched yet. The
		// user's fantasy build: half of 658 trips to the menu in 90 minutes were for a block that had been in the hotbar
		// under 3 minutes before - stone brick slabs 56 times - pushed out for the next count up and fetched straight back.)
		return now - usedAt[i] < 1200 ? n + 1000 : n;
	}

	private static boolean inHotbar(LocalPlayer p, Item it) {
		for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).is(it)) return true;
		return false;
	}

	/**
	 * The next of {@link #also} worth taking now, with {@link #hotbar} set to its slot (one not filled this time, holding
	 * what's needed least round here, and less than this one is), or null.
	 */
	private Item nextAlso(LocalPlayer p) {
		while (!also.isEmpty()) {
			Item n = also.poll();
			if (inHotbar(p, n)) continue;
			int slot = -1, least = Integer.MAX_VALUE;
			long now = HumanAutopilot.ticks();
			for (int i = 0; i < 9; i++) {
				if (filledNow.contains(i)) continue;
				int k = keep(p, i, now);
				if (k < least || (k == least && slot >= 0 && usedAt[i] < usedAt[slot])) {
					least = k;
					slot = i;
				}
			}
			// (Not worth what it would push out: an extra only takes a slot not used in the last minute, from something
			// needed well under half as much - a near tie swapped them back and forth.)
			if (slot < 0 || least >= 1000 || 2 * least + 2 > need.getOrDefault(n, 0)) continue;
			hotbar = slot;
			return n;
		}
		return null;
	}

	private void giveUp(String why) {
		giveUp(why, false);
	}

	/** {@code notThere}: it isn't in the menu at all (no search hit): for good. Anything else: a slip, try again later. */
	private void giveUp(String why, boolean notThere) {
		HumanAutopilot.LOGGER.info("[schem] couldn't get {} from the creative menu: {}", special != null ? specialQuery : name(item), why);
		failed.put(item, notThere ? Long.MAX_VALUE : HumanAutopilot.ticks() + 2400);
		step = Step.IDLE;
		if (special == null && notThere) HumanAutopilot.message("Can't find §e" + name(item) + "§r in the creative menu: leaving those blocks out.");
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof CreativeModeInventoryScreen) mc.setScreen(null);
	}

	/** Every rendered frame while our menu is open: the cursor glides. */
	public void frame() {
		mouse.frame();
	}

	/** Each tick while our creative menu is open. */
	public void screenTick(Minecraft mc) {
		if (!(mc.screen instanceof CreativeModeInventoryScreen scr) || mc.player == null) return;
		long now = HumanAutopilot.ticks();
		if (now - Math.max(startedAt, openedAt) > 400) {
			giveUp("took too long");
			return;
		}
		if (now < nextAt) return;
		var acc = (CreativeScreenAccessor) scr;
		var cacc = (ContainerScreenAccessor) scr;
		EditBox box = acc.humanautopilot$searchBox();
		switch (step) {
			case WAIT_SCREEN -> {
				openedAt = now;
				mouse.reset();
				step = Step.SEARCH_TAB;
				nextAt = now + Human.rangeInt(3, 7); // take in the screen
			}
			case SEARCH_TAB -> {
				if (box != null && box.isVisible()) {
					step = Step.CLEAR;
				} else {
					// Not on the search tab: T jumps there (a vanilla shortcut).
					press(scr, mc.options.keyChat);
					nextAt = now + Human.rangeInt(2, 4);
				}
			}
			case CLEAR -> {
				if (box != null && !box.getValue().isEmpty()) {
					scr.keyPressed(new KeyEvent(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL));
					scr.keyPressed(new KeyEvent(GLFW.GLFW_KEY_BACKSPACE, 0, 0));
					nextAt = now + Human.rangeInt(2, 4);
				}
				typed = 0;
				step = Step.TYPE;
			}
			case TYPE -> {
				// Type until it shows up (a few letters usually do it), or the whole name.
				if ((typed >= 3 && visibleSlot(scr) != null) || typed >= query.length()) {
					step = Step.FIND;
					scrolls = 0;
					nextAt = now + Human.rangeInt(2, 5); // spot it
					return;
				}
				typeChar(scr, query.charAt(typed++));
				nextAt = now + Human.rangeInt(1, 3);
			}
			case FIND -> {
				Slot s = visibleSlot(scr);
				if (s != null) {
					slot = s;
					jx = Human.gauss(-4, 4);
					jy = Human.gauss(-4, 4);
					step = Step.HOVER;
					stepSince = now;
					return;
				}
				int idx = indexOf(scr);
				if (idx < 0) {
					giveUp("no \"" + query + "\" in the search", true);
					return;
				}
				// Further down the list: roll the wheel over the grid until it's in view.
				int rows = (scr.getMenu().items.size() + 8) / 9;
				int top = Math.round(acc.humanautopilot$scrollOffs() * Math.max(0, rows - 5));
				int row = idx / 9;
				double gx = cacc.humanautopilot$leftPos() + 9 + 81, gy = cacc.humanautopilot$topPos() + 18 + 45;
				mouse.moveTo(gx + jx, gy + jy);
				if (!mouse.arrived() || ++scrolls > 60) {
					if (scrolls > 60) giveUp("couldn't scroll to it", true);
					return;
				}
				scr.mouseScrolled(mouse.x(), mouse.y(), 0, row < top ? 1 : -1);
				nextAt = now + 1;
			}
			case HOVER -> {
				double x = cacc.humanautopilot$leftPos() + slot.x + 8 + jx, y = cacc.humanautopilot$topPos() + slot.y + 8 + jy;
				mouse.moveTo(x, y);
				if (mouse.arrived() && cacc.humanautopilot$hoveredSlot() == slot) {
					step = Step.PRESS;
					nextAt = now + Human.rangeInt(1, 3);
				} else if (now - stepSince > 60) {
					giveUp("couldn't get the cursor onto it");
				}
			}
			case PRESS -> {
				if (cacc.humanautopilot$hoveredSlot() != slot) {
					step = Step.HOVER;
					stepSince = now;
					return;
				}
				press(scr, mc.options.keyHotbarSlots[hotbar]);
				step = Step.CHECK;
				nextAt = now + Human.rangeInt(2, 4);
			}
			case CHECK -> {
				ItemStack got = mc.player.getInventory().getItem(hotbar);
				if (matches(got)) {
					usedAt[hotbar] = now; // (just filled: not the next one to go)
					filledNow.add(hotbar);
					HumanAutopilot.LOGGER.info("[schem] got {} in {} ticks", special != null ? specialQuery : name(item), now - startedAt);
					Item next = nextAlso(mc.player);
					if (next != null) {
						// Another one while we're here: a new search.
						item = next;
						query = name(next);
						attempts = 0;
						startedAt = now;
						step = Step.CLEAR;
						nextAt = now + Human.rangeInt(3, 6);
						HumanAutopilot.LOGGER.info("[schem] while the menu's open: fetching {} into hotbar slot {}", query, hotbar + 1);
						dev.humanautopilot.modules.BoxFillModule.fetches++;
					} else {
						step = Step.CLOSE;
						nextAt = now + Human.rangeInt(2, 5);
					}
				} else if (++attempts > 3) {
					giveUp("the number key didn't put it in the hotbar");
				} else {
					step = Step.HOVER;
					stepSince = now;
				}
			}
			case CLOSE -> {
				scr.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
				if (mc.screen == scr) mc.setScreen(null);
				step = Step.IDLE;
			}
			default -> {
			}
		}
	}

	/** One letter, the way the keyboard sends it: the key, then the character. */
	private static void typeChar(CreativeModeInventoryScreen scr, char c) {
		int key = c >= 'a' && c <= 'z' ? GLFW.GLFW_KEY_A + (c - 'a') : c == ' ' ? GLFW.GLFW_KEY_SPACE : GLFW.GLFW_KEY_RIGHT_SHIFT;
		// (A digit's own key over a slot would swap that slot into the hotbar: those go as plain characters.)
		scr.keyPressed(new KeyEvent(key, 0, 0));
		scr.charTyped(new CharacterEvent(c));
	}

	private static void press(Screen scr, KeyMapping km) {
		scr.keyPressed(new KeyEvent(KeyMappingHelper.getBoundKeyOf(km).getValue(), 0, 0));
	}

	/** A slot of the item grid (the first 45) showing our item right now. */
	private Slot visibleSlot(CreativeModeInventoryScreen scr) {
		var slots = scr.getMenu().slots;
		for (int i = 0; i < Math.min(45, slots.size()); i++) {
			Slot s = slots.get(i);
			if (matches(s.getItem())) return s;
		}
		return null;
	}

	private int indexOf(CreativeModeInventoryScreen scr) {
		var items = scr.getMenu().items;
		for (int i = 0; i < items.size(); i++) if (matches(items.get(i))) return i;
		return -1;
	}

	static String name(Item it) {
		return new ItemStack(it).getHoverName().getString().toLowerCase(Locale.ROOT);
	}
}
