package dev.humanautopilot.survival;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.util.Human;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Opens a real inventory / crafting table / furnace screen (E key or right-click) and works in it
 * one slot click at a time with human pauses between clicks, then closes it.
 */
public final class ScreenWorker {
	public enum Kind { INVENTORY, TABLE, FURNACE, CHEST }

	public enum Result { RUNNING, DONE, FAILED }

	private enum Phase { OPEN, WORK }

	public interface Job {
		Result step(Ctx c);

		String describe();
	}

	private Kind kind;
	private BlockPos block;
	private Job job;
	private Phase phase;
	private long nextActionAt;
	private long openDeadline;
	private int openAttempts;
	private long openStarted = -1;
	private Vec3 aimPoint;
	private double faceYaw = Double.NaN;

	/** Turn to this direction before opening (e.g. face backwards so tossed junk lands behind us). */
	public void faceFirst(double yaw) {
		faceYaw = yaw;
	}
	private Result outcome;
	private String failReason = "";
	private String lastJob = "";

	/** What the last finished job was ("crafting stone pickaxe", "smelting"...). */
	public String lastJob() {
		return lastJob;
	}

	public void start(Kind kind, BlockPos block, Job job) {
		this.kind = kind;
		this.block = block;
		this.job = job;
		phase = Phase.OPEN;
		openAttempts = 0;
		pending = null;
		pendingPoint = null;
		pendingKey = null;
		pendingFinish = null;
		mouse.reset();
		faceYaw = Double.NaN;
		openStarted = -1;
		openDeadline = 0;
		outcome = null;
		failReason = "";
		aimPoint = null;
	}

	public boolean active() {
		return job != null;
	}

	/** Result of the last finished job (once), or null while running / idle. */
	public Result takeOutcome() {
		Result r = outcome;
		outcome = null;
		return r;
	}

	public String failReason() {
		return failReason;
	}

	public String describe() {
		return job == null ? "" : job.describe();
	}

	public boolean owns(Screen s) {
		if (job == null) return false;
		return switch (kind) {
			case INVENTORY -> s instanceof InventoryScreen;
			case TABLE -> s instanceof CraftingScreen;
			case FURNACE -> s instanceof AbstractFurnaceScreen<?>;
			case CHEST -> s instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen;
		};
	}

	private void finish(Result r, String reason) {
		pending = null;
		if (job != null) lastJob = job.describe();
		outcome = r;
		failReason = reason;
		job = null;
	}

	public void abort(String reason) {
		Minecraft mc = Minecraft.getInstance();
		if (job != null && mc.screen != null && owns(mc.screen) && mc.player != null) mc.player.closeContainer();
		if (job != null) finish(Result.FAILED, reason);
	}

	/** World tick (no screen open): look at the block and press the key that opens it. */
	public void worldTick(LocalPlayer p, InputDriver in, AimController aim) {
		if (job == null) return;
		long now = HumanAutopilot.ticks();
		if (phase == Phase.WORK) {
			finish(Result.FAILED, "screen closed");
			return;
		}
		if (block != null) {
			if (aimPoint == null || (now - openStarted) % 20 == 19) aimPoint = facePoint(p, block, (int) ((now - Math.max(openStarted, 0)) / 20));
			Vec3 pt = aimPoint;
			aim.lookAt(block, () -> pt);
			if (p.getEyePosition().distanceTo(Vec3.atCenterOf(block)) > p.blockInteractionRange() + 0.5) {
				finish(Result.FAILED, "too far from the " + kind.name().toLowerCase());
				return;
			}
		}
		if (openStarted < 0) openStarted = now;
		if (now - openStarted > 60 && now >= openDeadline) { // never give up while a click is still being answered
			finish(Result.FAILED, "couldn't get at the " + kind.name().toLowerCase());
			return;
		}
		if (now < openDeadline) return; // waiting for the screen to appear
		// Stand still first, like a player: opening a menu mid-stride by a ledge lets the body slide off it.
		if (!p.onGround() || p.getDeltaMovement().horizontalDistance() > 0.03) return;
		if (openAttempts >= 4) {
			finish(Result.FAILED, "couldn't open the " + kind.name().toLowerCase());
			return;
		}
		if (block == null && !Double.isNaN(faceYaw)) {
			aim.lookDirection("face-first", faceYaw, 15);
			if (aim.error() > 8) return;
		}
		if (block == null) {
			in.click(InputDriver.opts().keyInventory);
			openAttempts++;
			openDeadline = now + 20;
		} else {
			HitResult hr = Minecraft.getInstance().hitResult;
			if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK && bhr.getBlockPos().equals(block)
					&& aim.error() < 4) {
				in.click(InputDriver.opts().keyUse);
				openAttempts++;
				openDeadline = now + 20;
			}
		}
	}

	/** A point on the n-th most visible face of the block (the face toward us first), slightly jittered. */
	private static Vec3 facePoint(LocalPlayer p, BlockPos block, int n) {
		Vec3 c = Vec3.atCenterOf(block);
		Vec3 toEye = p.getEyePosition().subtract(c);
		java.util.List<net.minecraft.core.Direction> faces = new java.util.ArrayList<>(java.util.List.of(net.minecraft.core.Direction.values()));
		faces.sort((a, b) -> Double.compare(
				-(a.getStepX() * toEye.x + a.getStepY() * toEye.y + a.getStepZ() * toEye.z),
				-(b.getStepX() * toEye.x + b.getStepY() * toEye.y + b.getStepZ() * toEye.z)));
		net.minecraft.core.Direction d = faces.get(Math.floorMod(n, 3));
		return c.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45)
				.add(Human.range(-0.15, 0.15) * (d.getStepX() == 0 ? 1 : 0), Human.range(-0.15, 0.15) * (d.getStepY() == 0 ? 1 : 0),
						Human.range(-0.15, 0.15) * (d.getStepZ() == 0 ? 1 : 0));
	}

	/** Called every tick while one of our screens is open. */
	public void screenTick(Minecraft mc) {
		if (job == null) return;
		LocalPlayer p = mc.player;
		long now = HumanAutopilot.ticks();
		if (phase == Phase.OPEN) {
			phase = Phase.WORK;
			nextActionAt = now + Human.reaction(); // glance at the screen before clicking
			return;
		}
		if (p.hurtTime > 0) {
			// Something is hitting us: close up and deal with it.
			p.closeContainer();
			finish(Result.FAILED, "got hurt");
			return;
		}
		// A click is waiting: move the real cursor onto the slot first, then click there.
		if (pending != null || pendingPoint != null || pendingKey != null) {
			if (!performPending(mc, now)) return;
			if (pendingFinish != null) {
				p.closeContainer();
				finish(pendingFinish, pendingReason);
				pendingFinish = null;
			}
			return;
		}
		if (now < nextActionAt) return;
		Ctx c = new Ctx(mc, p, p.containerMenu, kind);
		Result r = job.step(c);
		if (c.queued != null || c.queuedPoint != null || c.queuedKey != null) {
			pending = c.queued;
			pendingPoint = c.queuedPoint;
			pendingKey = c.queuedKey;
			pendingSince = now;
			arrivedAt = -1;
			jitterX = Human.range(-3, 3);
			jitterY = Human.range(-3, 3);
			if (r != Result.RUNNING) {
				pendingFinish = r; // finish once this last click has been made
				pendingReason = c.failReason;
			}
			return;
		}
		if (r != Result.RUNNING) {
			String reason = c.failReason;
			nextActionAt = now + Human.actionDelay();
			p.closeContainer();
			finish(r, reason);
		}
	}

	// ------------------------------------------------------------------ real cursor clicks

	record PendingClick(int slot, int button, ContainerInput type) {}

	/** A click on a spot of the screen that isn't a slot: the recipe book's button, a recipe, its search box. */
	record PendingPoint(double x, double y, int button, int mods, String what) {}

	/** A key or a typed character (codepoint >= 0) for whatever has focus, e.g. the recipe book's search box. */
	record PendingKey(int key, int mods, int codepoint) {}

	private PendingPoint pendingPoint;
	private PendingKey pendingKey;

	/** Cursor to a point (and settle), then click it; or type a key. True once done. */
	private boolean performOther(Minecraft mc, long now) {
		Screen scr = mc.screen;
		if (scr == null) {
			pendingPoint = null;
			pendingKey = null;
			return true;
		}
		if (pendingKey != null) {
			PendingKey k = pendingKey;
			if (k.codepoint() >= 0) scr.charTyped(new net.minecraft.client.input.CharacterEvent(k.codepoint()));
			else scr.keyPressed(new net.minecraft.client.input.KeyEvent(k.key(), 0, k.mods()));
			pendingKey = null;
			nextActionAt = now + Human.rangeInt(1, 3); // typing: quicker than clicking about
			return true;
		}
		PendingPoint pp = pendingPoint;
		mouse.moveTo(pp.x() + jitterX * 0.5, pp.y() + jitterY * 0.5);
		boolean timedOut = now - pendingSince > 60;
		if (!mouse.arrived() && !timedOut) return false;
		if (arrivedAt < 0) arrivedAt = now + Human.rangeInt(1, 3);
		if (now < arrivedAt && !timedOut) return false;
		var ev = new net.minecraft.client.input.MouseButtonEvent(mouse.x(), mouse.y(), new net.minecraft.client.input.MouseButtonInfo(pp.button(), pp.mods()));
		scr.mouseClicked(ev, false);
		scr.mouseReleased(ev);
		HumanAutopilot.LOGGER.info("[gui] clicked {}{}", pp.what(), pp.mods() != 0 ? " (shift)" : "");
		nextActionAt = now + Math.max(2, Human.actionDelay());
		lastClickSlot = -1;
		pendingPoint = null;
		return true;
	}

	public final GuiMouse mouse = new GuiMouse();
	private PendingClick pending;
	private Result pendingFinish;
	private String pendingReason = "";
	private long pendingSince, arrivedAt = -1;
	private double jitterX, jitterY;
	private int lastClickSlot = -1;

	/** Per-frame: glide the cursor (from the mouse-handler frame hook). */
	public void frame() {
		if (job != null || pending != null) mouse.frame();
	}

	/**
	 * Move the cursor onto the pending slot; once it has settled there, click it through the
	 * screen's own mouse / key handling. Returns true when the click has been made.
	 */
	private boolean performPending(Minecraft mc, long now) {
		if (pending == null) return performOther(mc, now);
		PendingClick pc = pending;
		if (!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> scr)) {
			// Not a slot screen (shouldn't happen): fall back to a plain slot click.
			mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, pc.slot(), pc.button(), pc.type(), mc.player);
			pending = null;
			return true;
		}
		var acc = (dev.humanautopilot.mixin.ContainerScreenAccessor) scr;
		var slot = mc.player.containerMenu.getSlot(pc.slot());
		double gx = acc.humanautopilot$leftPos() + slot.x + 8 + jitterX;
		double gy = acc.humanautopilot$topPos() + slot.y + 8 + jitterY;
		mouse.moveTo(gx, gy);
		boolean timedOut = now - pendingSince > 60;
		if (!mouse.arrived() && !timedOut) return false;
		if (arrivedAt < 0) arrivedAt = now + Human.rangeInt(1, 3); // brief settle on the slot, like a hand
		if (now < arrivedAt && !timedOut) return false;
		// Key-based actions act on the hovered slot, which the screen updates from the cursor while rendering.
		boolean keyAction = pc.type() == ContainerInput.THROW || pc.type() == ContainerInput.SWAP;
		if (keyAction && acc.humanautopilot$hoveredSlot() != slot && !timedOut) return false;
		if (keyAction && acc.humanautopilot$hoveredSlot() != slot) {
			// Never got onto the slot: pressing the key now would throw / swap whatever is under the cursor.
			HumanAutopilot.LOGGER.info("[gui] skipped {} on slot {}: cursor never got there", pc.type(), pc.slot());
			nextActionAt = now + Human.actionDelay();
			pending = null;
			return true;
		}

		if (keyAction) {
			HumanAutopilot.LOGGER.info("[gui] {} {} (slot {}), hovered={}{}", pc.type(), slot.getItem().getHoverName().getString(), pc.slot(),
					acc.humanautopilot$hoveredSlot() == null ? "none" : acc.humanautopilot$hoveredSlot().index, timedOut ? " TIMED OUT" : "");
		}
		double mx = mouse.x(), my = mouse.y();
		switch (pc.type()) {
			case PICKUP -> {
				var ev = new net.minecraft.client.input.MouseButtonEvent(mx, my, new net.minecraft.client.input.MouseButtonInfo(pc.button(), 0));
				scr.mouseClicked(ev, false);
				scr.mouseReleased(ev);
			}
			case QUICK_MOVE -> {
				var ev = new net.minecraft.client.input.MouseButtonEvent(mx, my,
						new net.minecraft.client.input.MouseButtonInfo(0, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT));
				scr.mouseClicked(ev, false);
				scr.mouseReleased(ev);
			}
			case THROW -> {
				Inv.noteTossed(slot.getItem().getItem(), mc.player.position());
				int key = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.getBoundKeyOf(mc.options.keyDrop).getValue();
				scr.keyPressed(new net.minecraft.client.input.KeyEvent(key, 0, pc.button() == 1 ? org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL : 0));
			}
			case SWAP -> {
				int key = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.getBoundKeyOf(mc.options.keyHotbarSlots[pc.button()]).getValue();
				scr.keyPressed(new net.minecraft.client.input.KeyEvent(key, 0, 0));
			}
			default -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, pc.slot(), pc.button(), pc.type(), mc.player);
		}
		// Same slot twice in quick succession would count as a double-click; leave a human gap.
		int gap = Math.max(2, Human.actionDelay());
		if (pc.slot() == lastClickSlot) gap = Math.max(gap, 6);
		lastClickSlot = pc.slot();
		nextActionAt = now + gap;
		pending = null;
		return true;
	}

	// ------------------------------------------------------------------ click context

	public static final class Ctx {
		public final Minecraft mc;
		public final LocalPlayer p;
		public final AbstractContainerMenu menu;
		public final Kind kind;
		boolean clicked;
		PendingClick queued;
		PendingPoint queuedPoint;
		PendingKey queuedKey;
		String failReason = "";

		Ctx(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu, Kind kind) {
			this.mc = mc;
			this.p = p;
			this.menu = menu;
			this.kind = kind;
		}

		/** Queue a click on a slot. The worker moves the cursor there and clicks for real. */
		public void click(int slot, int button, ContainerInput type) {
			queued = new PendingClick(slot, button, type);
			clicked = true;
		}

		public Result fail(String why) {
			failReason = why;
			return Result.FAILED;
		}

		/** Queue a click on a screen spot (GUI coordinates) that isn't a slot. */
		public void clickAt(double x, double y, int button, int mods, String what) {
			queuedPoint = new PendingPoint(x, y, button, mods, what);
			clicked = true;
		}

		/** Queue a key press (with modifiers) for whatever has focus. */
		public void key(int key, int mods) {
			queuedKey = new PendingKey(key, mods, -1);
			clicked = true;
		}

		/** Queue one typed character. */
		public void type(char ch) {
			queuedKey = new PendingKey(0, 0, ch);
			clicked = true;
		}

		public ItemStack carried() {
			return menu.getCarried();
		}

		public ItemStack at(int slot) {
			return menu.getSlot(slot).getItem();
		}

		/** Menu slot of crafting-grid cell {@code i} (0..8, 3x3 row-major). */
		public int gridSlot(int i) {
			if (kind == Kind.INVENTORY) return 1 + (i / 3) * 2 + (i % 3);
			return 1 + i;
		}

		public int resultSlot() {
			return kind == Kind.FURNACE ? 2 : 0;
		}

		/** Player inventory slots in this menu (main + hotbar, no armor/offhand). */
		public int sourceStart() {
			return switch (kind) {
				case INVENTORY -> 9;
				case TABLE -> 10;
				case FURNACE -> 3;
				case CHEST -> menu.slots.size() - 36;
			};
		}

		public int sourceEnd() {
			return sourceStart() + 36;
		}

		public int find(Predicate<ItemStack> what) {
			int best = -1, bestCount = -1;
			for (int i = sourceStart(); i < sourceEnd(); i++) {
				ItemStack s = at(i);
				if (!s.isEmpty() && what.test(s) && s.getCount() > bestCount) {
					best = i;
					bestCount = s.getCount();
				}
			}
			return best;
		}

		public int findEmpty() {
			for (int i = sourceStart(); i < sourceEnd(); i++) if (at(i).isEmpty()) return i;
			return -1;
		}
	}

	// ------------------------------------------------------------------ jobs

	/**
	 * Puts {@code amount} items matching {@code what} into {@code target} one right-click at a time:
	 * pick up the stack, right-click the slot, put the rest back. Shared by crafting and smelting.
	 */
	static final class Placement {
		private final Predicate<ItemStack> what;
		private final List<Integer> targets;
		private int source = -1;
		private final boolean wholeStack;

		Placement(Predicate<ItemStack> what, List<Integer> targets) {
			this(what, targets, false);
		}

		/** wholeStack: drop the whole carried stack into each target (left click) instead of one at a time. */
		Placement(Predicate<ItemStack> what, List<Integer> targets, boolean wholeStack) {
			this.what = what;
			this.targets = new ArrayList<>(targets);
			this.wholeStack = wholeStack;
		}

		/** One click per call. Returns DONE when everything is placed and the cursor is empty again. */
		Result step(Ctx c) {
			ItemStack carried = c.carried();
			if (carried.isEmpty()) {
				if (targets.isEmpty()) return Result.DONE;
				source = c.find(what);
				if (source < 0) return c.fail("missing ingredient");
				c.click(source, 0, ContainerInput.PICKUP);
				return Result.RUNNING;
			}
			if (what.test(carried) && !targets.isEmpty()) {
				c.click(targets.remove(0), wholeStack ? 0 : 1, ContainerInput.PICKUP);
				return Result.RUNNING;
			}
			// Put the rest of the stack back where it came from.
			int back = source >= 0 && c.at(source).isEmpty() ? source : c.findEmpty();
			if (back < 0) back = c.find(s -> ItemStack.isSameItemSameComponents(s, carried));
			if (back < 0) return c.fail("no room to put items back");
			c.click(back, 0, ContainerInput.PICKUP);
			return Result.RUNNING;
		}
	}

	public static final class CraftJob implements Job {
		private final Recipe recipe;
		private int remaining;
		private final List<Predicate<ItemStack>> groups = new ArrayList<>();
		private int group;
		private Placement placement;
		private int waitTicks;
		private boolean placing = true;

		public CraftJob(Recipe recipe, int times) {
			this.recipe = recipe;
			this.remaining = Math.max(1, times);
			for (Predicate<ItemStack> g : recipe.grid()) if (g != null && !groups.contains(g)) groups.add(g);
		}

		public String describe() {
			return "crafting " + recipe.name();
		}

		// The way a player crafts: click the recipe in the recipe book (shift for a whole stack), then shift-click
		// the result. Laying it out by hand only for recipes the book doesn't have yet.
		private enum Book { TRY, PLACED, NO }

		private Book book = Book.TRY;
		private int swapStage, swapSlot;

		/** The hotbar slot (menu index) holding a worse tool of the same kind as {@code made}, or -1. */
		private static int oldToolSlot(Ctx c, ItemStack made) {
			int kind = Inv.toolKind(made), tier = Inv.toolTierOf(made);
			if (kind == 0) return -1;
			int hotbar = c.sourceStart() + 27, best = -1, bestTier = tier;
			for (int i = hotbar; i < hotbar + 9; i++) {
				ItemStack s = c.at(i);
				if (Inv.toolKind(s) == kind && Inv.toolTierOf(s) < bestTier) {
					bestTier = Inv.toolTierOf(s);
					best = i;
				}
			}
			return best;
		}
		private int bookStage, bookClicks, openTries, typed, waitSearch;
		private String term;

		private Result bookStep(Ctx c) {
			if (!(c.mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen<?> scr)) return noBook();
			var sacc = (dev.humanautopilot.mixin.RecipeBookScreenAccessor) scr;
			var comp = sacc.humanautopilot$book();
			if (comp == null) return noBook();
			if (!comp.isVisible()) {
				// The green book next to the grid: open it (it stays open next time, like the player's).
				if (openTries++ >= 2) return noBook();
				var pos = sacc.humanautopilot$bookButtonPos();
				c.clickAt(pos.x() + 10, pos.y() + 9, 0, 0, "the recipe book");
				return Result.RUNNING;
			}
			var cacc = (dev.humanautopilot.mixin.RecipeBookComponentAccessor) comp;
			net.minecraft.client.gui.screens.recipebook.RecipeButton b = findButton(cacc);
			if (b != null) {
				if (!b.getCollection().isCraftable(b.getCurrentRecipe())) return noBook(); // short of something: hand-laying will say what
				boolean stack = recipe == Recipe.PLANKS; // a whole stack of logs at once
				c.clickAt(b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0, 0,
						stack ? org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT : 0, "the " + recipe.name() + " recipe");
				if (stack || ++bookClicks >= remaining) {
					book = Book.PLACED;
					placing = false;
					waitTicks = 0;
				}
				return Result.RUNNING;
			}
			// Not on this page: the "all" tab, then type the name in the search box, the way a player looks it up.
			if (bookStage == 0) {
				bookStage = 1;
				var tabs = cacc.humanautopilot$tabs();
				if (!tabs.isEmpty() && tabs.get(0).visible && cacc.humanautopilot$selectedTab() != tabs.get(0)) {
					var tab = tabs.get(0);
					c.clickAt(tab.getX() + tab.getWidth() / 2.0, tab.getY() + tab.getHeight() / 2.0, 0, 0, "the recipe book's search tab");
					return Result.RUNNING;
				}
			}
			if (bookStage == 1) {
				var box = cacc.humanautopilot$searchBox();
				if (term == null) term = searchTerm(recipe);
				if (box == null || term == null) return noBook();
				bookStage = 2;
				c.clickAt(box.getX() + Math.min(30, box.getWidth() / 2.0), box.getY() + box.getHeight() / 2.0, 0, 0, "the search box");
				return Result.RUNNING;
			}
			if (bookStage == 2) {
				bookStage = 3;
				typed = 0;
				c.key(org.lwjgl.glfw.GLFW.GLFW_KEY_A, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL); // select what's there, typing replaces it
				return Result.RUNNING;
			}
			if (bookStage == 3) {
				if (typed < term.length()) {
					c.type(term.charAt(typed++));
					return Result.RUNNING;
				}
				bookStage = 4;
				waitSearch = 0;
				return Result.RUNNING;
			}
			if (++waitSearch < 6) return Result.RUNNING; // the list filters as we type: give it a moment
			HumanAutopilot.LOGGER.info("[gui] {} isn't in the recipe book (not unlocked yet?): laying it out by hand", recipe.name());
			return noBook();
		}

		private Result noBook() {
			book = Book.NO;
			return null;
		}

		private net.minecraft.client.gui.screens.recipebook.RecipeButton findButton(dev.humanautopilot.mixin.RecipeBookComponentAccessor cacc) {
			var page = cacc.humanautopilot$page();
			if (page == null) return null;
			for (var b : ((dev.humanautopilot.mixin.RecipeBookPageAccessor) page).humanautopilot$buttons()) {
				if (b.visible && recipe.result().test(b.getDisplayStack())) return b;
			}
			return null;
		}

		/** What a player would type to find it: its name as the game shows it ("planks", "bed" for the any-kind ones). */
		private static String searchTerm(Recipe r) {
			net.minecraft.world.item.Item item = r.item();
			if (item == null) return null;
			String name = new ItemStack(item).getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
			if (r == Recipe.PLANKS || r.name().equals("bed")) {
				String[] words = name.split(" ");
				return words[words.length - 1];
			}
			return name;
		}

		public Result step(Ctx c) {
			if (book == Book.TRY) {
				Result r = bookStep(c);
				if (r != null) return r;
			}
			for (int guard = 0; guard < 20; guard++) {
				if (placing) {
					if (placement == null) {
						if (group >= groups.size()) {
							placing = false;
							waitTicks = 0;
							continue;
						}
						Predicate<ItemStack> g = groups.get(group);
						List<Integer> targets = new ArrayList<>();
						for (int i = 0; i < 9; i++) {
							if (recipe.grid()[i] == g && c.at(c.gridSlot(i)).isEmpty()) targets.add(c.gridSlot(i));
						}
						// Planks: drop the whole log stack in and craft them all at once, like a player does.
						placement = new Placement(g, targets, recipe == Recipe.PLANKS);
					}
					Result r = placement.step(c);
					if (r == Result.FAILED) return r;
					if (r == Result.DONE) {
						placement = null;
						group++;
						continue;
					}
					return Result.RUNNING;
				}
				// A new tool goes where the old one was, like the recorded player does it: pick it up, click it onto the
				// old tool's hotbar slot (they swap), put the old one in the bag (it gets thrown out later).
				if (swapStage == 1) {
					if (c.carried().isEmpty()) return Result.DONE; // went in cleanly (the slot was empty after all)
					int empty = -1;
					for (int i = c.sourceStart(); i < c.sourceStart() + 27; i++) if (c.at(i).isEmpty()) { empty = i; break; }
					if (empty < 0) empty = c.findEmpty();
					if (empty < 0) return Result.DONE;
					c.click(empty, 0, ContainerInput.PICKUP);
					swapStage = 2;
					return Result.RUNNING;
				}
				if (swapStage == 2) return Result.DONE;
				if (swapStage == 3) {
					// The new tool is on the cursor: onto the old tool's slot.
					if (c.carried().isEmpty()) return Result.DONE;
					c.click(swapSlot, 0, ContainerInput.PICKUP);
					swapStage = 1;
					return Result.RUNNING;
				}
				// Wait for the server to show the result, then take it.
				if (c.at(0).isEmpty()) {
					if (++waitTicks > 40) return c.fail("recipe didn't produce " + recipe.name());
					return Result.RUNNING;
				}
				if (book == Book.PLACED || remaining == 1) {
					int old = oldToolSlot(c, c.at(0));
					if (old >= 0 && c.at(0).getCount() == 1) {
						swapSlot = old;
						swapStage = 3;
						c.click(0, 0, ContainerInput.PICKUP);
						return Result.RUNNING;
					}
				}
				c.click(0, 0, ContainerInput.QUICK_MOVE);
				if (book == Book.PLACED) return Result.DONE; // the shift-click makes every set the book laid out
				if (recipe == Recipe.PLANKS) remaining = 1; // shift-click already made them all
				if (--remaining > 0) {
					placing = true;
					group = 0;
					return Result.RUNNING;
				}
				return Result.DONE;
			}
			return Result.RUNNING;
		}
	}

	/** One recipe, made {@code times} times. */
	public record Order(Recipe recipe, int times) {}

	/**
	 * One sitting at the grid for everything worth making now, the way a player does it: the sticks for the whole
	 * lot first (logs into planks if needed), then the thing we came for, then the rest of the list we have the stuff
	 * for. Anything we turn out to be short of is left off; only the first order is a must.
	 */
	public static final class BatchCraftJob implements Job {
		private final List<Order> orders;
		private int next, prep;
		private CraftJob current;
		private boolean currentIsOrder;
		private int made;

		public BatchCraftJob(List<Order> orders) {
			this.orders = orders;
		}

		public String describe() {
			return "crafting " + orders.get(0).recipe().name();
		}

		/** How many orders were finished (for the log). */
		public int made() {
			return made;
		}

		private static int count(Ctx c, Predicate<ItemStack> what) {
			int n = 0;
			for (int i = c.sourceStart(); i < c.sourceEnd(); i++) {
				ItemStack s = c.at(i);
				if (!s.isEmpty() && what.test(s)) n += s.getCount();
			}
			return n;
		}

		private Result skip(Ctx c, String why) {
			if (next == 0) return c.fail(why);
			next++;
			return null;
		}

		public Result step(Ctx c) {
			for (int guard = 0; guard < 16; guard++) {
				if (current != null) {
					Result r = current.step(c);
					if (r == Result.RUNNING) return r;
					current = null;
					if (r == Result.FAILED) return made > 0 ? Result.DONE : r;
					if (currentIsOrder) {
						made++;
						next++;
					}
					// Its last click (taking the result) is only queued: let it happen before the next craft starts
					// (one click per step) and before the bag is counted again.
					if (c.clicked) return Result.RUNNING;
					continue;
				}
				if (next >= orders.size()) return Result.DONE;
				Order o = orders.get(next);
				Recipe r = o.recipe();
				int times = o.times();
				// What can't be made here (stone, iron, coal, wool): only as many as we've got.
				List<Predicate<ItemStack>> seen = new ArrayList<>();
				for (Predicate<ItemStack> g : r.grid()) {
					if (g == null || seen.contains(g) || g == Inv.PLANK || g == Inv.STICK || g == Inv.LOG) continue;
					seen.add(g);
					times = Math.min(times, count(c, g) / r.uses(g));
				}
				if (times <= 0) {
					Result s = skip(c, "missing ingredient for " + r.name());
					if (s != null) return s;
					continue;
				}
				int sticks = count(c, Inv.STICK), planks = count(c, Inv.PLANK), logs = count(c, Inv.LOG);
				int sticksNeed = r.uses(Inv.STICK) * times;
				if (sticks < sticksNeed) {
					// Sticks for this and everything after it, in one go.
					int all = sticksNeed;
					for (int k = next + 1; k < orders.size(); k++) all += orders.get(k).recipe().uses(Inv.STICK) * orders.get(k).times();
					int crafts = (all - sticks + 3) / 4;
					if (planks >= 2) {
						current = new CraftJob(Recipe.STICKS, Math.min(crafts, planks / 2));
						currentIsOrder = false;
						continue;
					}
					if (logs > 0 && prep++ < 6) {
						current = new CraftJob(Recipe.PLANKS, 1);
						currentIsOrder = false;
						continue;
					}
					Result s = skip(c, "not enough wood for sticks");
					if (s != null) return s;
					continue;
				}
				int perCraft = r.uses(Inv.PLANK);
				if (perCraft > 0 && planks < perCraft * times) {
					if (logs > 0 && prep++ < 6) {
						current = new CraftJob(Recipe.PLANKS, 1);
						currentIsOrder = false;
						continue;
					}
					times = planks / perCraft;
					if (times <= 0) {
						Result s = skip(c, "not enough planks for " + r.name());
						if (s != null) return s;
						continue;
					}
				}
				current = new CraftJob(r, times);
				currentIsOrder = true;
			}
			return Result.RUNNING;
		}
	}

	public static final class SmeltJob implements Job {
		private final Predicate<ItemStack> input, fuel;
		private final int count, fuelCount;
		private int stage;
		private Placement placement;
		private long started;
		private int lastInput = -1;
		private long lastChange;

		public SmeltJob(Predicate<ItemStack> input, int count, Predicate<ItemStack> fuel, int fuelCount) {
			this.input = input;
			this.count = count;
			this.fuel = fuel;
			this.fuelCount = fuelCount;
		}

		private boolean leave;

		/** Load the furnace and walk away; come back later with {@link #collect()}. */
		public SmeltJob leaveItCooking() {
			leave = true;
			return this;
		}

		/** Just take what's finished (and leftovers) out of the furnace. */
		public static SmeltJob collect() {
			SmeltJob j = new SmeltJob(s -> false, 0, s -> false, 0);
			j.stage = 3;
			return j;
		}

		public String describe() {
			return stage == 3 && count == 0 ? "emptying the furnace" : leave ? "loading the furnace" : "smelting";
		}

		public Result step(Ctx c) {
			long now = HumanAutopilot.ticks();
			switch (stage) {
				case 0 -> { // input
					if (placement == null) {
						if (!c.at(0).isEmpty()) {
							stage = 1;
							return Result.RUNNING;
						}
						// Smelting all of it: drop each whole stack in with one click, like a player.
						// Only part of it: right-click them in one at a time.
						int total = 0, stacks = 0;
						for (int i = c.sourceStart(); i < c.sourceEnd(); i++) {
							if (!c.at(i).isEmpty() && input.test(c.at(i))) {
								total += c.at(i).getCount();
								stacks++;
							}
						}
						boolean all = total <= count && total <= 64;
						List<Integer> t = new ArrayList<>();
						for (int i = 0; i < (all ? stacks : count); i++) t.add(0);
						placement = new Placement(input, t, all);
					}
					Result r = placement.step(c);
					if (r == Result.DONE) {
						placement = null;
						stage = 1;
					}
					return r == Result.FAILED ? r : Result.RUNNING;
				}
				case 1 -> { // fuel
					if (placement == null) {
						if (!c.at(1).isEmpty()) {
							stage = 2;
							started = now;
							if (leave) return Result.DONE;
							return Result.RUNNING;
						}
						List<Integer> t = new ArrayList<>();
						for (int i = 0; i < fuelCount; i++) t.add(1);
						placement = new Placement(fuel, t);
					}
					Result r = placement.step(c);
					if (r == Result.DONE) {
						placement = null;
						stage = 2;
						started = now;
						lastChange = now;
						if (leave) return Result.DONE;
					}
					return r == Result.FAILED ? r : Result.RUNNING;
				}
				case 2 -> { // wait for it to cook
					int in = c.at(0).getCount();
					if (in != lastInput) {
						lastInput = in;
						lastChange = now;
					}
					boolean finished = c.at(0).isEmpty() && !c.at(2).isEmpty();
					boolean stalled = now - lastChange > 300;
					if (finished || stalled || now - started > count * 240L + 400) {
						stage = 3;
						if (c.at(2).isEmpty()) return c.fail("furnace isn't smelting");
					}
					return Result.RUNNING;
				}
				case 3 -> {
					if (!c.at(2).isEmpty()) {
						c.click(2, 0, ContainerInput.QUICK_MOVE);
						return Result.RUNNING;
					}
					if (!c.at(1).isEmpty()) {
						c.click(1, 0, ContainerInput.QUICK_MOVE); // take leftover fuel back
						return Result.RUNNING;
					}
					if (!c.at(0).isEmpty()) {
						c.click(0, 0, ContainerInput.QUICK_MOVE);
						return Result.RUNNING;
					}
					return Result.DONE;
				}
				default -> {
					return Result.DONE;
				}
			}
		}
	}

	/** Shift-click everything we don't need to carry into the open chest. */
	public static final class DepositJob implements Job {
		private final Predicate<ItemStack> deposit;
		private final java.util.Set<Integer> tried = new java.util.HashSet<>();

		public DepositJob(Predicate<ItemStack> deposit) {
			this.deposit = deposit;
		}

		public String describe() {
			return "putting things in the chest";
		}

		public Result step(Ctx c) {
			for (int i = c.sourceStart(); i < c.sourceEnd(); i++) {
				ItemStack s = c.at(i);
				if (s.isEmpty() || tried.contains(i) || !deposit.test(s)) continue;
				tried.add(i); // if the chest is full it just stays put; don't retry forever
				c.click(i, 0, ContainerInput.QUICK_MOVE);
				return Result.RUNNING;
			}
			return Result.DONE;
		}
	}

	/** A chest we came across (a structure's loot): shift-click out everything but junk, one stack at a time. */
	public static final class LootJob implements Job {
		private final java.util.Set<Integer> tried = new java.util.HashSet<>();

		public String describe() {
			return "looting a chest";
		}

		public Result step(Ctx c) {
			for (int i = 0; i < c.sourceStart(); i++) {
				ItemStack s = c.at(i);
				if (s.isEmpty() || tried.contains(i) || Inv.isJunk(s)) continue;
				tried.add(i);
				if (c.findEmpty() < 0) return Result.DONE; // bags full: leave the rest
				c.click(i, 0, ContainerInput.QUICK_MOVE);
				return Result.RUNNING;
			}
			return Result.DONE;
		}
	}

	/** Shift-click armor (and a shield) out of the bag so it goes onto the body / off-hand. */
	public static final class EquipJob implements Job {
		public String describe() {
			return "putting on gear";
		}

		public Result step(Ctx c) {
			// Off-hand torches back into the bag so the shield can go there (menu slot 45 is the off-hand).
			if (!c.carried().isEmpty()) {
				int e = c.findEmpty();
				if (e < 0) return c.fail("cursor stuck");
				c.click(e, 0, ContainerInput.PICKUP);
				return Result.RUNNING;
			}
			boolean shieldInBag = false;
			for (int i = c.sourceStart(); i < c.sourceEnd(); i++) shieldInBag |= c.at(i).is(net.minecraft.world.item.Items.SHIELD);
			if (shieldInBag && c.kind == Kind.INVENTORY && Inv.TORCH.test(c.at(45)) && c.findEmpty() >= 0) {
				c.click(45, 0, ContainerInput.PICKUP);
				return Result.RUNNING;
			}
			for (int i = c.sourceStart(); i < c.sourceEnd(); i++) {
				ItemStack s = c.at(i);
				if (s.isEmpty()) continue;
				var slot = c.p.getEquipmentSlotForItem(s);
				boolean armor = slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.HUMANOID_ARMOR;
				boolean shield = s.is(net.minecraft.world.item.Items.SHIELD) && slot == net.minecraft.world.entity.EquipmentSlot.OFFHAND;
				if ((armor || shield) && c.p.getItemBySlot(slot).isEmpty()) {
					c.click(i, 0, ContainerInput.QUICK_MOVE);
					return Result.RUNNING;
				}
			}
			return Result.DONE;
		}
	}

	/** Toss junk (Q on a stack) and move important items down into the hotbar (number key on a slot). */
	public static final class OrganizeJob implements Job {
		private final List<Predicate<ItemStack>> wanted;

		private final java.util.Set<Integer> thrown = new java.util.HashSet<>();
		private final boolean toss;

		/** Just put the important things in the hotbar (nothing gets thrown out). */
		public OrganizeJob(List<Predicate<ItemStack>> wantedInHotbar) {
			this(wantedInHotbar, false);
		}

		/** {@code toss}: also throw out junk; the caller has turned us toward open ground first. */
		public OrganizeJob(List<Predicate<ItemStack>> wantedInHotbar, boolean toss) {
			this.wanted = wantedInHotbar;
			this.toss = toss;
		}

		public String describe() {
			return "sorting inventory";
		}

		public Result step(Ctx c) {
			if (!c.carried().isEmpty()) {
				int e = c.findEmpty();
				if (e < 0) return c.fail("cursor stuck");
				c.click(e, 0, ContainerInput.PICKUP);
				return Result.RUNNING;
			}
			// 1. Junk out
			int stoneStacks = 0;
			for (int i = c.sourceStart(); i < c.sourceEnd() && toss; i++) {
				ItemStack s = c.at(i);
				if (s.isEmpty()) continue;
				boolean extraStone = Inv.COBBLE.test(s) && ++stoneStacks > 2;
				// Toss each slot once per sort (if it lands next to us and gets picked back up, leave it).
				boolean obsolete = Inv.obsoleteTool(c.p, s); // wooden tools once we have stone ones
				if ((Inv.isJunk(s) || extraStone || obsolete) && !thrown.contains(i) && thrown.size() < 8) {
					thrown.add(i);
					c.click(i, 1, ContainerInput.THROW);
					return Result.RUNNING;
				}
			}
			// 2. Important items into the hotbar
			int hotbarStart = c.sourceEnd() - 9; // last 9 player slots are the hotbar in every menu we use
			for (Predicate<ItemStack> w : wanted) {
				boolean inHotbar = false;
				for (int h = 0; h < 9; h++) if (!c.at(hotbarStart + h).isEmpty() && w.test(c.at(hotbarStart + h))) inHotbar = true;
				if (inHotbar) continue;
				int src = -1;
				for (int i = c.sourceStart(); i < hotbarStart; i++) if (!c.at(i).isEmpty() && w.test(c.at(i))) src = i;
				if (src < 0) continue;
				int dest = -1;
				for (int h = 0; h < 9 && dest < 0; h++) if (c.at(hotbarStart + h).isEmpty()) dest = h;
				for (int h = 8; h >= 0 && dest < 0; h--) {
					ItemStack s = c.at(hotbarStart + h);
					boolean important = false;
					for (Predicate<ItemStack> w2 : wanted) if (w2.test(s)) important = true;
					if (!important) dest = h;
				}
				if (dest < 0) continue;
				c.click(src, dest, ContainerInput.SWAP);
				return Result.RUNNING;
			}
			return Result.DONE;
		}
	}
}
