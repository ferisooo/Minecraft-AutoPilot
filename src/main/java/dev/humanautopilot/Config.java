package dev.humanautopilot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** All tunables, saved to config/humanautopilot.json. Edit the file and run /autopilot reload. */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("humanautopilot.json");

	// Human timing
	public int actionDelayMinMs = 100;
	public int actionDelayMaxMs = 300;
	public int reactionMinMs = 180;
	public int reactionMaxMs = 380;
	public int pauseEveryMinSec = 25;
	public int pauseEveryMaxSec = 90;
	public double pauseMinSec = 0.6;
	public double pauseMaxSec = 2.5;

	// Camera
	public double aimWobbleDegrees = 0.6;
	// Spring stiffness of the camera "hand". 15-22 matches the recorded player (typical turn ~0.2 s, ~250 deg/s peak).
	public double aimSpeedMin = 15.0;
	public double aimSpeedMax = 22.0;
	/** Bumped when defaults change so old config files get the new values once. */
	public int configVersion = 0;

	// Movement
	public boolean sprint = true;
	/** How often a walk turns into a sprint (the recorded player runs about half the time they move). */
	public double sprintChance = 0.65;
	public boolean sprintJump = true;
	public int maxSafeDrop = 3;
	public boolean breakBlocksForGoto = false;
	/** With the Baritone mod installed: hand it the trips our own pathfinder can't do (bridges, pillars, jumps). */
	public boolean useBaritone = true;
	/** /goto goes straight to Baritone (when installed) instead of trying our own pathfinder first. */
	public boolean baritoneForGoto = true;
	/** Baritone's body walks strictly where our (smoothly turning) camera points: purer, but jumps get less reliable. */
	public boolean baritoneBodyFollowsCamera = false;

	// Combat
	public double combatScanRange = 12.0;
	public double attackReach = 2.9;
	/** "closest" or "danger" */
	public String targetMode = "danger";
	public double minAttackCharge = 0.95;
	public boolean useShield = true;

	// Survival
	/** Eat around here (+-2 each meal); the recorded player eats at about half a hunger bar. */
	public int eatAtFoodLevel = 10;
	public float retreatAtHealth = 8.0f;
	public float resumeAtHealth = 14.0f;
	public double creeperPanicDistance = 5.0;

	// Mining
	public int mineRadius = 20;
	public int mineVerticalRadius = 12;
	public boolean mineOnlyExposedOres = true;
	public boolean mineAllowTunneling = true;
	public int minToolDurability = 10;
	public String[] defaultOres = {"diamond", "iron", "coal"};

	// Parkour
	/**
	 * How often a climb goes wrong on purpose, like a person's: about this share of climbs has one slip somewhere (the
	 * jump key missed and a walk off the edge, or a jump let go of half way). 0 = never (the user: "make it so she's not
	 * 100 perfect, maybe 80 percent").
	 */
	public double parkourSlipChance = 0.2;

	// Box fill
	/** Use each layer's own block (true) or, like the recorded player, keep using the block in hand (false). */
	public boolean boxMatchLayers = false;

	// Window
	/** Keep the game running (no pause menu) when its window loses focus, so the autopilot carries on. */
	public boolean keepRunningUnfocused = true;

	// Default feature toggles at startup
	public boolean startCombat = false;
	public boolean startAutoEat = false;
	public boolean startIdleLook = false;
	public boolean showHud = true;

	public static Config load() {
		try {
			if (Files.exists(FILE)) {
				Config c = GSON.fromJson(Files.readString(FILE), Config.class);
				if (c != null) {
					c.migrate();
					c.save();
					return c;
				}
			}
		} catch (Exception e) {
			HumanAutopilot.LOGGER.warn("Could not read {}, using defaults", FILE, e);
		}
		Config c = new Config();
		c.configVersion = CURRENT;
		c.save();
		return c;
	}

	private static final int CURRENT = 4;

	/** Bring an older config file up to date with defaults that were retuned since it was written. */
	private void migrate() {
		if (configVersion < 2) {
			// v2: camera retuned to the recorded player's speed (was 9-14, felt slow and floaty).
			aimSpeedMin = 15.0;
			aimSpeedMax = 22.0;
		}
		if (configVersion < 3) {
			// v3: sprint chance was tuned on a recorder bug (double-tap-W sprints weren't seen); eat at half hunger.
			sprintChance = 0.5;
			eatAtFoodLevel = 10;
		}
		if (configVersion < 4) {
			// v4: the recorded player sprints ~40-50% of their moving time.
			sprintChance = 0.65;
		}
		configVersion = CURRENT;
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			HumanAutopilot.LOGGER.warn("Could not save {}", FILE, e);
		}
	}
}
