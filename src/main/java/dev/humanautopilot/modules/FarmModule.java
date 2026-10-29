package dev.humanautopilot.modules;

import dev.humanautopilot.AimController;
import dev.humanautopilot.HumanAutopilot;
import dev.humanautopilot.InputDriver;
import dev.humanautopilot.util.Angles;
import dev.humanautopilot.util.Human;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * StreamToEarn's Farm mini-game (s2e-farm-pro): a field of farmland; a snowball on a bare or sprouting cell grows it
 * ripe at once (found with the test bot: one snowball, ripe wheat, the snowball never runs out in creative); the whole
 * field ripe starts the win timer. Gifts undo it: farm animals run a row eating it bare, TNT blows a crater, a spider
 * leaves a web; a chicken stays and eats - and a snowball kills it. The user: "test this minigame to near perfection".
 *
 * Like the streamer: in the field, walking (never jumping: that tramples farmland) to wherever's not ripe, throwing
 * downwards at close range - from further off a shot skims in and lands on the wrong cell (the bot's first try: 780
 * snowballs, 232 cells) - nearest first, the camera hopping cell to cell; chickens on the field shot first. A cell
 * thrown at is left a moment for the snowball to land and the wheat to grow before it's tried again.
 */
public final class FarmModule {
	private static final FarmModule INSTANCE = new FarmModule();

	public static FarmModule get() {
		return INSTANCE;
	}

	/** How far round her the field is looked for; how close she throws from. */
	private static final int FIND_RADIUS = 48;
	private static final double REACH = 4.2;

	private boolean on;
	private Set<Long> field = new HashSet<>();   // the farmland cells (asLong of the farmland block)
	private int fy;                                // the field's farmland level
	private int x0, x1, z0, z1;
	private long foundAt = -10000, startedAt, statusAt, tpAt = -10000;
	/** Right-click held since (spraying), -1 when not. Held, a snowball goes every 4 ticks (the user showed it: about 5 a second). */
	private long sprayFrom = -1;
	private final Map<Long, Long> pending = new HashMap<>(); // cell -> thrown at (tick)
	private final Map<Long, Integer> tries = new HashMap<>();
	/** Cells hit again and again that don't grow (something else on them - the wrong game's gifts put blocks there): left a minute. */
	private final Map<Long, Long> skipUntil = new HashMap<>();
	private BlockPos target;
	private long targetSince;
	private Entity chickenTarget; // (the animal last pelted)
	private int thrown, grown, chickens, wins;
	private int lastRipe = -1;
	/** For the half-minute status line (the 15-minute audits read it): snowballs and wins at the last one. */
	private long statusLineAt;
	private int thrownAtLine, winsAtLine;
	private String lastStatus = "";

	private FarmModule() {}

	public boolean on() {
		return on;
	}

	public int thrown() {
		return thrown;
	}

	public int wins() {
		return wins;
	}

	public int fieldSize() {
		return field.size();
	}

	/** Ripe cells at the last look. */
	public int ripeNow() {
		return Math.max(0, lastRipe);
	}

	public void start(LocalPlayer p) {
		on = true;
		save(true);
		field = new HashSet<>();
		foundAt = -10000;
		pending.clear();
		tries.clear();
		skipUntil.clear();
		target = null;
		thrown = grown = chickens = wins = 0;
		lastRipe = -1;
		startedAt = HumanAutopilot.ticks();
		HumanAutopilot.LOGGER.info("[farm] on at {}", p.blockPosition().toShortString());
	}

	public void stop() {
		if (!on) return;
		on = false;
		sprayFrom = -1;
		save(false);
		HumanAutopilot.LOGGER.info("[farm] off ({} snowballs, {} chickens, {} wins)", thrown, chickens, wins);
	}

	// ---- carried on after the game restarts, like a build

	private static boolean startChecked;
	private static long inWorldAt = -1;

	private static java.nio.file.Path file() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("humanautopilot").resolve("farm.properties");
	}

	private static void save(boolean on) {
		try {
			java.nio.file.Files.createDirectories(file().getParent());
			java.nio.file.Files.writeString(file(), "on=" + on + System.lineSeparator());
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("[farm] couldn't save: {}", e.toString());
		}
	}

	public void worldTick(LocalPlayer p) {
		if (startChecked) return;
		if (inWorldAt < 0) inWorldAt = HumanAutopilot.ticks();
		if (HumanAutopilot.ticks() - inWorldAt < 100) return;
		startChecked = true;
		boolean was = false;
		try { was = java.nio.file.Files.readString(file()).contains("on=true"); } catch (Exception ignored) { }
		if (was && !on && !net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
			HumanAutopilot.LOGGER.info("[farm] the game closed in the middle of the farm game: carrying on");
			HumanAutopilot.setFarm(true);
		}
	}

	// ---- the field

	/** The farmland joined to the farmland nearest her (her own village's fields further off aren't it). */
	private void find(LocalPlayer p) {
		Level level = p.level();
		BlockPos me = p.blockPosition();
		BlockPos seed = null;
		double best = Double.MAX_VALUE;
		// (Well up and down: the plugin's field floats - she joined on the ground 11 blocks under it: "no field".)
		for (int dy = -24; dy <= 24; dy++) {
			for (int dx = -FIND_RADIUS; dx <= FIND_RADIUS; dx++) {
				for (int dz = -FIND_RADIUS; dz <= FIND_RADIUS; dz++) {
					BlockPos b = me.offset(dx, dy, dz);
					if (!level.getBlockState(b).is(Blocks.FARMLAND)) continue;
					double d = dx * dx + dz * dz + dy * dy * 4;
					if (d < best) { best = d; seed = b; }
				}
			}
		}
		Set<Long> f = new HashSet<>();
		if (seed != null) {
			ArrayDeque<BlockPos> q = new ArrayDeque<>();
			q.add(seed);
			f.add(seed.asLong());
			while (!q.isEmpty() && f.size() < 20000) {
				BlockPos c = q.poll();
				for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					BlockPos n = c.offset(o[0], 0, o[1]);
					// (A cell blown out by TNT is no farmland for a moment: the field is the box round what's left.)
					if (f.contains(n.asLong()) || !level.getBlockState(n).is(Blocks.FARMLAND)) continue;
					f.add(n.asLong());
					q.add(n);
				}
			}
			fy = seed.getY();
		}
		x0 = z0 = Integer.MAX_VALUE;
		x1 = z1 = Integer.MIN_VALUE;
		for (long l : f) {
			BlockPos b = BlockPos.of(l);
			x0 = Math.min(x0, b.getX()); x1 = Math.max(x1, b.getX());
			z0 = Math.min(z0, b.getZ()); z1 = Math.max(z1, b.getZ());
		}
		// (The field doesn't shrink: a dragon's fire turned it all to fire for a moment - "the field: 0 cells", then 682 -
		// and she lost it and stood there. Smaller than before: the last one kept.)
		if (!field.isEmpty() && (f.isEmpty() || (long) (x1 - x0 + 1) * (z1 - z0 + 1) < field.size() * 0.9)) {
			x0 = keptX0; x1 = keptX1; z0 = keptZ0; z1 = keptZ1; fy = keptY;
			return;
		}
		// Every cell of the box counts, farmland now or not (a TNT crater's cells are to be grown again once it's back).
		Set<Long> box = new HashSet<>();
		if (!f.isEmpty()) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) box.add(BlockPos.asLong(x, fy, z));
		if (box.size() != field.size()) {
			HumanAutopilot.LOGGER.info("[farm] the field: {} cells, x {}..{} z {}..{} at y {}", box.size(), x0, x1, z0, z1, fy);
		}
		field = box;
		keptX0 = x0; keptX1 = x1; keptZ0 = z0; keptZ1 = z1; keptY = fy;
	}

	private int keptX0, keptX1, keptZ0, keptZ1, keptY;
	private int tpTries;

	/** Not ripe: bare farmland, or a crop not yet grown (or something else on it, a cobweb). */
	private boolean toDo(Level level, BlockPos cell) {
		BlockState soil = level.getBlockState(cell);
		if (!soil.is(Blocks.FARMLAND)) return false; // (blown out: nothing to throw at till it's back)
		BlockState c = level.getBlockState(cell.above());
		if (c.isAir()) return true;
		if (c.getBlock() instanceof CropBlock crop) return !crop.isMaxAge(c);
		return c.is(Blocks.COBWEB);
	}

	private int ripe(Level level) {
		int n = 0;
		for (long l : field) {
			BlockPos c = BlockPos.of(l);
			BlockState s = level.getBlockState(c.above());
			if (s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s)) n++;
		}
		return n;
	}

	// ---- each tick

	public String tick(LocalPlayer p, InputDriver input, AimController aim) {
		String s = tick0(p, input, aim);
		long now = HumanAutopilot.ticks();
		if (now - statusLineAt >= 600) {
			statusLineAt = now;
			HumanAutopilot.LOGGER.info("[farm-status] {} of {} ripe | +{} snowballs, +{} wins (wins in all {}, {} chickens) | doing: {}",
					Math.max(0, lastRipe), field.size(), thrown - thrownAtLine, wins - winsAtLine, wins, chickens, s);
			thrownAtLine = thrown;
			winsAtLine = wins;
		}
		lastStatus = s;
		return s;
	}

	private String tick0(LocalPlayer p, InputDriver input, AimController aim) {
		long now = HumanAutopilot.ticks();
		Level level = p.level();
		if (now - foundAt > 100 || field.isEmpty()) {
			foundAt = now;
			find(p);
		}
		boolean overField = !field.isEmpty() && p.getX() > x0 - 2 && p.getX() < x1 + 3 && p.getZ() > z0 - 2 && p.getZ() < z1 + 3;
		// (Up over the field is the gift's prison: waited out below, not escaped with a /farm tp.)
		if (field.isEmpty() || !overField && Math.abs(p.getY() - (fy + 1)) > 3 || overField && p.getY() < fy - 2) {
			// Not on it (it's somewhere else, or fell off): the plugin's own way there, typed in chat like the streamer would.
			// Knocked off (TNT, a dragon) or never on it: the plugin's /farm tp; still off 3 s later, a /tp to the middle of
			// the field she knows (the user: "when he falls off he gets stuck - maybe teleport him back").
			if (now - tpAt > 60) {
				tpAt = now;
				tpTries++;
				if (field.isEmpty() || tpTries % 2 == 1) {
					HumanAutopilot.LOGGER.info("[farm] {}: /farm tp", field.isEmpty() ? "no field round here" : "not on the field");
					p.connection.sendCommand("farm tp");
				} else {
					String c = String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f", (x0 + x1 + 1) / 2.0, fy + 1, (z0 + z1 + 1) / 2.0);
					HumanAutopilot.LOGGER.info("[farm] still not on the field: /{}", c);
					p.connection.sendCommand(c);
				}
				foundAt = now - 60; // (looked for again soon after)
			}
			if (field.isEmpty()) return "farm: no field round here (asked for /farm tp)";
			input.releaseAll();
			return "farm: off the field - getting back on";
		}
		if (overField && Math.abs(p.getY() - (fy + 1)) <= 3) tpTries = 0; // (on it: the next fall starts with /farm tp again)
		int r = ripe(level);
		// A win: the whole field ripe, then (after its timer) back to sprouts for the next round.
		if (lastRipe == field.size() && r < field.size() * 0.2) {
			wins++;
			HumanAutopilot.LOGGER.info("[farm] a win! (win {}, {} snowballs so far)", wins, thrown);
		}
		if (r != lastRipe && now - statusAt > 200) {
			statusAt = now;
			HumanAutopilot.LOGGER.info("[farm] {} of {} ripe ({} snowballs, {} chickens)", r, field.size(), thrown, chickens);
		}
		lastRipe = r;
		// Flying (a TNT blast threw her up and she was left hanging in the air: "she shouldn't be flying"): down gently with
		// sneak - a fall onto farmland would trample it - unless that's the gift's prison (a floor right under her).
		if (p.getAbilities().flying) {
			release(input);
			input.hold(InputDriver.opts().keyShift);
			return "farm: flying - coming back down";
		}
		// Held in the plugin's prison over the field (or off it, /farm tp asked for): nothing to do but wait.
		if (p.getY() > fy + 3.5 || p.getY() < fy - 2) {
			release(input);
			input.releaseAll();
			return "farm: off the field (in the prison?), waiting";
		}
		// The snowball in hand: from the hotbar, or the creative menu.
		if (!p.getMainHandItem().is(Items.SNOWBALL)) {
			int slot = -1;
			for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).is(Items.SNOWBALL)) { slot = i; break; }
			if (slot >= 0) {
				input.selectHotbar(slot);
				return "farm: getting a snowball in hand";
			}
			if (p.isCreative()) return dev.humanautopilot.schematic.CreativePicker.get().fetch(p, input, Items.SNOWBALL);
			return "farm: no snowball";
		}
		pending.values().removeIf(t -> now - t > 30);
		skipUntil.values().removeIf(t -> now > t);

		// Gift animals on the field (a cow, a pig... runs a row eating it bare; a chicken stays): the user, "if he detects
		// a animal ... he runs to it immediately". Pelted when in reach (a chicken dies to a few snowballs); out of reach,
		// she heads for the bare cells it's leaving. (Not the wolf: it's on her side, it eats the others.)
		net.minecraft.world.entity.Mob near = null;
		double nearD = Double.MAX_VALUE;
		for (var m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new AABB(x0 - 1, fy, z0 - 1, x1 + 2, fy + 3, z1 + 2))) {
			if (!m.isAlive() || m instanceof net.minecraft.world.entity.animal.wolf.Wolf) continue;
			double dd = m.distanceTo(p);
			if (dd < nearD) { nearD = dd; near = m; }
		}
		// Eyes and feet apart, like a player: the feet keep going where she's heading (lanes across the field while there's
		// lots to do - the start, after a win - else straight at the nearest bare cells, or at an animal), the eyes glide
		// over the cells in reach that aren't ripe, right-click held. (Before: she stood spraying, turned, walked, stopped -
		// "walk stop walk stop" - and the camera flicked cell to cell: "his camera is snapping".)
		Vec3 here = p.position();
		Vec3 focus = near != null ? near.position() : here;
		int left = 0;
		BlockPos nearestTodo = null, underFoot = null;
		double nearestD = Double.MAX_VALUE;
		for (long l : field) {
			BlockPos c = BlockPos.of(l);
			if (skipUntil.containsKey(l) || !toDo(level, c)) continue;
			left++;
			Vec3 mid = cellTop(c);
			if (Math.hypot(mid.x - here.x, mid.z - here.z) < 1.1) { underFoot = c; continue; }
			if (pending.containsKey(l)) continue;
			double df = Math.hypot(mid.x - focus.x, mid.z - focus.z);
			if (df < nearestD) { nearestD = df; nearestTodo = c; }
		}
		// Where the feet go.
		Vec3 go = null;
		String doing;
		if (near != null && nearD > REACH) {
			go = near.position();
			doing = "running at the " + name(near);
		} else if (left > Math.max(20, field.size() * 0.15)) {
			go = laneTarget(here);
			doing = "sweeping the field";
		} else if (nearestTodo != null) {
			go = cellTop(nearestTodo);
			doing = "running to " + nearestTodo.toShortString();
		} else if (underFoot != null) {
			// Only what's under her feet left: a step or two off it (towards the middle), then back at it.
			Vec3 at = cellTop(underFoot);
			Vec3 mid = new Vec3((x0 + x1 + 1) / 2.0, fy + 1.0, (z0 + z1 + 1) / 2.0);
			Vec3 away = mid.subtract(at);
			go = at.add(away.lengthSqr() < 4 ? new Vec3(2.5, 0, 0) : away.normalize().scale(2.5));
			doing = "stepping off the cell to throw at it";
		} else {
			doing = left == 0 && pending.isEmpty() ? "all ripe, waiting for the win" : "waiting for the snowballs to land";
		}
		Vec3 moveDir = Vec3.ZERO;
		if (go != null) {
			double gd = Math.hypot(go.x - here.x, go.z - here.z);
			// (Not right up to a cell: in reach of it is enough - then on.)
			boolean there = nearestTodo != null && go.equals(cellTop(nearestTodo)) ? gd < 2.2 : gd < 0.8;
			if (!there) {
				moveDir = new Vec3(go.x - here.x, 0, go.z - here.z).normalize();
				keysToward(p, input, moveDir.x, moveDir.z);
				double fwd = Math.abs(Angles.wrap(Math.toDegrees(Math.atan2(-moveDir.x, moveDir.z)) - p.getYRot()));
				if (gd > 6 && fwd < 30) input.hold(InputDriver.opts().keySprint);
			}
		}
		// Where the eyes go: an animal in reach; else the cell in reach that isn't ripe nearest where the eyes are now
		// (and not behind the way she's going); else ahead over the field.
		Vec3 eye = p.getEyePosition();
		Vec3 want = null;
		BlockPos wantCell = null;
		if (near != null && nearD <= REACH + 0.8) {
			want = near.position().add(0, near.getBbHeight() * 0.5, 0);
			if (near != chickenTarget) { chickenTarget = near; if (near instanceof Chicken) chickens++; }
			doing = "pelting the " + name(near);
		} else {
			Vec3 from = aimPoint != null ? aimPoint : eye.add(p.getLookAngle().scale(3));
			double best = Double.MAX_VALUE;
			for (long l : field) {
				if (pending.containsKey(l) || skipUntil.containsKey(l)) continue;
				BlockPos c = BlockPos.of(l);
				Vec3 mid = cellTop(c);
				double d = Math.hypot(mid.x - here.x, mid.z - here.z);
				if (d < 1.1 || d > REACH || !toDo(level, c)) continue;
				double score = mid.distanceTo(from);
				if (moveDir.lengthSqr() > 0 && (mid.x - here.x) * moveDir.x + (mid.z - here.z) * moveDir.z < -1.5) score += 6; // (behind her)
				if (score < best) { best = score; wantCell = c; }
			}
			if (wantCell != null) want = cellTop(wantCell);
		}
		if (want == null) {
			release(input);
			Vec3 ahead = moveDir.lengthSqr() > 0 ? here.add(moveDir.scale(4)) : new Vec3((x0 + x1 + 1) / 2.0, fy + 1.0, (z0 + z1 + 1) / 2.0);
			want = new Vec3(ahead.x, fy + 1.0, ahead.z);
		}
		glide(aim, eye, want);
		if (wantCell != null || near != null && nearD <= REACH + 0.8) {
			// At most 3 seconds on one cell ("stays at each for 3 seconds unless it's filled"), then on, back to it later.
			if (wantCell != null) {
				if (!wantCell.equals(target)) {
					target = wantCell;
					targetSince = now;
				} else if (now - targetSince > 60) {
					skipUntil.put(wantCell.asLong(), now + 400);
					HumanAutopilot.LOGGER.info("[farm] 3 s on {} and not ripe: on to the next, back to it later", wantCell.toShortString());
					target = null;
				}
			}
			spray(p, input, aim, now, want, wantCell, level);
		}
		return String.format("farm: %s (%d cells to do)", doing, left);
	}

	private static Vec3 cellTopOf(BlockPos c, int fy) {
		return new Vec3(c.getX() + 0.5, fy + 1.0, c.getZ() + 0.5);
	}

	private Vec3 cellTop(BlockPos c) {
		return cellTopOf(c, fy);
	}

	private static String name(Entity e) {
		return e.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
	}

	/** The point the eyes are on: moved towards {@code to} a little each tick - a steady pan, not a flick. */
	private Vec3 aimPoint;

	private void glide(AimController aim, Vec3 eye, Vec3 to) {
		if (aimPoint == null || aimPoint.distanceTo(to) > 12) aimPoint = to;
		Vec3 d = to.subtract(aimPoint);
		double step = 0.45;
		aimPoint = d.length() <= step ? to : aimPoint.add(d.normalize().scale(step));
		aim.setQuick(false);
		final Vec3 pt = aimPoint;
		aim.lookAtGliding("farm-eyes", () -> pt);
	}

	/** Lanes across the field, 6 apart (she throws 4 either side), walked end to end and back. */
	private int lane = -1, laneEnd;

	private Vec3 laneTarget(Vec3 here) {
		int lanes = Math.max(1, (z1 - z0) / 6 + 1);
		if (lane < 0 || lane >= lanes) {
			lane = Math.min(lanes - 1, Math.max(0, (int) Math.round((here.z - z0 - 2) / 6.0)));
			laneEnd = here.x - x0 < x1 - here.x ? 1 : 0;
		}
		double z = Math.min(z1 + 0.5, z0 + 2.5 + lane * 6);
		Vec3 t = new Vec3(laneEnd == 1 ? x1 - 0.5 : x0 + 1.5, fy + 1.0, z);
		if (Math.hypot(t.x - here.x, t.z - here.z) < 1.2) {
			// The end of this lane: the next one over, back the other way.
			lane = (lane + 1) % lanes;
			laneEnd = 1 - laneEnd;
			z = Math.min(z1 + 0.5, z0 + 2.5 + lane * 6);
			t = new Vec3(laneEnd == 1 ? x1 - 0.5 : x0 + 1.5, fy + 1.0, z);
		}
		return t;
	}

	/** The feet towards a direction whatever way the eyes point: forward/back/left/right, eight ways, like a player. */
	private static void keysToward(LocalPlayer p, InputDriver input, double wx, double wz) {
		double len = Math.hypot(wx, wz);
		if (len < 1e-6) return;
		wx /= len;
		wz /= len;
		double yaw = Math.toRadians(p.getYRot());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // where forward goes
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);  // where right goes
		double f = wx * fx + wz * fz, r = wx * rx + wz * rz;
		var o = InputDriver.opts();
		if (f > 0.38) input.hold(o.keyUp); else if (f < -0.38) input.hold(o.keyDown);
		if (r > 0.38) input.hold(o.keyRight); else if (r < -0.38) input.hold(o.keyLeft);
	}

	/**
	 * Right-click held, like the streamer sweeping the field: a snowball every 4 ticks wherever the crosshair is, the camera
	 * going cell to cell. The cell each one lands on (where the crosshair meets the field) is left for a moment; the
	 * target, once the crosshair's been on it, too - and on to the next (no waiting on each one: a click a cell made it
	 * about 2.3 a second, held it's 5).
	 */
	private void spray(LocalPlayer p, InputDriver input, AimController aim, long now, Vec3 at, BlockPos cell, Level level) {
		var use = InputDriver.opts().keyUse;
		if (sprayFrom < 0) {
			sprayFrom = now;
			input.click(use);
		}
		input.hold(use);
		Vec3 eye = p.getEyePosition();
		double err = Math.hypot(Angles.wrap(Angles.yawTo(eye, at) - p.getYRot()), Angles.pitchTo(eye, at) - p.getXRot());
		double ok = Math.toDegrees(Math.atan2(0.45, eye.distanceTo(at)));
		if (cell != null && err <= ok) pending.putIfAbsent(cell.asLong(), now);
		if ((now - sprayFrom) % 4 == 0) {
			thrown++;
			// Where this one lands: the crosshair's ray down onto the field.
			Vec3 look = p.getLookAngle();
			if (look.y < -0.05) {
				double t = (fy + 1.0 - eye.y) / look.y;
				BlockPos hit = BlockPos.containing(eye.x + look.x * t, fy, eye.z + look.z * t);
				if (field.contains(hit.asLong())) {
					pending.putIfAbsent(hit.asLong(), now);
					int n = tries.merge(hit.asLong(), 1, Integer::sum);
					if (n >= 10 && toDo(level, hit)) {
						// (9 snowballs each on the last few cells and none grew: something on them she can't fix. A minute
						// off; another go after that.)
						HumanAutopilot.LOGGER.info("[farm] {} thrown at {} times and not ripe: leaving it a minute", hit.toShortString(), n);
						skipUntil.put(hit.asLong(), now + 1200);
						tries.put(hit.asLong(), 0);
					}
				}
			}
		}
	}

	/** Let go of right-click (walking over, nothing in reach). */
	private void release(InputDriver input) {
		if (sprayFrom < 0) return;
		sprayFrom = -1;
		input.drop(InputDriver.opts().keyUse);
	}

	/** Off: hands off the right button too. */
	public void releaseKeys(InputDriver input) {
		release(input);
	}
}
