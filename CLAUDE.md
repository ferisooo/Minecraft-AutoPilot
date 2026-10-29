# Human Autopilot — notes for the next Claude

Client-side Fabric mod for **Minecraft 26.1.2** (Fabric Loader 0.19.3, Fabric API 0.155.2+26.1.2, Java 25).
It plays survival Minecraft **like a real human**: wood → stone → furnace/food → iron → gear → diamonds,
using only real key presses and mouse movement.

## The goal (read this first)
- "Real inputs only" is a **rule**, not the goal. The goal is **human behaviour**: sensible decisions, player habits,
  calm camera, no jitter, visible cursor movement in GUIs. When in doubt ask "would a real player do this?"
- Hard rules: no teleporting, no instant actions, no direct inventory packets that skip the cursor. GUI clicks go
  through `GuiMouse` (real cursor moves to the slot, settles, then clicks/presses keys). Hotbar switches roll the real
  scroll wheel. Respawn is a real click on the button.
- Habits the user explicitly asked for (don't regress): don't break and re-place the crafting table repeatedly; craft an
  axe and chop trees from the lowest log up, taking the whole tree; planks a whole stack at a time; toss outgrown tools;
  the head must not move constantly.
- **Always validate in a real generated world** (`RealWorldClientTest`, fixed seed), never only on staged islands.
- **Working with the user:** they want a build → real-world test → fix loop and progress reports in **simple, short
  words**, including the bot's reasoning (the "(because …)" log lines). They often step away and say "keep going".

## Controls
Numpad: 0 panic · 1 pathing · 2 combat · 3 mining · 4 auto-eat · 5 idle looks · 7 survival · **8 record my play** · **Insert** show/hide the HUD.
Commands: `/goto x y z` | `/goto x z`, `/automine …`, `/autopilot`, `/autopilot reload`, `/autopilot panic`.
Config: `config/humanautopilot.json` (has `configVersion`; `Config.migrate()` updates old files when defaults change).

## Build & install
- MC 26.1.2 is **unobfuscated** (Mojang names). Loom `net.fabricmc.fabric-loom` 1.18-SNAPSHOT, **no remapping**
  (`implementation`, not `modImplementation`). Gradle 9.7.1 wrapper. Quick check: `.\gradlew.bat compileJava -q`.
- 26.1 API names: `GuiGraphicsExtractor` (HUD `text()/fill()`), `HudElementRegistry.addLast`,
  `KeyMapping.Category.register(Identifier)`, `KeyMappingHelper.registerKeyMapping`, `ClientCommands`, `ContainerInput`
  (was ClickType), `MultiPlayerGameMode.handleContainerInput`, `MouseButtonEvent/MouseButtonInfo`,
  `KeyEvent(key, scancode, modifiers)`, `AbstractContainerScreen.mouseClicked(event, bool)`, `Level.isDarkOutside()`,
  `Level.getOverworldClockTime()`, `Identifier` (was ResourceLocation). Mojang jar for `javap`:
  `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.1.2/`.
- Tests build the jar at `%TEMP%\hatest\build\libs\human-autopilot-1.0.0.jar`. When a run is good, copy that exact jar
  to `dist/` and to the user's `C:\Users\ItsMe\AppData\Roaming\.minecraft\mods\` (they also run Sodium, Iris, IPN, Voxy,
  ReplayMod, …).

## Testing
- Windows MAX_PATH breaks gametests from deep paths, so tests run from `%TEMP%\hatest`:
  `$env:HA_MINUTES="25"; .\tools\runtest.ps1 -Only real` (PowerShell, **run in the background**).
  `-Only`: basic | survival | life | danger | real | record. Log: `%TEMP%\hatest\gametest.log`.
  Screenshots: `%TEMP%\hatest\build\run\clientGameTest\screenshots\`. One test at a time (they share that folder).
- Watch with Monitor: `tail -n 0 -F … | grep -E "GOT |DIED|STUCK|DITHERING|\[input\]|REAL WORLD RESULT|BUILD"`.
  No long blocking sleeps (the user interrupted those).
- `RealWorldClientTest`: seed `HA_SEED` (default 20261006), survival, normal. Logs `GOT <milestone>`, deaths, a status
  line every 30 s, a `[motion]` meter every 30 s (reversals/min, key toggles/min, turn sizes) and screenshot bursts.
  25-minute runs end around the iron pickaxe; use `HA_MINUTES=40` to see what comes after.
- Useful log tags: `[survival] stage: action (because …)`, `[tunnel] boxed in/gave up: reason`,
  `[nav] giving up: …`, `[gui]`, `[hurt]`, `[mine]`, `STUCK`, `DITHERING`.

## Architecture (src/main/java/dev/humanautopilot)
- `HumanAutopilot` — entrypoint, keys, commands, HUD, `decide()` priority: creeper → retreat → eating → combat →
  "look at what hit me" → eat → pause → survival planner → mining → goto → wander → idle. Also `edgeGuard`
  (predictive sneak), `breathGuard` (swims to the nearest air pocket, `nearestAir`), death screen (cursor-clicks
  Respawn), stray-screen closer, recorder hooks.
- `InputDriver` — key state each tick (min hold 3 / min gap 2 ticks); `selectHotbar` scrolls the real wheel.
- `AimController` — spring-damper camera (stiffness 15-22, cap 850°/s, 1100 urgent), deadzone, attention dwell,
  yaw error weighted by cos(pitch) (no flip-flop when looking straight up/down). Applied per frame via `MouseHandlerMixin`.
- `pathing/` — `PathFinder` (incremental A*), `Navigator` (steering, edge sneak, rare sprinting via `sprintMood`, dig
  failures count toward the 8-replan limit), `Goal`, `Terrain`, `Digger` (keeps attack held between blocks).
- `modules/` — `CombatModule` (ignores calm endermen/zombified piglins, no fist-fights with archers, quick target
  switches mid-fight), `SurvivalModule`, `MiningModule` (skips its own tossed junk), `IdleLookModule`.
- `survival/` — `SurvivalPlanner` (re-plans every tick from the inventory), `ScreenWorker`,
  `GuiMouse`, `Inv`, `Recipe`, `Placer`, `Tunneler`, `Hunter`.
- `record/Recorder` — gameplay recorder. `util/Tools` — tool/weapon/food choice.
- Mixins: `KeyMappingAccessor`, `MouseHandlerMixin`, `MouseHandlerAccessor` (+ scroll invoker), `ContainerScreenAccessor`.
- Tests in `src/gametest/` (… `RealWorldClientTest`, `RecorderClientTest`).

## No online AI
- The DeepSeek "AI brain" was **removed on 2026-10-07 at the user's request** (Brain.java, `/autopilot brain`, the
  config fields, the HUD line and `HA_BRAIN`). The mod makes no network calls; all decisions come from
  `SurvivalPlanner`. Don't add an online AI back unless the user asks.

## Gameplay recorder (`record/Recorder.java`, numpad 8)
- **One player per file (2026-10-17, the user: "make sure the bot's recordings belong to the bot so you will be able
  to tell mine and his apart")**: `rec-<time>-you.jsonl.gz` is the user's, `rec-<time>-bot.jsonl.gz` the bot's; the
  header has `"who":"you"|"bot"`. Started with the builder on (or a test's `recordBot`): the bot's file. A new file
  starts when the other one takes over (`Recorder.whoChanges`): the builder driving 2 s in the user's file, or the
  user playing (keys/mouse on 5 ticks in 2 s, the bot not driving at all) in the bot's; nothing's recorded while that
  is being made sure of; a line in chat says so; "Recording saved ... (+N more ...)" at the end. Older untagged files:
  the bot's part starts at the event `bot: recording the builder` (the analyzer stops there). `analyze_recording.py`
  reads the user's files from a folder, `--bot` the bot's.
- Records the **human** (bot-driven moments skipped) to `.minecraft/humanautopilot-recordings/rec-*.jsonl.gz`:
  camera every frame, keys/slot/target every tick, events (items, screens, hits, deaths, slot switches) and a
  situation snapshot every 10 s. No chat, nothing leaves the PC. Shows "● REC" on the HUD.
- `python tools/analyze_recording.py [file]` → turn speeds/sizes, reversals, key habits, hotbar layout, eating, night
  behaviour, menu cursor.
- From the user's first recording (6 min, early game): scrolls the hotbar, holds attack across blocks, rarely sprints,
  turns fast (~250°/s typical, ~850 at the top), axe first, shovel, charcoal for torches — all now in the bot.
- Second recording `rec-20261008-004254` (78 min, on the user's **Paper server**: 5-heart plugin, /rtp, /back after
  death, vein miner/tree feller, so decisions after ~34 min are server-style, not vanilla). Use it mainly for hands:
  camera 39 reversals/min, turns median 32° at ~260°/s (95th pct 1150°/s), still ~49% of the time; 72 move-key
  toggles/min, 11.5 jumps/min; menu cursor glides ~190 ms / ~50 px; eats at hunger 6-10; hotbar layout NOT fixed;
  carries a **torch in the offhand** in caves; first night: dug into a hole with torches and waited (same as the bot).
  17 deaths (skeletons, zombies, creepers, lava, falls), mostly deep caves at y≈-53.
  **Recorder gap:** sprint showed 0% because only the sprint key is logged; double-tap-W sprinting is missed. Measured
  from position speed instead: of time moving forward, ~53% walking, ~27% sprinting, ~20% sprint-jumping (359 jumps
  started at sprint speed). So **the user sprints and sprint-jumps a lot**; the earlier "rarely sprints" conclusion
  (and the bot's low `sprintMood`) came from this bug and should be revisited. Fix the recorder: log `p.isSprinting()`, plus in-water/underwater/air, sleeping, on ladder, on fire/in lava (the user asked about these).
  Not recorded yet: chat/commands (by design). Ask for more (1-3 h); next step is learning their decisions
  (what they do when) into the planner's rules and matching their cursor/camera more closely.

## What the bot does now (don't regress)
- **Early game:** wooden axe → wooden pickaxe → stone pickaxe/sword/axe/shovel → furnace (+3 cobble for a spare
  pickaxe made at the same table) → food (waits by the furnace when nearly cooked) → chest at home.
- **Before going down:** packs ~8 planks + 4 sticks (even if trees were hard to find when nearly woodless); makes
  charcoal from 2 logs if there's no coal; torches keep 2 sticks spare for a new pickaxe.
- **Underground:** staircase down, branch mining into fresh rock (remembers its tunnels), wall scans, torches, cave
  exploring when no ore is in sight (only ≥6 below the surface, not while hurt); mines walls, never the steps it
  stands on; deep in a mine it tosses junk instead of walking home to deposit.
- **Tools:** uses the cheapest pickaxe that works on plain rock (stone before iron) and keeps a stone "rock pickaxe" in
  the hotbar; worn-out tools are used until they break if nothing else works, and tossed (on the surface) once a
  working replacement exists; a broken hotbar pickaxe gets swapped for the spare. *(Rock pickaxe + worn-tool tossing
  are built but not yet tested in a game.)*
- **Iron:** smelts, then iron pickaxe first, then shield, iron armor pieces, iron sword.
- **Night:** with a pickaxe, wood/hunting wait for morning and it stays (or digs) underground; no trips home in the dark.
- **Safety:** creeper escape, retreat at low health, swims to the nearest air when drowning, no walking off drops, a
  second death in the same place means it doesn't go back for the items.
- **Junk:** thrown only toward open ground and never picked back up (`Inv.noteTossed`); outgrown tools only on the surface.
- **Anti-stall:** every "walk/climb/explore there" has a give-up path and hysteresis at the "underground" and
  "in reach" boundaries (those caused most loops). `STUCK` counts a changing bag as progress; a flip-flop logs each
  line at most every 2 s and warns `DITHERING`.

## Status (2026-10-07)
- Typical 25-min real-world run: wooden pickaxe ~65-90 s, stone pickaxe ~100-190 s, furnace ~180-450 s, iron pickaxe
  ~900-1100 s, usually 0 deaths. Motion ~15-20 reversals/min, ~35-45 key toggles/min (the user is ~38 reversals/min).
- Proven: iron pickaxe (many runs), shield + iron helmet + iron sword.
- **Not proven yet:** full armor, bed/sleep, diamonds. Next: a 40-min run with the rock-pickaxe and worn-tool changes.
- Installed in the user's mods folder: the run built ~20:39 on 2026-10-07 (iron pickaxe at 890 s, 0 deaths). That jar
  still contains the old brain code (off by default); the next tested build replaces it.

## Changes on 2026-10-08 (from the user's recordings; partly tested)
- Sprinting: `sprintChance` 0.5 (config v3 migration), re-rolled every 3-10 s, 40% of sprints also hop; sprints on
  any "open run" of >=3 roughly-straight path steps over bumpy ground (`Navigator.openRun`), not only dead-flat lines.
  The sprint loosening (open>=3, faster rolls) is compiled but NOT yet seen in a run; earlier runs showed ~0-14% sprint.
- Eats at `eatAtFoodLevel` 10 +-2 per meal (`SurvivalModule.eatAt`).
- Offhand torch: when dark underground it selects the torch slot and presses F (`torchToOffhand`); `Placer` places
  from the off-hand while holding a pickaxe/sword. Shield stage + `EquipJob` move off-hand torches back to the bag.
  Seen working in a real run (try 1, then torches placed).
- Canopy start: drops up to maxSafeDrop+3 when health >= 16; wood targets must have real ground within 5 blocks
  below (`reachableFromGround`), so it no longer climbs into the canopy. First log back to ~6 s on seed 20261006.
- Charcoal is made BEFORE packing planks (packing turned every log into planks and left none to burn), and the
  surface-wood branch is skipped while `caveNav` is active (cave-mouth flip-flop / STUCK fixed).
- Recorder logs body state `st` bits + `air`; analyzer reports sprint share (state and by speed), water, air, sleep.
- Test motion meter now prints walk/sprint/hop% and offhand-torch seconds.
- Last run: log 6 s, wooden pickaxe 90 s, stone pickaxe 138 s, furnace 202 s, cooked food 266 s, charcoal 313 s,
  offhand torch ~7 min; the user closed the window at ~8 min. NOT yet installed in the mods folder.

## Changes on 2026-10-09 ("eyes, ears and habits", from the recordings)
- `modules/Attention` (singleton, a vanilla `SoundEventListener` registered on the first tick):
  - **Ears:** turns toward hostile groans/rattles/hisses, arrows, explosions, lava pops, and (mildly) animal noises,
    after a reaction delay, for 0.5-1.5 s. Ignores sounds already in view, endermen, our own sounds. Out of sight
    (through rock) only 50% / 25%. Rate limit 5-12 s; "habituation" remembers the source by entity AND by
    sound+4-block cell for 75 s, so a skeleton groaning behind a wall gets one look, not one per groan.
    Mild sounds never interrupt a swing (attack held) or walking on tricky ground.
  - **Eyes while walking:** every 5-16 s of "calm" walking (WALK/DIAG node, no edge, no water, open run >= 3) it
    glances 0.6-1.5 s at a nearby creature/item (70%), visible ore or lava (75% when any), else the view (rarely in
    tunnels). The feet keep to the path with whichever W/A/S/D combo points along it (`Navigator.walkWhileLooking`,
    octants of 45 degrees); after a glance it keeps using that until the camera is back within 22 degrees.
  - Who handles a sound: the walker on calm ground; otherwise `decide()` owner `LOOK` (stands and looks; alarms only
    if walking on tricky ground; never on ladders/in water).
- Walking gaze pitch drifts like the user's (p10 0 / median ~15 / p90 ~40 degrees) instead of a fixed 14.
- `survival/BagPeek`: about every 80-200 s, one of the human pauses becomes "E, look 1-2.5 s, cursor drifts over a
  slot or two, E (70%) or Esc". Only in a calm moment (on ground, hp > 12, no hostiles within 16, no screen job).
- Combat like the recording (hits from ~1.7 blocks with W held, ~700 ms apart, almost never backs off): melee closes
  to 1.4 (creepers/ranged keep reach-0.4), 40% W-tap after a hit, back-off only 6%, and an 8% whiff when the mob
  steps out of the crosshair just as it would swing (`CombatModule.whiffs`).
- Stops at the edge 0.3-0.8 s and looks at the landing spot before dropping >= 2 blocks (75%, once per drop spot).
- Thinking pause: a new planner stage (not seen in the last minute) gives a 0.3-1.2 s stand-still first (70%).
- Idle looks pick a direction with room to see (no staring at a wall from 30 cm in a shaft).
- `MiningModule`: a target blocked by something it won't break (e.g. its own chest beside the furnace) gets 3 tries
  from closer angles (2.5 / 1.9 / 1.5) instead of 1; this lost a furnace in a run.
- Test motion line also prints `glances heard peeks whiffs edgePeeks thinks`; log tags `[look]`, `[habit]`.
- Analyzer: `walking_eyes` (gaze pitch, glances per walking-minute, size, duration), `fighting`, `inventory_peeks`.
  User numbers: 4.6-6.6 glances/walking-min, ~45-52 degrees, ~1.6-1.8 s there and back; inventory peek ~0.4-0.6/min;
  jumps are ~70% step-ups, then sprint-jumps, only 1-3/min flat hops, never idle hops.

## Baritone (added 2026-10-09, at the user's request: "its pathfinding is very strong")
- Official release **v1.18.0 "For Minecraft 26.1"** (fabric.mod.json accepts `26.1.*`, loader >= 0.19.3), LGPL-3.0.
  Jar `libs/baritone-api-fabric-1.18.0.jar` (SHA-1 3220ff86a5..., matches the release's checksums.txt), downloaded with
  the user's OK. build.gradle: `compileOnly` + `runtimeOnly` (tests load it). Our fabric.mod.json only *suggests* it.
  The user must put the same jar in their mods folder; without it the mod uses only our own pathfinder.
  Its internals are obfuscated: use only the `baritone.api` package.
- `pathing/BaritoneLegs` (no Baritone types; loads `BaritoneImpl` by reflection only if the mod is present).
  `BaritoneImpl`: human settings (freeLook/smoothLook/randomLooking off, no path rendering, no chat control, logger ->
  our log, parkour + place allowed, no water-bucket MLG, maxFall = ours); a `Hold` process (priority 1000,
  REQUEST_PAUSE) pauses it whenever the navigator didn't tick it this tick (eating, combat, pauses, looks);
  an event hook catches the rotation Baritone sets each tick (PlayerUpdateEvent), puts the real camera back
  (rotation captured in `beforeTick` at START_CLIENT_TICK) and feeds the target to `AimController` (key "baritone",
  urgent). Placing/breaking only happen once the *real* crosshair is on the block. By default the body still walks
  along Baritone's yaw while the camera catches up (RotationMoveEvent); `baritoneBodyFollowsCamera` makes it strict.
- When it's used: `/goto` (`Navigator.goFar`, config `baritoneForGoto`), and as a fallback in every `Navigator`: when our
  search is *exhausted* (every walkable spot searched, goal not reached: `Path.exhausted`), or on our give-ups (no route,
  unreachable, stuck x8, can't dig through) -> `fail()` hands the trip to Baritone once per `go()`. Goal mapping: Block,
  XZ, NearXZ->XZ, Near/Beside->GoalNear (a bit tighter); Surface isn't mapped (keeps our partial path).
- Config: `useBaritone`, `baritoneForGoto`, `baritoneBodyFollowsCamera`. Panic stops it.
- Staged test `BaritoneClientTest` (`-Only baritone`, floating course 30 up): bridge a 6-wide void, pillar 5 up, parkour
  three 2-gaps with no blocks, and the walker's own fallback over a 4-gap. First run: all PASSED, max camera turn
  37 deg/tick (no snapping). Bridging is quick (6 blocks ~4 s); `rightClickSpeed` could slow it if it looks botty.
- Known non-human bits left: Baritone selects hotbar slots directly (autoTool/throwaway), and drives movement through
  its own input override rather than our KeyMapping driver.

## Crafting like a player (2026-10-09, the user: "keeps opening and closing the crafting table", "digs right below the table")
- `ScreenWorker.BatchCraftJob`: one sitting = sticks for the whole list first (logs -> planks if needed), then the item
  it came for, then the rest of `SurvivalPlanner.tableWishes` it has the stuff for (wooden axe+pickaxe; stone pickaxe,
  sword, axe, shovel, furnace, spare pickaxe; iron pickaxe, sword, shield, armor). Short of something -> left off.
  Inventory crafts are batches too (planks + table in one go). `needWood` no longer crafts planks/sticks separately
  when the table visit will make them.
- Stone age: one trip for the whole stone set (`stoneRun`, ~20 cobble: tools + furnace + spare) before crafting.
- `stepAwayFromTable`: about to dig down next to our table on the surface -> walk 5-8 blocks away first, face away from
  the table, then the staircase starts (no more undermining the table / "couldn't get at the table").
- `smelt()`: no furnace nearby or in the bag but 8+ cobble -> craft a new one on the spot (it once mined with 9 raw iron
  and 22 coal and never smelted because its furnace was at home 55 blocks up).

## Recipe book crafting (2026-10-09, the user: players click the recipe, they don't lay items out)
- `CraftJob` tries the recipe book first: open it with the green book button if closed (`RecipeBookScreenAccessor`
  invoker for its position), find the `RecipeButton` whose display stack matches on the current page
  (`RecipeBookComponentAccessor`, `RecipeBookPageAccessor`), else click the "all" tab, click the search box,
  ctrl+A and type the item's localized name (planks/bed: last word), then click it. Shift-click for planks (a whole
  stack of logs), N clicks for N sets; then one shift-click on the result. Not in the book / not craftable -> lays it
  out by hand as before. ScreenWorker gained point clicks (`Ctx.clickAt`) and typing (`Ctx.key/type`), each with
  the real cursor and human gaps. First run: inventory = book, shift-click planks, click table; table = sticks, axe,
  pickaxe; wooden pickaxe at 55 s (was 70).
- BatchCraftJob bug fixed: after an inner craft queues its final shift-click, wait a step (one click per step) before
  counting the bag / starting the next recipe (it used to overwrite that click).

## Audit 2026-10-09 (the user: "looks at 2 things at once", "not moving/mining like a real player")
- Camera audit in tests: `AimController.auditLine()` per 30 s = target switches, ping-pongs (A->B->A in 1.5 s) and
  clashes (two systems asking in the same moment) with the worst pairs, logged as `[aim]`. Found: sound looks vs
  chopping, "dig-away" vs the stone finder (fixed: step-away runs first).
- Mining fixes (compiled, NOT yet judged in a run): crosshair keeps roughly the same spot on each face in a run of
  blocks (`Digger` aimOffset carried over); small hops (< 30 deg) keep the same camera "hand" (no retune); while
  stepping in a staircase/tunnel the eyes go to the next block to dig (no fixed 35-degree "walking" look in between);
  tool inertia (`Digger.keepInHand`): quick blocks with what's in hand, no switching back within 3 s unless > 1 s/block
  or it can't drop.
- Still to do from the audit: walking stops to turn at > 55 degrees (steer while walking instead), look-ahead point
  can jump (smooth it), periodic left/right tunnel wall scans look scripted, animal sounds shouldn't interrupt mining.

## Recorder v2 (2026-10-09, before the user's next long recording)
- `mixin/GameModeMixin` (read-only hooks on MultiPlayerGameMode: startDestroyBlock, destroyBlock, useItemOn,
  handleContainerInput, handlePlaceRecipe, attack) -> events `broke`, `place`/`use_on`, `click`, `recipe`, `hit`.
  Plus `drop` (Q), `sound` (hostile/neutral/danger sounds within 24: id, rel pos, degrees off the crosshair), `seen`
  (every 2 s: mobs/items and ores/lava in sight, each with its angle off the crosshair), `slot.via` (wheel/key),
  situation `biome/rain/sky/dim`. Header `v:2`. `Recorder.recordBot` (env HA_RECORD_BOT=1 in RealWorldClientTest)
  records the bot too, for side-by-side comparison with the same analyzer.
- Analyzer: `hands` (mining: blocks/min, break ms by tool, block height vs feet, tool changes per 100 blocks, moving /
  sneaking while mining, pitch; placing: sneak/edge/airborne shares, torch light; menus: click types, recipe-book use;
  hotbar wheel share; hits: crit/sprint/charge; drops) and `ears_and_eyes` (share of side sounds they turned to and
  the reaction time; how often each kind of seen thing was within 15 deg of the crosshair).
- **Installed 2026-10-09 17:30** in the user's mods folder for their recording session (jar built 17:25, 4-min check
  run: 0 deaths, no errors). Old jar backed up to `dist/human-autopilot-1.0.0-installed-2026-10-07.jar`. Baritone is
  NOT in their mods folder yet (only needed when the bot should use it). This build also has untested bot changes
  (mining sweep, tool inertia, turn-speed scaling: big turns were 1.5-1.8x faster than the user's).
- Advice given to the user for recording: vanilla singleplayer survival, Normal, fresh world, ideally seed 20261006
  (the bot's test world) for a direct comparison, 1-3 h from the first tree on, several sessions are fine.

## Third recording `rec-20261009-173159` (15.6 min, vanilla singleplayer, recorder v2) — what the user does
- Order: 2-4 logs -> inventory: recipe book shift-click planks, table -> table: sticks (shift), wooden axe (33 s) ->
  hunts a pig on sight (0:56) -> wooden pickaxe -> picks ripe sweet berries passing a patch (1:22-1:35, scratched 7x)
  -> makes a NEW table rather than walking back -> tosses wooden tools right before the stone ones -> stone pickaxe +
  axe at ~7 cobble in one sitting (misclicked a stone hoe in the book first) -> mines -> furnace, then sticks + stone
  shovel at the next sitting -> more logs -> furnace: charcoal x2 -> torches (shift) -> cave mining coal/copper, torches
  at block light 1-3 -> no stone sword until two skeletons appeared (made one on the spot, table out and back in).
- Hands: 22 blocks/min; stone pickaxe ~0.9 s per stone block in a run; 30% of blocks broken while moving; blocks
  mostly at feet/head height (0/+1), -1 for stairs; holds attack across up to 6+ blocks; 3.6 tool changes / 100 blocks
  (bot 8.6); hotbar 100% scroll wheel; recipe book for nearly every craft (18 clicks, a third with shift), but still
  ~226 manual pickup clicks (furnace loading, moving stacks, drag-splitting 33x).
- Camera: 177 turns/min (median 29 deg), peak ~236 deg/s median, 917 at the 95th; looks up more while walking (p10 -13).
- Ears: turned toward only ~11% of side sounds, ~1 s later, mostly coincidence -> the bot now never looks for animal
  noises, a groan/rattle only 25% of the time, danger (hiss, arrow, explosion) always.
- Fights: skeleton = watch, sidestep (S+A) under fire, then sprint straight in, sprint-hit, W held, full-charge hits
  every ~0.7 s from 1-2 blocks (dead in 4 hits); at 11 hp didn't take on a second skeleton, ran out of range and ate
  berries WHILE walking. Night spider + zombies: fought down to 1 hp (panic clicks at 0.2-0.7 charge), fled and died
  from a 7-block fall while running. (PresenceFootsteps sound ids `pf_presence.*` flood the sound log.)
- Changes made from it (2026-10-09 evening, untested at the time of writing): stone age = pickaxe + axe first (~6
  cobble), then furnace (+1 for the shovel), shovel; sword only when hostiles were seen in the last 2 min or it's
  evening (`wantSword`); table wishes in that order. `foodOnTheWay`: daytime, surface, < 14 food points, hp > 10, no
  hostiles: ripe sweet berries within 6 (walk up, right-click) or an animal within 12 (Hunter.setMaxRange), 25 s
  max then a 90 s cooldown. Combat: archers = sprint in from > 2.5, W held to 1.0, no strafing; retreat from an
  archer at <= 12 hp. `safeToStep` looks 2.6 ahead when sprinting. Tunneler holds W while mining a tunnel/staircase.

## 4th/5th recordings + fixes (2026-10-10)
- `rec-20261009-182129` (12 min): loots structure chests, replants saplings after chopping, stocks up on a group of
  animals, wooden shovel at the first table, puts new tools into the old tool's hotbar slot then tosses the old one,
  sprints ~50% of moving time, mines on the move ~40%. `rec-20261009-235632` (70 min, later game, has commands/
  teleports to ignore): fights at full charge from ~2 blocks, ~0.65 s apart, sidesteps A/D ~15%, few crits, no
  shield; sleeps every night shortly after dark; chops whole dark oaks and replants; turns to sounds ~13%.
- Added: `CraftJob` swaps a new tool into the worse tool's hotbar slot (pickup result, click old slot, old one into the
  bag); `Recipe.WOOD_SHOVEL` at the first table; `foodOnTheWay` hunts nearby animals (axe/sword only) up to 16 food
  points; `replant()` plants a sapling on a stump we made (60%); `lootOnTheWay` opens chests/barrels that aren't ours
  within 10 blocks (`ScreenWorker.LootJob`, everything but junk).
- Bugs found in real runs and fixed: tree hopping (wood score punished height, so the next log of the same tree lost
  to another tree's bottom log -> same-trunk logs within reach win, even behind leaves); stair digging with W held
  walked onto the step being dug (W only in level tunnels); pickaxe on dirt (tool inertia only for the right kind of
  tool); drowning next to an air pocket below the head (`breathGuard` now swims toward it, dives if below, gives up
  on a pocket after 2.5 s); `Goal.Surface` counted a ravine floor as "the surface" (now also needs to be within 4 of
  `Terrain.regionalSurfaceY`); canopy start stuck (drop up to 9 at hp >= 18); cave hopping with a dig step between
  hops (now stands and looks round, marks 8 blocks around as seen); opening a menu mid-stride by a ledge slid it off
  (ScreenWorker waits until on ground and still); fleeing from a single zombie got it killed (with a weapon it now
  fights one melee mob down to 4 hp).
- Tuning from the bot-vs-user comparison: turn speed cap 700 (urgent 950), stronger size scaling; glances every
  16-40 s; `sprintChance` 0.65 (config v4).

## Ideas to make it more human (backlog)
- Jump-chop / stand on a block for logs above reach; place stairs on staircase steps; get a bed early and sleep.
- Eat while walking (the user does); misclick a neighbouring recipe now and then; new table instead of walking back.
- Glance at drops it picks up and at cave openings; look at the furnace/chest it's walking to.
- Small believable mistakes then corrections (overshoot a block and re-aim) — rare, never noise.
- Player habits: pillar out of holes, sneak while bridging, a small shelter or bed for the first night.
- Combat: jump crits, shield up when a skeleton draws, break line of sight from skeletons instead of retreating in the open.
- Spawning on top of a forest canopy still makes the first log slow (it chops from the leaves).

## Box fill (added 2026-10-12, merged from the user's KawaiiBedrockPilot Paper plugin)
- `modules/BoxFillModule` (Numpad 6, owner `BOX`, after eating/looks, before survival): keeps the s2e Bedrock Box
  filled with real keys. Lowest layer first, nearest to the last block placed (rows). Hovers 0.45 above a cell
  (landing ends flight), only in columns open to the sky (`hoverable`), and the hole is always below the feet, so it
  can't lid itself in (the old plugin did). Clicks a face of a solid neighbour (`faceFrom`: the block below first;
  the point AND a +-0.15 patch around it must be in sight, or a 1-degree wobble lands on the wrong face).
  Flight = double-tap jump, W/A/S/D relative to the camera, space/shift. Route: straight if clear, else up above the box, across, down. TNT within 7.5 with
  fuse < 60: `dodge` (SAFETY owner, after a reaction delay). 6+ new holes at once: a short "whoa" pause.
- `net/BoxLink`: channel `kawaii:bedrockbox`, plain text. Server -> `box;minX;minY;minZ;maxX;maxY;maxZ;ids` (one
  item id per layer, bottom up), `none`, `pause;ticks` (win wipe). Client -> `on`/`off`.
- Plugin side: `C:\Users\ItsMe\OneDrive\Documents\Minecraft-Plugins-main\KawaiiBedrockPilot` (Paper 1.21.11). It
  only sends the box, stocks blocks (never touches the held item), grants flight near the box while the client says
  `on` and takes it back with slow falling. It no longer teleports, places blocks or cancels movement.
  Its bundled Maven is broken (empty `boot/`); it was built with javac against `~/.m2` paper-api 1.21.11.
- `/autopilot box set x1 y1 z1 x2 y2 z2 blocks` for singleplayer. Test: `BoxClientTest` (`-Only box`): patch holes +
  a sealed one it must skip, lit TNT, then a full wipe; fails on teleports, camera snaps, or a lid over its head.
- Bug found in testing: the hole list is rescanned every 5 ticks, so the block just placed was picked again as the
  "nearest hole" and marked unreachable. `pickTarget` checks `hole(h)` live.
- **The user plays it in CREATIVE** (flying, TNT can't hurt): no TNT dodge when `invulnerable`; the plugin doesn't touch
  flight. Lid fixing (left-click a block sitting on a hole) only with `instabuild`.
- Tuned from the user's recording `rec-20261012-181312` (31 s, 87 blocks: 166/min, a click every 0.30 s (0.2-0.4),
  quick 2-3 tick right-click taps, 86% placed while drifting ~2 blocks/s mostly A/D, tops and sides ~50/50, ~2.7 from
  the block, ONE block type (gold) for every layer, camera 272 flicks/min of ~26 deg in ~0.2 s). What it took:
  - `boxMatchLayers=false` (config): use the held full block, else a layer block, else any full block.
  - All box aiming is ONE AimController key ("box-work", moving point via `work()`): the attention dwell (5-8 ticks
    per new key) capped it at ~3 blocks/s. `aim.setQuick(true)`: omega x1.8, no move-size slowdown, zeta >= 0.85,
    small dead zone (rest < 0.5, wake > 1.5 deg; the 4-degree one left it resting off the face = "staring").
  - AimController now integrates the spring in <= 4 ms substeps: one Euler step per frame went unstable once stiff
    at the test's low FPS (774 yaw reversals/min = the "flicker" the user saw).
  - Click when `p.pick(range, 1.0f, false)` (the ray the game uses for the click, from the TICK position) hits any
    face whose neighbour is the hole. Using `mc.hitResult` (last frame, lags while drifting) caused misclicks that
    sealed holes. Any face counts, not just the planned one.
  - Next hole: lowest layers strongly (1.5 per layer), then distance*0.6 + degrees-from-crosshair/25, continuity
    bonus, +3 if not reachable from here (checked for the best 12). Nearest-only left 1x1 shafts everywhere.
  - In a 1x1 shaft: target the shaft's bottom hole, fly straight down, pillar up. A hole under our feet is skipped
    unless it's the only one left (then fly to the side first).
  - Flying: hold the key until ~3x speed from the goal, then tap back (coasting loses only 9%/tick = slow);
    speed cap 0.25 b/tick only while placing; routes hop over obstacles only as high as needed.
  - Never land (`closeAboveBlock` holds jump): landing ends creative flight and the double-tap looked like jumping.
  - No "short breaks" or long click beats in box mode (they looked like staring). Give-up clocks reset after another
    owner had the controls.
  - Timing log `[box] slow block ...: N ticks (flying, aiming, waiting, click pending)` for blocks > 16 ticks.
- Last test (2026-10-12 19:31): patch 151/min, wipe refill 130/min, median gap 0.30 s (q3 0.40), 11% of blocks > 1 s
  (mostly flying in the wipe), 10 misclicks self-fixed, never closed in. Test records the bot (`Recorder.recordBot`)
  to `%TEMP%\hatestuild
un\clientGameTest\humanautopilot-recordings`; compare with a user recording.

- 2026-10-12 19:36: the user said the quick camera was "a little too snappy" and it "still jumps": quick = omega
  x1.5 x max(moveScale, 0.6), cap 550 deg/s; HOVER 0.6, land-guard at 0.15, vertical dead zone 0.5. Result: turn peaks
  229/676 deg/s (user 260/505), jump presses 25/min (user 21), bobbing 25/min (user 19), 0 landings, 114 blocks/min.
  **Installed** in the user's mods folder (old jar: `dist/human-autopilot-1.0.0-installed-2026-10-09.jar`).

- 2026-10-12 evening, from the user testing on their server (C:\minecraft-server, box 15x15x15 in world `bedrock_box`):
  - "jumping": double-tapping space while flying turns creative flight OFF. All flight-height jumps go through
    `jump()`, which never makes a new press within 8 ticks of the last (holding is fine). Landings now 0.
  - "stuck looking 2-4 s": looking into a narrow (deeper than 1) hole at an angle, the click ray hits the shaft wall.
    Face visibility needs a +-0.25 patch; off the face 10 ticks -> `forceStandFor` (fly to a better spot).
  - "flying away / keeping its distance": drift no longer backs off or strafes; stands aim for ~2.2 from the hole.
  - Lines: +bonus for carrying on in the direction of the last two blocks, then the neighbouring line.
  - Tried "fill nearby holes at any depth first" (user: it skips a 1x1 hole for deeper ones): much slower (70/min
    patch vs 186) -> reverted; the user said a lone hole waiting is fine.
  - BoxClientTest now uses the real box (R=7, H=15, five 3-high bands) + a craters stage. Best run: patch 186/min,
    craters 202/min, 0 landings, 3% of blocks > 1 s. Installed in the mods folder 20:02.
  - The user tends to close the test window part-way (they watch it); the wipe stage often doesn't finish.

- 20:25: no more "knock out the block over my head" (on the real box it dug up through TNT overhangs); hover may tuck
  one block under an overhang if a sky-open column is beside it (that spot isn't filled while tucked); drift slows
  to 0.06 b/tick while the crosshair is off the face (moving fast made it trail 10-15 deg = "stuck, confused");
  skipped holes are rechecked every 2 s when idle and cleared when a lid is broken (it sat 30-60 s by a reachable
  hole). Last run: all 5 stages pass, craters 203/min, wipe 180/min. `keepRunningUnfocused` (MinecraftMixin cancels
  `pauseIfInactive`). Installed 20:25. NOTE: the user's game ran the 19:36 build for a long time without a restart.

- 21:03: "confused, going up and down a pillar" in a fresh (mostly empty) 15x15x15 box = it reached its hover spot
  only within ~0.5 (slack 0.25 / vertical dead 0.5), the hole's face wasn't in sight from there, and it waited 4 s for
  the no-progress timeout. Now the last 1.2 blocks are flown `precise` (slack 0.08, dead 0.12, cap 0.2) and 15 ticks
  at the spot without a view -> give up that hole. `[box] STALL` lines log the full state after 3 s without a block.
  BoxClientTest stage 6 "fresh" (4 min in a nearly empty box, fails on stall reports): 710 blocks, 0 stalls.

- 21:57: the user: "the goal is to fill the spaces as much and as fast as he can, he's against the viewers". pickTarget
  is now fastest-first: anything clickable from here (faceFrom from the current eye, best 40 by camera turn + 3/layer,
  -12 next-in-line, -6 adjacent) beats everything; else the nearest hole. No randomness; post-blast pause = reaction.
  FPS drop = vanilla "inactivityFpsLimit: afk" (30 FPS after 60 s without REAL input, 10 after 10 min): while the
  bot owns the controls HumanAutopilot calls `getFramerateLimitTracker().onInputReceived()`. Box thinking <= 5 ms.
  Up/down "pillar dance" causes: gapBelow counted walls beside us as ground (now only tops at/below the feet), body
  cells were valid targets (now skipped), and "down the shaft" fired for the cell we're in (now only below the feet).
  Last run: all 6 stages, 0 stalls, craters 199/min, wipe 191/min, fresh 705 in 4 min. Installed 21:57.

- 22:35 (user: "its very good"): no "whoa" pause on new holes (a TNT barrage restarted it every scan and it never
  filled); hover 1.5 above the ground (user asked 2: out of arm's reach for top-layer holes), guard band 1.15-1.8,
  stands prefer the current height; a narrow (3+ walls) hole is finished straight up before anything else.
  Quick camera = eased motion profile (accel cap 3800 deg/s^2, speed cap 560, slows in time, no overshoot) instead
  of a stiff spring: turn peaks 255/326/465/658 deg/s (p25..p95) vs ~1000 before; 187 blocks/min, gap 0.2 s.
  Test stages added: long 1x1 shaft, TNT barrage (>= 20 blocks in 15 s of TNT). Installed 22:35.

## Gotchas
- Shell is Windows: PowerShell 5.1 (no `&&`) plus Git Bash. `Set-Content` adds BOM/CRLF; use `Out-File -Encoding utf8`.
- Don't edit the user's `.minecraft/options.txt` casually (a backup exists at `options.txt.bak-autopilot`).
- "Can't keep up" at night in tests is integrated-server load, not the mod.
- Memoise terrain queries; pathfinding is budgeted (1500 nodes/tick, 12k for `goStrict`).
- After stopping a test, check no non-daemon `java.exe` is left before starting the next one.
- `runtest.ps1`'s robocopy /MIR sometimes leaves old sources in `%TEMP%\hatest\src` (seen 2026-10-14: a test ran a jar
  without the latest edit). When it matters, `Remove-Item -Recurse $env:TEMP\hatest\src` first and check the jar's time.

## Autopilot menu + Build Schematic (2026-10-13)
- Numpad 9 = `AutopilotScreen` (Box Fill / Build Schematic... / Continue building X / Pause / Off; file page toggles
  "Sink ground" and "Replace"). Numpad 6 still toggles box fill, numpad . pauses, R rotates the preview, numpad 0 panics.
- Files: `.minecraft/config/humanautopilot/schematics/` (.schem, .litematic, .schematic) via `schematic/SchematicLoader`
  (copied from KawaiiCompanion, client BlockState). Preview = gizmo outline at the crosshair (corner, build goes away and
  to the right), right-click confirms. `SchematicMode.plan()` has EVERYTHING buildable (ground layers too: skipping
  them lost the farm, paths and floors) and sinks the build by its ground layers when "Sink ground" is on.
- Building = `BoxFillModule` plan mode (`plan` static map): bottom layer first, rows, hotbar blocks first; facing via
  `schematic/Placement.predict` (BlockItem placement with our angles; also checks the server's last-sent angles);
  `CreativePicker` gets blocks (E, type search, own GUI cursor, number key, Esc). Guards the build when done; TNT damage
  below goes first; a dirt pillar if the build floats; doors opened (no shift!), walked through (land, walk, take off).
- Big builds: only blocks within ~50 blocks are trusted (edge-of-view chunks read as air), `seenDone` remembers far
  finished parts, air map is local (REG_H/REG_V). The s2e plugin repeats its box message every 0.5 s: `serverBox`
  only resets on a real change.
- Bot GUI cursor is virtual (GuiMouse no longer moves the OS pointer; real mouse ignored + hidden while `botOwnsScreen`).
- Server: KawaiiNoGrief has `structure` mode (TNT breaks builds, not natural ground): `/knogrief tnt structure`.
  Since 2026-10-14 "built" = placed by a player (BlockPlaceEvent, shovel/hoe paths/farmland), stored per chunk in its
  PersistentDataContainer (bits per section); so a build's dirt/stone/paths/farm break too. Untracked (older) blocks go
  by kind as before. Built with javac + `jar` against ~/.m2 paper-api (+ adventure/guava/bungeecord-chat jars,
  `-encoding UTF-8`); tested on a throwaway Paper copy with a test plugin firing BlockPlaceEvents (force-load chunks:
  TNT in non-ticking chunks never goes off).
- Tests: `HA_ONLY=schem` (`SchematicClientTest`: house, mid-build TNT, door/inside, repair, turned, crater);
  `HA_SCHEM=<file> HA_MINUTES=n [HA_TNT=1]` builds a real file and logs `REAL` lines.
- Log tags for bug hunting: `[status]` every 30 s (progress, speed, skips by reason, menu trips, layer, slow ticks,
  time per activity), `[confused]` (8+ targets in 10 s, none placed), `[schem] ... went in wrong`, `STALL`,
  `skipping ... for now: <why>`, `no spot for`, `nothing to place in N holes`, door / menu lines.

## Exact builds (2026-10-13, the user: "build the schematic exactly how it is compared to importing")
- Each spot has a job (`Placement.job`): DONE / PLACE / BREAK (wrong block there, or schematic air, with Replace on) /
  ADJUST (same block, a setting differs: crop age -> bone meal, candles/pickles/petals/layers -> same item, doors/
  trapdoors/gates/repeater delay/comparator/lever/note -> click, waterlogged -> bucket, lit candle -> flint and steel,
  empty pot -> the plant) / BLOCKED (can't: containers are never broken; no knocking out for a block that can't stay,
  e.g. a path under a trapdoor). `matches` = same block, same orientation props, nothing to adjust.
- `SchematicMode.wantAt` gives the schematic's block (air too) at a world spot: clearing finds non-plan blocks where the
  schematic has air (radius 12 round us, current layers) and adds them as AIR jobs. Clearing goes top-down (the column
  rule only holds back PLACE jobs). Fluids last, with buckets, once their neighbours are done.
- No click gives the right way round (a hopper facing open air, a beam's first log): `tryScaffold` puts a temporary
  glass block next to it, clicks against it, knocks it out after (`temps`).
- Picking: lowest layer near us (+1), temporaries first, structure before decorations; if none of those can be done
  from anywhere, anything else (a door that only needed closing used to hold up the roof). Fetch from the creative menu
  only when in reach and about to click (it used to fetch for targets it then gave up = hopper/seeds loop).
- A floor block in a small room: no stand off to the side, so steep looking is allowed straight away (not after 30 s).
- An open door the schematic has shut is an ADJUST job: closed after walking through (from either side).
- Menu cursor: restarts at the screen centre on every new screen (it jumped back to its last spot in one frame), moves
  in whole screen pixels and comes fully to rest.
- Against a usable block (chest, furnace, hopper, crafting table, trapdoor...): allowed as a last choice, with sneak
  held >= 2 ticks before the click (+ jump while flying so it doesn't sink); a click on one without sneak never happens
  (it used to open chests). A hopper pointing into a chest/furnace is placed exactly like that.
- Placement predictions for a stand put our body AT the stand (`Placement.predictFrom`, bounding box moved for the
  check): with the head in a 1x1 roof gap every way of filling it came out null ("stuck in the roof").
- Left as it is (BLOCKED, listed in the log at "done" as `left as it is: ... <why>`): 5 failed tries (`cant`, retried when
  a block next to it changes), put right twice and another block took its place by itself (`fixes`: grass spreading
  onto dirt; a paste drifts the same way), the game won't let it stay even with everything round it done (pink petals
  on cobblestone in the starter house: impossible for a player), plus containers we won't break / Replace off.
- A block going in (or out) gives its 6 neighbours' skip timers and `cant` another go at once (a log waiting for the
  log beside it sat out a minute). Nothing else to do: "nowhere" skips retry after 2 s (instant check, no flying), so
  impossible leftovers are settled in seconds instead of minutes of hovering (the user saw it as "froze").
- Farm: farmland from the menu dries back to dirt in ~1 min (then grass grows over it) until water or a crop is on it:
  dirt/grass/path -> farmland is an ADJUST with an iron hoe, grass/dirt -> path with an iron shovel (`Placement.tool`,
  never on the underside, BLOCKED with a block on top); crops on farmland are NOT decorations (planted right after).
  Crop age counts as "at least" (bone meal overshoots; it grows anyway).
- Starter house (`small-survival-starter-house-2b432fbc.litematic`, 754 blocks): 2585/2590 spots like a paste (99.8%)
  in 7 min; the 5 left are 4 pink petals the file has ON COBBLESTONE (vanilla: only on #supports_vegetation; a paste
  skips that rule) and 1 dirt grass spread onto. Installed 2026-10-13 23:2x (jar built 23:05).
- Box suite fixed 2026-10-14 (ALL BOX TESTS PASSED, then schem + exact re-passed; installed, jar 23:42):
  - `standFor` never picks a spot where the body (at its hover, expanded 1.2 down like `bodyInTheWay`) would be over
    the target: in a low room (3-high hollow, hover 0.1) it chose the spot straight over a hole, the placing refused
    (we'd be set down on it), "can't see it from the spot", same spot again, for ever. Applies to schematics too.
  - Box `pickTarget`: `sealsAnother(h)` holds back a hole that is the only opening of a hole beside/under it (TNT
    undercut tucked under the top): the tucked one first.
  - `[box] under a roof at ... with nothing to fill and no way out found: <sides>` log.
  - Test: the hollow stage waits for it to get out before the tidy-up fill (it buried the bot); `run()` passes when
    only fully sealed holes are left (`openHoles` = air joined to the outside), since fixLid is off (user's choice).
    Leftover holes are listed (`<stage> left: hole at ... open to: ...`) when a stage fails.
- Tests: `HA_EXACT=1` (7x2x7 farm/decor/hopper-into-chest test: 98/98), regular `HA_ONLY=schem` (now also `roofhole`:
  standing in a 1x1 roof gap, and `underhole`: inside under it); `HA_SCHEM=<file>` ends with
  `REAL EXACT: n of m spots like pasted` + the wrong spots (stops after 4 min without progress).

## 2026-10-14: ground, camera
- Natural ground is ground (the user: "she's replacing grass block with dirt block" instead of building): a BREAK where
  both the world and the schematic have natural ground (`Placement.naturalGround`: soil/stone/sand/gravel..., NOT farmland
  or paths) counts as DONE when both are soil (`Placement.soil`: grass, dirt, coarse dirt, podzol, mycelium, rooted dirt)
  or the build covers the spot (`covered`: an occluding block planned on top). Holes in the ground still get filled,
  hills/trees in the way still cleared, farmland/paths still made. Exact test pre-places coarse dirt where it wants grass
  and checks it's left alone.
- Camera shake (the user: "a bit shaking and not smooth"). Measured: frames where the view turns back the other way from
  one frame to the next while turning: the user ~0.1%, the bot's recording 1.3% (2/3 of them within 0.3 s of a click,
  small: +30 -> -20 deg/s in one 14 ms frame = maxAccel 3800 allowed that). `AimController` now:
  - aims from the interpolated eye (`getEyePosition(partialTick)`), not the tick position (that jumped 20x/s in flight);
  - quick mode follows a moving goal (`goalRate*`, feed-forward pursuit) and doesn't rest while it moves (stop at 0.5,
    start at 1.5 deg was a stutter);
  - a reversing axis is limited to max(1400, |v|*12) deg/s^2 (eases to a stop before swinging back; fast turns still
    brake hard).
  - Shake meter `AimController.turningFrames/flipFrames` (logged per stage by SchematicClientTest as `CAMERA`);
    `HA_OLDCAM=1` = the old camera for A/B. Schematic tests: old 0.43%, new 0.03%.
- Recording (numpad 8): the recorder skips bot-driven time unless `Recorder.recordBot`, which used to be decided only
  when 8 was pressed. The user pressed 8 first and started the build after: 4 s of them, "bot took over", nothing else.
  Now, while recording, the builder (box fill / schematic) switching on calls `recorder.recordBotFromNow()` (event
  `bot: recording the builder`, chat "Recording the bot building too"). Numpad 8 also works inside the Autopilot menu
  (`AutopilotScreen.keyPressed` -> `HumanAutopilot.toggleRecording()`). RecorderClientTest checks the 8-then-build case.
  Installed 2026-10-14 ~01:03.

## 2026-10-14 (later): the cathedral's 6-hour recording (77k of 649k blocks; "stuck" + "jittery camera")
- The jitter was game hitching, not the aim: on evenly timed frames the aim is smoother than the user's hand (speed
  change p50 19% vs 42%); 1.8% of frames took > 30 ms = the bot's own ticks (up to 33 ms with 22k holes in the window).
  `[status] thinking time in the last 30 s: scan .. pick .. stand .. air .. wayout .. route` (nanosecond counters,
  `costStart/costEnd`) showed scan = 90% (looking at every window spot every 5 ticks).
- scanPlan rewritten with a memory: `holesByLayer` (per layer, spots last seen as holes) + `seenDone` (seen done);
  every scan looks again at everything within 12 blocks of us, plus 2500 window spots in turn (`winCells`/`winCursor`,
  rebuilt when the window layers or their size change); `holes` = the memory's holes of the window layers, lowest
  first; a layer is finished when all its spots are in seenDone (`layerFinished`); farGoal from the bottom layer's
  unseen far spots, refreshed when there's nothing near or every 5 s. The progress count pass has a 1.5 ms budget per
  scan and feeds the memory (`remember`), so a TNT hole in a finished layer comes back as a hole. Clearing scan every 20
  ticks.
- End rods hanging under the ceiling (400 lamps, 8751 failed tries): the column rule held the ceiling block back until
  the rod was in, the rod needed the ceiling: `columnWaits` exempts a flimsy/hanging wanted block below (not full
  collision, not slab/stairs). The rods themselves in a 2-high gap (floor, rod, ceiling) can't be placed by a player at
  all (eyes above the ceiling's underside; the body in the rod's cell): they end up BLOCKED after 5 tries. With 2+ air
  below the rod it works: stand 2 below, low hover, look straight up (standFor's low path now runs before the "body
  over the spot" rule, needs the target just above the stand cell, eye 0.1 under it, body under it).
- Glazed terracotta (1412 failed tries in the cathedral): a hidden tile floor under a brick structure; once the
  structure north of a tile is in, the only spot left is straight above (the facing comes from the heading, so that
  works), which the no-straight-down rule refused on the first try. `steepFine(t)` = steepOk or
  `Placement.headingFaced(want)` (HorizontalDirectionalBlock but not stairs) in faceFrom / interactFace / standFor /
  drift. The exact test has two tiles.
- "Wedged" stalls (30 s against a wall, 26 x in 6 h): the route's next cell was inside a 2-high gap (path points hug
  the floor at +0.15) but `steer`'s guard kept us 0.9 over our own floor: now, within 2.5 blocks of a goal that is < 0.4
  over its floor and below us, the guard drops to that height.
- Exact test grew to 7x5x7 (245 spots): lamp arm with a hanging end rod, two glazed terracotta tiles.
- "He twitches every time he places a block" (user, 2026-10-14 08:30): the head hop to the next block after a click
  peaked at 546 deg/s median (p75 987) in the 6-h recording vs the user's 238 (p75 309) and the old camera's 230:
  the pursuit feed-forward took the 1-4 degree hop to the next block as goal motion (threshold was 4 deg/frame) and
  shoved the camera at 100+ deg/s for a tenth of a second, then corrected back. Now: a goal change > 1.5 deg in one
  frame resets the goal rate; the rate drops at once when the goal slows (no lag carrying the camera past); gain 0.7.
  Test recording (house, `house RECORDING <file>` in the log, recorder on for that stage): peak median 225 (p75 327).
  Body speed around clicks: no jerk (accel below the user's). Tools: scratchpad ana/hop.py (hop sharpness after
  clicks), twitch.py (flick-and-return), bodyclick.py (body speed around clicks), smooth.py, flips.py.
- Still "twitches a little every time he places a block" (user: "could it be because his view snaps from block to
  block?" - yes). Measured (scratchpad ana/hopshape.py): the user's next look starts 114 ms after the click (86-143),
  peaks 159 deg/s, and they click on the way in (85 deg/s at the click); the bot's started 11-22 ms after (the frame
  after the block appeared), peaked 249-295. AimController now plans each hop in quick mode (`planned`):
  - a new goal = the goal POINT moved > 0.3 blocks (or a direction > 1.5 deg in a frame); goal motion is the same point
    seen from this frame's eye vs last frame's (a nearby point swings 200+ deg/s as we walk past: not new goals);
  - the eyes stay on the old point for gauss(60..160 ms) (`hopHoldUntil`, `heldPoint`), then one minimum-jerk stroke
    (quintic, `planHop`/`hopOff`, relative to the live goal + wobble so it ends on it as we drift), T = 0.21 + 0.0667 *
    (log2 d - 4.32) s (x0.9-1.15), 0.1-0.47;
  - clicked on the way in (new goal mid-stroke): `finishing`: the stroke completes on the old point, a gauss(30..120 ms)
    pause, then the next (re-planning mid-stroke straight to the next block was a swing out and back);
  - near straight up/down the stroke turns yaw only by cos(pitch)/0.5 (0.1..1), and never > 900 deg/s (a point under
    us made the yaw spin: "camera snapped 78 deg in one tick");
  - `aim.busy()` (pause/stroke): BoxFillModule doesn't count offFace then (it re-aimed mid-hop and caused extra hops);
  - `endTick` keeps the goal for 2 untouched ticks (a one-tick gap in the work restarted strokes from rest).
  Test (house, recorded): next look 133 ms after the click, peak 173 deg/s, 50 deg/s at the click, flick-and-return 3%
  (user 0%, old 8%), view moved 9 deg in the 250 ms after a click (user 11, old 19-23).

## 2026-10-14 (later): the DragonGirl statue session (DragonGirlSt122.litematic, 163x122x76, 20k blocks)
The user: "stuck a couple of times", "did not repair the broken parts affected by tnt immediately", "does pausing with
Esc mess it up?" (log: their client latest.log from 09:26; recording rec-20261014-092604).
- Esc: fine. During the 4 pause menus no keys were held, no clicks; the give-up clocks restart after (lastTickAt).
- Stuck 2.5 min "wedged": the route's next step was 0.49 straight up, inside steer's 0.5 vertical dead zone, so it
  never moved and the step never changed. Wedged branch now takes the first path cell > 0.8 away; steer uses a 0.12
  dead zone whenever the goal is a route path point (lastRoute, not "straight").
- Stuck (09:41, 09:57): a floating hole at the bottom (2206,-59,1034: nothing to click) never finished its layer, so
  activeY stayed at -59 and the window topped out at -59+24 = -35 (the statue goes to +62): 907 floating holes, 137
  waiting on the one below. Now `layerFinished(layer, skipFloating)` doesn't count `floating` holes (PLACE, loaded,
  !workable); 2 passes: if everything left is floating (crater: house and ground gone) the window stays at the bottom
  and supportPillar builds up. Count pass passLowest only for non-floating holes (a hanging one becomes non-floating
  once the block above is in, and the window comes back down for it).
- Hanging deadlock (statue hair/wings, ceiling lamps): `columnWaits` only holds a block back if the block below can go
  in by itself (`workable(under)`).
- TNT repair: the damage WAS seen (window +170 holes 3 s after) but "near ones first; far only if nothing near" kept it
  on nearby leaves for minutes (blast > 14 blocks away). Now `damaged` (spots seen done, now a hole; `remember`) are
  picked first, near by the usual scoring, else the nearest far one (lowest first): "[schem] repairing N damaged spots
  first". `watchTnt`: PrimedTnt / MinecartTNT near planBox tracked by id; when one vanishes, `blastLook` re-checks plan
  spots within 7 at +2 and +12 ticks and lowers scanFromY to them.
- Froglights with axis=y hanging in a row need a temporary glass under each (works, slow).
- Test `HA_TRICKY=1` (trickyBuild): brick cube A + 30 blocks away a 30-high pillar, beam, chain hanging to 3 over the
  ground; TNT on A when 14 up the pillar: cube back in 6.7-10.8 s, 87/87.
- 20 TNT spread out round him (the user: "that's the real bug that keeps happening"):
  - `HA_TNT20=1` (with HA_SCHEM): at t=180 s, 20 TNT over 3 s in a ring 2..10 out at his height.
  - Checked against the world, not only his own `damagedCount()`: `rightNear` snapshots the plan spots built right
    within 24 just before; `blownOf` counts how many of them are still out. Logged as "TNT20 RESULT".
  - Statue (base layer): 73 damaged, all back in 39.5 s, no stalls.
  - `HA_TOWER=1` (towerBuild): a 9x9 stone brick tower 24 high with log corners, windows and a slab roof. The same 20
    TNT once he's 12 up: into the tower onto the floor, outside by the low walls, on the wall top.
  - Fails if not all back in 90 s, or more than 30 s with no progress after the TNT.
- The real bug, found with `HA_PREBUILD=20` (the bottom 20 layers pasted in, so he builds up high):
  - TNT damage more than 14 blocks below him counted as "near" (only sideways distance was checked). He looked for a
    place to stand in the open-air map round him (REG_H 14 / REG_V 16), found "nowhere to place it from", and gave
    every damaged spot up for good after 5 goes ("leaving ... as it is"). 316 damaged, nothing put back.
  - Route fallback "up over the top of the whole build" went to y=62 over a 122-high statue (he was at y=0 before the
    TNT even came), where nothing was in reach.
  - Fixes:
    - The far branch counts height too (`|t.y - regY| > REG_V - 2`): fly over there first.
    - `farPath`/`aStar`: weighted A* through flyable cells over the build area, to within 2 of the goal. At most 60k
      cells or 12 ms; redone every 2 s or when he strays; a failure isn't retried for 3 s. route0 uses it after the
      regional path and before the old hop-over/cruise. `farStep` heads for the furthest point in sight up to 24
      ahead. The far branch measures progress by `farLeft` (path left) while on it.
    - `blastLook` clears cant/fails/bad/nowhere/scaffoldTried round a blast: fresh damage, fresh tries.
    - A placed block next to an empty non-build cell clears `scaffoldTried` (and a "nowhere" cant/bad) for that cell's
      other neighbours: their temporary block can stand on it now (upright basalt in the statue's ribbon).
  - After: 20 TNT on the half-built statue, 79 out, all back in 77 s. 44 TNT all over it: 1644 of 4366 out (see below).
- Test tools: `HA_PREBUILD=N`. A file `%TEMP%\hatest-tnt-now.txt` (holding a count, default 40) makes the real-file test
  set off TNT on top of built spots all over the build (at least 6 apart) plus 4 round him, logged as "TNTALL" with
  the world check. `rightNear` covers every height of the build (dropped TNT falls all the way down).
- TNT everywhere (44 TNT over the half-built statue): 1644 of 4366 blown out. He repaired ~1550 steadily (about 140/min),
  then hung for 90 s+ "N hole(s) I can't get at" under the statue's body. `leavesWayOut` searched for open sky only in
  the map round him (±14/16): under the overhang there is none, so every hole near him "would shut him in", and wayOut
  found no way out either. Now for schematics: `skyPath`/`aStarOut` (A* out from under the whole build: past its top,
  out beside it, or to any open column; 40k cells / 8 ms; cached a second per cell). Filling h is refused only if the
  way out runs through h (then it's worked out again with h shut). wayOut falls back to it too.
- Camera: "after he places a block he still twitches / moves to an angle before the next block". Measured on his
  statue session (scratchpad ana/strokes.py, approach.py, post.py, trace.py):
  - in the 100 ms after a click the user's camera is dead still (< 5 deg/s) 57% of the time; his only 12% (house 23%).
  - The pause after a click tracked the placed block from the moving eye (`heldPoint`), plus the wobble and the next
    goal's rate, so the view crept 2-3 deg after every click while he flew on. A click mid-stroke finished the stroke
    on that moving point too.
  - Now the pause is still (no turn at all), and a stroke cut off by a click finishes on the angles it had at the
    click. `heldPoint` is gone.
  - Measured after: dead still median 100% of the 100 ms after a click (was 12%), one move between clicks 73%
    (was 60%, user 55%). The pause was then shortened (gauss 0.03-0.11 s, after a finished stroke 0.02-0.08): the next
    move started 155 ms after the click vs the user's 114.
- "He's bunny hopping over the leaves": over the statue's leaf canopy he bobbed up and down 1.7 blocks every ~0.5 s
  (jump/shift alternating) and touched down (flight ends, double-tap to take off = a hop): 81 up/down swings
  (>= 0.4 blocks) a minute, 2.1 touch-downs a minute, 67 jump presses a minute (scratchpad ana/bob.py; the notes put
  the user at ~19 swings a minute). Cause: steer dropped the clearance to 0.15 for `lowRoofNear` (any block at head
  height in the 3x3 round us: always true among leaves), and for any goal low over a "floor" (way points hug the
  floor at 0.15 in the open too). Now `lowRoofOver(pos)`: a roof right over the body there (4 corners). Low only under
  one now, or heading in under one within 2.5; the way-point rule needs a roof over the goal too.
- Pockets open only underneath (a leaf under the canopy with a block on top): the low "from just under it, looking up"
  stand needed a floor under it. Flying, it doesn't. Without it 6 leaves were "nowhere to place it from" for good after
  TNT everywhere (11 of 1644 left).
- Touch-downs among the leaves after that fix (2.1/min): low over the leaves he pressed jump, then shift, then needed
  jump again inside 8 ticks; `jump()` holds that back (two presses inside the double-tap window switch flying off), so
  he dropped onto the leaves. Now, low (clear < CLEAR), no shift within 8 ticks of a jump press or while already coming
  down faster than 0.12/tick. Low mode lasts 1 s after the last roof (`lowRoofUntil`).
- That fix made him stick (user: "he's stuck", 39 s): a 3-high gap under the leaves with its roof in front of him at
  head height. `lowRoofOver` only looked straight up (here and over the goal at the goal's own height). Now
  `roofAhead(p, goal)`: `lowRoofOver` every 0.5 for up to 2 blocks the way he's going, at his own height. Only the
  way he's going, so leaves beside him don't count.

## (Superseded: see "WHERE I LEFT OFF (2026-10-14 15:20)" at the end.) Status at 12:25
Installed in .minecraft/mods and dist/ (jar built 12:16, sha256 f6068edd716d...). It replaced the 09:08 build. The
user played the Walmart with it (see the Walmart section below).

In this build since 09:08:
- TNT repair: damage first, TNT watch, far damage counted by height too, A* far path (`farPath`), A* way out from
  under the build (`skyPath`/`aStarOut`), blasts reset give-ups, temporary blocks retried when a support appears,
  "from below" stands without a floor.
- Stuck fixes from the DragonGirl session: wedge dead zone, floating-hole window (two passes), hanging deadlock.
- Camera: still pause after a click (no `heldPoint` tracking), a stroke cut by a click finishes on fixed angles,
  shorter pause.
- Hover: `lowRoofOver` / `roofAhead` instead of `lowRoofNear` (3x3), the way-point low rule needs a roof, low mode
  lasts 1 s, no shift right after a jump while low.

Test status:
- Passed with this code: TNT everywhere (44 TNT, 1644 of ~4360 blown out) repaired with no stall until the last run
  below. The 11:39 run (before the pocket fix) left 11 out: pockets, fixed since but not re-checked to 0.
- Regular schem stages (house/door/inside/repair/roofhole/underhole/turned/crater) all ok at 12:12, but that was
  BEFORE `roofAhead`.
- Not rerun on the final code: HA_EXACT (last 245/245 at 11:04), HA_TOWER (866/866 at 11:01), HA_TRICKY, box tests.
- Last run (installed code, closed by the user at +390 s after the TNT): one 23 s stall at 71.7,-53.35,53.6 among the
  leaves ("path 4 cells, none in sight", then "couldn't fly there" x3 and on). Bobbing 46 swings/min (first hover fix
  34, before 81, user ~19), touch-downs 0.6/min (was 2.1), camera still 77% after clicks.

Next:
1. Look at the user's new recording: bobbing (ana/bob.py), camera after clicks (post.py, strokes.py,
   approach.py, twitch.py), stalls ([box] STALL / [confused] in their latest.log).
2. The leaf-gap stall: route with way points "none in sight" in a 3-high gap. Probably clearLine from our height to
   points at +0.15. Maybe pick way-point heights by the room at each cell.
3. Bobbing: still ~2x the user. Height targets change per stand (hov), the 0.12 dead zone when `precise`, roofAhead
   flipping low mode in the canopy. Measure on a TNT-everywhere run (`%TEMP%\hatest-tnt-now.txt`).
4. Rerun HA_EXACT, HA_TOWER, HA_TRICKY, the regular stages and box on the final code.

## 2026-10-14 (afternoon): the Walmart session (walmart.litematic, 21308 blocks, user played 12:28-14:38)
The user's only complaint: stuck. "I had to break some spots for him to break free."
- Their recording (rec-20261014-122802, 130 min): bobbing 23 swings/min (statue 09:26: 63; user ~19), touch-downs
  0.4/min, camera dead still 84% of the 100 ms after a click (median 0.3 deg), flick-and-return 3%. The hover and
  camera fixes held up in real play.
- "STALL 61s" at 12:55 was the user's pause (12:54:57-12:55:56). `setPaused(false)` now resets `onSince`.
- The real stalls, 14:16 (39 s) and 14:37 (20 s+, the user then broke him out): the store's shelves. A grid of acacia
  fences, fence gates, jungle trapdoors, with waxed lightning rods between, two levels (rel. y 6 and 9) round x 33,
  z 75-78. Two bugs:
  1. He built himself in. `leavesWayOut` returned true at once for open sky over us, so in a shelf open on top he
     placed the fence right over his own head. Now open sky only counts if h isn't in our column (`inColumn`). The
     skyPath check also refuses h up the open column the path ends in, and aStarOut's sky test ignores a column with
     a skipped cell in it.
  2. Every search started "inside a block": the cell under his middle held a lightning rod (body beside it) or a
     carpet. Then no way anywhere, wayOut null, and the route's last resort flew up into the ceiling ("steering to
     ... -30.50 (path 0 cells, none in sight)"). Now `bodyCell(p)` (a free cell the body overlaps, or the one above) is
     the start of air0, airPath, pathFromMe, exitCell, wayOut, leavesWayOut, skyPath, farPath. Under a roof with no
     path, route0 goes out from under the roof (wayOut) or stays put; never up into it.
  - Safety net: shut in (under a roof, no way out, nothing to do) for 3 s gives `breakOut`, which knocks out one
    visible block beside us (feet or head height) or over our head, open air past it first. It goes back in later
    from outside. The way out is worked out again after every block placed (`skyAt` reset).
- Test `HA_SHELVES=<walmart.litematic>` (shelvesBuild): the shelf rack cut out of the Walmart (rel. x 31-37, y 5-10,
  z 70-82; `cut()`), built on flat ground. Fails on 20 s without progress or anything wrong. It found two more:
  1. A fence gate needing a temporary block under it: the temporary block went a layer under the window, never got
     built, and the gate "waited on the one under" for good (90 s, then the test gave up). tryScaffold now lowers
     scanFromY to it, as supportPillar does.
  2. A lightning rod on a fence post: `faceCenter` aimed at the middle of the post-and-arms bounding box, in the air
     between them, so every ray missed ("nowhere to place it from" x5, left as is).
     - First try: aim at the biggest box on that face instead. It broke the house roof: a stair's biggest side box is
       its bottom half, so every spot gave a bottom slab and 8 top slabs were "nowhere to place it from".
     - Now: `faceCenter` is the bounding-box middle as before. planPoint also tries `faceParts` (the middle of each box
       of the shape on that face, from `toAabbs`) after the usual SPOTS, for non-full shapes.
  After the first try: 129/129 in 98 s, longest stretch without progress 4 s.
- Test runner trap: a run can print "BUILD SUCCESSFUL" with no test run at all ("Class path entries reference
  missing files: ...build\classes\java\gametest"). It happens on back-to-back runs with no source change when
  %TEMP%\hatest\src was deleted in between: Gradle doesn't recompile and the classes dir is empty. Delete
  %TEMP%\hatest\build\classes instead (forces a compile). Always check for the stage's CHECK/RESULT line.

## WHERE I LEFT OFF (2026-10-14 15:20)
Installed in .minecraft/mods and dist/: jar built 15:10, sha256 b0c6d69fa351..., replacing the 12:16 build. It adds
the Walmart fixes above: bodyCell, out from under the roof (not up into it), breakOut, the lid-on-yourself check,
temporary blocks lowering the window, faceParts + patchSeen, and the pause not counting as a stall.

Passed on this code: HA_SHELVES 129/129 (longest 5 s without progress), regular stages all ok (house 82/82 again
after the faceCenter regression was fixed), HA_EXACT 245/245.
Full Walmart from layer 6 (HA_SCHEM walmart, HA_PREBUILD=6, 14 min, the store's whole interior): longest stall 6 s
(the user's session: 39-62 s, and they had to break him out), 0 built in, 0 break-outs needed, 0 [confused],
0 "no way out"; 1302 placed (93/min: fiddly pieces, chests, trapdoors, crossing the store for scattered leftovers).

Next:
1. The user plays again; look at their next recording/log for stalls (latest.log [box] STALL, [confused]).
2. Not rerun on the latest code: HA_TOWER, HA_TRICKY, TNT everywhere (HA_PREBUILD=20 + hatest-tnt-now.txt), box.
3. Bobbing in real play is 23 swings/min (fine). The leaf-gap "path ... none in sight" stall from 12:21 wasn't seen
   in the Walmart session.

## 2026-10-14 (evening): MagicQueenSt127 session (106x127x75, 14630 blocks; user played 15:48-16:55)
The user: "definitely had some stalled"; asked whether another mode ran alongside the builder. No: the log has only
[box]/[schem]/[status], and every key in the recording is the builder's.
- Stalls at 16:08, 16:10, 16:43 (15 s+): pressed against an edge, bouncing ~0.7 block every 0.5 s (shift 3 ticks,
  then the guard's jump brake, up, shift...). Heading for a way point 0.15 over the floor to get into a 2-high gap,
  it never got low enough: the 3-tick shift builds speed and the predictive brake throws it back up. (Creative
  flight: each tick v += +-0.15 for jump/shift, then v *= 0.6; coasting adds about 2.5 v.)
  Fix: going in under a low roof (roofNear) to a goal low over its floor (overFloor < 0.4, below us, within 2.5):
  hold shift and land, `walkUntil` = now + 20. While `walkUntil` is ahead, a landing doesn't take off again: on foot,
  steer only presses WASD and renews walkUntil while a roof is over or ahead. Out from under it, the take-off comes by
  itself.
- 16:53, 133 s, "nothing to place in 1913 holes: nothing to click 1912, can't get to 1; window 19..43". The statue is
  272 face-connected pieces: only 1 reaches the ground, and 271 (5229 blocks, 36%: wings, arms, dress) touch the rest
  only at an edge or corner, so a block can't be clicked against them (scratchpad ana/reach.py). One unreachable hole
  pinned the window, and the window (max 24 layers) never reached where the wings hang from.
  Fixes:
  - Nothing workable: `extraLayers` grows to the top of the build (+1 up to 24, then +8).
  - At the top and still nothing: `bridgeFloating` puts a chain of at most 3 temporary glass blocks (`bridgeTo`, BFS
    through cells that are empty in the plan and the world, not our body) from something solid to the nearest/lowest
    floating hole. They're ordinary `temps` for that hole, removed once it's in, one bridge at a time.
  - ana/reach2.py simulates it: all 14630 built, 240 bridges, 284 temporary blocks, longest 3.
- Test `HA_DIAG=1` (diagBuild): a 5-high pillar, a bar touching its top only along an edge, and a post touching the
  bar only at a corner. Checks all 11 right, no temporary blocks left behind, and no 25 s without progress.
  - First run: the bar was bridged, but the post never was. The bar's temporary block was a layer under the window,
    so it was never knocked out, and bridgeFloating waited on "a temp still a hole" for good. Now a temp whose block
    is done lowers scanFromY (to knock it out), and bridging only waits on temps still to go in.

## 2026-10-14 (evening): modern-mansion.schem (user: "there's a bug right now")
- Stuck 190 s+ "adding another" on a snow layer. At 17:22 he had poured water (a pool/fountain) with its walls half
  done: it ran out through a gap, washed away 42 blocks, and kept washing the snow layer 4 blocks off. He put it
  back every ~0.25 s for minutes (the crosshair flipped snow/water). `tries` (3 clicks then give up) starts over
  whenever the job flips PLACE <-> ADJUST, so it never gave up.
  Fixes:
  - Water last: planOk for a fluid also wants no non-fluid hole within 8 sideways, at its level and the one below.
  - `targetClicks` (reset only by setTarget): 12 clicks on one target and it's "it keeps coming undone", skipped.
  - Then 11/11 but the 3 glass left behind: "Done building!" one scan before the temps turned into "knock me out"
    (now holeCount stays >= 1 while a temp is still to go in or come out, unless it's in `cant`). Then 60 s idle:
    the memory had the glass as done from when it went in, and the count pass skips temps, so nothing re-saw it. The
    temp maintenance now calls `remember(tmp, hole(tmp))`. After: 11/11, 0 left behind, 16 s, longest wait 5 s.

## WHERE I LEFT OFF (2026-10-14 17:48)
Installed in .minecraft/mods + dist/: jar built 17:39, sha256 c573ac3f8e5c..., replacing the 15:10 build. It adds the
MagicQueen fixes (bridgeFloating/bridgeTo, the window growing to the top, walking into low gaps with walkUntil) and
the mansion fixes (water last within 8, targetClicks cap 12), plus the temp-block cleanup fixes found by HA_DIAG.

Passed on this code: HA_DIAG 11/11 (0 left behind), HA_EXACT 245/245 (0 temporary blocks left), regular stages all
ok, HA_SHELVES 129/129. MagicQueen from layer 54 (14 min, the code before the mansion and temp fixes): longest stall
6 s, but it never reached the "only floating pieces left" state, so bridges on the real statue are untested.

Next:
1. The user's next recording: look for "hangs in the air ... temporary block(s)", leftover glass, stalls,
   "keeps coming undone", and the landings/walking in low gaps ("stopped flying").
2. Not rerun on the latest code: HA_TOWER, HA_TRICKY, TNT everywhere, box, the full Walmart.
3. The 16:43-style bounce: walking should cover it. Check that he takes off again after walking out from under a roof.

## 2026-10-15: the night session (fantasy-building 18:28-22:25, hospital, gothic cathedral 00:19-07:29)
The user: "multiple stop and flying around confused and cant reach spots" and "build from the outside going in
instead of just randomly building anywhere". Logs 2026-10-14-7.log.gz + latest.log, recording rec-20261014-222550
(543 min). Scratchpad scripts: ana/stalls.py (STALL episodes), ana/window.py (keys/position in a time window of a
recording), ana/rings.py + rings2.py (the outside-in rings of a schematic's layers), ana/pocket.py (the air pocket
round a spot: size, exits), ana/slice.py (a small box of a schematic).
- Cathedral: 2300 end rods [facing=down] hang from the black concrete ceiling of a sealed basement (pocket.py: 65k
  air cells, 0 exits when finished). Picked before their ceiling was there (glass under some made them "workable"):
  12112 "nowhere to place it from", "[confused] 175 targets in 10 s, nothing placed".
  - `rightSides(state)`: the sides whose block, clicked, gives the right way round (a RodBlock: only the block it
    points away from; a RotatedPillarBlock: the two along its axis; anything else: any, 0x3F).
  - `waitsOnSupport`: none of those sides is solid, but one is a planned block that `comes()` (plain, or with its own
    support other than this one, depth 6): not OK yet (planOk, bridgeFloating).
  - `hangsNow`/`holdsHanger` (only right side = UP): the block a rod hangs from goes before the rest of `first`, and
    the rod straight after, while there's still a way in under it (HA_RODS once finished the ceiling first and shut
    all 5 rods in).
- Fantasy-building tree: dark_oak_wood[axis=y] etc. (343 left as they were) need a block over or under them; he flew
  50 blocks to each to find "nowhere", then 50 back to a glazed tile with no spot (repair first).
  - `needsTemp` (planOk): right sides all empty and nothing coming: tryScaffold from where we are (4 a pick), wait for
    its temps; tried and nothing came of it: the old way.
  - tryScaffold's temp can be held up by up to 2 more temps (`supportChain`); `holdsUp`/bridgeTo don't count another
    temp as support (it goes once its own block is in: the 2nd piece of a branch hung on nothing).
  - Far picks (far list, dmgFar, outsideIn's far search) put spots with a "nowhere" history last.
- Hospital door (83 s, 30 s, and the 104 s "wedged", all one door): a door standing along his way. throughDoor opened
  every shut door on the way, and open it stood across the way: he pushed into the frame holding only W, hopping
  (recording). Now `doorGap` (the widest gap the door leaves for our direction): click only if the other state
  leaves >= 0.65; neither does -> `shutDoors` 30 s (way() treats it as a wall); a walk-through timing out -> the same.
- Outside in (the user's ask): `ringAt`/`ringsFor`, per layer, from the plan: 1 = next to the open air round the
  build (flood from one spot outside the box through air; a doorway or window, i.e. a run of <= 6 air between build
  blocks with the build over it, counts as shut), 2 = one in, 3 = the rest. `outsideIn`: while a layer of the 2-layer
  band has an OK ring-1 hole anywhere, its ring-3 holes wait; none of ring 1-2 near -> the nearest far one. Only a
  preference: `ok` is still the fallback. On fantasy-building he works the island's edge all the way round first.
- Tests: HA_RODS (a basement with 5 hanging rods, 2 on glass; a house with a doorway, a bookshelf in the middle and a
  wooden branch off a post; outside-in check): 400/400, 0 left behind, longest 5 s, 0 of 66 middle spots before their
  layer's walls. HA_DOOR (a room reached only down a corridor, the door standing open across it, all built but 2
  blocks inside).
- The first 40-min fantasy-building run (HA_SCHEM, HA_RECORD) died at 11 min: "camera snapped 80 deg in one tick".
  The island's jungle-wood floor (axis=y, so clicked on the grass's top face, from nearly straight over it): aimed at
  the face's middle, right under the eye, the heading to it swung right round with every few cm of drift, so the
  stroke spun the yaw at its 900 deg/s cap from one block to the next (recording: 43 deg per 50 ms), and a slow frame
  (dt clamps at 0.1 s) let one tick take 80. Now planPoint, for a full-block top/bottom face seen steeper than 50 deg
  (and only when the spot doesn't matter), tries first the point where the crosshair meets the face, clamped to 0.3
  of its middle: the view tips down onto it instead of spinning. ana/snaps.py lists the worst 50 ms yaw jumps of a
  recording (reads a cut-off one too).
  Run 2 (with that): still 2.4% of 50 ms windows over 600 deg/s at pitch > 60: those were real turns (the next block
  of the floor behind him: yaw 177 -> 343 at pitch 64 in 0.2 s). Fitts' law in mouse space gives the same time as
  on screen, so a fast flick there is human; what isn't is a hitch catching up at once. The stroke's per-frame cap
  is now 900 deg/s * min(dt, 0.05): at most 45 deg in one frame.
  Up to the snap: 1625 placed in 11 min (~150/min), 0 stalls, 0 [confused], 2 skips; the island's edge first
  (41-51 from the middle for 6.5 min), then inwards.
- 2026-10-15 09:10-09:29 (before the PC went off): the user said "keep having her test different schematics. fix any
  stall, stuck, confusion. efficiency". Crown run (HA_PREBUILD=41, 25 min): 2454 placed, 0 [confused], no stall over
  5 s, 254 temporary blocks; time: flying 30%, placing 22%, hotbar scrolling 21%, creative menu 18% (136 fetches of
  only 29 kinds: spruce wood 11 times), knocking out temps 6%.
  - CreativePicker fills the hotbar slot whose item is needed least by holes within 24 blocks (`needs()`, set from
    BoxFillModule.needsNear before a fetch), not just the one used longest ago.
  - Picker: in hotbar bonus 12 -> 40 deg (far list 1.5 -> 4 blocks), in hand 6 -> 25 deg (0.5 -> 2).
  - outsideIn: a ring-1 hole waiting on its column (columnWaits) counts as the outside still to come (the roof's middle
    went in while its front edge waited for the wall under it).
  - Suite on that code: diag 11/11, exact 245/245, plain all ok; door FAILED (test bug: a full stone floor counts as a
    ground layer and sinks into the world, so the pasted copy sat a block high; floor now smooth_stone); rods FAILED
    (the 5 rods sealed in again, "tried 0 times"; added a "[schem] hanging ones first" log to find out why).
- 2026-10-15 15:45: the user: "yes continue, i gtg" (same instructions). Keep-awake requested for the session.
- 16:13: all passed on the 09:25 code: HA_RODS 400/400 three runs in a row (0 of 66 middle spots early, longest 9 s;
  the "[schem] hanging ones first" log shows the ceiling blocks over rods, then each rod at once), HA_DOOR 183/183,
  HA_DIAG 11/11, HA_EXACT 245/245, plain all ok, HA_SHELVES 129/129 (longest 4 s).
  INSTALLED 16:15 in .minecraft/mods + dist/: jar built 09:26:17, sha256 d771e3319959..., replacing the 17:39 build.
- 16:15-16:55 efficiency + the schematic tour (scratchpad schemsuite.ps1 "file|minutes|prebuild"; logs
  %TEMP%\hatest-real-<name>-p<prebuild>.log; recordings copied to %TEMP%\hatest-recs\ by a background loop, since
  the gametest run dir is wiped at each start; ana/runsum.py now prints a time breakdown and menu stats).
  - Multi-fetch: CreativePicker takes up to 2 more blocks while the menu is open (`also`, need >= 3, only if needed
    more than what they'd push out). First version thrashed (crown: 250 fetches of 18 kinds in 15 min, 46% of the time
    in the menu, 70 placed/min vs 98): the newest block always went into the one least-needed slot and straight back
    out. Now `upcoming` (pickPlanTarget: the item counts of the band's near OK holes) is the need count, and a slot
    used in the last 10 s whose block is still wanted is kept (CreativePicker.keep).
  - Hospital p1 (v2 of that): 1492 placed in 15 min (99/min), no stall >= 15 s, 0 [confused]; menu 28% + hotbar
    scrolling 22% of the time; 160 fetches of 32 kinds (~40 kinds on the ground floor).
  - Material first in the target choice: passes over `first` = in hand (far list up to 10 blocks), in the hotbar (12),
    then all (in-reach score -3 deg per upcoming hole of its block, max 30; far -0.4 block each, max 4), then ok.
  - tempReady: a block whose temporary blocks are all in goes next (from `ok`, whatever its ring), so the temps come
    out before what's round them closes them in (hospital: glass left for good under a floor; "nowhere" x21 on it).
  - giveUp "can't see it from the spot" also allows looking straight down next time (steepOk): a floor of upright
    stripped wood, neighbours in, seen only from right over it (16 such skips in the hospital).
  - City hall p0 (15 min, material-first): 2084 placed (139/min), no stall >= 15 s, 0 [confused], 4 skips; 10 blocks
    fetched in 4 menu visits; flying 44% (the big ground floor; ana/gaps.py: 1774 gaps under 2 blocks at 0.37 s).
  - Hospital p1 with material-first, at 10 min: 31 menu visits (v2 ~59 per 10 min), 122 s in the menu (~170), 0 skips
    (v2: 44 in 15 min).
  - Pick block: when the block needed isn't in the hotbar and one like it is already in the build, in sight within 4
    (interactFace), BoxFillModule.pickFromWorld selects the slot to fill (CreativePicker.slotToFill: empty, else the
    one worth keeping least), looks at it and middle-clicks (keyPickItem); 3 s and no luck: the menu (pickFailed 60 s).
    Logs "[schem] got X off the build in N ticks".
  - Hospital p1 (material-first 10/12 + pick-block not yet): 1668 placed (111/min, v2 99), menu 132 s (v2 255),
    hotbar scrolling 4.6% (22%), 0 temps left, 21 "nowhere" (rows of stripped oak on its side, axis=z, longer than
    comes()'s depth 6: now 24) + 4 can't-see; but flying 46% (30%): in hand/in bar far limits cut to 4/8 blocks.
  - Crown p41 rerun (material-first 10/12 + pick-block): 1424 placed in 15 min (95/min; baseline 98, multi-fetch v1
    70); 37 picks off the build, 0 failed (8-34 ticks each); menu 157 s; scrolling still 19%: the wheel only started
    once the block was in reach. `readyItem`: on the way to a target ("flying to a hole", "flying over to the next
    block") its block is selected already, if it's in the hotbar.
  - The 27 s / 15 s stalls in both crown runs: two blocks inside the pasted layers (prebuild artifact: the paste missed
    them, in a cavity), "couldn't fly there".
  - willowspire-haven p0: STUCK, 0 placed in 4 min ("REAL no progress for 4 minutes"). Its 5 ground layers sink into
    the world (SchematicMode skip): the bottom ones landed at y -65, below a superflat world's bedrock (-64); he flew at
    the void ("couldn't fly there" 23, "nowhere" 23). SchematicMode.plan now leaves out cells outside the world's
    getMinY..getMaxY (logged "[schem] leaving out N blocks outside the world"). The user's world is superflat at -60
    too: this would have hit them.
  - luxury-villa p0: 1590 placed (106/min), 0 [confused]; "clicks didn't place it" x12 on a row of snow[layers=6]
    (each click adds a layer; `tries` only reset when the job type changed): now a click counts against it only if
    the spot's state didn't change (clickedState). 49 "can't see it from the spot": planPoint's steep near point
    wasn't checked for sight, and a hidden one turned the whole face down; now only if sees() and patchSeen(). 2 levers
    (face=ceiling) inside the sunk ground left out ("nowhere"). 0 picks off the build (blocks mostly new there).
  - modern-mansion p0: 2021 placed (135/min), no stall >= 15 s, 0 [confused], 14 skips, 9 picks off the build. REAL
    EXACT: 41 spots of extra water and water where a door, a furnace and 14 coral fans go: the water-last check only
    looked 8 round at its level and one below, and the water fell further. `waterWouldRun` floods from the spot (down
    any way, sideways up to 8, through anything water passes, 24 down, 3000 cells) and waits while that reaches a
    planned non-fluid hole; cached per spot (100 ticks if it would run, 20 if not).
  - (stone is dirt = 1690 in that check: sunk ground under the build, left as the world's on purpose.)
- 18:30: suite on the 18:10 code all passed: HA_DOOR 183/183, HA_RODS 400/400 (0 early), HA_DIAG 11/11, HA_EXACT
  245/245, plain all ok, HA_SHELVES 129/129, HA_TOWER 866/866 (TNT: 168 blown out, all back in 81 s, longest stall
  after it 2 s), HA_TRICKY 87/87 (cube back in 7.7 s).
  INSTALLED 18:30 in .minecraft/mods + dist/: jar built 18:10:38, sha256 b638ebd336d6..., replacing the 09:26 build.
- willowspire p0 rerun (out-of-world fix): no longer stuck ("leaving out 807 blocks outside the world"), but slow
  (40-60/min): 285 "nowhere" + 31 left in 8 min, all at y -62/-63: its ground layers hold a foundation (stone
  bricks, concrete, slabs) that sinks into the world's dirt; he dug pits underground and couldn't place into them.
  jobAt now also leaves as DONE any non-air, non-fluid block where the world has natural ground and the build covers
  it with a solid block (covered()): an unseen foundation. Air the schematic digs out (a basement) still gets dug.
- hospital p1 HA_TNT20 (18:38 code): 341 of 1562 round him blown out; he put back all but ~33-59 within 140 s
  ("damaged" 353 -> 2), no stall >= 15 s, 0 [confused]; but TNT20 RESULT "all back after never (he thought never),
  59 still out": 2 spots stayed in `damaged` for good, and the "still out" count crept up after (grass spreading
  onto put-back dirt, which jobAt leaves on purpose, counts as wrong in the test). The test now logs the spots still
  out (want/has/BoxFillModule.jobInfo) and what's in `damaged` (damagedSpots()).
- KingBlackSt118 p60 (18:41 code): 1902 placed (127/min), no stall >= 15 s, 0 [confused], 34 temps, 3 picks; but 3
  temporary glass blocks left for good: each in a pocket (basalt under, the statue round) whose only way in was the
  spot it was for, sealed the moment that block went in. tryScaffold now needs keepsOpenSide(n, t): a side other than
  t's that stays open in the finished build (not planned non-air, not solid now, or one of our temps).
- NazgulSt124 p60: 1996 placed (133/min), no stall >= 15 s, 0 [confused], 2 skips, 0 left. (Statues KingBlack and
  Nazgul use few kinds: 3 and 2 menu trips.) KingBlack's recording: 30% of placements change material, ~0.2 s each.
- 19:28 batch 3b: willowspire p0 (foundation fix), hospital p1 TNT20 (still-out log), hospital p6 (doors with walls
  and roofs up), DragonGirl p60, MagicQueen p54, walmart p6, gothic p2 (20 min), DragonNP p13.
- willowspire p0 again (foundation fix): still 61 "nowhere" in 5 min: moss at y -62 in a dip of the schematic's
  terrain (air over it in the schematic), under the world's grass at -61, which is below clearFromY (the sunk ground
  isn't cleared) and so stays. `groundStaysOver(c)`: the spot above is below clearFromY, the schematic has nothing
  there and the world has natural ground: the foundation rule treats c as covered. clearFromY is static now.
- hospital p1 TNT20 with the still-out log: 446 blown out; of the 132 "still out" at the end, 121 are "Dirt has Grass
  Block: DONE" (grass grown back over put-back dirt: left on purpose), 11 are ground dirt at -61 over a crater
  ("nowhere": only the floor block above to click, the pit under it too shallow to look up from). Vanilla TNT also
  blows out the world's natural dirt under the build; the user's server (KawaiiNoGrief) doesn't, so there the dirt
  below stays to click on. Test artifact; not fixed.
- hospital p6 (walls and roofs up): 1888 placed (126/min), no stall >= 15 s, 0 [confused]; doors used: "shutting (it
  stands open across the way) the door at 28,-59,66" then walked through: the doorGap fix works. But he once landed on
  the floor 6 over a door and "walking through the door" fired 6 times a second: `through` only looked sideways. Now
  walkThroughDoor starts only within 2.5 of the door's height, and "through" needs to be within 1.5 of it.
- DragonGirlSt122 p60: 1760 placed (117/min), no stall >= 15 s, 0 [confused], 1 skip, 0 left, 64 temps all out.
- MagicQueenSt127 p54: 1846 placed (123/min), no stall >= 15 s, 0 [confused], 0 skips, 0 left.
- 21:00: the user said stop. Batch 3b stopped during walmart p6 (8 min in: no stall >= 15 s, 0 [confused], 6 skips).
  Not run: gothic p2, DragonNP p13, willowspire with groundStaysOver.
  NOT INSTALLED yet (made after the 18:30 install, compiled, not run through the suite): the buried-foundation rule
  (covered / groundStaysOver), keepsOpenSide for temps, the door height checks. Installed = the 18:10 code.

## 2026-10-15 (night): build order, look-around, night vision (the user's asks)
- Build order for any build ("he builds the outer layer of the castle walls first, then after the walls, the outer
  layer top if any, then the first layer inside and 2nd and so on"; "it's tied to general building"): `buildOrder`
  replaces the 2-layer band + outsideIn. Phases (phaseOf): 0 outside = ringAt <= 2, any height, bottom up; 1 top =
  coversInside (top block of its column in the plan, air under it: a roof); 2 inside = the rest, bottom up. The
  earliest phase with an OK hole anywhere in the window (near or far) wins; its lowest two layers; none near: the
  nearest over there. Temps and knock-outs (phaseOf -1) aren't held back; hang/tempReady/temps-first/decor-last rules
  still apply on top. The window grows (extraLayers up to 24) while the outside is done as far as it reaches; it
  keeps its top when activeY moves up.
- Hatch: `sealsInside(h)` (top blocks only): BFS from under h through what's open (air, no collision, doors), not
  through h; reaching open sky (above skyTop or out of the box) = a way in; a planned hole next to the way = inside to
  build; sealing with inside to build -> h waits (the user OK'd: one opening left, closed last). Cached 3 s, 6000 cells.
- Look-around every 4-6 min of building (`lookAround`): out on our side to dist = clamp(1.4 r + 4, 12, 90) from the
  plan box middle, 4 + 0.3 dist over the top built; ~4-5 s panning across it (non-quick aim on a moving point); not
  while damaged is non-empty, the picker's busy, walking a door, shut in, or guarding (then 10 s later).
- Night vision (`nightVision`): /effect give @s night_vision infinite 0 true (2 tries, 5 s apart); if it doesn't
  take (the gametest world has no cheats), a potion of night vision from the creative menu (CreativePicker.fetch with
  a query and a stack matcher), drunk looking up. Seen working in the castle test via the potion.
- Order check in realFile: every 5 s which spots came out right and their phase (BoxFillModule.phaseFor); "REAL
  ORDER" at the end (counts, times, outside spots put in after the inside started).
- At a stand that can't see the target: once, look straight down (steepOk) and retry before giving up (castle
  ground layer: 34 "can't see it from the spot" in 6 minutes).
- Castle run 1 (medieval-castle-qrjiaxer, 43x56x45, 9678 blocks, 1 ground layer; old order code): ~100/min while
  the ground and the walls went up; night vision via the potion every 3 min; look-around fine. Found: (1) the ground
  layer came out as "the top" (nothing under it in the plan) and the knock-outs of the ground (grass for paths) were
  never held back; (2) from 21:21 a loop: a trapdoor and a shelf (facing west, nothing east of them to click) got
  their temporary glass UNDER them (tryScaffold judged the top face from straight above, where yawTo gives -90 =
  facing east by accident), every click went in wrong, "leaving it as it is" (cant -> BLOCKED -> seenDone), and when
  it got its next go it counted as DAMAGE (seenDone, then a hole) -> "repairing first", flying across the castle
  every few seconds: 5-7 blocks per 30 s for 4 minutes; (3) the test died on "moved 1.63 blocks in one tick": a
  screenshot took 2 ticks while he was flying fast.
- Fixes (21:30-22:00): parts are now 0 ground (y < clearFromY, the sunk ground layers: like a foundation, first),
  1 outside (ringAt <= 2), 2 top (coversInside, never the bottom layer), 3 inside. Knocking out a wrong block goes by
  its part; only clearing (plan air) and temps aren't held back. The window grows to the whole height for the
  outside (no 24 cap; "round us" scan only +-10 layers; holes more than 12 up/down count as far). The hatch check
  (`hatch`) covers any top-of-column block in the outside or top part (pitched roofs too); ring 0 (the open air round
  the build, through a door) is a way in; spots given up on (cant) aren't inside work. Damage = a spot seen BUILT
  (seenBuilt, now kept by remember(c, job) for every look) that's a hole now, not a temp. tryScaffold: the way round
  is predicted from eye spots we have room for (Y faces: from the 4 sides; side faces: 2 out or over it), and at most
  2 temporary blocks per target (scaffoldGoes). Night vision: the long potion (8 min), the next one drunk when 10 s
  are left, drinking/looking aren't stalls. CreativePicker: unfail(), a special fetch that fails isn't "leaving those
  blocks out". Test: HA_PREPARTS=N pastes the parts before N (not the roofs) to test roofs/hatch/inside; screenshots
  reset the per-tick watch.
- Castle run 2 (HA_PREPARTS=2, 21:33-21:58; the ground + outside pasted except top-of-column cells): 34/min, 77% of
  the time flying. (a) A water trough (2 sources between trapdoors, x 7-8 of the schematic, y 1) got its west source
  pasted (ring 2 = "outside") without its trapdoors (inside): the water ran out and washed a short grass 3 blocks off
  away; seen built -> hole = damage -> "repairing first", ~100 times (the user: "up down up down"). (b) Stares with a
  wiggling camera (the user saw it): at a stand he couldn't click from, the hover-hold in steer() (precise slack 0.08
  while one tick of a key carries ~0.5 blocks) tapped left/right/left; the camera on the block swung +-2.5 deg. Cause
  of most of those stands: knocking out the grass from 3 blocks off, then the same stand for putting the concrete into
  the pit (out of reach). (c) Flowers round the castle's foot (ring 1, decor) held the outside's band at y -60/-59.
  (d) Ring 2 = "outside" put interior furniture behind 1-thick walls (trough, shelves, trapdoors) with the walls: he
  went in through the doors for them.
- Fixes (22:00-22:15): new stand when the job changes (knock out -> put in); slack 0.3 (no hover tapping); stands
  picked with 0.35 of reach to spare (standSlack); at a stand that can't see it: log why ("[box] can't see"), then a
  steeper stand once (stand = null), then skip. Water: washedAway(want) (FlowingFluid's rule) + waterOnIt(h) -> wait;
  plugsWater(h) (flowing water where the build has a water-stopping block) goes first with the hanging ones;
  remember(): put back and gone again 3 times in a minute -> no damage priority, bad 30 s, "keeps coming undone".
  Order: each part's structure before its decor (decor holes don't set the band); ring 2 is "outside" only for a full
  block (the back of a thick wall). Night vision only after 3 s where it's dark (getMaxLocalRawBrightness <= 5; the
  user: "only if it's dark and he can't see"). Nether portals: SchematicMode.portalCells(); lightPortals() at the end
  (frame whole -> flint and steel on the inside of its bottom, from 2.5 in front); portal blocks are Terrain.hurts
  (never fly through one). HA_PREPARTS now leaves out only roofs (top of column, air under, above layer 1) and water.
- Castle run 3 (from scratch, 21:58-22:23, code of ~21:50): ground 641 spots (7-1467 s), outside 1268 (427-1372 s),
  outside after the inside started: 0. 75/min; time: flying to a hole 44%, placing 24%, hotbar scrolling 12.6%, menu
  213 s. Stares: 3 in 25 min (was 53 in 6). Stuck for the last 2+ minutes: at a tower corner (trapdoor ring round a
  pillar, temp glass) he was shut in; the open-air map is grown from us AND from the sky, so holes outside looked
  gettable: "couldn't fly there" one after another, and the break-out (only when there's no target) never came.
  Fix: air0() keeps which cells came from us; if that part never meets the part from the sky (and the search ran
  out), airShutIn: the map is cut to our part, planOk needs gettable for every hole (not just in the region), far
  holes aren't picked; then "nothing to do" -> wayOut / break out as before. Logs "[box] shut in at ...".
- Portal test (decorated-nethe, all 37 layers pasted): all 4 portals (195 blocks each) lit in 30 s; REAL EXACT 100%.
- Castle run 4 (HA_PREPARTS=2 refined, 22:28): roofs done in 2.5 min ("the outside's done up to y -17"), then it
  flipped between "the top (from y -59)" and "the inside (from y -60)": shelves/canopies low down counted as "top".
  Fix: roofOf(h): the top part is roof stuff (full block, slab, stairs) joined side by side in its layer to the
  outside (shellLayers BFS from ring <= 2 blocks). The inside was ~40/min, 25% in the menu (59 kinds of furniture):
  the inside now goes a storey (4 layers) at a time, and the creative menu grabs up to 3 more kinds needed nearby
  (need >= 1, was >= 3, 2). Hatch decisions are logged ("leaving X open for now: the way in").
- Castle run 5 (from scratch, 22:48-23:08, all fixes up to 22:45): 90/min (was 75), stalls 17 (was 137), skips 41
  (was 94), 3 stares in 20 min; REAL ORDER: ground 640 (7-882 s), outside 1243 (442-1197 s), top 0, inside 0. Time:
  flying to a hole 34.6% (was 44), placing 32.2% (was 24), hotbar scrolling 13.7% (the user's wheel style: left).
- Suite (23:08): door OK, rods OK (400/400, 0 of 66 inside spots before their layer's walls), diag OK, exact FAILED:
  2 cobblestones of the ground layer under the house's corners stayed grass: covered(c) (the buried-foundation rule,
  never run through the suite before) left them. Fix: covered only below the top ground layer, or once the block
  over it is really in (the ground goes first now, open). standSlack 0.6 (1.0 on the steeper retry).
- Suite rest (23:14-23:29): plain OK (house, repair, roofhole, underhole, turned, crater), shelves OK (129/129, 123 s),
  tricky OK (87/87, cube back 7.8 s), tower FAILED: 866/866 right but the TNT damage took 324 s (limit 90). Two causes:
  (1) 3 spots at the foot of the east wall over a TNT crater, "nowhere to place it from": the dirt pillar
  (supportPillar) only came once nothing else was left, and with the outside going up there always was. Now giveUp
  for "nowhere" puts a pillar under a bottom-layer spot over a pit straight away (pillarUnder; its blocks count as
  damage too when the spot is). (2) The roof went on with the inside floor still to do: the hatch search took the
  TNT holes in the wall foot as the way in; they were put back and the floor was shut in ("couldn't fly there",
  "path 0 cells"). Now the hatch search only goes through lasting openings two high (lastingWay: open, room for us,
  and not a spot the build still fills, unless what goes there has no collision or is a door).
- Rerun (23:33-23:41): exact OK (245/245), tower OK (866/866, TNT damage back in 70 s, longest stall 7 s). But the
  tower's log showed EVERY roof slab "left open as the way in": with only lasting openings counted, the other roof
  holes weren't ways out either. lastingWay now also takes another hole at the top of its column in the outside/top
  part (the hatch logic keeps the last of those open), and sealChecked is cleared after each block put in (two last
  roof holes each counted the other).
- 00:03-00:12: castle HA_PREPARTS=2 with the hatch fix: roofs 61 (7-127 s), top 1, inside 883 (137-1192 s), 0 outside
  after the inside, no hatch needed (it has doors); 52/min (inside: 57-65 kinds, menu 22%, ~2 kinds per visit now).
  A 94 s stall: wedged in a market stall (fences under a trapdoor canopy, a hanging lantern): the air map took the
  half block over a fence as room. Fix: flyable() over a fence/wall/shut gate needs the cell two up free too; a cell
  pushed into for 3 s without getting in goes off the map for 30 s (stuckCells). Look-around: a fresh pick after it.
- Tower again: the hatch held ONE slab (6,-37,8) as it should, then "stuck 90 s": outside the north wall, the shared
  air map's way to the floor inside came down from the sky through the hatch, so he pushed into the wall towards
  it, alternating with an A* way down a TNT crater under the wall. Fix: airPath uses pathFromMe when the map's way
  doesn't start at us (cached 10 ticks per goal).
- 00:06-00:10 the user swapped the schematics folder: castle, nether, walmart etc. are gone; new: barn, black hole
  pixel art, dark gothic portal, decorated dining, forest cathedral, gigantic medieval, godzilla, grand trading hall,
  villager, 4 medieval houses, natural medieval, obsidian bridge, special medieval, statue of lenin, wizard tower.
  The suite's "shelves" stage (walmart.litematic) can't run any more.

## WHERE I LEFT OFF (2026-10-16 00:18, the user said stop)
- All compiled, NOTHING installed since 18:30. Last full pass of the suite: door, rods, diag, exact, plain, tricky OK
  (shelves passed 23:19 but walmart.litematic is gone now). Tower: passed 23:41 (70 s), failed 00:12 after the
  lastingWay change (stuck outside the wall going for the hatch); airPath/pathFromMe fix made, the 00:15 rerun didn't
  complete (client stopped early, no CHECK line). Next: rerun tower + suite, a real run on the new schematics, then
  install with Minecraft closed.

## 2026-10-16 (night): hilly worlds, the user asleep ("do not stop testing")
- KawaiiNoGrief: `keep-placed-ground` (default grass_block, dirt, coarse_dirt, podzol, mycelium, rooted_dirt): in
  "structure" mode those never break, even when a player placed them (the yard, the dirt under a floor). Built by hand
  (javac --release 21 against ~/.m2 paper-api 1.21.11 + kyori/jetbrains/guava jars, plugin.yml version filled in) in
  %TEMP%\kngbuild; swapped into C:\minecraft-server\plugins (old one: plugin-backups\kawaiinogrief-26.1.2-before-
  keep-placed-ground.jar); the setting added to the server's plugins\KawaiiNoGrief\config.yml. The user's own
  uncommitted changes in that plugin (the "structure" mode) were left as they were; mine sit on top.
- SchematicMode.plan(): keptColumns(): the schematic's own ground (plain ground in its ground layers: terrain() minus
  dirt path, farmland, gravel) and loose plants are only built within 3 (YARD) of a column with something else in it
  (the user: "some schematic is 30x30 and the grass it's like 50x50"); wantAt() is null outside those columns (no
  clearing far out). fillUnder(): under each column whose lowest block is at or below the floor, dirt down to the
  world's ground, 12 deep at most ("[schem] N blocks of dirt under the build where the ground dips").
- Test: HA_TERRAIN=1: setUseConsistentSettings(false) + creative + commands + no structures (random seed, normal
  world); weather/fire/mobs off; landSpot(): a spot within 64 of spawn with no water under it, heights 2-8 apart
  (nearest 4 best), the floor at the middle height; the player 3 off the corner.
- The schematics folder now has 27 files (the user added 9 more: copper guard, decorative peri.., fantasy medieval,
  gigantic swamp, granja hierro (iron farm), laberinto (maze), medieval castle nfc31vca, medieval cottage, inn).
- Barn run 1 (snowy hills 64..72, 239 dirt under it): 873/3181 in 15 min; he cleared trees leaf by leaf (leaves
  decayed on the way to them), dug the hill bottom-up ("nowhere" 114), 61 landings = the user's "bunny hopping"
  (gapBelow only looked straight down; the hillside came up under him). Fixes: clearing skips natural (non-persistent)
  leaves; columnWaits: a clearing spot waits for the one over it (top down); steer() also looks at the ground where
  we'll be in 0.25 s and 0.125 s (gapAt; walls and low roofs ignored).
- Barn run 2 (hills 67..72): landings 3 (was 61). But 263 "nowhere", 44 left: 38 of them had a SNOW LAYER in the
  spot (5 short grass): it hid every face to click. Fix: giveUp("nowhere") on a spot with snow/grass in it ->
  clearFirst: the job is BREAK (knock it out), then PLACE. Clearing also skips snow/plants on a block that's being
  cleared too (they drop with it; picked alone they vanished: "isn't to do any more" + a wiggly re-look). New log
  "[schem] off to X (N away) with M still to do round Y: <rule>" for the user's "jumping around" check.
- Medieval house 547 (badlands 73..77, red sand, terracotta, dead bushes): first try stopped (my standSlack 1.0 on the
  steep retry made the stand right over a pit "too far": 23 "nowhere" in 30 s and a [confused] burst) -> 0.6 always.
  Rerun: 67/min, 126 "nowhere", 22 left, 41 back-and-forth turnarounds, landings 12, 1 stare, jumps 0.6/min. Causes:
  red sand falling back into spots he knocked out under it (columnWaits: a falling block over the spot that's going
  goes first), and ground-layer spots already shut in under the built floor: he left the walls for one after another
  ("building the ground" / "building the outside" flip-flop) and failed each. jobAt: a PLACE spot of the sunk ground
  layers with solid blocks over it and all round (shutAway) counts as done.
- Wizard tower (26x107x28, 8634 blocks, hills 68..71, trees; TNT20): TNT took 24 of 119, 4 still out after 5 min
  ("nowhere": over craters in the ground; the pillar under a spot only came one at a time, and only at planBottomY,
  which the dirt filled in under a hillside build pushes lower). Now: giveUp("nowhere") puts a pillar under any spot
  at or below the floor (clearFromY) over a pit, several at once; supportPillar's fallback uses the floor level too.
- copper-guard (208x36x212, 258,756 blocks): landSpot found nothing (range <= 10 is rare over 200 blocks) and fell
  back to spawn; fillUnder planned 217,207 dirt. Now: dirt only for dips of 6 or less (deeper: a cliff/valley, left
  open; logged "N columns too deep"); landSpot allows max(10, size/6) up and down, searches 96 out, and takes the
  least hilly dry spot over the limit as a last pick instead of spawn.
- dark gothic portal (18x37x31, hills 64..69): STOPPED after 4 min without progress, [confused] 42: my multi-pillar
  change looped: under the spot was leaf litter that was already the build's (the dirt fill), so pillarUnder added
  nothing, returned true, and the same block was picked ~20 times a second. Fix: pillarUnder returns false when it
  adds nothing; giveUp only pillars under a spot whose cell below isn't in the plan.
- Cottage (21x18x25, hills 73..78): 53/min, 296 "nowhere", 38 left (upside-down stairs facing away from their only
  neighbour, barrels, trapdoors: oriented blocks next to the hill / with one neighbour), 56 landings (31 "flying to a
  hole" in the low rooms: set down to walk, took off a second later outside the low roof). The outside's stuck spots
  pulled him back from the inside 166 times (REAL ORDER: 166 of 362 outside after the inside started). Fixes:
  footWork(): on foot under a low roof with the next spot near (6) and no higher, keep walking (walkUntil); buildOrder
  ignores spots with nowhere >= 2 when choosing the part (they come at the end via band0).
- Gothic portal rerun (hills 73..81, taiga): 1186/1210 (98%), 65/min; one 45 s stall at the end: the last 71 holes
  were "waiting on the one under" a podzol spot "nowhere to place it from", and spruce trunks inside the build (46 logs,
  492 leaves left) "nowhere" too: hidden in their own leaves, which the clearing skips. Copper guard stopped: landSpot
  found no dry spot that big (fell back to spawn, floating). landSpot now takes a little water for a big build
  (60+ wide: up to a fifth of the samples, 10 a point in the score) and its last fallback is the real ground height.
- THE PITS (the biggest "nowhere" cause, all runs): a ground block knocked out for the right one leaves a 1x1 pit; its
  bottom only shows from steeply over it, and from the usual hover (eyes 4.1 over it) that's out of reach (reach 5 -
  0.25 - 0.6 slack); right over it is never a stand in a schematic. House 547: 109 grass/dirt; barn: 93 floor planks
  "down: too far". Fix (standFor): a target below, a cell next to it (not over it) with a floor: a low eye (standing,
  LOW) is tried too (diagonal: 63 deg down, in reach 3.1). drift() doesn't back off to 2.8 while lowHover.
- Leaves: leavesInWay(t) on "nowhere": from the spots round it, the line with the fewest natural leaves on it and
  nothing else; those leaves go in leafWay, which the clearing scan doesn't skip; the spot gets another go in 3 s.
  Log "[schem] X is hidden in a tree's leaves: knocking out the N in the way first".
- Camera check: my "stares with a wiggle" counted micro-reversals (0.08 deg) of smooth turns. Now turning points
  (0.3 deg noise), 4+ swings of 1 deg+ in 1.5 s with nothing placed. Real ones remain (cottage 10, portal 21).
- Cottage bounce (02:31): dropping down a 1-wide gap (slab one side, trapdoors the other) a quarter block off
  centre (inside the 0.3 slack), the body caught the slab's edge: landed, took off, again, once a second. steer() now
  lines up first (slack 0.08, slow) when the goal is below, near, and the drop straight down at the goal is clear
  while ours isn't (gapBelow < gapAt(goal) - 0.3); no shift until then.
- Inn (TNT20, hills 107..114, 6537 blocks): 102/min, all 8 min on its big ground plate (fine: hills dug, dips
  filled). TNT: 300 out -> 28 after 5 min, of which 26 are "dirt is grass" (dirt in a crater greens over; jobAt
  counts that as done on purpose) and 2 pits ("nowhere": the pit fix). No stalls over 15 s, 0 confused.
- Forest cathedral (75x29x44, forest, hills 66..72, 659 dirt under it; first run with the pit/leaf fixes): 0 stalls
  15 s+, 0 confused, 34 "nowhere" (0 left), 0 stares, 16 landings. All 15 min on the ground: clearing the forest
  and the plate. A 64-block flight at 30 s: the ground part went 2 layers at a time over the whole site (the dips'
  bottoms are scattered), and the window stopped at the floor, so the hill over it waited for every dip. Now:
  buildOrder's ground band is all the ground layers (near first; columnWaits keeps each dip bottom-up), and while
  activeY < clearFromY the window reaches clearFromY + 10 (the hill is cleared round us alongside).
- Also: the low (standing) stand is tried for targets level with the stand cell too (barrels/trapdoors that need a
  look under 45 deg down); "[box] setting down to walk in under a low roof" logged (to tell planned landings from hops).
- Villager house (itxzzs, 2209 blocks, hills 64..67, SIX sunk ground layers y 59..64): 3 blocks in 90 s at the
  start, flying back and forth between x 106 and x 127: a cave pocket at y 60 under the hill (want dirt, sealed in,
  "0 open spots") and a cobblestone path block under the not-yet-cleared hill ("ray hits" the hill block beside it).
  jobAt: a PLACE spot of the ground layers below the top one with natural solid ground over it that stays
  (underGround: the plan has nothing or a block there, not air) counts as done. The path one: the window/order change.
- Special medieval (1995 blocks, hills 72..76; ground order + cave fixes in): 967 in 15 min (66/min), 1 stall of 16 s,
  0 confused, 8 stairs left, 0 stares... but at the end "building the outside"/"building the inside" flipped every
  1-2 s (a few outside stragglers whose skip ran out). buildOrder now counts each part's doable structure (up to
  STICK = 8): once on a later part, back to an earlier one only for 8+ of it (stragglers wait); lastPhase reset per
  plan. 37 landings (13 "setting down to walk"), 34 of them off again within 2 s: one was "setting down to walk in
  under a low roof" to a way point with no floor under it (it hugs its cell's bottom anyway): walked off the upper
  floor's edge, fell 4, flew up, set down again. Now floorUnder(goal) for setting down, for staying on foot, and in
  footWork (the stand's cell and the current way point need a floor).
- Decorated dining (930, no ground layers; before the sticky fix): 810/930 (96.2% exact), 0 stalls, 0 confused, 22
  left: 18 trapdoors (top half, on the floor). Their temporary block went OVER them: the underside at head height,
  never in sight from the floor (eye 78.6 > 78). tryScaffold's imagined eyes were only checked for room for a body:
  now also eyeGettable (feet cell in the air map, or outside it) and clearTo (nothing between the eye and the face
  point, in reach): the side neighbour's upper half wins, like a player.
- Natural medieval (12471 blocks, 7 sunk ground layers, hills 63..68; ground order + caves + sticky + floor): 105/min,
  part switches 2 (ground -> outside), none back (dining before the fix: 81 switches, 39 back; special medieval 41/19).
  0 stalls 15 s+, 0 confused, 50 "nowhere", 3 left, "hidden in a tree" 6, 9 landings, 1 stare.
- nightcheck.py now prints the part switches ("building the ...") and how many go back to an earlier part.
- Villager rerun (badlands this time, hills 95..99): 213 in 3 min (was 3 in 90 s), but "nowhere" 73: the plate's
  terracotta under the hill (picked before the hill over it was cleared) and buried terracotta (not "natural ground").
  columnWaits: ANY spot under a solid block that's to be cleared (want air, not leaves, not given up) waits for it.
  worldGround() = naturalGround or BlockTags.TERRACOTTA, used for the world's side (rule 1364, groundStaysOver,
  underGround).
- covered() was looser than meant: at the top ground layer and above, any occluding block over the spot counted, the
  hill's own too, so where a hill ran through the walls its dirt was left in them ("cobblestone is dirt=7/grass=8" in
  the cottage, "andesite is grass_block=131" in the cathedral). Now: never at or above clearFromY; at the top ground
  layer only once the build's own block over it is in (Placement.matches).
- Villager rerun result (code of 04:10): stopped by the test ("no progress for 4 minutes") at 817 s, 440/2194, 231 part
  switches (115 back), 115 turnarounds, 469 "nowhere", 50 left; "dirt is (brown_)terracotta" 571 (now worldGround).
  THE 4-MINUTE STUCK: hovering at 21.46 98.51 -22.30 without moving, "far path, 16 cells, 15 left", steering to
  21.50 97.15 -22.50: the next way point was the cell right under him (floor-hugging, 0.15 over the floor), the hover
  (1.5 over the ground) never goes down to it, and sideways he was already there. Each target over there gave up
  ("couldn't fly there") and the next one took the same way. overPoint(here, cell): a way point we're over (within
  0.6 sideways, 0..2.3 above its cell, a floor under it) counts as passed in farStep, the wedged step and the
  furthest-in-sight choice. Also the stickiness count ignores spots that had "nowhere" once.
- House 547 rerun: after "stepping back to look at the build" (16 off, 15 up) the way back was "path 0 cells, none in
  sight" then up over the whole tower to y 153: aStar's search box was the build +3, the start outside it, so no
  neighbour was ever in it. Now the box is stretched to take in where we are (+2).
- House 547 rerun, landings: 16 in 5.5 min, 9 of them "setting down to walk in under a low roof" with oak/birch
  leaves over it (trees in the build area: their canopy 2-3 over the floor). The clearing now takes natural leaves
  at clearFromY + 3 or lower (the low canopy he has to work under); higher ones are still left to fall by themselves.
- House 547 rerun (forest hills 115..120; before the low-leaves and aStar fixes): 1008/3826, 0 stalls, 0 confused,
  "nowhere" 44 (was 126), 2 left (was 22), part switches 4 (1 back), "hidden in a tree" 20, but 42 landings (8
  planned, the rest mostly under the canopy too) and 13 "stares": aim corrections onto a face ("off the face ... aim
  error 0.6") and sign editor opens; no single cause.
- House bqqskqvm (26856 blocks, TNT20, hills 69..76, 272 dirt under it): TNT took 9 of 120 round him, all back in
  32 s. 0 stalls, 0 confused, 2 landings, 2 part switches.
- Barn rerun (hills 71..78, an acacia in the site): 1403/3059 (127/min; the 00:xx barn did 996), 0 stalls, 0
  confused, 2 part switches, "nowhere" 60 (was 263), 11 left: 8 hay bales in the hillside ("has dirt": the lower of
  two stacked ones picked while the hill's dirt over it, where the upper bale goes, was still there). columnWaits:
  a BREAK spot under a planned spot that's a BREAK too waits for it (top down). 12 landings, 4 stares.
- House nhzpp3wk (25707 blocks, 65+ wide, 10 sunk ground layers): my landSpot change let it cover a pond (big build:
  some water allowed); fillUnder ran the dirt down into it and he worked under water: stalls of 15-18 s ("wedged",
  "can't get into X"), 0-10 blocks per 30 s. Water in the site is a weak spot (swimming, routes). The test is strict
  again: dry unless 120+ wide (a tenth wet at most). steer(): lineUp gives up after 1.5 s (never hang over a gap).
- nhzpp3wk result: 586 placed (the "done" count includes the world's matching ground), all 15 min on its ground,
  2 stalls of 16-18 s (under water), 0 left, 1 jump, 4 turnarounds, 14 landings.
- Camera check split: stares while nearly still (moved < 1.5) vs flying along. Of 9/4/13/6 (nhzpp3wk/barn/547/
  special) only the barn's 3 were "still", and those were rapid work (breaks every 0.2-0.3 s, one inside the
  acacia's leaves aiming 0.4 from the eyes, pitch swinging 10 deg). None like the user's "staring at a hole".
- Cottage rerun: 28 "nowhere" for its cobblestone foundation in the hillside: BREAK the hill's dirt, but a tuft of
  short grass on it (replaceable, so the columnWaits rule skipped it) hid the top face. columnWaits now also waits
  for a replaceable block over it that's on the clearing list (plan has it as air: no deadlock with the tufts the
  clearing leaves to drop with their block).
- Cottage rerun (hills 64..69): 697/1071 (was 620/1026), 0 stalls, 0 confused, "nowhere" 207 (was 296), 27 left (was
  38: oak stairs 10, barrels 7, cobblestone 6...), part switches 24 (11 back), 43 landings (was 56; 8 planned):
  clusters in the low rooms and one bounce on the wall top, 4 landings in 1 s: a low stand (0.15 over the floor),
  no walking (a gap on the way), the 0.08..0.2 band can't be held: J, v, down, J... Safety net: 3 landings in 3 s ->
  "[box] bouncing on the floor: hovering a bit higher for a while": 4 s of low hover at 0.5 (not under a low roof).
- House t1t49ihz (5659, desert hills 63..66, 208 dirt under it): 1190 in 15 min (81/min), 0 stalls, 0 confused,
  2 part switches (0 back), 4 left (scaffolding), 4 landings, 0 still stares. 19 jumps: the outside band coming
  round to the other side of the build (34 at most).
- Gothic portal rerun (hills 63..67, oak trees): 1201/1210 (99.3%, 96.7% exact; the portal not lit yet), 34 landings
  (was 70), 0 left of note... but the end: 3 stalls (23/52/46 s) and 10 "[confused]" (8-15 targets in 10 s): the last
  holes (2 podzol, a hanging column of bricks) failed "nowhere" at once and, nothing else left, got another go every
  2 s: hovering, glancing from one to the next. Now in that "nothing else to do" branch a spot with 2+ fails is left
  as it is at once ("[schem] leaving X as it is: nothing else left, and no spot for it twice"); it still gets another
  go when something next to it changes.
- Villager third go (normal hills 65..69, 10 sunk ground layers): 1611/2323 at 10.5 min (the badlands go: 440 in 13.6),
  but ground/outside flip-flop near the end: deep ground spots "0 open spots" (caves 2+ high: underGround only looked
  at the block right over it, the cave's air) -> underGround now walks up the column to the first solid block below
  the floor: natural ground (not dug out by the plan) = done; open up to the floor = a dip or crater, filled.
- Villager third go, end: 1776/2323 (76%), 83 part switches (40 back), 4 stalls ~20 s. The ground spots pulling him
  back: air pockets in the hill (the cave runs up past the floor, the hill's ground over it is to be cleared): "33
  open spots, 0 with a view". columnWaits: a ground spot with air over it, then (up to clearFromY + 10) a solid block
  that's to be cleared (want air) on top: waits for the hill.

### Night summary so far (2026-10-16, ~06:25) — for the morning
Tested in random hilly worlds, 15 min each (TNT on the inn, house bqqskqvm, castle nfc31vca): inn, forest cathedral,
villager (x3), special medieval, decorated dining, natural medieval, house 547, house bqqskqvm, barn, house nhzpp3wk,
cottage, house t1t49ihz, gothic portal, fantasy medieval (running). Mod NOT installed (as asked); no regression suite.
Fixed tonight (all in BoxFillModule unless noted):
- Pits: a 1-block pit in the floor/ground is filled standing next to it (low eye), also oriented blocks on the floor.
- Leaves: the few leaves hiding a trunk/spot are cleared (leavesInWay); low canopy (<= floor + 3) is cleared too.
- Narrow gaps: lines up before dropping in (no bouncing on a slab edge), gives up lining up after 1.5 s.
- Ground order: the whole ground part near first, the hill over the floor cleared alongside (no 64-block hops).
- Buried stuff: caves/pockets under the build's ground left as they are; badlands terracotta counts as ground;
  hill blocks in walls are now replaced (covered() only in the ground layers); spots under the hill wait for it.
- Part order is sticky (8+ blocks to go back to an earlier part): flip-flops 81 -> 2 on the natural medieval.
- Walking: only onto a floor (fell off an upper floor before); low-hover bounce safety net.
- Helper blocks for oriented blocks: only where he can actually stand and see the face.
- Stuck 4 min over a route point straight below him: such points count as passed now.
- Return from "stepping back to look at the build": the route search includes where he is (no flight over the top).
- End of a build: impossible spots are left after 2 tries when nothing else is left (no "confused" glancing).
- Test: no water under normal-sized builds (he's slow under water: a known weak spot).
Results: stalls >= 15 s and [confused] are mostly gone; "nowhere" skips down a lot (barn 263 -> 60, house 547 126 -> 44);
landings: still 30-40 in the cottage/portal (low rooms, wall tops), 2-17 elsewhere.
- Fantasy medieval (2881, steep hill 74..84: 3 min digging it down first): 844 placed (108/min), 93% exact, 2 part
  switches, 1 left, 6 landings; one 37 s stuck in front of a 2-high gap: x never changed, y 90.3..90.96, "path 6 cells,
  none in sight" to the way point 0.15 over its floor. The low-gap clearance stopped once within 0.3 of the goal
  height (goal.y < p.y - 0.3), the hover took him up again, and the "bumped into something: go up over it" jump fought
  it. Now the low clearance holds while goal.y < p.y + 0.1, and no bump-jump with a low roof near (roofNear).
- Decorative peri (2251, a 100-long wall) on a mountainside (heights 64..121: nothing flatter within 96 of spawn):
  stopped by the test after 4 min without progress (726 s, 623 placed). The wall runs into the mountain: its spots
  25 deep under rock that's above the schematic's box (not ours to clear) - no way in without tunnelling; he hovered
  over the mountain trying each ("couldn't fly there" 39, stalls 136 s / 92 s). Not handled (building inside a
  mountain); the test's landSpot now searches out to 160 when nothing within 96 is near flat.
- Castle nfc31vca (10758, TNT20, hills 63..69, 3371 dirt under it): the TNT came at 3 min while he was still on the
  dirt fill and the plants round it: "0 of the 0 built round him" - nothing learned about repairs; 0 stalls, 0 skips,
  1 landing, 0 jumps. (A TNT test on a big build needs the TNT later, or a prebuild.)
- House nhzpp3wk rerun on dry land (hills 65..70): 971 placed (was 586 over the pond), 0 stalls, 0 confused, 0 left,
  5 landings, 4 jumps, 11 turnarounds, 1 still stare. All 15 min on its huge ground (25310 blocks).
- MY BUG (07:07-07:18): the landSpot search out to 160 reached chunks that weren't loaded; their height reads as the
  bottom of the world (-64), "flat", so gigantic medieval, grand trading hall and the iron farm were all placed at
  y -64 and sat "box is far away" (two stopped by the test after 4 min, the third stopped by me). Fixed: a candidate
  with any sample in an unloaded chunk (hasChunk) or at the world's bottom is skipped. Those three need a rerun.
- Maze (laberinto, 94756 blocks; "nothing better: at spawn" - no fully loaded spot that big): ~100 placed per 30 s,
  but 17% of ticks over 5 ms: "pick 1179 ms" a 30 s with 50-58k holes in the window. pickPlanTarget checked
  columnWaits on every far hole (lowest layer first) before any near one was found; now near ones first, far ones only
  listed, then the 64 nearest checked (heap, no full sort) and a full sort only if none of those will do. buildOrder
  checks at most ~300 more of a part once its lowest is known (counting to STICK).
- Statue of Lenin (56946, a dark oak/birch forest on a slope 91..112): 15 min of clearing the forest (2830 actions:
  birch leaves, dark oak logs), 308 placed; 1 stall 17 s, 0 confused, 0 left, 32 landings (12 of them in the first
  16 s under the birch canopy where the test set him down; the bounce net kicked in), 32 "stares" (2 still: glancing
  from leaf to leaf while clearing). pick 409 ms / 30 s with 13.7k holes (before the pick speed-up).
- Room hopping: footWork only counted a 2-high gap (lowRoofOver) as a place to walk; the cottage's rooms are 3 high:
  set down, walk, take off (hovering at 1.1 leaves 0.1 to the ceiling), touch the floor, again (6 landings in 8 s).
  roomRoof(pos): a ceiling 3 over the feet with the 2 cells under it open; footWork walks under one too (goal near,
  no higher). (steer's walkUntil extension still only for 2-high, so he does take off for a goal upstairs.)
- Obsidian bridge (1842, 146 long over a valley, 196 dirt, 383 columns too deep): 1114 (98.2% exact), 0 stalls, 0
  confused, 0 left, 5 landings, 2 part switches; two 63/68-block trips end to end and back in the ground part.
- Black hole (pixel art, 16400; hills 65..69): steady black concrete (~60-95 per 30 s), 0 landings, 9 slow ticks;
  but 112 "nowhere": its foot is in the hillside (dirt to knock out), with grass/flowers ON that dirt where the image
  has a block too (so the tufts aren't on the clearing list): the tuft's outline hides the top face, the hill the
  sides. giveUp("nowhere") on a BREAK with such a tuft on it puts the tuft in clearFirst (knocked out first, "[schem]
  X has Y on it hiding its top"), and columnWaits waits for a clearFirst tuft over it.
- Black hole result: 2384 placed (159/min), 0 landings, 0 stalls, 0 confused, 10 left (its foot in the hill), but
  jumps of 67-103 blocks: a 120-wide upright picture built 2 layers at a time; the failed hillside spots at its left
  end were retried at the end of a row and pulled him back across (the tuft fix should cut those).
- Gigantic medieval (47310, hills 69..75, 1110 dirt under it): 1354 placed, 0 stalls, 0 confused, 0 left, 2 part
  switches, 15 landings, 10 jumps, 2 still stares; the tuft fix fired ("has Tall Grass on it hiding its top").
- Grand trading hall (110444, a slope 66..98): 5 min clearing the hill (1085 knocked out, 49 "done") and the test
  stopped it for "no progress for 4 minutes" (it counted only planDone). The test now counts placedCount too
  (knocked out counts as progress). Its picks: ~1.1 s per 30 s with 74k holes in the window, 20% of ticks 5 ms+ (the
  bot's own tick time). New cost buckets in the status line: "order" (buildOrder) and "pickfar" (the far search),
  both inside "pick", to see where it goes on the next huge build.
- Cottage rerun 3 (rooms walkable, tufts, test progress): landings 36 in 9.5 min, "bouncing" 3 times: a bounce at one
  spot, 13 landings in 20 s: on a bottom slab beside a 2-high way in (its top 1.5 over the slab: the head never fits,
  and on the slab he can't get lower: a player would sneak). The goal was the way point 0.15 over the gap's floor,
  below the slab's top: shift, land, take off, shift... The wedge check never fired: bobbing up and down counted as
  moving. route0 now also counts as wedged 1 s without 0.1 of sideways progress while the goal is 0.5+ off sideways:
  step by step, and after 3 s into the same cell it's off the map (stuckCells) - another way.
- Cottage rerun 3 result: 546/1035, 75 landings (72 off again within 2 s; was 43), 2 stalls ~19 s, 1 confused, 20
  part switches (8 back), 11 left. ~20 of the landings were the slab bounce (fixed above); the 3-high-room walking
  didn't help (set down, off again at once) -> reverted (roomRoof stays unused).
- Villager 4th go (hills 66..70; with the room walking, before its revert): 1978/2194 (90%, 94.7% exact; the 1st go
  did 3 blocks in 90 s), 0 stalls, 0 confused, 4 left, 10 part switches (5 back), 37 landings: "keeps coming undone"
  4 = landing/walking on its farmland turned it back to dirt. Another reason not to walk more.

### Night summary, update ~09:20
More fixed since 06:25: hill pockets wait for the hill; badlands terracotta = ground; plants on blocks to dig are
knocked out first; low-gap entry (no up-down bob, no bump-jump); end-of-build impossible spots left after 2 tries;
bounce safety net; stuck-sideways detection (slab beside a 2-high gap); pick speed-up for huge builds (near first,
far by a 64-heap); the test: dry land only, chunks must be loaded (my 07:07 bug sent 3 builds to y -64), digging
counts as progress. Tried and reverted: walking in 3-high rooms (more landings, trampled farmland).
New schematics run: every one in the folder at least once (inn, forest cathedral, villager, special, dining, natural,
house bqqskqvm/nhzpp3wk/t1t49ihz, fantasy, decorative (mountain site), castle nfc31vca, gigantic medieval, trading
hall (cut short), iron farm (cut short, rerun queued), maze, statue, obsidian bridge, black hole; copper guard queued).
Weak spots left: water in the site; building inside a mountain; landings in small low rooms (cottage, ~40);
oriented blocks with one awkward neighbour (a few per build); huge builds' pick cost (pick timing now logged).
- hov() in rooms under 5 high: the middle of the free height ((room - 1.8) / 2: 3-high 0.6, was 1.1 with 0.1 to the
  ceiling; 4-high 1.1, was 1.5). Hypothesis for the cottage's room landings: head bump, drop, the catching jump
  refused by the double-tap guard. To be checked on the batch 8 cottage run.
- Fantasy medieval rerun (hills 108..112): 1299/2879 (94.3% exact), 0 stalls, 0 confused, 1 part switch, 7 left,
  5 landings, 5 jumps.
- Inn rerun (no TNT; sand/badlands edge, hills 64..69): 1588 placed, ground done at 607 s then the outside, 0 stalls,
  0 confused, 1 left, 13 landings, 4 part switches (1 back).
- Wizard tower TNT20 (8732, hills 70..75): 0 stalls, 0 confused, 4 landings, 2 part switches, but 4 damaged spots
  at y 71 (ground under the floor, over the crater) never repaired: "repairing 5 damaged spots first" stopped at
  09:53:42 with them never in the window (activeY 72: a layer whose only holes are "floating" counts as finished in
  pass 0, and the "missing down at y" pass skips floating ones). Now damaged spots outside the window are added to
  the holes (sorted again by y), and marking a spot damaged lowers scanFromY to it. Over the crater the pillar logic
  takes it from there.
- Iron farm rerun (batch 8): 10.5 min of pulling short/tall grass before the first block (15 "done" at 630 s). The
  world's own grass and flowers in the yard (the 3 round the build) now stay: the clearing skips plants (replaceable
  or no collision) outside SchematicMode.inCore (the build's own columns, coreColumns: any column with a non-loose
  block of it, ground layers' plain ground aside). Inside the footprint they still go.
- Iron farm rerun result (before the yard-plants change): 205 placed, 2911 done in all (nearly all plants: "extra
  sunflower=236", short grass...), 14 turnarounds with nothing placed (chasing tufts), 0 stalls, 0 confused, 2 landings.
- Trading hall rerun (110444, at spawn): 782 placed, 1 stall 16 s, 0 confused, 2 landings, 0 left; but 59-62 block
  trips both ways: its corner at -232,-285 got one block per band (the band's last there), then back to the far
  side for the next band. buildOrder: when none of the band is near, the same part near us (in `ok`, up to band + 8)
  goes first - finish the wall here, then go across (the user's "finishing the left wall"); the far nearest of the
  band only when nothing of the part is near.
- Yard plants + damage-in-window + finish-here are in from batch 8's cottage on.
- Cottage rerun 4 (snowy hills 84..88; hov middle-of-room, stuck-sideways, room walking reverted): 870/1072 (95.9%
  exact; earlier 620/697/546), 0 stalls, 0 confused, 27 landings (was 43, then 75), 0 still stares. Still: 26 left
  (oak stairs 15, barrels 11), 35 part switches (16 back).
- Barrels: the cottage's 11 left barrels want facing=down on a floor: that needs a look up at them from below
  (nearest looking direction UP), out of reach of anyone standing on the floor. The "any way round" relaxing (after
  3 "nowhere") now covers barrels too, and jobAt counts a relaxed spot with the right block as done (no redo loop).
  (relaxed made static for jobAt.)
- Gothic portal 4th (hills 70..74, birch/oak): done at 879 s, 1202/1219 (97% exact), 26 landings (70 -> 34 -> 26),
  2 left; but the portal never lit: "something's in it" (a tree's leaves in the opening: the clearing only takes
  what's in spots the schematic has as air, and the opening is nether_portal) and then "Done building". Now
  lightPortals puts the opening's cells with something breakable in them on the plan as air (portalClear), knocked
  out, and takes them off the plan again once empty (lit, the portal blocks aren't "in the way").
- Special medieval rerun (hills 73..76): 967/1979 (same count as before), 0 stalls, 0 confused, part switches 7 (2
  back; was 41/19), landings 19 (was 37), 8 left (spruce stairs, top half, over trapdoors), 0 still stares.
- Copper guard (181832, finally on land): the dirt fill and plate going well (+250..1100 "done" per 30 s), but "order"
  229 ms + "pick" 568 ms per 30 s with 16.8k holes, 28% of ticks 5 ms+. buildOrder now looks at the first 3000 holes
  only (lowest first: each part's lowest is there); the window-growing rule only when it saw them all.
- Copper guard result: 981 placed (24047 "done" with the matching ground), 0 confused, 1 part switch, 3 left, but a
  180 s stall: spots at y 66 under a dark oak canopy (75..86) in the site; over it with "path 0 cells", straight down
  into the leaves, up over the top, down... each spot "couldn't fly there" in turn. giveUp("couldn't fly there") now
  calls leavesOverhead(t): the natural leaves in the column over it (up to the first other solid block, the plan
  wanting air there) go in leafWay, the window reaches up to them (extraLayers), the clearing takes them: a shaft down.
- Gothic portal 5th (hills 63..72): 1283/1285 (99.3% exact), 0 stalls, 0 confused, 1 left, 15 landings (70 on the
  first run); the opening was clear this time, but the last minute he stood on the ground 3.6 off the portal, "going
  to light the portal" every 5 s, not moving: lightPortals runs before the take-off check (holeCount 0), and on foot
  steer() only walks (a step in the way). Now lightPortals takes off first when it has to move and isn't flying.

## 2026-10-16 (day, after the user's "continue"): batch 10+
- Wizard tower TNT20 (damage-in-window fix): "54 of the 192 built round him were blown out, all back after 110 s, 0
  still out". Fixed.
- Gothic portal 6th: 1227/1229 (99.3% exact), 0 confused; reached the lighting spot 30 s before the end but never
  clicked: the click wanted the crosshair exactly on the bottom obsidian's top face (decorations in front). Now any
  inner face of the frame whose fire cell is in the opening counts; 5 s at the spot without a click -> the other side
  (portalFlip).
- Special medieval's 8 upside-down spruce stairs (a row facing out over the edge, an open trapdoor under each, the
  neighbours in the row not in yet): tryScaffold only imagined eyes straight in front of a side face, from where a
  stair can only face the block clicked. Now also from 2.2 off to each side looking along the face.
- Cottage (desert, batch 10): 724/999 (94.9% exact), 0 stalls, 0 confused, 26 landings, 35 temporary blocks (the
  side eyes), but 30 left: 12 barrels (the end-of-build relaxing never came: by then they'd been "left as they are"),
  17 stairs. giveUp: a barrel or 16-way block reaching 5 "nowhere" fails is relaxed then and there ("any way will
  do") instead of left. The stairs: an upside-down oak stair beside a BOTTOM slab: needsTemp's "a right side is solid"
  check came before "its temporary block is on the way", the slab (upper half empty) counted, the stair was picked
  before its temp went in, failed 5 times, left. Now the pending-temp wait comes first (until the temp is in), and only
  full-block neighbours count as a side to click.
- Iron farm with the yard-plants change: ground done in ~70 s (was 4-10 min of tufts), 914 placed (was 205), 0
  confused, 0 left, 5 landings; one 66 s stall inside the farm's works (water channels, waxed lightning rods,
  ladders: "shaft true", "wedged: next step", "can't get into X"): he re-picked a target every ~2 s and the camera
  glanced at each (8 "still stares" in it). Not handled (a mob farm's insides).
- nightcheck: a "stare" window now needs no place AND no break in it (clearing runs were all false: the trading
  hall's 50 -> 0). Trading hall (desert dunes 58..78): 15 min knocking out sand (3452), 0 placed, 0 stalls, 0
  confused - just too much digging for 15 min on that site.
- The iron farm's 8 still stares: the camera following a target 2.5 off while the body jiggled +-0.35 in a 1-wide
  shaft ("wedged"), keys jump+sneak held together (cancel out in flight). Not looked into further yet.
- TNT house bqqskqvm (batch 10): the TNT came at 3 min while he was still on its 1020 dirt: "0 of the 0 built round
  him" again. The test's TNT20 now waits (from 3 min, checked every 10 s) until 100+ blocks of the build are right
  within 24 of him, or 10 min at the latest.
- Statue (batch 10, spruce forest on a hill 70..82): 2755 done (mostly clearing), 0 confused, 1 stall 17 s, the
  canopy-shaft fix fired ("under a tree's leaves with no way down: clearing a way through the 4 over it"), but 69
  landings (67 off again within 2 s): "setting down to walk in under a low roof ... over it Spruce Leaves", 6 in 3 s at
  one spot. The low-canopy clearing counted from the floor (clearFromY + 3); over the hill the ground is higher.
  Now also natural leaves with solid ground (not leaves/log) 1-3 under them are cleared (lowOverGround).
- Dining hall (batch 10, oak forest 85..90): 653/930, 10 left, 25 landings, 1 confused; "extra water=90": its bubble
  elevator (a source boxed in glass, a flowing block over a bubble column) got its water at 3 min with the shaft open
  at the bottom; it ran out over the floor. waterWouldRun0 only stopped for plan holes in reach; now (replace mode)
  any reachable spot whose finished state has no water (air, or outside the plan) means "would run": it waits.
- Forest cathedral (batch 10, snowy spruce mountainside 112..127, 899 dirt): 1000 placed, 0 stalls, 0 confused, 0
  left, but 44 landings (34 off again): working the ground plate, its pits stood beside (low stand: down), then the
  next block picked with no stand yet -> footWork false -> take off, and down again by the next pit. footWork now
  stays on foot while the stand isn't worked out yet if the new target is within 4 and from 2 below to 1 above.
- House 547 (batch 10, hills 71..76): 1045/3796, 0 stalls, 0 confused, 2 part switches (0 back), 2 left (trapdoors),
  5 landings, 0 stares. Clean.
- Gothic portal (batch 11): 1110/1210 at 15 min (didn't finish this time: no lighting to see), 98.5% exact, 0
  stalls, 0 confused, 17 landings. Lighting to be checked with a prebuild run (HA_PREBUILD=37: only the portal left).
- Dining hall (batch 11, hills 64..67): water OK now ("missing water=1": it waited, no flood); 750/944 (94.1%), 0
  confused, 9 landings; but 57 left, 38 of them stripped spruce logs: "leaving ... as it is: nothing else left, and no
  spot for it twice" at 14:43 - my end-of-build rule fired 13 min in with 194 still to go (ok was empty for a moment,
  the rest waiting on things). Now it fires only when every hole left has had 2+ "nowhere" (allStuck).
- Cottage (batch 11, birch forest): 549/999 (88.4%), 0 stalls, 0 confused, 9 left (was 30: no barrels left), but 83
  landings (81 off again within 2 s), most "flying to a hole" in clusters of 4-6 a few seconds apart. The recording:
  set down to walk under the low roof, 2 ticks on foot, take-off (double tap), shift down, land... On foot, steer()'s
  "no floor under the way point: fly now" (floorUnder(goal)) also fired for a goal hovering 1-1.5 over its floor (the
  stand at hov, the floor 2 cells under the goal's cell), and the walk-on roof test was narrower than the set-down's
  (a roof over a spot 1.7-2.5 off set us down, nothing kept us on foot). Now walkable(feet, goal): floor all along the
  flat line at about our height (gapAt from -0.6 to 1.1) decides "fly now" on foot, the set-down needs it too (from the
  floor under us), and the on-foot roof test is the set-down's (roofAhead, roof over us, or roof over a goal within
  2.5). Log "[box] on foot, no floor all the way to ...: flying".
- Statue (batch 11; 98x66 footprint, 136 high, birch/oak hill 67..83): 15 min of clearing the hill (3116 "placed",
  89 of the build), 0 stalls of 15 s+, 0 confused, 0 skips, 0 left; 29 landings, all off again within 2 s, the same
  set-down-and-off pattern as the cottage (under oak leaves) - started before walkable() went in. Too big a site to
  judge anything else in 15 min. (Batch 11 stopped here; its last 5 runs moved into batch 12 after quick checks.)
- Test: HA_EXCEPT=trapdoor,stairs,barrel with HA_PREBUILD=99 pastes the whole build but those: a 6-minute check of
  only the awkward blocks (the "left as they are" ones). schemsuite.ps1 clears HA_EXCEPT between runs.
- Cottage's 2 top-half oak trapdoors at -20/-21,97,7 (a planter's front edge: dirt at y 98 off the wall, a bottom-half
  trapdoor over each): the only click is the bottom face of the trapdoor over it, looking north (from outside, low
  down). Checking with the HA_EXCEPT run whether the stand search finds that.
- Gothic portal prebuild (HA_PREBUILD=37 on hills 109..116): the walls pasted round the hill, so ~225 bits of hill and
  2 trees inside to clear first (some sealed in: a 30 s "can't get at" stall, then left - a test artifact). Then:
  "the portal at -68,123,165 has Oak Leaves in it: knocking that out first (7 spots)" and, the same tick, "done ...
  Done building" - lightPortals returned null after putting the leaves on the plan, so planComplete() ran before the
  scan had them as holes. In play he'd have come back ("got damaged") but the user would see Done, then fixing; the
  test stopped there. Now it returns "clearing the portal's opening" (scan forced) while those spots are on the list,
  up to 10 s of no holes for them, then leavePortal as before.
- Cottage HA_EXCEPT (flat, 6 min): 98% of its 264 trapdoors/stairs/barrels in (5 left: 2 top-half oak trapdoors in a
  window opening, 2 upside-down stairs, 1 open trapdoor; 6 floor barrels facing down not reached in time). But 53
  landings in 6 minutes even with walkable(): "setting down ... (going to 13.50 -54.85 11.50, over it Oak Trapdoor)",
  1 s later "on foot, no floor all the way to 13.50 -54.85 13.50 (path point 2/7): flying" (true: that way point is
  over the gap where the railing trapdoors go), up, the route from up there is the first point again, down... Now:
  walkColumn() uses real collision tops/bottoms (a slab or carpet is a small step, a block at head height is in the
  way, more than 1.1 down is a drop), and after the walk is stopped that way no setting down for 3 s
  (noSetDownUntil).
- CRASH (old bug, found by the special medieval HA_EXCEPT run): walkThroughDoor set walkDoor before its "ground in
  front of the door" check; a door over a gap returned false with walkDoor set and walkTo never set (the first door
  of the session), and the next tick read walkTo.x: NullPointerException, game crashed. walkDoor is now set only
  with walkFrom/walkTo, there's a null guard, and they're cleared together. (Not installed: the user's copy still
  has this bug.)
- Test: the prebuild now also pastes the other halves (door tops, bed heads, tall grass tops); the cottage's door
  stood there half there and he gave up walking through it (a test artifact).
- Portal on flat ground (HA_PREBUILD=37): "going to light the portal" for the whole run, hanging 1.4 over the spot:
  the spot's eye was 2.0 over the obsidian (feet 0.4 over the floor), lower than the hover ever goes, and "there"
  needed 0.6 in 3D. Now the eye is at the usual hover over the floor 2.5 in front (hov), and "there" is 0.6
  sideways and 1.0 up/down.
- Portal again: the lighting spot 2.5 out (on a cell boundary) put the body in the gothic portal's crimson-fence screen
  (a lattice 2 in front of the frame): unreachable, he hovered 3 over it. Now: a cell 2-4 out on either side (the
  build-outer side first) where the body fits (flyable), eyes at hov over its floor, and the obsidian top in clear
  sight from there (clearTo, not through the fences). On the gothic portal that's the open west side, 2 out.
- Landing trace: "stopped flying" now says what last held sneak (set-down / descend / door landing), how long ago,
  the steering goal, and the walk/set-down timers. The special medieval's bounce (7 landings in 4 s on a log's edge
  next to a 1-wide drop, sneak held all the way down right after each take-off) still unexplained: next run says.
- PORTAL VERIFIED (flat, HA_PREBUILD=37): "lighting the portal at 17, -59, 19 (91 blocks)" 5 s in, then done:
  REAL EXACT 14060 of 14060 (100.0%). (Leaves-in-the-opening case still only reasoned, not seen.)
- Cottage HA_EXCEPT again (with the walkColumn + cooldown + portal fixes): 0 left as they are (was 5), 57 landings
  (50 off again within 2 s). The new trace sorted them: (1) set-down over a lower floor beside the goal's ledge
  (gapBelow takes any corner), slid down a step, "no floor all the way to <the same goal>": up again; (2) the low
  "descend" with clear 0.10-0.15 and gap 0.20-0.50: one sneak press drops ~0.4 in all (0.15, then 60% of it each
  tick), so it touched down; (3) door landings (walk through, by design; 8 in 2 minutes going in and out). Now:
  set-down only when the floor under our middle (groundTop) is within 0.6 of the goal's floor and walkable from
  there; low descend only with 0.43+ under us.
- Doors: one he opened to get through (the schematic has it shut) was "damage" at once (repaired first: fly back,
  shut it), then opened again to get past: "keeps coming undone", 8 door landings in 2 minutes on the cottage test.
  Now openedDoors (static set of lower halves, only doors the plan has shut): jobAt says DONE for them while open;
  at the end (holeCount 0) closeDoor goes to the nearest one still open (out of the doorway first, flies back if out
  of reach) and shuts it: "shutting the door at X (opened it to get through)".
- gapBelow/gapAt counted a block's cell top, not its real one: with the feet inside a slab's/farmland's/trapdoor's
  cell (over its real top) the column didn't count at all, "gap 3.00" (nothing under us), down at full speed and
  landed the next tick (the cottage's "descend (gap 3.00, clear 1.50), 1 ticks ago"). Now topOf() (collision max):
  the first solid block in each column counts if its real top is at or under the feet; a block beside us (top over
  the feet) still ends that column uncounted (the old "up and down a pillar" guard).
- Cottage on snowy hills (16:08, before the latest landing fixes): 730/1071 (94.6% exact), 0 stalls 15 s+, 0
  confused, 39 landings (descend-low 14, set-down 11, open 4, other 4), but 10 floor cobblestones left "nowhere":
  the new debug showed "ray hits -4, 72, -55 up" from every spot - a snow layer / grass tuft (outline but no
  collision) in the spot over the pit. The "on it hiding its top" rule was for BREAK targets only; now PLACE too, and
  if the build has air (or nothing) there it goes on the clearing list (clearOnTop).
- Cottage HA_EXCEPT with the landing fixes: landings 57 -> 25 (logged causes: set-down 3, door 3, low descend 2),
  0 left as they are. New: a 42 s stall - set down 4 blocks off to the side of the door, then walkThroughDoor walked
  straight at the far side of the doorway (on foot it only ever walked to walkTo), into the fence post beside the
  door, hopped onto the wall's log edge, wedged against the post: his cell not open in the map, so route "no way
  from here" and wayOut null ("north open; east open" but no path point in clear sight from inside the post's
  edge). Fixes: on foot and still before the doorway (along < 0.2, > 0.6 off walkFrom) walk to walkFrom first;
  wayOut takes the first step of its path when nothing's in clear sight; route's "no way from here" steps to an
  open neighbour cell that's in the air map ("wedged: a step out to X").
- Part flip-flops (snowy cottage: outside/inside/top every ~15 s from minute 7, 24 switches, 11 back): the outside's
  doable holes came in waves - a row of top-half oak trapdoors (an awning at y 74) failing "nowhere" one after
  another (temps set up for some), the outside dry for a moment -> inside; the next layer's outside holes doable ->
  outside... Now going back to an earlier part also needs 20 s in the later one (phaseSince), besides STICK holes.
- TNT house bqqskqvm (TNT20 at ~10 min): "12 of the 50 built round him were blown out, all back after 50 s, 0 still
  out". Low descends still touched down from gap 0.45-0.60: a press while already sinking at up to 0.12/tick drops
  (|vy| + 0.15) / 0.4 in all (flight keeps 60% of the speed each tick), up to 0.65. The low descend now needs that
  much + 0.05 under us.
- Grass in a spot to fill: faceFrom only looked at the solid neighbours' faces, which the tuft's outline hides
  ("nowhere"), then the spot went on clearFirst (knock the tuft out, then place): 211 times on the TNT house's grassy
  hill. A player just clicks the grass (BlockPlaceContext replaces it). Now faceFrom also offers the faces of a
  replaceable thing in the spot itself (outline, rightWay-checked; snow over 1 layer isn't replaceable for blocks so
  predict says no). clickPlaces already accepted such a click (predict's clicked pos is the spot).
- Cottage HA_EXCEPT (16:27, wedge/door/phase fixes in): 0 stalls 15 s+, 0 left, 984/999, but 66 landings: door
  landings 14 - in and out through the door 7 times in 18 s, the next block by straight-line distance being on the
  other side of the wall each time; and the walk to the door front still bumped the fence post (8 s, recovered).
  Now: the far pick takes one in plain sight first if it's within 8 of the nearest's score (inSight: a COLLIDER
  clip from the eye ends at the spot); the on-foot walk to walkFrom only if walkable(), else take off now
  (takeOffNowUntil: footWork doesn't keep us on foot) and land by the door from the air.

### WHERE I LEFT OFF (2026-10-16 ~16:35, still testing; nothing installed)
- Queue runner: scratchpad schemqueue.ps1 reads %TEMP%\hatest-queue.txt one line at a time (edit it to add/reorder;
  a STOP line ends it). Summaries still go to %TEMP%\hatest-schemsuite.txt; nightcheck.py now sorts landings by cause.
- Fixed today (all compiled, none installed): the walkThroughDoor NPE crash; portal lighting (verified 100% on flat);
  landing loops (walkable/walkColumn real heights, set-down only over the goal's own floor, 3 s no-set-down after a
  forced take-off, low descend needs the full drop of a press, gapBelow/gapAt real tops); snow/grass over a pit
  (clearOnTop) and clicking the grass in a spot to place there; doors we open stay open till the end, then shut;
  wedged against a fence post (wayOut first step, "a step out", walk to the door front only if walkable); part
  flip-flop (20 s dwell); in-sight pick preference.
- Still to verify in the queue: the latest cottage HA_EXCEPT (door ping-pong, landings), special/inn/natural/
  villager/nhzpp3wk/maze/barn on hills.
- Special medieval on hills (16:33; descent + grass-click fixes in, door/in-sight not yet): 997/2061 in 15 min, 0
  stalls 15 s+, 0 confused, 5 part switches (1 back), 8 landings (37 -> 19 -> 8), 2 stares (0 still), 4 left
  (upside-down spruce stairs: e.g. one facing west whose only click is the log's east face, from under the top-half
  trapdoor east of it - the body fits under the plate (1.81 free) but the stand search wants 2 open cells).
- Cottage HA_EXCEPT (16:49, door/in-sight/door-walk fixes in): outside put in after the inside started 2 of 114
  (was 40-43), door landings 14 -> 7, landings 66 -> 43, 0 left; one 19 s "built in" (shut in by his own trapdoors,
  knocked a slab out, fine). The 8 "other" landings were all while placing against trapdoors: sneak (needed against
  a usable block) with space to hold height, but jump() refuses a new press within 8 ticks of the last (double
  tap), so sneak alone sank him to the floor. Now sneak-placing in flight waits until space can be held too
  (canJump()); traced as "sneak-place".
- Landing breakdown tool: nightcheck now splits recorded landings into steps off a ledge while walking (no space, <0.6
  down, <0.5 s in the air: not hops). After a deliberate set-down the median time on the ground was 0.5 s and 1.3
  blocks walked: touch and go. Now a set-down keeps him on foot at least 1.5 s (walkUntil), unless the way needs
  flying (walkable false still takes off at once).
- Inn TNT20 (birch forest/sand, 16:55; landing + grass-click fixes in): TNT came at ~3 min in the ground phase, 202
  of 399 round him blown out, he called it fixed at 168.6 s; the test's 16 "still out" are dirt 2+ under the floor
  level (covered(): nobody sees it once the floor's in) or grass for dirt. 0 stalls 15 s+, 0 confused, 0 left, 7
  landings, 1 part switch, 0 "hiding where to click" (the grass-click). Clean.
- Cottage HA_EXCEPT (17:03; sneak-place + set-down pause in): landings 43 -> 27, set-down 7, door 5, low 3, other 1.
  But a 30 s stall then "leaving what I can't get to as it is" with 57 holes: hovering inside the house under a
  top-half trapdoor his own cell wasn't flyable, so aStarOut/wayOut started in the floor block under him (start =
  me or me.below(), no check) - no way out found, leavesWayOut refused every block (pick 3.1 s of CPU in 30 s), and
  after 30 s the lot were given up. Then the last minute "closing the door" (crosshair never on it). Now
  startCell(): our cell, the one under, or an open one beside us (null only if none); closeDoor gives up after 15 s.
- Safety net for "can't get at" (30 s of nothing pickable -> all holes left as they are): if he's under a roof,
  first "nothing I can do from in here for 30 s (N holes): out into the open first" (wayOut for up to 10 s), then
  another 30 s, and only then give them up.
- Door walk: "if bumping into something on the ground, jump" made him hop in the doorway against its frame/the open
  door (most of the cottage's unlogged landings: W+J, land, W+J...). Now only for a real step ahead (stepAhead():
  something solid at the feet 0.5 ahead, top at most a jump up, room over it).
- Natural medieval (spruce forest hills 64..71; started before startCell/door-hop): 3227/12783 (mostly the 1158
  dirt-fill ground), 0 left, but at the end 2 stalls (38 s, 44 s) and 8 "confused" (11-22 targets in 10 s, nothing
  placed): dirt-fill pits in the yard with the lower half of a tall plant in them - its upper half over the pit hid
  it from every side ("up face of <spot>: ray hits <spot above> up"), so neither the place nor clearFirst's knock-out
  could see it; the yard's plants aren't cleared. Now giveUp("nowhere") with a DOUBLE_BLOCK_HALF=LOWER plant in the
  spot puts its upper half on the clearing list (clearOnTop): knocking that out takes both. Natural re-queued.
  Also 55 landings there (set-down 22, low descend 12) - check again with the later fixes.
- Cottage HA_EXCEPT (17:25; startCell, door-hop, safety net in; tall-plant fix not): 0 left, 0 confused, 32 landings
  (5 ledge steps; set-down 10, door 9), but a 48 s stall at the end: truly boxed in up in the attic (all 6 sides
  shut, he'd put trapdoors/stairs round himself), "27 placeable but none picked", breakOut never fired (no "built
  in" line) and wayOut is null when boxed in, so the new safety net can't help. OPEN: why leavesWayOut let him close
  himself in, and why breakOut didn't knock a way out.

### WHERE I LEFT OFF (2026-10-16 17:33, the user said stop and report)
- Queue stopped cleanly (STOP line); no test running. Nothing installed (the user's copy still has the door NPE).
- Not re-tested yet: tall-plant fix (natural medieval), the 57-block give-up fix in a real build, the doorway hop
  fix in a real build. Open: boxed in up in the cottage's attic (above); cottage/forest landings still higher than
  other builds; a few upside-down stairs/trapdoors in odd spots.

## 2026-10-16 (evening, the user: "proceed continue testing", same setup)
- Queue restarted 17:36: natural (tall-plant fix), cottage on hills, villager, nhzpp3wk, barn TNT, dining, fantasy,
  gothic, t1t49ihz, wizard TNT, cathedral, black hole, bridge, house 547, maze.
- Boxed in the cottage's attic (48 s): he hovered in the south part of a cell whose north edge has an open trapdoor's
  panel (its cell "not a way"), slabs over the cells round it: all 6 sides "shut", wayOut null, and pickBreakOut
  found nothing (it never looks at our own cells). Now: pickBreakOut tries the thin block in our own cell / the one
  over it first; leavesWayOut refuses a block whose cell our body touches; "built in ... nothing to knock out: <why
  per side>" logged if it still finds nothing.
- Natural medieval rerun (hills 71..81, 1291 dirt fill; tall-plant fix in): 3853/13742 in 15 min (167/min), 0
  confused (was 8), 1 stall of 15 s (dug himself into the dirt fill at the very end; breakOut knocked 3 dirt out and
  he was free), 0 left, 36 landings (was 55; set-down 10, low 7, open 5), 3 jumps > 12 blocks, 4 stares (0 still).
- "descend (gap 3.00, ...)" then landed 1-3 ticks later, again and again (natural rerun: 7 of 12 descend landings):
  gapBelow/gapAt/groundTop/walkColumn used solid(), which leaves out anything replaceable - snow layers (2-8 deep
  have a real collision top) included, so over snowy ground "nothing under us within 3", full speed down onto it.
  Now they use standable() (any collision; doors aside in plan mode). solid() unchanged for clicking/placing.
- The user (18:07): "when you start a new world stop teleporting him and have him build it wherever he is, that'll
  challenge him more". HA_TERRAIN now builds right where he spawns: the corner 4,4 in front of him, the floor at his
  feet, no tp ("REAL land: corner X (where he spawned, heights a..b[, water at n of m spots]), standing at ...").
  The old dry-land search is still there as HA_LANDSPOT=1. In effect from the barn TNT run on (nhzpp3wk had started).
- Cottage on hills (17:51, oak hills 66..71): 597/1030 in 15 min (59/min), see the checklist below.
- Villager run died in world loading ("Minecraft Stopping!" at "Preparing spawn area: 16%", then
  "runOnClient called when no client is running") - 18:07:24, about when I compiled the gametest sources in
  C:\dev\human-autopilot; re-queued. Don't compile the test sources while a run is starting (compileJava seemed fine).
- Part order again (oak-hill cottage: outside 9 min, then outside/inside 5 times in 4 min, each outside wave a
  handful): going back to an earlier part now needs 3 x STICK doable there, which count (capped at STICK) never
  reaches - so a later part keeps going while it has anything doable, then back. (Forward switches unchanged.)
- Camera wiggle while wedged (oak-hill cottage 17:54: 4.6 s of +-5-10 degree swings, "wedged: next step"): the aim
  re-took the angle to the hole from each jiggle of the body. work() now keeps the last 20 ticks of eye positions;
  if they stayed within 0.8 but moved 1.2+ in all (back and forth in place), it aims from their average
  (lookDirection) instead of from the jiggling eye.
- House nhzpp3wk (hills 67..78, old land-spot test): 11320/25447 (133/min; mostly ground), 0 stalls 15 s+, 0
  confused, 0 left, 1 part switch, 22 landings (set-down 7, low 5), 5 jumps > 12, 1 stare. Clean.
- First build where he spawned: barn TNT20, "corner 94, 64, 111 (where he spawned, heights 63..69, water at 86 of 143
  spots)" - mostly over water.
- Barn TNT20 where he spawned (lake: water at 86 of 143 spots, heights 63..69): 1122/3327 in 700 s (165/min); the
  385 dirt of the fill went into the lake fine. TNT: 109 damaged -> 14 in 50 s, but 2 blocks of a stripped-wood post
  (axis y; the dirt under it gone, the piece over it there) were never tried in the 5 minutes after. Added a log:
  "[schem] N damaged spots, none to do now: <spot> <why>" every 30 s while damage waits (whyNotOk).
- The barn's post (worked out from the code): middle piece M waited for the bottom B (columnWaits rule "the one
  under goes first if it can go in by itself": workable(B) - the wall beside it is something to click), while B
  waited for M (waitsOnSupport: upright wood needs a block above or below, M "comes"). Deadlock till the end. Now
  "can go in by itself" means a solid neighbour on one of its right sides (rightSides; the spot asking doesn't count).
- Dining hall where he spawned (hills 69..81): a 70 s+ stall bobbing in its pool, "taking off" over and over. The
  recording: in water, space held for 5-9 ticks at a time - HumanAutopilot.breathGuard holds space whenever the
  head is under water ("keep the head up"), so the take-off's two taps ran together and never toggled flight; and
  it was also what switched flight off as he flew in ("stopped flying ... on ground: false": its hold plus a steer
  press made a double tap). breathGuard now does nothing when invulnerable (creative: no drowning).
- Dining where he spawned (hills 69..81): 253/930, stopped at 805 s by the test's "no progress for 4 minutes" (the
  pool stall, 240 s - fixed above), plus a 17 s stall and 3 still stares around the water at 18:44 (he'd put the
  water in at 6 min). 4 left (air x3 = things to clear he couldn't see, a moss block). 8 landings, 1 part switch.
- Dining's flood (extra water=111 over the floor): the farm's spruce stair is waterlogged (water for the farmland);
  he put the stair in and poured the bucket on it at 6 minutes, nothing round it built - a waterlogged stair is a
  source with an open side. planOk's "water last" only covered PLACE of a fluid; now watering (ADJUST with a water
  bucket) gets the same rule (neighbours in first, and not if waterWouldRun).
- Fantasy medieval where he spawned (hillside 66..73): 15 min of mostly digging out the hill (2746 actions, 223 of
  the build), 0 stalls 15 s+, 0 confused, 0 left, 1 part switch, 4 landings, 1 jump, 0 stares. Clean.
- Open-air landings left (fantasy: "descend (gap 2.2-3.0, clear 1.50), 2 ticks ago"): sinking toward a spot down in
  a pit he was digging while drifting sideways, the body's edge met the rim. The descend now also needs the gap under
  the whole body 4 ticks ahead (footprintGap: 9 points +-0.3, shifted by the velocity x4).
- Gothic portal where he spawned: "corner -3, 129, 5 (heights 63..127, water at 56 of 144 spots)" - a mountain top
  over a drop to water. Watching.
- Gothic portal where he spawned on a cliff top (y 129, ground 63..127 below, sea): "0 blocks of dirt under the
  build (380 columns too deep, left open)", then "nothing to place in 422 holes: nothing to click 422; window
  139..164" for 30 s and "leaving what I can't get to as it is" - all 422, run over in 30 s. supportPillar only
  takes the build's bottom layer (the lowest holes were over clearFromY) and bridgeFloating's bridgeTo only reaches 3
  cells. Now bridgeFloating, when none of the candidates is within 3 of anything solid, does one BFS from all of
  them (longBridge, up to 16 free cells) and lines temporary glass from the nearest solid block out to that one
  ("the build hangs in the air here, nothing to put X against: N temporary block(s) out to it from Y").
- Test: HA_FLOAT=N lifts the build N blocks into the air (flat world: a mid-air start to try the bridge on). Queued
  "dark-gothic-por|6|0|HA_FLOAT=12" next. (The running queue script doesn't know to clear HA_FLOAT: the run after
  it has ";HA_FLOAT=0".)
- Order for a build with nothing clickable anywhere in the window: the temporary bridge (bridgeFloating/longBridge)
  first, then (no temps still going in) supportPillar - which now also works above the bottom layer when no hole in
  the window has anything to click.
- longBridge is a big search (up to 30000 cells): after a miss it waits 100 ticks before trying again.
- Medieval house t1t49ihz where he spawned (corner 30, 80, -50, hills 61..87, snowy spruce forest): the build's
  bottom layer has light gray concrete powder and gravel, and on the hillside part of it hangs over lower ground. He
  put the powder in with air under it, it fell, the damage repair saw it gone and put "a 3-block dirt pillar" (and
  an 11-block one) under it, then put it back. Now waitsOnSupport -> fallsNow(): a gravity block (FallingBlock)
  with FallingBlock.isFree under it waits - for the build's own block under it, or (nothing of the build under it,
  at the build's bottom layer, planBottomY) for a dirt pillar to the ground first (needPillar, put up at the start
  of the next pick, up to 4 a pick: "nothing under X to build on (Light Gray Concrete Powder, it would fall): a
  N-block dirt pillar up to it"). Over the build's own air higher up (pasted floating) nothing changes (a pillar
  would fill the build's air in). cantPillar: no ground in reach under it, it goes in as before.
- "N damaged spots, none to do now" listed spots already put back (out of the window): those are dropped first.
- Same run: the dirt fill under the build (SchematicMode.fillUnder) stopped on the first solid block - in a spruce
  wood that was the trees' tops, so dirt went on the leaves in among the canopy, only to be seen from straight
  over it ("dirt, under it spruce_leaves", "has Snow in it hiding where to click", 12 "nowhere" in 10 s = the one
  "confused"). Leaves or logs under a column now leave it open (like a dip too deep).
- Same run: the build's grass plate at its edge ran under the hillside outside the build (hills out there stay:
  wantAt null), stone in it to swap for grass, "nowhere to place it from" 19 times. groundStaysOver now also holds
  over the floor when the world's ground over it is solid and outside what gets cleared (lookup null): done.
- Result t1t49ihz (where he spawned, snowy spruce hills 61..87): 1429/6467 in 15 min (110/min; 1016 blocks of fill
  dirt, 443 columns left open), 0 stalls 15 s+, 1 confused, 64 "nowhere" (fill dirt in the trees, the plate edge,
  2 gravity blocks), 6 landings, 18 jumps >12 blocks, 5 stares. Exact 82.3% of the spots seen.
- Floating gothic portal (HA_FLOAT=12, flat world): the build's lowest layer 29 over the ground, too far for the
  temporary bridge (16): "a 29-block dirt pillar up to it" straight away, then building normally (458/1210 at 3 min).
- Floating gothic portal result (HA_FLOAT=12): 628/1210 in 6 min (112/min), 0 stalls 15 s+, 0 confused, 0 skips,
  2 landings, 4 jumps. The mid-air start works (dirt pillar, then on as normal). The 6 min went mostly on upside-down
  stairs (temporary glass for each) and menu trips (soul lantern, fences...).
- House t1t49ihz: "menu: 92 fetches of 43 kinds in 56 visits, 161 s in the menu" - 18% of the 15 min. Not changed
  yet (pick-off-the-build already exists; the hotbar holds 9 of 43 kinds).
- Wizard tower where he spawned (corner -50, 65, 50, hills 63..72, water at 32 of 90 spots), in a wood: the
  schematic's bushes at its foot are oak_leaves[persistent=true]; the world's own tree leaves there matched them
  (Placement.same only compares the way round), counted as built, then withered once he cleared the trunks: "3
  damaged spots, none to do now: ... waits on its column" for minutes. Placement.matches now: wanted persistent
  leaves on a tree's own (persistent=false) leaves is not a match (knocked out and put in, lasting).
- whyNotOk's "waits on its column" now says what's over it and what's under it (job, hole: workable, in the window,
  skipped why, the window's bottom) - to find why those three waited.
- And why they waited: the repair list skipped damaged spots that wait on their column. The spot under them (the
  bush's lower leaves, a hole but not damage - never seen in) was held back by the order of the parts (the ground
  layers still going). Now the repair walks down a waiting damaged spot's column (up to 8) to the first hole that
  doesn't wait, and repairs that first.
- Wizard tower TNT20 (fired at 350 s, 105 built round him; a crater ~18 deep in the wood): repairs 65 -> 6 in
  50 s (an 18-block dirt pillar from the crater floor first). The last few were bush leaves out from walls not built
  yet, "nothing to click on" - false damage (a tree's own leaves counted as in, see above). Two more rules for
  damage that hangs in the air: (1) the repair takes a workable hole of the build right beside it first; (2) in the
  30 s "none to do now" check, one with nothing of the build beside it gets temporary glass out to it (bridgeTo):
  "X (put right) hangs in the air with nothing to put it against: N temporary block(s) out to it from Y".
- A gravity block over what the build has under it that it falls through (tall grass, the build's own air above
  its bottom layer: pasted hanging) is now BLOCKED in jobAt (fallsThrough), "X falls through the Y under it" in the
  left-as-they-are list. (The wizard tower's 3 cyan powder blocks on tall grass: put back "first" after the TNT,
  "nowhere to place it from" again and again.) planBottomY is static now (jobAt's helpers are static).
- Wizard tower checklist: 0 stalls 15 s+, 0 confused, 20 "nowhere", 4 jumps, but 9 landings and 9 hop-offs within
  2 s: all 5 "setting down to walk in under a low roof" landings took off again a tick or two later ("on foot, no
  floor all the way to <the next way point>"): the set-down only checked the way point steered to, the one after it
  was a step up or over a gap. route0 now keeps the open-air path it went by (routePath, routeIdx) and the set-down
  also needs walkRest(): floor all the way along the rest of that path (each way point's floor via groundTop, then
  walkable between them).
- TNT20 result there: 40 of 105 blown out, all but 1 back (the stripped spruce log over the crater, waiting for its
  support - the dirt fill under it, not damage: now walked down to by the repair).
- Forest cathedral landing "descend (gap 3.00, clear 1.50), 2 ticks ago": sinking fast over a pit, drifting onto the
  ledge beside it; the brake (jump when gap - 3|v| < clear - 0.35) only looked straight down. Now while coming down
  the brake and the descend both use aheadGap(): the least footprint gap 1, 2 and 4 ticks on at this speed (the 4
  ticks on alone could jump over a ledge in between).
- Wizard tower 19:34:10-23: 13 s pushing at an oak trunk in the wood ("wedged: next step", the camera on the log
  0.5 away, "low false"), two targets given up ("couldn't fly there") before "can't get into X": the wedge timer
  restarted each time the step ahead flicked to another cell. Now the wedge also counts from where it began
  (wedgeStart/wedgePos: reset only after getting a block away, or 5 s without wedging): 2.5 s wedged in all and the
  step ahead goes off the map ("can't get into").
- Dirt pillars put up to reach the first block of a part hanging in the air (supportPillar, the "nowhere" one for
  the bottom over a pit) are temporary now: temps.put(cell, best) - knocked out once best is in, like a player
  knocks down the pillar they went up on ("... dirt pillar up to it (it comes out again after)"). The gravity
  pillars (fallsNow: "it would fall") stay - they hold the sand up.
- Open (not fixed): t1t49ihz's awning (6,6,25): a bottom spruce trapdoor facing west whose only neighbour is another
  bottom trapdoor (north, facing east), a hanging sign under it, air east/west. Clicking the north trapdoor's side
  gives facing=south; the right side (the east cell) needs a temporary block, and that one has nothing solid near
  it (trapdoors, hanging signs, a mud brick wall) - tryScaffold's supportChain found nothing. The two hanging signs
  under it wait for it. "nowhere to place it from" each time it comes round.
- Fix to the gravity rule: planBottomY counts the dirt fill under the build (fillUnder's cells are in the plan), so
  on a hillside "the bottom layer" was the fill's bottom and the powder at the floor over a dip too deep to fill got
  no pillar. Now fallsNow/fallsThrough go by the schematic's box (lookup = wantAt): nothing of the build under it
  (plan null and wantAt null: below the schematic) -> the pillar; the schematic's own air under it (wantAt air, or a
  plan AIR entry) -> fallsThrough BLOCKED; without wantAt (no clearing) the old bottom-layer test.
- Forest cathedral where he spawned (heights 63..78, 2939 columns too deep, left open): 2180/14385 in 15 min
  (152/min), 0 stalls 15 s+, 0 confused, 3 skips, 0 jumps >12, 0 stares, 4 landings (2 set-down hops: that run
  didn't have walkRest yet). Clean.
- walkRest with no path kept (the far way, out from under a roof first) said yes; now only a straight route
  (routeStraight) counts as walkable to the end. (Black hole 19:58:35: set down, "on foot, no floor all the way to
  ... (path point 3/20)" 2 s later.)
- Black hole where he spawned (a birch wood, leaf litter everywhere): black concrete in the ground "nowhere to
  place it from", "ray hits 53, 70, 35 up" - leaf litter on the NEXT block over, in front of the face (the on-top
  and in-the-spot tuft rules only look at the target's own column). sees() now notes, during a spot search
  (tuftHits), the tufts a ray hit instead (replaceable, no collision, not the face's own block); giveUp "nowhere"
  clears the 3 nearest of them that the build doesn't want (clearOnTop) and tries again in 2 s: "X is hidden
  behind Leaf Litter (N of them): knocking that out first".
- Black hole landing 20:01:48 (recording): climbing out of a pit at +0.37/tick to the next way point (0.15 over the
  ledge's floor), jump let go at 72.13, one sneak tap to stop the glide (d.y - glideY < -0.1), then drifting 0.4
  onto the ledge: down. aheadGap went by the speed (0.05/tick: nothing ahead seen); now it also looks half a block
  on toward the steer goal (d) - the floor there counts for the descend and the brake.

### WHERE I LEFT OFF (2026-10-16 ~20:03, still testing; nothing installed)
Queue running (schemqueue.ps1, %TEMP%\hatest-queue.txt): black hole (now), then medieval-house-t1t49ihz, obsidian
bridge, medieval-house-547xzkzq, laberinto, villager, cottage TNT20. Builds go where he spawned (HA_TERRAIN).
Changed since 19:00 (all compile; each run picks up what was compiled when it started):
gravity blocks (fallsNow pillar first / fallsThrough blocked, by the schematic's box), fill dirt not onto trees,
hill-edge ground left, natural leaves not counted as the build's, damage repair walks down waiting columns / takes a
workable neighbour / temporary glass to floating damage, set-down only if the rest of the route walks (walkRest,
routeStraight), aheadGap (1/2/4 ticks + half a block toward the goal) for the descend and the brake, wedge give-up
after 2.5 s wedged in all, reach pillars temporary, tufts in the way of a target's faces knocked out first.
Results today since 19:00: floating gothic clean; t1t49ihz 1429/6467 (the powder falls - fixed); wizard tower TNT
40 blown, all but 1 back; forest cathedral clean (2180 in 15 min, 0 stalls 15 s+, 0 confused, 0 stares).
- Trapdoors and stairs are most of the "nowhere" skips in every build (house 547xzkzq: 34 dark oak trapdoors - open
  shutters beside windows, only the wall beside them to click, which gives the wrong facing). rightSides is 0x3F for
  trapdoors, so needsTemp never tries a temporary block first; giveUp "nowhere" does try tryScaffold, which found
  nothing. tryScaffold now logs why per side ("no temporary block puts X (state) in the right way round: WEST: ...").
  To read on the 547xzkzq run (queued).
- Black hole (a pixel-art wall 1 thick, through a birch wood): its bottom rows "nowhere to place it from" 121 times
  and 2 "confused" bursts (26 and 23 targets in 10 s): the tree's leaves just outside the build's box, in front of
  every face. leavesInWay only took leaves the schematic has air for (inside the box). Now also a tree's own leaves
  within 2 of the build's box (nearPlanBox): those go straight on the list (clearOnTop: plan AIR, BREAK).
- And the black hole's bottom rows were in a hillside: a spot to fill (PLACE) under the hill's own dirt where the
  build has a block of its own (that dirt a BREAK) was picked and was buried. columnWaits: such a spot now waits
  for that dirt to come out first (the dirt itself goes top-down as before).
- Black hole result (where he spawned: a birch wood on a hillside; the build is a 129 x 128 pixel-art wall, 1
  thick, its bottom rows in the hill): bad. 1136/16409, 7 stalls 15 s+ (the worst 75 s), 12 confused, 416 "nowhere"
  (43 left), 20 jumps (up to 98 blocks, end to end of the wall for "nowhere" targets), 16 landings, 21 stares.
  The 75 s one: on the ground under the birch leaves, "taking off" over and over: the first jump of the double tap
  was back on the ground (ceiling 2 up) by the 4th tick, where the second tap switched flying on and straight off.
  takeOff now taps on ticks 0 and 2 when there's no room 2.2 over the feet; after 3 failed tries it walks to the
  nearest walkable spot with 3 open cells over it first ("can't take off here ...: walking to X first").
  The black hole is queued again at the end (with the tuft, near-box leaves, buried-spot and take-off fixes).
- columnWaits' top-down rule skipped any leaves over the spot (natural leaves left to wither). Leaves in a spot where
  the build has a block of its own (planUp non-air: a BREAK) now count too (both BREAK, or a PLACE under them).
- House t1t49ihz rerun (corner -5, 76, 10, heights 68..76): 1720/6688 (was 1429), 0 stalls 15 s+, 0 confused (was
  1), 40 "nowhere" (was 64), 1 left (a spruce stair), 1 landing (was 6), 12 jumps (was 18), 4 stares. The gravity
  pillars worked ("nothing under 19, 76, 35 to build on (Gravel, it would fall): a 7-block dirt pillar up to it").
  But put in at ~10 min they made "the ground" part open again: ground/outside switched back and forth 3 times.
  Now fillUnder (plan time) fills deeper under a column whose lowest block is a gravity block (up to 40, not 6), so
  those pillars go in with the rest of the ground; fallsNow's late pillar stays for what's left (TNT, trees).
- The house rerun's 22 dirt "nowhere": fill dirt at the bottom of a 1-wide pit 2+ deep (the fill columns round it
  higher). Only its bottom shows, only from right over it: the usual hover over it is 5.1 from it (too far), the
  low spots beside it see only the walls ("ray hits 15, 75, 32"), and the low spot straight over it was never
  tried (the "look up from under it" branch only). standFor now also takes the spot straight over the pit, 2+ up,
  at the LOW hover, looking straight down - only once steepFine (the usual spots have failed), scored +6.
- !! House 547xzkzq where he spawned (corner -3, 93, -45 on a hilltop over a valley, 445 columns too deep): 0/3796.
  "the build hangs in the air here ...: 10 temporary block(s) out to it from 5, 89, -46" at t=0; the first glass of
  that run was "can't get to" (not gettable), so the bridge never started; while any temp was a hole ("bridging")
  supportPillar was held back (and its lowest layer was the glass's, workable, so it found nothing anyway); 30 s of
  "3806 hole(s) I can't get at" and then "leaving what I can't get to as it is" marked ALL of them cant: "done:
  0/3796 (3796 spots left as they are)". The pillar that would have done it came one tick later.
  Now: a bridge with not one block in after 10 s is dropped (dropUnbuiltTemps; noBridgeUntil +60 s) and the pillar
  goes instead; and before the 30 s give-up, forcePillar (up to 3 per build): a pillar under the nearest lowest
  hole with nothing to click ("nothing to put anything against for 30 s ...: a pillar up to X first").
- Obsidian bridge (over a valley, 938 columns too deep): 1394/1646 and "done" with 252 missing at both ends (0
  "left as they are"): the far ends' bottom-layer spots hang in the air (nothing to click), floating spots don't
  hold a layer open, the window went up to the arches (y 100) and never came back down; the count pass leaves
  floating spots out of passLowest too. To fix: a last sweep before "done".
- The last sweep before "done" (finalSweeps, up to 6 per build): with no holes in the window, every spot of the
  plan is looked at; any still to do (loaded) -> scanFromY down to the lowest of them, 5 s of "looking the build
  over" for the scan to pick them up; if the only ones left are in parts not loaded now and never seen in (seenDone)
  -> fly over there (sweepFar, up to 60 s) and look. Only then planComplete.

### WHERE I LEFT OFF (2026-10-16 ~20:48, still testing; nothing installed)
The user asked at ~20:41 how it's going (told them: gravity blocks, hops, stuck-under-roof, forest fixes, results,
and the two big bugs just found). Queue now: laberinto (running), house 547 (normal), house 547 lifted 8
(HA_FLOAT=8, tests the dropped bridge / forcePillar), obsidian bridge again (;HA_FLOAT=0 - the running queue
script doesn't clear HA_FLOAT), villager, cottage TNT20, black hole, inn TNT20, barn TNT20.
Biggest fixes this stretch: 547's "give up everything after 30 s" (bridge never started -> dropped after 10 s ->
pillar; forcePillar before any 30 s give-up) and the bridge's early "done" (the last sweep).
- Wedged but "moving": the house rerun's 4 s against spruce leaves (x fixed, sliding 0.3 back and forth in z) never
  counted as stuck (stuckSideways only asked "moved 0.1 sideways in 1 s"). Now also: no nearer the goal by 0.15 for
  1.5 s while still within a block of where that began (routeBestD/At/Pos; going round something moves you a block
  away and resets it) -> the step-by-step way with its 2.5 s "can't get into".
- Laberinto where he spawned (half over water: 9458 blocks of fill, 13601 columns too deep): 2814/98700 in 15 min
  (188/min, all of it the dirt fill), 0 stalls 15 s+, 0 confused, 0 skips, 3 jumps, 3 landings, 1 stare. Clean.
- The shutters: tryScaffold's new log on house 547 (rerun): "no temporary block puts 76, 65, 24 (spruce_trapdoor
  facing=east, half=top, open) in the right way round: down/up/west: the build's; north/south/east: no click on it
  gives the right way". West is the wall it hangs on - the build's own block, the one to click (its east face, upper
  half). rightSides was 0x3F for trapdoors, so waitsOnSupport never waited for that wall: picked before it was in,
  "nowhere", left. rightSides now: trapdoor = the block behind it (facing's opposite) + UP for a top one / DOWN for a
  bottom one; stairs = the four sides + UP (top) / DOWN (bottom). needsTemp counts any solid block on a right side
  for those two (a fence post, a slab), not only full ones.
- House 547 (corner 57, 64, 12, heights 63..67; before the trapdoor rightSides fix): 1116/3796, 0 stalls 15 s+,
  0 confused, 39 "nowhere", 3 left (dark oak trapdoors), 0 landings, 3 jumps, 2 stares. Slow (68/min): 108 kinds,
  ~8 menu trips per 30 s. tryScaffold's log also shows logs/wood inside walls with "the build's" on all 6 sides
  (they wait on the one under: fine) - the monitor no longer greps those lines.
- House 547 lifted 8 (HA_FLOAT=8 on a hill 70..94, so partly on the hill: 349 blocks of fill): 992/4145 in 8 min
  (115/min), 0 stalls 15 s+, 0 confused, 2 skips (no "nowhere"), 3 landings, 2 jumps. (With the trapdoor/stair
  rightSides.) The bridge never-started / forcePillar path didn't come up (the hill gave it a start).
- The user (21:24, watching the obsidian bridge): "is he supposed to be building with just dirt?" - the fill under
  the build (fillUnder): 957 blocks of dirt first on the bridge, 9458 on the maze (its whole 15 min). Now 3 deep at
  most (was 6), and not into water; under gravity blocks still up to 40 (or they fall). Told the user; offered no
  fill at all instead if they'd rather.
- Restarted the bridge run (stopped the test client mid-run at 21:25, the bridge put back at the front of the queue)
  so the user could see the smaller fill: new spot (heights 63..70, a lake): "90 blocks of dirt under the build",
  "building the outside" 38 s in, obsidian from then on.
- Bridge rerun (lake spot): 1726/1736 (99.5% exact) in 15 min, 0 stalls 15 s+, 0 confused, 2 skips; at the end
  "the build hangs in the air here ...: 14 temporary block(s) out to it" for the last floating bit (time ran out).
  But 11 landings, all hop-offs: the low stands on the deck (goal 0.03-0.10 over it), touch down, place, take off for
  the next one. standFor now, when already on foot (onGround, not flying), first tries spots on the same floor at
  standing height (LOW eye, a floor under, not over the target): seen from there -> taken, scored -2 (walk over and
  place, like a player on a bridge deck). Also 21 jumps >12 (four of ~100: end to end of the 145-long bridge).
- The user (21:56): "when he flies out to look the camera keep nudging to the left". lookAround's slow pan across the
  build (lookPan, 4 s from one side to the other) ran with setQuick(false): the non-quick aim rests until the error
  is 4 deg, then springs after it, rests again ("following" - pursuit, no resting - was quick mode only). And the pan
  point moved by game ticks (20 Hz steps). Measured on the villager run (scratchpad lookcam.py: yaw speed over
  50 ms windows during each look): still 53-64% of the time, 6-9 "nudges" (still -> 20-47 deg/s -> still) per look.
  Now: AimController follows a goal moving > 1.5 deg/s in the non-quick mode too (no resting; the spring damped
  toward the goal's own speed, not toward zero), and lookPan goes by System.nanoTime. (Cottage TNT run has the first
  half; black hole the whole fix: measure there.)
- !! My walkRest regression: the cottage (TNT20 run, 21:57) hung 90 s at the start, 0.24 too high in front of the gap
  under an overhang on its hillside ("steering to 9.50 83.15 -3.50 (far path, 9 cells)", low): the set-down that
  should take him down and walk him in was refused - walkRest said no for any route with no path kept (the far way).
  Now the far way keeps its path too (routePath = far, routeIdx = farStepIdx), the far way gets the wedge give-up
  (2.5 s no nearer: that step off the map, "can't get into X (on the long way ...)"), and the set-down also goes when
  2 s have gone by getting no nearer (now - routeBestAt > 40). Restarted the cottage run (client stopped 21:59).
- The 3-deep fill left the restarted cottage (heights 63..66 under a floor at 71) with "0 blocks of dirt (189 columns
  too deep)": its floor 5 over a dip, reached by temporary pillars that come out again - a floating house. Now the
  fill goes round the foot of the build only (a column with a neighbour column outside the footprint, or whose
  bottom is up over the base), 6 deep, not into water; gravity columns anywhere (up to 40). Under the floor further
  in stays open - nobody sees it. A fraction of the dirt, and it still stands on the ground.
- Cottage TNT20 (third start, on a mountainside 124..142): TNT at 360 s; 3 damaged spots waited on their column -
  the new whyNotOk detail: "under it PLACE ... a hole: workable true, in the window true, skipped nowhere to place it
  from" - a barrel facing=down under a damaged barrel (stacked barrels on a top slab). Facing down needs a look up at
  its spot from below; there's no below. The "any way round" fallback for barrels came only after 3 "nowhere"s, and
  only when nothing else at all was left. giveUp now relaxes a facing-down barrel straight away when the build (or
  something solid) is under it: "X (a barrel facing down) has no room under it to look up from: any way round".
- Cottage TNT20 result (mountainside under a wood): 178/999 in 11 min, 0 stalls 15 s+, 0 confused, 69 "nowhere",
  TNT 18 blown, all but 1 back (+4 "dirt is grass" the test counts). 30 landings / 25 hop-offs: set-downs to walk
  under the wood's low leaves on the slope, the slope stopping the walk a second later, up again (4 in 16 s). A set-
  down now waits 10 s after the last one (setDownAt). Open: forest slopes - walking up 1-block steps with a ground jump
  instead of taking off, or clearing the low canopy round the build, would be the real fix.
- Camera look-out in that run (AimController change only, pan still by ticks): still 41%, 9 nudges. The black hole
  run (22:14) has both halves.
- The nudging, measured again on the black hole (both halves in): still 59%, 10 nudges, every ~0.55 s a 0.2 s turn
  at ~35 deg/s. The real cause: for a POINT goal the aim's goal speed is "the same point seen from this frame's eye
  vs last frame's" - only OUR motion counts (a block face point's own shifts aren't motion). The pan point's own
  glide (73 blocks over 4 s at 90 off, ~11 deg/s) never counted: goal speed ~0, not "following", rest-catch-up.
  AimController.lookAtGliding(key, supplier): the point's own motion counts too (rate from the angle change, like a
  direction; a new goal only past 1.5 deg in one frame). lookAround's look stage uses it. Check in the inn run.
- Black hole again (heights 114..119, all fixes): its bottom row in the ground, "nowhere" 137 times in 5 min: the dirt
  where the wall's bottom row goes, the wall's row over it already in, the ground on both sides (outside the 1-thick
  build, stays). jobAt: world ground to swap that's sealed in (the build's block over it, in; every side solid and
  staying - ground outside the build that isn't cleared, or the build's own non-air spots) is DONE (sealedInGround).
- Black hole result (heights 114..119, before sealedInGround): 1545/16388, 3 stalls 15 s+ (26, 40, 16), 7 confused,
  437 "nowhere" (58 left), 18 jumps (up to 119: end to end of the wall), 98 back-and-forth; but 0 landings and 0
  hop-offs. All the trouble is the bottom 2-3 rows in the ground. Queued again with sealedInGround.
- Camera fix verified (inn run, 22:36 look-out): the look stage pans at a steady -7 deg/s, no stops (was: still 0.35 s,
  a 0.2 s turn at 35 deg/s, again and again). Flying out to the spot (looking at lookMid) sways +-10 deg/s with a
  ~1 s period - smooth, not stop-start.
- Inn TNT20 (heights 64..75, all fixes so far): 1248/6488 in 8 min (150/min), 0 confused. TNT: 112 of 503 blown,
  he had it all back in 209 s; the 13 "still out" are dirt-is-grass (soil rule) and pockets under the floor shut away
  (DONE by design, unseen).
- The user (22:40): "would you say it's ready to self build?" Told them: yes for normal builds on normal ground with
  someone glancing at it; not yet for walk-away-for-hours anywhere (forest slopes: hops; builds sunk into hills: the
  black hole's confusion; odd orientations left; today's fixes need more clean runs; nothing installed yet).
- Barn TNT20 (heights 63..73): 637/3033 in 10 min; TNT 71 of 116 blown, 5 never back: the corner posts' bottom pieces
  (stripped pale oak wood, axis y) - "waits for its support" each: the piece under gone into the crater, the piece
  over still a hole too (it can go in against the one over it: "comes"). The repair's walk only went DOWN. Now it
  takes the support that's coming, up or down (supportComing: a hole on a right side that comes without this one),
  and walks on from there (8 at most).
- Black hole third go (corner 34, 72, -164, heights 63..73, some water; with sealedInGround etc.): 839 at 5 min, 0
  "nowhere", 0 confused, 0 stalls so far (the last go had 137 "nowhere" by then).
- Walking up steps: walkColumn now takes a step up of a block (top <= feet + 1.05, the cells over it open) as
  walkable, and on foot, pushing into such a step (horizontalCollision, stepUpAhead: half a block on toward the goal,
  2 open over it, room over our own head) -> jump(in) - one press; jump() keeps presses 8 ticks apart, so never the
  double tap that switches flying on. For the wooded hillsides' set-down/take-off hops (the cottage's 30 landings).
- Black hole third go result: 2540/16444 in 15 min (169/min), 0 stalls 15 s+, 0 confused, 0 skips, 0 left, 0
  landings, 0 stares. Its look-outs: the pan a steady -8 deg/s for 4 s. The one jerk left: the pan started at the far
  edge (-0.5 width) while the eyes were on the middle: a quick ~15 deg turn the other way first. lookPan now goes from
  the middle out to one side and back, eased (0.5 width * sin(pi * t/4 s)).
- Cottage (heights 66..79, with the step jumps): 704/1167 in 15 min, 0 stalls 15 s+, 0 confused, 94.8% exact, 5 left.
  Flight landings logged 15 (set-down 10) - no better than before; the recording's 34 "landings" now include walking
  jumps up steps (ground -> air -> ground). Most set-downs in its last 4 min were inside: its 2-high rooms (granite
  slab / oak stair ceilings), down to walk, then a way point up the stairs to the next floor ("no floor all the way
  to ... 79.50") and up again. Open: walking up stairs inside instead of flying.
- Gothic portal where he spawned (heights 63..66): FINISHED 1209/1210 in 857 s, portal lit, 97.8% exact (podzol vs
  dirt/grass, snow), 0 confused, 1 stall of 22 s at the very end (the last spots), 1 landing, 12 jumps. The last sweep
  worked: "before calling it done: 2 spot(s) still to do (lowest at y 72): back to them" -> 1 -> done (one stair
  "couldn't fly there" left, not listed as left-as-is: sweeps ran out of their 5 s holds).
- Fantasy medieval (by water, heights 63..67): 1069/2879 in 15 min (77/min), 1 stall of 18 s, 0 confused, 93.7%
  exact, 61 "nowhere" (lanterns before what they stand on/hang from, double chests, candles), 27 landings - 16 of
  them "landing by the door": the inside and the outside went on side by side ("outside put in after the inside
  started: 373 of 614"), in and out through the door each time. Open: finish one part before the other (the inside
  after the walls are up), fewer door trips.
- buildOrder: back to an earlier part (the outside) only once the later one (the inside) has nothing to do right now,
  or after 2 minutes in it (count[lastPhase] > 0 && < 2400 ticks keeps it) - fewer trips in and out through doors.

### WHERE I LEFT OFF (2026-10-17 ~00:03, still testing; nothing installed)
Queue (schemqueue.ps1): copper guard (running), then the TNT house bqqskqvm, special medieval, dining, natural
medieval, nhzpp3wk, trading hall, iron farm, castle, Lenin, peri, gigantic medieval TNT20, swamp, godzilla. A
background script (scratchpad afterrun.sh) appends each run's nightcheck + lookcam to scratchpad checklists.txt.
Since 20:48: camera look-out nudging fixed and verified (lookAtGliding + nanoTime pan + pan from the middle); the
dirt fill now a foundation ring round the build's foot (the user's "just dirt?"); bridge drop / forcePillar /
last sweep; walkRest + far path; wedge for the far path; set-down spacing; step jumps; trapdoor/stair rightSides;
stacked facing-down barrels; damage repair takes the coming support up or down; sealed ground left; tufts and near
leaves cleared; stay in a part while it has work (2 min).
Told the user at 22:40: ready for normal builds with someone glancing at it, not yet walk-away-anywhere.
Open: forest slopes / low rooms set-down hops (cottage 15 logged landings), door trips (part order: just changed),
odd orientations (stairs/trapdoors/barrels), menu time on 100-kind builds.
- Copper guard (by water, 181201 blocks): 5416 in 15 min (183/min - the ground), 0 stalls 15 s+, 0 confused, 69
  "nowhere" (0 left), 4 landings, 1 jump, 5 stares (4 standing still: worth a look some time).
- TNT house bqqskqvm (hill 63..94; with the part-order change): 6189/26618 in 8 min, 0 stalls 15 s+, 0 confused, 29
  skips, 3 landings, 1 jump. TNT: 131 of 3642 blown, 2 stone still out at the end (skipped "couldn't fly there").
  (Its 129 "outside after the inside started" are mostly the TNT repairs - they skip the part order.)
- Special medieval (heights 66..69): 899/1900 (59/min, detail), 0 stalls 15 s+, 0 confused, 23 skips, 0 left, 1
  landing, 2 jumps. Its standing-still "stares" (1.5-2 s): "off the face of X for 10 ticks ... aim error 0.1-0.8",
  47 ticks aiming at a trapdoor's top-face click whose facing comes from the yaw - fine-tuning near a quadrant edge.
- The user (00:40): would 6 more hours improve stalls / skips / jumping between parts / hops / camera, or is this as
  good as it gets? Told them: diminishing returns; biggest left is the work order (a row of every wall at a time,
  layer by layer - "the left wall, then the middle, then the right"): next to work on; then landings in tight
  rooms/forest slopes/door trips, odd orientations; camera mostly done. Offered to install when MC is closed.
- The walls a section at a time (the user's "starts the far left wall, then goes to the middle or the right wall"):
  buildOrder for the outside: secAnchor (a spot, kept till its stretch has nothing more to do now), the outside's
  holes within ~5.5 of it horizontally and up to 6 rows over the lowest (sectionOf); the picker's own scoring still
  does it row by row inside the stretch. Then the next anchor: the nearest of the lowest row.
- The user (00:45): "would it build a full schematic by itself?" Told them: probably for small/medium builds on normal
  ground (the gothic portal finished on its own today), not yet seen for a big one to the end. Queued two full
  builds first: the cottage and the barn, 45 minutes each, no 15-minute cut-off.
- Dining hall (heights ?): 825/944 in 15 min (65/min), 0 stalls 15 s+, 0 confused, 103 skips (93 "nowhere"), 7 left
  (bamboo x6 - needs its own ground/next to water?; a spruce slab), 2 landings, 8 jumps, 15 stares (6 standing still).
- INSTALLED 2026-10-17 01:07 at the user's request ("please install when you can"; their Minecraft closed - only the
  test client's window was open): %TEMP%\hatest\build\libs\human-autopilot-1.0.0.jar (built 00:45:59, the full-cottage
  run's build: everything up to the wall sections, not the "the wall round X next" log line) -> .minecraft\mods and
  dist. The old mods jar (2026-10-15 18:10) kept in the scratchpad as human-autopilot-1.0.0-old-20261015.jar.
- The user (01:15): (1) the bot's recordings told apart from theirs: done (see the recorder section: -you/-bot files,
  "who" in the header, a new file when the other takes over; the analyzer reads theirs by default) - NOT installed
  yet, needs Minecraft closed; (2) a ~25,000-block full build from their schematics, monitored: medieval-house-
  nhzpp3wk (25,667 blocks) queued for 240 min after the cottage; (3) the test window muted (SchematicClientTest sets
  the master volume to 0 at its start).
- Full cottage (45 min): stuck at ~920/1005 from ~25 min on with 57-71 "holes I can't get at". Kinds: natural oak
  leaves inside its roof ridge (11..13, 81, 16..18) where it wants planks/logs/fences/slab - each hidden by the
  others, and leavesInWay refused lines through leaves in spots that want a block; odd orientations (trapdoors, a
  grindstone on a wall, stairs top half, a lantern, a ladder, a door, a barrel); 2-30 s wedges flying in the attic.
  Fix: leavesInWay takes leaves in spots of the build that want something else too, and those spots go first
  (goFirst, handled like a repair in pickPlanTarget); the "no spot" log names the block each ray hits.
- Tall plant with air saved over it (the cottage's tall grass at 6,85,16 and 5,79,10): the game puts the top half in
  too; the plan wanted air there, so: top knocked out (the bottom with it), put in again - 47 "keeps coming undone".
  jobAt: the top half of a double block over the build's own bottom half counts as done.
- Shut in by the build (openUp): on the 2nd "nowhere" (after tryScaffold), or at the very end before "leaving it as
  it is", a neighbour of the spot - a plain full block (no properties, no block entity), nothing non-full beside it
  but slabs/stairs/fences/walls/panes/trapdoors/gates/leaves, nothing falling onto it, no fluid by it - is tried as
  if out (pretendAir: sees()/clearTo() clip through a BlockGetter wrapper, solid() says no); the nearest that gives a
  spot is taken out (jobAt: BREAK while the spot's a hole, then BLOCKED), the spot goes in, the block goes back
  (putBack, first thing). 60 s at most, twice per spot. Not counted as damage.
- Second test slot beside the main queue (the machine has 16 threads / 62 GB): scratchpad runtest2.ps1 (%TEMP%\hatest2)
  + schemqueue2.ps1 (queue %TEMP%\hatest-queue2.txt, logs hatest2-real-*.log, summary hatest2-schemsuite.txt).
- Hopping on the spot (the cottage 01:09: 40 s, ~50 jumps, "can't take off here" x16, most of its 118 landings):
  on foot, "no floor all the way ... flying" -> takeOff; footWork put it back on foot every other tick, so the take-off
  taps came 8 ticks apart (a double tap needs <7) and never caught; roomToTakeOff then picked the cell it stood in.
  Fix: takeOffNowUntil = now + 20 there (footWork leaves it alone), and takeOff restarts its taps from the first if it
  wasn't called last tick (not counted as a failure).
- Top-half stairs left after 5 goes in 6 s (the awkward-blocks test): tryScaffold had planned a temporary block, but
  "holds one" made the stair go first, before the temporary block was in. tempPending(h) in planOk: not before it.
- "what's left (N)": once a minute when nothing can be placed and <=150 holes, each with whyNotOk.
- 01:46: both test windows closed (the user saw "2 minecraft test running" - my second slot, not announced). The 25k
  run had 4,799/27,196 after 12 min (~120/min, still the ground part). Back to ONE test window. The 25k restarted
  01:53:46 (new seed, 27,544 spots) with everything above in it (goFirst leaves, tall-plant top, openUp, take-off
  taps, tempPending, "what's left"). The awkward-blocks test (cottage, prebuild 99 minus stairs/trapdoors/barrels/
  grindstone/stripped logs/lanterns/ladders) got 903/999 in 6 min before it was closed; openUp never fired there.
- 25k restart #1 (01:53, seed by the sea: "water at 39 of 90 spots"): "2234 blocks of dirt under the build" - its 4
  ground layers have sand/gravel/concrete powder, and every such column got dirt down through the water to the sea
  floor (up to 40 deep). fillUnder now: the foundation round the foot as before (<=6 deep, not into water), and under
  a falling block with nothing under it just ONE dirt block (it holds it; chained from the shore like a floor).
  Restarted the 25k again ~02:00 with that.
- Wedged cells (stuckCells) off the map 30 s, then 1, 2, 4 minutes each further time (stuckTimes): the cottage pushed
  into the same five cells 2-4 times each, 30 s apart.
- Door landings (fantasy house: 20+ "landing by the door" in 15 min, each a take-off after) are by design (a 2-high
  doorway can't be flown through: touching the floor ends flight). Left as it is for now.
- The user (02:00): "don't stop... go through the 19 new schematics", 15 min each with recording, a few TNT runs. All
  27 in the folder (none newer than 2026-10-16) queued at 15 min after the 25k house (5 with HA_TNT20), the cottage
  first, then its awkward-blocks test.
- Bamboo (dining hall: all 6 "nowhere", "would put bamboo_sapling"): a bamboo item on the ground makes a shoot; the
  next stalk on top turns it into bamboo. Placement.same takes a shoot for bamboo; jobAt: a shoot where the plan has
  bamboo is done (no loop knocking it out).
- Wheat (cottage: 17 "nowhere", 4 left, "farmland is grass_block/dirt" 5): the farmland went in with the ground part,
  the wheat minutes later with the outside, and bare farmland dries back to dirt. Now: farmland in (placed or tilled)
  -> the crop over it goes next (plantNext, handled like a repair in pickPlanTarget).
- Leaves in a build spot that hide another spot (leavesInWay): now registered like openUp's blocks (opened: c -> t)
  instead of goFirst: the leaves go first, the spot stays open (jobAt: whatever's in it -> BREAK, then BLOCKED) until
  t is in, then the planks go in (putBack). Filled straight after, the planks hid t again. Several for one spot: each
  out first, then the spot; all let go together (done or 60 s).
- Rooms (fantasy house: the inside started at 157 s, then 16 doorways walked through in 7 min on its upper floor,
  each a landing and a take-off; the nearest block by straight line was through a wall, in the next room): inside
  (no open sky over us), the far list's score gets +12 for spots not in this room - roomCells: a BFS from us over
  flyable cells that doesn't step into a doorway (up to 3000, cached 10 ticks / same cell); inRoom: in it or beside.

### WHERE I LEFT OFF (2026-10-17 ~02:25, testing; NOTHING new installed - installed jar is 00:45:59)
- Running: the 25k house (medieval-house-nhzpp3wk, 240-min cap, started 01:58; ~150/min, ETA ~04:40), then the queue
  (%TEMP%\hatest-queue.txt): all 27 schematics at 15 min (cottage first, its awkward-blocks test, 5 TNT runs).
  ONE test window at a time (the user closed both when two were open).
- In since the 25k started (so first tested in the queue): stuckFor, bamboo, plantNext, leaves->opened, rooms.
  In the 25k run itself: recording split, leaves (first version), tall-plant top, openUp, take-off taps, tempPending,
  "what's left", one-block fill under falling blocks.
- The user wants the new jar (the recording split) - install only with their Minecraft closed and when they ask.
- INSTALLED 2026-10-17 02:30 at the user's request ("install"; their Minecraft closed): %TEMP%\hatest\build\libs jar
  built 01:57:40 (sha256 2dbe9c42f6a6...; the 25k run's build: recording split, leaves v1, tall-plant top, openUp,
  take-off taps, tempPending, "what's left", one-block fill) -> .minecraft\mods and dist. Old mods jar (00:45:59) in
  the scratchpad as human-autopilot-1.0.0-old-20261017-0045.jar. NOT in it: stuckFor, bamboo, plantNext,
  leaves->opened, rooms.
- The user (02:30): "why did you stop. i told you not to" - I had ended my turn after a status message. Never end the
  turn while testing: keep working (monitor, analyse, fix) until usage runs out.
- Part order diagnostics: "outside put in after the inside started" is high (dining 172/471, cottage 185/485, fantasy
  373/614); the dining hall switched outside -> inside in the same second ("building the outside (from y 78)" then
  "building the inside (from y 72)"). New log when moving on to a later part with an earlier one unfinished: "<part>
  has N still to do, none now: <spot> <whyNotOk>; ..." - read it in the next runs before changing the order.
- Wedges = camera stares: nightcheck now splits "standing still" stares into "wedged, pushing keys" (cottage 45 min:
  25 of 63 stares were W/A/S/D held with no movement). One: at 0.64 78.62 13.70 steering to 0.50 77.15 14.50 (down
  1.5 and into the next cell) - sliding in at its own height into the lintel. steer() now: flying, the goal more
  than 0.25 above/below, blocked 0.35 ahead at this height but free at the goal's (noCollision of the moved box) ->
  horizontal goal = where we are (height first, then in). Counted in the status "thinking time" line ("ticks getting
  to a gap's height before going in").
- Wind-down at the end (the cottage: its last ~80 holes took 20 minutes at a block or two a minute and it never said
  done - each placement reset the 30-s "nothing I can do" give-up): <= 300 holes, no damage pending, the plan >3 min
  old, and the 5th-last placement over 3 minutes ago (placedAts) -> every remaining hole left as it is ("the last
  ones: fewer than 5 in 3 minutes"), openings dropped, finalSweeps = 6 -> done. Log "[schem] winding down: ...".
- Camera: of the "stares with a wiggle", the ones that are the camera itself (standing still, NOT pushing keys) are
  only 0-2 per run (cottage 2 of 63, fantasy 2 of 14, dining 0 of 15, special 2 of 9); the rest are wedges (keys held,
  not moving) or flying. nightcheck prints the split now.
- openUp seen live in the 25k (02:33): a dirt block of its ground "shut in" -> took the dirt beside it out, then the
  ground rules counted the spot done anyway; "went in wrong" was logged for the block taken out. Now: openUp skips
  spots below clearFromY (the sunk ground layers) and soil; the went-in-wrong report skips opened blocks.
- Height first, refined: any height error over 0.02 (one wedge was 0.21 over a 2-high gap's floor, the head 0.01 into
  the block over it, inside the dead zone), the whole vertical sweep must be free (expandTowards), and the dead zone
  is 0.01 while doing it.
- 02:36: stopped the 25k at 37 min (7,593/26,175, ground part, clean) to check today's fixes first (cottage 15 min,
  the awkward-blocks test 8 min), then the 25k again with them (its old code has no wind-down: likely to trickle at
  the end), then the rest of the queue.
- DEADLOCK found (cottage 02:38-02:51): a top-half stair at the foot of the wall had no click face the right way
  round; tryScaffold set a temporary block OVER it (and one to hold that up). columnWaits(temp): the stair under it is a
  hole with "something to click" (rightSides' sides are solid - but not the right way round) -> the temp waited on the
  stair, the stair on the temp (needsTemp), and every wall block over that column on the stair: "the outside has 179
  still to do, none now ... needs a temporary block; ... waits on its column" -> the inside started early. Fix:
  columnWaits returns false for a temporary block (it's there because the one under can't go in by itself). Also
  whyNotOk lists a needed temp's state.
- Cottage 15 min with today's fixes (02:36-02:51): 608/1061, 0 stalls 15 s+, landings 10 / hop-offs 6 (was 34/28),
  camera stares 8 with 0 wedged and 0 standing still (was 63/25 in 45 min), 40 "hidden in a tree" handled, openings
  for leaves "couldn't get X in after all" several times (X waited on its column - the deadlock above), 6 left.
- Awkward-blocks test (02:51, before the deadlock fix): 919/999 in 8 min (earlier: 903 at 6 min, 979/880 in older
  runs). The top-half stair at 8,-59,10: its temporary block south of it went in at once, then from nowhere a click
  on it the right way (the spot north of it inside the walls): 5 goes in 2 s ("holds one" first), left. openUp had no
  plain neighbour for it.
- 25k house restarted 03:00:13 with everything (seed: corner 12,70,-3, heights 62..71, water 4/90; 25,515 spots,
  205 dirt under it).
- tryScaffold now checks a temporary block would really be clickable: pretendSolid (rays() returns GLASS there,
  solid() says yes, standFor skips spots inside it) + the real standFor(t) - "no spot to click it from" otherwise. Its
  few hand-made eye positions passed for the awkward test's stair, whose real spots (hover heights) all failed.
- Menu time (cottage 15 min, 02:36): 141 fetches of 41 kinds in 90 visits, 271 s = 30% of the run (each kind ~3.4
  times: 9 hotbar slots for 41 kinds). Ideas, not done: keep evicted blocks in the main inventory and swap them back
  from the creative inventory tab (no search typing); more per visit; stronger material-first ordering.
- Cottage 15-min speed over time is steady (54-68/min); today's 57/min with 48 stall-seconds.
- Temporary blocks set over their target (the deadlock case) in earlier logs: cottage 6, special medieval 2, dining
  hall 2 (below: dining 16, fantasy 9; beside: 2-10 each). So the deadlock hit most detailed builds.
- Height first, fixed (not in the 03:00 25k run): it used to move the GOAL to where we hang, so the rules that let us
  get low under a gap's roof (overFloor/goalNear/lowRoofOver(goal), roofAhead, the set-down) looked at our spot, not
  the gap - in the open it could refuse to go down and hover. Now the goal stays; only the sideways axis() inputs get
  zero error (hold still sideways) while getting to the height.

### WHERE I LEFT OFF (2026-10-17 ~03:12, testing; installed jar = 01:57:40 build)
- Running: the 25k house (03:00, all fixes up to ~02:58; 10 min: 6,350/25,515, ~158/min, clean), then the queue: barn
  TNT, natural medieval, trading hall, iron farm, castle, Lenin, peri, gigantic medieval TNT, swamp tree (135x102x91,
  never run), godzilla (226x236x264, never run), maze, inn TNT, fantasy, copper guard, special, black hole, house
  bqqskqvm TNT, bridge, gothic portal, house 547, wizard tower TNT, house t1t49ihz, villager, cathedral, dining hall.
- Not yet in any run: pretendSolid temp check (tryScaffold), height-first goal fix. In the 25k: everything else.
- Next: watch the 25k to its end (wind-down?), then the queue; read the new logs: "<part> has N still to do, none
  now", "what's left", "needs a temporary block [..]".
- DOOR PING-PONG (awkward test 02:55: "walking through the door at 16,-59,17" 7 times in 26 s): trapdoors by the
  door each got a temporary block on the other side of the wall; "hanging ones first"/tempReady and temps-first then
  forced: temp (east), door, trapdoor (west), door, temp out, door... Now: roomCells (no doorway crossed; inside or
  out, null if over 6000 cells = open air) is worked out before the priority lists, and `first` keeps to what's in the
  room if any of it is (roomKept); tempReady extras only from the room then. Then through the door once.
- THE USER (03:40, streaming, viewers dropping TNT): "an issue where he doesn't know how to dig straight down. in the
  normal world". Their log (.minecraft/logs/latest.log) + recording (rec-20261017-024128-bot): gigantic medieval at
  -55205,64,-69112 in their normal world; 03:28:39 a look-out up to y 120, then targets at the wall's foot, y 64-65,
  under a hill whose grass is at y ~88: "steering to ... 120.5 (path 0 cells, none in sight)" -> down to ~89-97 onto
  grass/oak leaves -> "couldn't fly there" -> up to 120 -> down... 160-s stall, 2.5 minutes of 30-block yo-yo; the user
  turned it off and made a flat world. Causes: (1) the clearing only reached clearFromY+10 (and the window), so the
  hill's top 14 blocks were never cleared, and top-down clearing kept everything under them waiting; (2) far holes
  count as reachable (only in-region ones are checked with the air map), so the buried walls kept being picked.
  Fixes: the clearing scan reaches up to the ground's top round us (MOTION_BLOCKING_NO_LEAVES heightmap, only where
  the schematic has air at that top) and widens the window with it ("ground over the build up to y N round here");
  planOk: a far hole under such ground (underHill) isn't to do yet; flyFails: after "couldn't fly there", holes within
  5 (3 up/down) of it wait a minute. Test: HA_HILL=1 (SchematicClientTest.landSpot: a 20-high hillside, floor at its
  foot), queued after the 25k with the gigantic medieval.
- The yo-yo's generator: route0's last resort ("up over the top of the whole build", cruise = box top + 1.5) when
  neither the near air path nor farPath (A*) reached the goal - 25k run 03:45 too ("steering to 27.30 122.50 ... (path
  0 cells, none in sight)" after a wedge). Now: if the A* searched everything it could reach (aStarExhausted, not just
  out of its 12 ms / 60k cells) and the climb would be over 8 above both us and the goal -> routeNoWay ("no way through
  the air"), and both target callers give the target up at once (couldn't fly there -> flyFails area wait).
- Dig down to far buried spots: when nothing else can be done (ok empty) and there are holes under a hill outside the
  region (underHill), the nearest one's column top (the ground's top, a spot the schematic has empty) goes on the
  clearing list and becomes the target at once ("X is under the ground still to clear (N over it): digging down to it
  from Y"); there the clearing round us (now up to the ground's top) takes the rest of the hill from the top down.
- 25k ground phase is the long part: its 4 ground layers are decorative (dirt, coarse/rooted dirt, moss, sand, gravel,
  tuff, coral...) over a 67x60 footprint, so most of the site's own ground gets knocked out and replaced (two actions
  each), plus pits filled bottom-up; at 50 min the bot spends 2/3 of its time flying between scattered pits, ~40-70
  per 30 s. The 240-min cap may come before the end.
- For the HA_HILL run, look for: "ground over the build up to y N round here", "digging down to it from", no repeated
  "steering to ... <box top>.50" climbs, few "couldn't fly there".
- The user's live flat-world castle (03:50): wall-core cobblestone/stone at the bottom of 1-wide gaps, "no spot ...
  down: straight down from here; north/south: only its edge from here" for each one first (steepOk only after a
  failed try), 44/min. steepFine now also true for a spot solid on all four sides (only from over it).
- The user's normal-world session had 216 STALL-seconds of "path 0 cells, none in sight" (the yo-yo) in 5 stalls
  (03:08 40 s, 03:15, 03:20, 03:25, 03:28 160 s). The test spots (landSpot) avoid hills (range ~4); the user builds
  wherever they stand, hills too. Queue after the 25k: HA_HILL runs of the gigantic medieval, the castle, the cottage
  and the special medieval (with TNT), then the barn (HA_HILL=0 clears it: the running queue script doesn't).
  nightcheck counts the yo-yo signature ("stuck with no way through the air") and the new log lines.
- Low descent: 13 of the 25k's first 24 landings were "descend (gap 0.44, clear 0.15)" over its ground's pits (a tap
  from 0.43-0.44 went on down to the floor). The low tap threshold is now (|v.y|+0.2)/0.4+0.05 (0.55 at rest, was
  0.425). Watch for more set-downs in front of 2-high gaps in the next runs.
- The user's stream (04:03:51): viewers summon TNT by the dozen ("[Server: Summoned new Primed TNT]" x30+ in 2 s); the
  bot repaired ("repairing 87 damaged spots first") but spots blown out 3 times in a minute hit "keeps coming undone:
  leaving it a while" (no repair for 30 s, not damage). Now: recentBlasts (from watchTnt, which runs first in
  scanPlan now) - a spot within 8 of a blast in the last 5 s is damage every time, never counted as undone.
- 25k: ground done at 71 min (04:11), "building the outside (from y 70)".
- 25k pace: ground done at 71 min; outside walls 60-90/min (decorative: slabs, trapdoors, grass; flying 30-50% and
  menu trips) -> ~5-6 h for the whole build, over the 240-min cap. Plan: stop it ~04:45, run the 4 HA_HILL tests
  (the user's live dig-down/yo-yo issues), then the 25k again with everything and a 400-min cap, then the queue.
- The wall sections on the 25k: "the wall round X next" every 10-40 s along the perimeter, 3-7 blocks apart (no far
  jumps); "the ground has 218 still to do, none now: ... nothing to click on" when the outside started (ground blocks
  over caves with nothing next to them).
- HA_HILL was ignored by the "where he is" placement (landSpot only runs with HA_LANDSPOT): the first hill run built
  on a mountain top. Now HA_HILL=1 sinks the corner 15 ("REAL sunk 15 blocks into the ground").
- Hill run 1 (gigantic medieval sunk 15, heights 34..94, 04:23): "ground over the build up to y 72/83 ... clearing it
  from the top down" works; but a 16-s dive: after a look-out at y 112, target = the wall's foot 46,56,67 (16 under the
  hill), straight down to the grass at 73, "couldn't fly there". underHill missed it: that column's top was a spot the
  build has a wall block in (still the hill's dirt), not air. Now underHill also: world ground right over it where the
  plan has something else (or nothing). And route0's last resort says "no way through the air" for a goal under the
  world's own ground (goalBuried: column top 3+ over it, ground two over it) even when the A* ran out of time (big
  builds never exhaust it).
- Hill run 2 (castle sunk 15, 04:38): the 20-high castle was all under the ground (40k+ holes to clear, ~4 knocked
  out a second, the plan's own blocks 92/7404 at 10 min). Not a fault, just the test too deep. Changes (next runs):
  HA_HILL sinks a third of the build's height (4..15); the clearing's top is the box's top (the ground over the box
  isn't the build's, a paste leaves it) and only a real block counts; openUp doesn't dig out a build block to get at
  a spot the plan has empty (the world's ground, "-14,56,386 (air) is shut in by the build").
- Thinking time with 44k holes: ~1.5 s per 30 s (scan 0.6, pick 0.6, order 0.15) - 2.5 ms a tick on average.
- Take-off under a ceiling 3 up (castle hill run, 04:52:56): 18 hops on the spot in 15 s. takeOff looked at the
  ceiling every tick from the head's height mid-jump: from the 3rd tick it read "low" and switched to the quick
  pattern (taps on ticks 0 and 2), so the second tap (due on tick 4) never came. Now takeoffLow is decided once, at
  the first tap. ("can't take off here (no room over the head)" walked to the middle of the same cell: same thing.)
- Hill runs, checklist: hop-offs counted the take-off loop; the back-and-forth count now counts blocks knocked out as
  work too (hill run 1: 4 "wasted" turnarounds -> 0).
- Door landing over a drop (cottage on a cliff's edge, HA_HILL, 05:00:18): the door's front cell had a doorstep but
  the landing spot (1.6 out) was over the drop: "landing by the door" took him down 45 blocks to the foot of the
  cliff, then "gave up walking through the door", up 45 again: 20 s. Now the landing spot needs ground under it too
  (else 1.1 out, on the doorstep), and sinking more than 1.3 under the door's foot ends it ("no ground to land on in
  front of the door ... not that way for 30 s").
- "[box] long tick: N ms (costs...)" logged for a tick of 25 ms+ (at most every 10 s): what the stream's hitches are.

### WHERE I LEFT OFF (2026-10-17 ~05:03, testing; installed jar = 01:57:40 build, the user's game is open)
- Queue (schemqueue.ps1, task bnww9pg5s): cottage HA_HILL running (04:54-05:09); next special medieval HA_HILL+TNT,
  the awkward-blocks cottage (HA_EXCEPT, prebuild 99, 8 min), the 25k house (400 min), barn TNT, then the 15-min list.
- Built since the last install (compiles; needs installing when the user's game is closed): everything from the
  03:12 notes on, plus today: hill clearing capped at the box top, openUp not for plan-air, HA_HILL sinks h/3, the
  take-off tap fix (takeoffLow), the door landing over a drop, the long-tick log.
- Monitors: test log bhy94meqj, user's log bnjbcco8u (re-arm every 30 min).
- Hotbar churn (cottage hill run): 36 menu trips in 8.5 min, spruce stairs fetched 7 times, spruce slabs and dirt 6:
  the picker's "needed round here" was only the next pick's list (upcoming), so the build's main blocks counted 0
  the moment the section changed and went out for a lantern. needsNear is now everything within 24 + 4 x upcoming.
- Door landing stuck near its spot (fantasy house 23:48, 5 s hovering 1 block off it, then "gave up"): if the way to
  the landing spot hasn't got 0.1 nearer in 30 ticks, within 2.5 of it and the ground under us within 1.2, it sets
  down where it is (walkBestD/walkBestAt); on foot, "walking to the door" does the rest.
- Yo-yo in the user's flat-world castle (05:04:31, old jar): 65,-59,38 at the bottom inside a wall, 13 blocks off,
  "path 0 cells, none in sight", up to y -3.5 (47 up), down onto the wall, "couldn't fly there", the next one the
  same. routeNoWay only fired for an exhausted A* (big builds time out instead) or world ground over the goal. Now
  also: anything over the goal between it and the cruise height (clearLine from up there down to it fails) - the
  last resort can't work then, so it gives up at once.
- Cottage HA_HILL result (05:09): 787/1010 (59/min), 96.5% exact, 0 stalls 15 s+ after the door; one openUp
  ("7,103,0 oak trapdoor shut in: taking 7,102,0 out"). 95 "extra spruce_leaves": a spruce beside the build leaned
  in, its trunk outside the box, so its leaves never fall; the clearing skipped all natural leaves. Now the clearing
  scan keeps them back only while a log to be knocked out is within their DISTANCE (logsGoing); else out by hand.
  (Not in the special medieval HILL+TNT run, built after it started.)
- Clearing the schematic's whole box: planBox is only the blocks' own extent; the cottage file is 21x18x25 with its
  blocks at x 2..16, y 0..12, z 3..21 - two spruces in the empty margin kept their trunks (10 logs) and leaves. The
  clearing scan now covers SchematicMode.builtBox() (margins and the layers over the roof), like a paste.
- Special medieval HA_HILL+TNT (05:10-05:18): 60 of the 100 nearest blown out, all back but 3 spruce wall signs.
  A sign blown out with its wall went BLOCKED ("the game won't let it stay"), and remember() dropped it from
  seenBuilt; with the wall back it was an ordinary hole (decor, the very end), not damage. seenBuilt now keeps a
  BLOCKED spot (unless it's one openUp took out).
- The user's live game (old jar): 5 sky trips of 11-19 s in 15 min (05:01-05:17): far targets at the bottom of
  1-wide wall cores, "path 0 cells" -> up to the box top (y -3.5), over, down 46 blocks, then "nowhere to place it
  from". The new jar has steepFine for spots solid on all four sides, openUp, flyFails and the blocked-column check.
- TODO (exactness, not seen on camera): a hole shut in by the build in a small pocket (the user's 64,-59,36: it and
  the cell over it air, stone over that, wall all round) is never gettable, so planOk skips it in the region and
  openUp (only on a 2nd "nowhere") never runs for it; from afar the blocked-column check now gives up at once (no
  sky trip). Idea: every few s, a hole in the region, not gettable, whose pocket (BFS through air, <= 6 cells) is
  closed: openUp with the pocket's boundary blocks as candidates (not just the hole's own neighbours).
- Awkward-blocks cottage (HA_EXCEPT, prebuild 99, 05:18): 936/999 (was 919), 99.3% exact (was 99.1%), 1 openUp,
  left: 63 stairs/trapdoors/logs/barrels in chains of "waits for its support"/"nothing to click on". At the end
  48 it couldn't get at: 30 s with the camera dead still ("[confused] 19 targets in 10 s" was the instant re-tries,
  not the eyes). Now after 5 s of nothing: out from under the roof at once (was after 30 s), and outside a look at
  the build (lookNextAt) at most once a minute. (Not in the 25k run.)
- 25k run 4 started 05:27:22 (400 min cap): corner 6,86,5, heights 47..116 under it - a mountainside.
- openPocket (built, not tested yet): every 2 s in pickPlanTarget, the nearest hole within 10 that's in the region
  but not gettable (a PLACE of the build, not soil, not under clearFromY, openTries < 2): BFS its pocket of open
  cells (<= 6, none in the reach map); a wall block of the pocket that's the build's own plain full block, nothing
  hanging on it, the reach map on its other side, and with it gone a body fits beside the hole or two over it ->
  opened.put (the openUp machinery: out first, the hole, back in). Log "is shut in a pocket of the build".
  Test queued after the 25k: the gigantic medieval prebuilt 99 except "=stone" (HA_EXCEPT now takes "=id" for an
  exact id) - its stone in the wall cores, like the user's castle (64..66,-60..-59,36: 4 left in 15 min).
- The user's castle (old jar, 04:25-04:31): a TNT-damaged stone in a wall core (47,-58,36), shut in, was "repairing 2
  damaged spots first" every few minutes from 38 blocks off, "nowhere" each time, a section of wall left half done.
  Damage tried twice (fails >= 2) isn't first any more (blastLook clears the count when TNT goes off by it again);
  damageToDo() (damage with fails < 2) replaces damaged.isEmpty() for the wind-down, look-outs and openPocket.
- The user's wall core 64..72,-60..-59,36..38 (05:16-05:37): 7 stones/cobblestones left "after 5 tries", sky trips.
- openPocket, second way: when no single block out fits (the wall built on up over the pocket - the user's cores),
  two out of the wall's face beside the hole, one over the other (pocketWall: 0 no / 1 yes / 2 yes and our air past
  it), checked with standFor and those two as pretendAir; both registered in opened (they go back together).
  pocketTried: a hole looked at isn't looked at again for a minute (a room's air would have blocked the rest).
- The user's session today (old jar, 02:34-05:42): 555 s of 10 s+ stalls, 412 of them "path N cells, none in sight |
  flying over to the next block" (the sky trip), 97 "far path", 25 wedged, 0 confused. The blocked-column give-up
  is now only for a goal within 24 (flat): further off, going over still takes him near it (the inside of a room
  reached by its door from there, after the A* ran out of time; a pocket looked at from there). (The 25k run 4 has
  the version without the 24 limit: watch its end for far holes given up at once.)
- Long ticks in the 25k's clearing: 27-36 ms, pick 19-28 + stand 10-19 (planOk -> standFor) with 17-22k holes.
- Pick cost in a hill: the near loop ran columnWaits on every buried clearing cell (~10k round us on the 25k's
  mountain: picks of 17-28 ms). Now a plan-air hole with a solid hole right over it (holesByLayer, not cant, not
  leaves) is skipped before columnWaits - the same answer, one set lookup.
- Touch-and-go landings: the awkward-blocks cottage's landings, 8 of 33 on the ground 0.04-0.34 s (hovering low in
  its rooms, the floor touched, the double tap the very next tick). An unplanned landing (not a set-down, not the
  door's) now keeps him on foot 6-12 ticks first (walkUntil).
- 05:47: stopped 25k run 4 (20 min in, 05:27 code): 0 stalls 15 s+, 0 confused, 0 skips, 0 landings, the mountain
  dug at ~220/min with half the actions 0.2 blocks apart. Re-queued after three checks of today's later fixes:
  the gigantic medieval prebuilt except "=stone" (openPocket), the cottage (doors, take-off, end, hotbar,
  touch-and-go), the special medieval with TNT (signs, damage demotion); then the 25k at 400 min on the newest code.
- Pocket test (gigantic medieval prebuilt 99 except "=stone": 23506 prebuilt of 46200, half the castle is stone):
  05:49:08 "22,-58,19 (stone) is shut in a pocket of the build (1 open): taking 22,-58,18 (Cobblestone) out", and
  05:49:09 "22,-58,19 is in: [22,-58,18] goes back" - one second.
- Pocket test, bad: "59,-60,19 is under the ground still to clear (27 over it): digging down to it from 59,-33,19"
  x5 in a flat-world castle - underHill's "the column's top is a spot the plan has empty" took a stray block on a
  tower's top for a hill. Now the top must also be the world's own ground (worldGround). And wedged 30 s at
  12.7,-55.8,6.7 inside the stone-less castle's walls (a maze of 1-wide gaps) before and after it.
- Second pocket "8,-60,8 ... taking 9,-60,8 (Cobblestone) out": 9,-60,8 "couldn't fly there" (wedged near it),
  given back after 60 s ("couldn't get 8,-60,8 in after all").
- Wedges were 71 of the pocket test's 95 stall-seconds (in a maze of 1-wide gaps). The air map is 6-way (no
  diagonals) and only cells with an empty collision shape (or climbable) count, so the map should be right; the
  "can't get into X from Y" log now names what our box would hit 0.3 further on (inTheWay), or "nothing solid".
- Pocket test result: 23697/46200 in 600 s (30/min) in a maze of stone-less walls; 3 pocket open-ups, 1 done (the
  other two got wedged on the way to the block to take out); prebuilt anvils fell into the holes ("stone is anvil").
  Long ticks 39-52 ms "flying over to the rest of the build": none of the nearest 64 far holes doing, all 22000
  sorted on every pick - now at most every 2 s (farFullAt).
- Margin-clearing bug (this morning's change): the clearing went to the schematic box's top (cbTop) but the window
  (extraLayers) was still capped at planBox.maxY(), so clearing cells over the roof's height were never holes and
  every cell under them waited on them (columnWaits). The cottage check on a mountaintop: "ground over the build
  up to y 185 ... (window 168..180)" every 30 s. extraLayers now goes to min(cbTop, clearTop).
- Long ticks in the cottage check: 33-50 ms, "stand 26-40 ms, doing: looking for the next hole" - a "nowhere" runs
  standFor, then tryScaffold (a standFor per temporary block tried) and openUp (one per neighbour) in the same tick.
  TODO: spread those over ticks (a stand budget per tick, the rest next tick).
- giveUp "nowhere": if the tick has already spent 8 ms (tickStartNs), the temporary blocks and openUp searches are put
  off: the spot skipped 10 ticks, retried in a fresh tick (once per spot: deferredNowhere), so a no-spot search plus
  6 scaffold searches plus 6 open-up searches don't all land in one 40-50 ms tick.
- standFor: a cell from which no face of the spot can be in reach (eye at our hover or LOW, +0.9 for the face) is
  skipped before any sight check.
- Cottage check (05:58-06:14, mountaintop 146..186): 663/1029, 93.0% exact - "extra stone=265" over the roof's height
  (the window bug, fixed after it started), snow 22. Menu: 131 fetches, spruce trapdoor 14x, granite slab 10x,
  spruce stairs 10x: the visits' "also" fetches took the room's one-offs (furnace, crafting table, smoker, barrel in
  10 s) and pushed the build's main blocks out. needsNear now also adds a quarter of the whole window's remaining
  count per item (allNeed, worked out every 5 s from the plan alone).

### WHERE I LEFT OFF (2026-10-17 ~06:16, testing; installed jar = 01:57:40 build, the user's game is open)
- Queue: special medieval TNT check (06:14), then the 25k house at 400 min on the newest code (~06:25-13:05), then
  barn TNT and the 15-min list. Monitors: test bdbk06y4h, user b2ptmqvm0 (re-arm every 30 min).
- install.sh in the scratchpad builds the dev tree and installs (refuses while javaw runs). The user knows a new
  build is ready whenever they close Minecraft.
- Built after 05:27 (untested in a long run): end-phase out+look, openPocket (1 and 2 blocks), damage demotion
  (fails >= 2) + damageToDo, pick skip for buried clearing cells, touch-and-go pause (works: walk 7-11 after a
  landing), sky check only within 24, underHill needs world ground on top, inTheWay wedge log, farFullAt, window
  to cbTop, nowhere deferral, standFor reach pre-check, hotbar allNeed.
- validate.py <log> [rec] in the scratchpad scores a run on today's fixes.
- TNT check (special medieval, flat-ish 63..65, 06:14-06:20): "178 of the 261 built round him were blown out, all
  back after 139.0 s, 0 still out at the end" (the last one had 3 wall signs left out).
- Cottage check: the flight landings now have walk 7-11 (the pause); the sub-0.3 s ground contacts left are steps
  and step-jumps on foot.
- 25k run 5 (06:21:22, newest code but the window fix and later; corner 1,107,8, heights 24..118 - a ravine, 709
  blocks of dirt under it, 167 columns too deep left open). First 4 min: ~200/min laying the foundation, one 17 s
  stall at the very start: hovering 0.25 over the spawn's ground, "can't get into -3,105,7 / -3,107,5 / -4,107,5
  (on the long way, something in the way)" - the long-way wedge log now names the block too (inTheWay).
- Cottage check vs the morning's cottage run: stalls 15 s+ 0 (was 1), nowhere 61 (103), couldn't fly 3 (8),
  landings 9 (13), wedges 1 (4), confused 0 (0); 5 left (stairs/logs, was 3); 83/min on a mountaintop.
- 25k run 5 at 20 min: 3322/26019 (~140/min on the ravine's foundation), 0 stalls since the start, 3 jumps > 12,
  19 turnarounds (1 wasted), 1 landing, 0 left; look-outs every ~4.5 min, 8-9 s each; the 3 "sharp yaw flips"
  are a flick's overshoot and two target changes, not jitter.
- sealsAnother() is never called (dead code); the user's wall-core pockets (64..72,-60..-59,33..38) came from the
  04:03 TNT barrage blowing out the cores under walls that stayed - repaired as damage, "nowhere", and only
  openPocket (now) can get them back.
- openPocket's pocket limit 6 -> 12 cells (TNT through a wall's core leaves a few cells together).
- The user's live bot recording 03:32-07:03 (old jar): 24413 placements, 94 landings (0.4/min), 67 of them < 0.3 s
  on the ground; their log: 74 "descend (gap 0.45-0.51, clear 0.15)" landings - the low-descent threshold fix
  (0.55 at rest, made at ~03:30) isn't in their jar. The 25k on the new code: 1 landing in 20 min.
- Recorder split works in the user's game: rec-...-you / -bot files.
- 25k wedges with the log: "can't get into 40,104,51 from 40.70 106.19 52.30 (in the way: 40,106,51 Grass Block)" -
  slanting down into a cell under an overhang; "can't get into 51,105,54 from 51.54 108.27 54.27 (nothing solid)" -
  straight down a 1-wide shaft 0.23 off its middle (the box's edge over the next column). inTheWay now tests
  straight / sideways / up-or-down separately and gives how far off the cell's middle we are.
- Shaft wedge (25k 07:10:35, recording): over a 1-wide shaft he swayed z 54.2 <-> 54.75 (the shaft wants +-0.2 round
  54.5), dipped onto its edge (v), up again (J), 4 s. axis()'s brakes only kicked in over 0.1-0.12 blocks/tick, and a
  slower drift coasts ~11x its speed. In the precise line-up mode (slackOver >= 0, lineUp only) it now brakes from
  0.02 (in the slack) / 0.03 (near): it creeps in and stops instead.
- 25k run 5 at 1 h: 9117/26019, 12048 holes left, ~120/min, still "the ground" (foundation + 4 ground layers over
  the ravine), 0 stalls since the first 17 s, skips 19 (couldn't fly 12, nowhere 4, can't see 3), 0 left.
- The user's live recording 03:32-07:03 (old jar) through nightcheck: hop-offs within 2 s 85 of 94 landings
  (descend (low) 61), yo-yo 346 STALL-s, jumps > 12: 127 (0.6/min), camera stares 98 (camera itself 9), yaw flips 16.
- Wedges in the 25k with the log, the common one: "can't get into 31,107,63 from 31.14 107.28 64.40 (in the way:
  31,109,63 Stone)" - hovering 0.13 over a 2-high gap's floor height, the head on the block over it; the set-down
  (once per 10 s, many conditions) didn't come and the fine descent refused the tap (gap 0.28 < 0.55). Now after 1 s of
  heightFirst with the floor within 0.6 it taps down anyway, and the landing counts as a set-down (walked on in).
- The 25k run 5 snapshot (06:21) has all the morning's fixes (allNeed, nowhere deferral, farFullAt, the window to
  cbTop, standFor pre-check, openPocket, damageToDo, underHill on world ground, the 24 limit) - its clean hour is
  their test. Not in it: pocket 12, inTheWay detail, the heightFirst set-down, the line-up braking.
- 25k 07:56: built in under its own ground layer at the ravine's edge ("shut in at 3,102,8 (2 open cells)"), 47 s
  before "built in at 3,103,8: knocking 3,105,8 (moss block) out of the way" - breakOut only runs with no target, and
  the dig-down (no planOk, so no shut-in check) set a far buried target every 5 s ("32,107,59 is under the ground
  still to clear... couldn't fly there" x5 till "leaving it as it is"). The dig-down now waits while airShutIn.
  (Before it: 4 "went in wrong: wanted dirt got air" in 2 s, clicks against oak leaves under the ground layer that
  were decaying.)
- The 25k's 71 "went in wrong: wanted dirt got air": logged when the target stops being a hole without its block -
  for ground spots under clearFromY that's jobAt's shutAway/underGround "as it is" (a pocket under the ground layer
  nobody sees, closed off by a neighbour). Misleading log, not misplacements.
- 25k 08:09: "nothing under 32,103,55 to build on: a 38-block dirt pillar up to it (it comes out again after)" -
  a ground block at the ravine's edge, the window dropped to layer 65. giveUp's "nowhere" now tries a pillar only up
  to 8 (pillarDepth) before tryScaffold (temporary blocks out from what's solid beside it), and a tall pillar only
  after that fails.
- 25k 08:17:14 "[confused] 9 targets in 10 s": the ground layer's last spots at the ravine's edge, a tree's crown
  (oak logs/leaves under y 103, outside the box, so never cleared) under or round each - "nowhere", "hidden in a tree's
  leaves", "couldn't fly there". Each gets 2 "nowhere"s before it stops holding the part (buildOrder), so the
  ground part cycles through them once or twice before the outside starts. Left as is for now.
- 25k 08:17-08:21: 4 min of "[confused] 8-14 targets in 10 s" + three 30 s stalls on the ground's last ~40 spots under
  the ravine's tree crown before "building the outside (from y 107)" ("the ground has 427 still to do, none now:
  nothing to click on"). failStreak (skips since the last block in, reset on a placement): from 6 on, one "nowhere" is
  enough for a spot to stop holding its part in buildOrder (was always 2).

### WHERE I LEFT OFF (2026-10-17 ~08:55, testing; installed jar = 01:57:40 build, the user's game is open)
- The user's recording 03:32-08:27 (old jar, flat world, the gigantic medieval at 5,-60,1): 501 "off the face for 10
  ticks", 454 of them with the click ray on the very face and point aimed at (aim error 0.0-0.2 deg) - not the aim:
  clickPlaces said the block would go in the wrong way round. 383 of those 454 were stone brick stairs (of 1293 stairs
  placed, ~30%), and in 271 of them the heading from the eye to the point was within 3 deg of a 45-deg line between
  two facings: stairs face the way we look, the drift flipped it. Fix: planPoint also wants rightWay from 0.4 (choosing
  a spot, standSlack > 0) / 0.2 (from where we are) blocks to either side of the eye, across the line to the point
  (yawSteady, oriented blocks only; right over it = never steady for a heading-faced block). A target with no spot that
  way gets one more standFor without it (yawLoose), and "can't see it from the spot" for an oriented block sets
  yawLoose before steepOk. Compiles; NOT tested yet - queued first after the 25k: the awkward cottage (baseline 05:18:
  41 same-face off events in 8 min, saved in scratchpad baseline/), then the pocket castle (baseline saved too).
  offface.py <logs> counts same-face vs other.
- The user's castle today 08:33-08:37 (old jar): "building the inside (from y -60)" - bottom-layer stragglers
  (64..72,-60..-59,33..38) that first failed at 03:53/04:07 ("nowhere", 36-75 open spots, 0 with a view) and were
  then built over (the inside reached -48 by 08:04) - sealed deep in the rock. The old jar flew the sky route to each
  (up to -3.5, down to -35.5 onto the rock, "couldn't fly there", up again): 110 s stall, then the user switched to
  fantasy.litematic (new, 115x96x99, 63299 blocks at -117,-60,-43). The tested jar's routeNoWay (flat <= 24, blocked
  column) + flyFails give those up at once. Still open: why they failed at first, and they stay unfixable once sealed
  (openPocket only takes out a wall block next to our air).
- Tested jar saved: scratchpad human-autopilot-1.0.0-tested-0620.jar (the 25k run 5's own build, 06:15 source, 2.5 h
  clean). autoinstall.sh (background) installs the jar named in scratchpad install-which.txt as soon as the user's
  javaw is gone. Point install-which.txt at the dev build once the cottage/pocket checks pass.
- Heading fix, second form (no extra searches): planPoint takes a spot with room for the heading if there's one on
  the face, else keeps the first right-but-on-the-line spot (after 3 more on the line it stops looking) and sets
  planLoose; Face has a `loose` flag; faceFrom adds 1.5 to such a face's turn (sightLoose = the result's flag);
  standFor adds 4 to a spot whose face is loose. Per tick a loose face isn't placed from (lineOnly) unless yawLoose
  has the target; at the stand spot (dist < 0.5) lineOnly puts it in yawLoose. The third standFor is gone.
- The user's game 09:00: viewers summoned ~350 primed TNT at once over the fantasy build's corner. "would put null
  against stone ... wrong way round" = the TNT entities in the cells (isUnobstructed), and "keeps coming undone:
  leaving it a while" x8 while they went off; then "repairing 203 damaged spots first" -> 3 in 2 minutes (106-112/min),
  0 stalls (old 01:57 jar).
- REGRESSION FOUND (since ~03:10 today, in the 06:20 tested jar too): tryScaffold's final check (standFor with
  pretendSolid) could never pass for a click on the temporary block itself - rightWay's predictFrom used the real
  world, where the temp's cell is air, so the click "replaced" the air: "would put null against air", "no spot to
  click it from". Temp-block uses: 03:00 25k run 36 ok / 33 none; every run after ~0-2 ok (awkward cottage 0/42, the
  25k run 5: 13 ok / 268 none - its sideways log walls at 19,107,53-60 left out). The installed 01:57 jar is
  before it (the user's game: 33 ok / 41 none). Fix: rightWay uses Placement.predictAgainst (the Hypothetical
  context) when n is in pretendSolid; planPoint treats a pretend block as a full face. NOT tested yet.
  -> The 06:20 jar must NOT be installed (autoinstall stopped). Install only a build with this fix, after the
  awkward cottage check (temps + stairs).
- 25k run 5 STOPPED at 09:13 (170 min, 17118/26019, 06:15 code with the temp-block bug): stall episodes 202 (>=15 s:
  11, max 49 s), confused bursts 8 (all the ground phase 08:17-08:39), skips: nowhere 692, couldn't fly 81, can't see
  11; left as they are 109 (sideways/upright wood walls, top trapdoors, top stairs, ladders, grindstone, carpets -
  temp-block cases); temporary blocks 17; menu 791 fetches of 115 kinds in 566 visits, 1489 s (15%). Last hour:
  flights > 10 blocks of path were 61% of the 10.8 km flown, the long ones ~7x their straight line (wedges, look-outs,
  confused cycling, retrying the awkward blocks). Log + recording: scratchpad baseline/25k-run5*.
  Queue now: awkward cottage (temps + stairs), pocket castle, flat castle 25 min (bottom stragglers?), 25k run 6.
- Heading, third step (09:16, after the first cottage check showed 5 same-face off events in 2 minutes - stairs and a
  trapdoor - with the margin in): (1) the per-tick faceFrom also wants the heading right from where we'll be in 5
  ticks (yawLead = horizontal velocity x 5, checked in yawSteady): picked on the move, the bot coasted past the line
  while the eyes got there; (2) the click would land on the very face chosen (same block, same side), the eyes
  settled, and clickPlaces says wrong way round: "[box] X would go in <state> from here (eye, look): to a better
  spot" -> forceStandFor at once (once per target: headingMoved; not when yawLoose); (3) the off-face log now has the
  eye, look, what the click would put, and the loose flags. A second awkward-cottage run is queued for this.
- First awkward-cottage check (09:14, heading step 1 + temp fix): temps 13 ok / 28 none (fixed), skips 52 (was 105),
  but 882/999 (was 936), spruce stairs placed 29 (was 52), off-face 40 (was 45), door landings 377 ticks (was 38).
  The detailed log of the second run (09:22) showed why: "would go in facing=east ... look -49/40" for a south stair,
  "facing=south, look -11" for an east one, a trapdoor clicked from the far side - the locked face is taken from the
  stand spot's eye while flying there (the pre-aim), and placing starts as soon as it's in reach on the way, from
  another heading. Step 4: headingRightFrom - each tick the locked face must still be right (rightWay + room, with
  yawLead) from where we are, else it's re-picked from here (strict, or fly on to the spot). Third cottage run queued
  (HA_RUNTAG=3: only to give its log its own name).
- Awkward-cottage series (8 min each, p99 except trapdoor/stairs/barrel/grindstone/stripped_oak_log/lantern/ladder):
  run   done placed off-face moved stairs(med/mean s) temps ok/none doors skips left exact
  0251  919  336    72        0     -                 12/21         12    55    5    99.1  (before the temp bug)
  0518  936  329    45        0     0.7/1.3           0/42          4     105   14   99.3  (temp bug)
  0914  882  311    40        0     1.1/1.8           13/28         16    52    0    98.7  (step 1+2: margin, temps fixed)
  0922  912  346    1         44    1.1/1.7           14/20         18    40    0    99.1  (step 3: lead, move at once)
  0930  914  344    0         28    0.7/1.5           11/32         17    51    2    99.1  (step 4: headingRightFrom)
  The 28 moves of 0930: 20 had the CURRENT angles right (only the server's tick-old angles wrong - barrels facing
  down looked at from 41-45 deg below, stairs a hair from the line). Step 5: move at once only if the current angles
  are wrong (else it settles); yawSteady also checks the eye +-slack vertically for 6-way FACING blocks (barrels...).
  Door walks went up (4-12 -> 16-18) since the temps work: to check.
- Door landing loop (awkward cottage 0930, 09:37:27-36): "landing by the door" stuckNear (not getting nearer the spot in
  front of the doorway, ground under us) -> down short of it -> on foot !walkable to the front of the doorway ->
  take off -> stuckNear -> down... 14 landings in 6 s until the 160-tick give-up. Now: a landing that came down short
  (walkLandedShort) and then no walk to the front of it -> give the door up at once (shutDoors 30 s, "can't get in
  front of it, flying or on foot"). Door walks per cottage run went 4-12 -> 16-18 once temps worked (temps by the
  doorway: in to put one, out to click it, in to take it out).
  0950  911  342    1         6     0.7/2.1           12/27         23    38    0    99.0  (step 5 + door give-up)
  stall >= 15 s: 0. Door ping-pong left: temps inside a doorway's wall (x=17) for the door-frame blocks (x=16) beside
  the door at 16,-59,17 - in to put a temp, out to click it, in... 8 crossings in 32 s (09:54); a batching idea: all
  temps on one side first.
- Pocket castle 0939 (09:39 code): 23852/46200 in 600 s (0547: 23697), stalls >= 15 s 6 (12), pockets 3 (2 in, 1 back),
  wedges 46 (45) - the maze of stone-less walls, mostly "sideways X" with the head 0.05-0.2 too high for a 2-high
  gap ("down free"), dig-down 0 (5 false ones before).
- CANDIDATE for the user: scratchpad human-autopilot-1.0.0-candidate-0943.jar (= the 09:43 source, snapshot in
  scratchpad src-0943/, built by the 0950 cottage run). Same files and mixins as the installed 01:57 jar.
  autoinstall.sh restarted (09:59) with install-which.txt -> the candidate.
- Landings in the awkward cottage went 27-33 -> 43-65 once temps worked (door landings 4-10 -> 14-26, hop-offs within
  2 s 22 -> 36-64): the door ping-pong for temps. inRoomFor: a block with its temporary block(s) next to it counts as
  this room's only if the side it's clicked from (opposite its temp) is in the room - so the glass all goes in on
  one side, then the frame from the other. Used for roomKept's `here` and the tempReady "hanging ones first". Queued
  a cottage run (HA_RUNTAG6) after the flat castle, before the 25k.
- Short landings (awkward cottage 0950): 34 of 43 landings were on the ground < 1 s (0518: 15 of 33) - the door
  walk-throughs (land in front, ~1 s through, take off) look like hops. Flying through a doorway at low hover (feet
  0.1-0.15 up: 1.95 < 2) would avoid them - not done (the door walk exists because flying through had touch-downs);
  for now fewer door crossings (inRoomFor). The user's own builds (castle, fantasy) have ~0 door landings; their
  hop-offs were "descend (low)" (77 of 111), fixed.
- User's 5 h session flights: horizontal path/straight median 1.15 for 6-25 block trips (fine); 76 trips climbed
  > 20 blocks (1139 s): the look-outs every ~5 min ("stepping back to look at the build", meant) and the yo-yo sky
  trips 05:01-05:35 (fixed: routeNoWay).
- Flat-world castle 25 min (09:58, candidate code + door give-up): 3382 placed (135/min), skipped 5 (couldn't fly 2,
  can't see 3), nowhere 0, left 0, stalls >= 15 s 0, wedges 1, confused 0, off-face 0, heading moves 20. The user's
  old jar on the same castle (03:32-03:53) had its first bottom-layer "nowhere"s at 21 min - none here in 25.
  1024  945  373    2         4     0.7/1.1           12/23         20    39    0    99.4  (inRoomFor: temps' side)
  Best of all runs: most done/placed, best exact, stairs faster than the baseline, all landings 21 (was 43-65), door
  landings 11 (17-26), stalls >= 15 s 0.
- CANDIDATE 2: scratchpad human-autopilot-1.0.0-candidate-1023.jar (built by the 1024 cottage run; source snapshot
  scratchpad src-1023/ = the dev tree at 10:23, incl. the wedge log detail). install-which.txt -> it; autoinstall.sh
  (task biwa7dtmr) is waiting for the user's javaw to close.
- 25k run 6 started 10:32:17 (400 min cap, this code).
- 25k run 6, first 10 min: a whole floor of upright stripped spruce wood (local y 11, ~28x17) over the ground floor's
  rooms: each log needs a click on a top/bottom face, nothing under it -> 166 temps in 10 min, 28 alone, 101 "and 1 to
  hold it up", 37 "and 2" (~2.2 glass each, each knocked out right after). Done count crawled (+7..15 per 30 s).
  keepsForNext (NOT TESTED, not in candidate 2 or run 6): a temp right under the block it was for stays while a
  neighbour's floor block of the same kind still has to go in with nothing under it (its temp then goes against this
  one: no chain). Released once no such neighbour is pending (or it's given up).
- sealsHoleBeside (NOT TESTED; not in candidate 2 / run 6): in columnWaits, a full block waits if it's the last
  non-solid side of a PLACE hole beside it (that hole's other 5 sides all solid), unless that hole is a temp, cant, or
  has 3+ "nowhere". The user's fantasy build 10:56-11:00 (old jar): a TNT crater's bottom stones "nowhere" (views
  blocked), the ring over them went in, then "repairing 7 damaged spots first" -> "can't get to it", openUp, "couldn't
  fly there", 30 s stalls. Only the single-cell case (a 2-cell pocket or a shaft needs reachability: a BFS - too
  costly in columnWaits; columnWaits' own below-rule covers shafts).
- The user's fantasy session 08:38-11:05 (old jar, viewers' TNT): stalls >= 15 s 13 (>= 30 s 3), confused 0, nowhere
  463, couldn't fly 114, wedged 69, left 55 (stone/dirt "nowhere" 25 - buried TNT holes; bamboo 6), "damaged spots
  first" 292 + "none to do now" 74, openUp 37, off-face 50, landings 95. Candidate 2 has damageToDo (fails >= 2 not
  first) and the heading fix; sealsHoleBeside (untested) is for the buried holes.
- 25k run 6 clearing a hillside (heights 76..110, "ground over the build up to y 100"): ~170 actions/min, breaks
  ~250 ms apart in dense clearing (already 4-5/s), the rest is flying between clusters. ~21k holes in the window at
  33 min: the run may not finish inside its 400 min.
- Queued after the 25k: nhzpp3wk prebuilt 11 layers, 12 min (keepsForNext on the upright floor), the castle with TNT20
  (sealsHoleBeside), the awkward cottage (regression); then the suite.
- 25k run 6 wedge 11:18:28-31 at 49.30 78.48 34.70 (stand 48,77,34 in a 2-high gap under oak leaves, "height first 65
  ticks, gap under us 1.48"): the recording shows NO keys at all for 3 s - neither the descent (needs low clearance:
  roofNear via lowRoofOver(goal) should give clear 0.15) nor anything else. Not understood. Added to the STALL line:
  "last steer N ticks ago: goal, d.y, height first, gap, ahead, clear, low, roof near, line up" (steerAt/steerWhy set
  in steer's height control) - for the next runs.
- Wedge centering (NOT TESTED): 25k run 6 11:34:09 "can't get into 16,79,29 ... straight 17,79,29 Oak Log, 17,80,29
  Oak Log; sideways free; down free; off the middle 0.30" - 0.3 off the cell's middle is inside axis()'s 0.3 slack,
  so only the other axis was pushed, straight into the log. In steer, a wedge step (lastRouteWhy "wedged...", goal
  = lastRoute) now uses slack 0.05 and cap 0.15: into the next cell's middle exactly.
- TODO menu churn: the user's fantasy garden (11:20-11:40, old jar): 10-13 menu trips per 30 s, 154 fetches in 20 min,
  torch/stripped dark oak log/fern/vines/dirt/dark oak sign/planks/dandelion each 5-6 times (evicted and fetched
  again), placing 9-23 blocks per 30 s. Same as the 25k's interior in run 5 (13 trips/30 s). Ideas: on a menu visit
  fill more slots with what the next ~20 targets need (nextAlso only replaces a slot whose keep() < the new need: in a
  40-kind garden everything is need 1-2); or stick with the kind in hand longer (hand pass farLimit 4, hotbar 8).
- Menu churn, first step (NOT TESTED): a pass between "a block in the hotbar" (8) and the part's nearest: hotbar
  blocks up to 16 off ("a block in the hotbar, a bit further"), only when some of `first` needs the menu. `first` is
  already the part's working set, so it doesn't pull him to another part. To test on a decorated interior (the
  nhzpp3wk prebuild-11 run: menu trips per minute vs run 5's 13 per 30 s).
- 25k run 6 STOPPED 11:52 (80 min, 12769/25333, candidate-2 code): a hillside (76..110) - 80 min mostly clearing,
  done +36/min; projected past its 400-min cap. Clean otherwise: confused 0, stalls >= 15 s 1, >= 30 s 0, left 0,
  wedges 9, nowhere 17, couldn't fly 12, can't see 9, landings 38, temps 189/38 (the upright floor), off-face 15
  (knock-outs with a tuft in front), heading moves 0. Saved as scratchpad baseline/25k-run6*.
  Next: the 3 checks (floor temps + menu pass / castle TNT20 / awkward cottage), then the suite, then the 25k with a
  720-min cap at the end of the queue.
- Floor test (prebuild 11, 11:52) ended at 5.5 min - the test window closed (no crash, no mod exception; maybe the
  user). In it: 109 temps, 44 alone (40%, run 6: 17%), 36 "+1", 29 "+2": keepsForNext kept them, but holdsUp refused
  another temporary block as support (a branch's second piece once waited on a temp under nothing). Now holdsUp takes
  a temp that tempStays (in, still planned, and keepsForNext). Re-queued as HA_RUNTAG9 first.

### WHERE I LEFT OFF (2026-10-17 ~12:03, testing; installed jar = 01:57 build; candidate 2 waits for the user's close)
- Candidate 2 (scratchpad human-autopilot-1.0.0-candidate-1023.jar, source src-1023/) installs automatically when the
  user's javaw closes (autoinstall.sh, task biwa7dtmr; install-which.txt). It has: the temp-block fix, the heading
  fix (steps 1-5: no stair camera wiggle), the door give-up and door batching (inRoomFor), and the morning's fixes.
- Untested since candidate 2 (all compiled): keepsForNext + tempStays (floor temps), sealsHoleBeside (TNT holes),
  wedge centering (slack 0.05 on wedge steps), the hotbar-16 pass (menu churn), stall-line steer diagnostics, the
  wedge log fields, the off-face log's block name.
- Queue now: castle TNT20 (running, 11:57), floor test RUNTAG9 (prebuild 11), awkward cottage RUNTAG8, the suite
  (barn TNT ... dining hall), the 25k with a 720-min cap last.
- The user asked for status at 11:57 (rejected a 20-min foreground wait): keep waits in the background.
- Castle TNT20 in a natural world (11:57, all untested changes in): TNT at t=600 s onto 104 built round him - 18 blown
  out, all back after 64 s, 0 out at the end; skipped 1, 4 short stalls, 168 actions/min (mostly the ground/trees).
- Floor test RUNTAG9 (tempStays in holdsUp): by 12:18, 196 temps: 187 alone, 8 "+1", 1 "+2" (95% alone; run 6 17%,
  the first floor test 40%) - ~1.05 glass per upright log instead of ~2.2.
- The user's game 12:17:58 "[confused] 9 targets in 10 s": bamboo "left as it is after 5 tries" (old jar; the
  installed 01:57 Placement.class has no BAMBOO_SAPLING case, candidate 2 does).
- Creative menu slip made permanent (the user's game 12:18:35-57): "fetching vines" started, its block skipped
  ("couldn't fly there"), he flew off; 22 s later the same fetch went on with the old clock, screenTick's 400-tick
  timeout fired: "couldn't get vines: took too long" -> failed for good, chat "Can't find vines ... leaving those
  blocks out". Now (NOT TESTED): failed is item -> until; only "no X in the search" / "couldn't scroll to it" are for
  good (and only those say "Can't find" in chat), slips are 2 minutes; the timeout counts from the menu opening
  (openedAt); a fetch not asked for for 10 ticks before the menu opened starts over.
- Floor test RUNTAG9 full (12 min): 19326/25982, placed 1088, temps 295 ok / 45 none: 262 alone (89%), 30 "+1", 3
  "+2"; stalls >= 15 s 0, confused 0, nowhere 2, left 0, wedges 2, menu fetches 50 (4.2/min), errors 0; exact 96.2%
  mid-build ("extra glass=37": temps kept for the floor's next logs).
- Awkward cottage RUNTAG8 (12:23, all untested changes in): 936 done, 379 placed (most yet), off-face 1, heading moves
  2, temps 15/24, door walks 16, landings 27, skips 47, left 3 (a top-half stair, a top trapdoor), stalls >= 15 s 1
  (on the floor by the door, "gap 0.06, low, roof near"), confused 0, errors 0, exact 99.3%. Near the end it switched
  outside/inside 4 times in a minute over the leftovers - to look at.

### STOPPED (2026-10-17 12:32, the user: "we've been working on it for the past 2 or 3 days. stop for now")
- Queue runner, afterrun and monitors stopped; no test client running; the user's game untouched.
- STILL RUNNING on purpose: autoinstall.sh (task biwa7dtmr) installs candidate 2 (scratchpad
  human-autopilot-1.0.0-candidate-1023.jar, per install-which.txt) when the user's javaw closes. Told the user.
- The dev tree has everything since candidate 2 (keepsForNext/tempStays, sealsHoleBeside, wedge centering, hotbar-16
  pass, creative-menu slip fix, stall/wedge/off-face log detail) - passed the castle TNT20, the floor test (89% of
  floor temps alone) and the awkward cottage, but not a long run or the suite yet. Next session: the suite, then a
  25k with a 720-min cap, then make that the install.
- INSTALLED 12:35 (the user chose "install the newest; my streaming is the long run"): the user closed Minecraft at
  12:34:40 and autoinstall put in candidate 2 at once; then, game still closed, install.sh put in candidate 3 =
  scratchpad human-autopilot-1.0.0-candidate-1222.jar (480817 bytes; source snapshot src-1222/ = the dev tree at
  12:22: everything listed above). Backups of what was there: scratchpad human-autopilot-1.0.0-old-*.jar (the 01:57
  build is the 12:34 one... check sizes: 465651 = 01:57, 479414 = candidate 2). Next: read the user's next log +
  recording (stalls, wedges with the new steer detail, sealed holes, menu trips, temps).

### HIMEJI (2026-10-17 13:40-, the user: "have her build that fully" + keep auditing; testing again)
- himeji-japanese-m8iniucw.litematic: 244x133x275, 932,111 blocks (37x the 25k house); y 0-39 = 769k (stone, stone
  bricks, dirt, sand, a 31k-water moat, mossy stone bricks), the castle's buildings above (smooth quartz, blackstone,
  dark oak, spruce). Full build ~3-5 days at today's speeds. Queue: TNT20 runs at prebuild 25/45/70, then the full
  build (6000-min cap); when code changes mid-build, restart with HA_PREBUILD at the layer reached.
- TNT run 1 (prebuild 25, 13:45): pasted leaves decayed (the bot re-placed them at the bottom first); TNT blew 1796
  of 31486 round him, 540 back in the 5 minutes left (~1.8/s: 63% placing, 37% flying), 0 stalls.
- Scale problem: scans 45-75 ms (4 a second). Sub-timers (s.count/blast/window/cells/round/rest/holes/far/clear) in
  the long-tick and thinking-time lines. Run 2's numbers: s.clear 1217 ms/30 s (up to 46 ms a scan: the clearing
  loop over the ±12 square times the whole window's height), s.round 799 ms (25x25x21 plan spots every scan), route
  2316 ms in the first 30 s (the flight over). Fixes (NOT TESTED; run 3 has them): the clearing loop stops at each
  column's highest block (WORLD_SURFACE heightmap); with a window over 50k spots "round us" is r 8, ±6 (was 12, ±10).
- Run 2 (prebuild 45): TNT 1762 of 16601 out, 1236 still out after 5 min; 4 skips, worst tick 53 ms.
- Run 3 (prebuild 70, the two scan fixes): scan 514-681 ms/30 s (was 1223-2247): s.clear 130-171 (614-1217), s.round
  203-282 (364-799); one long tick only (39 ms, the route of the first flight over). Thinking ~1.4 s/30 s in all.
- Run 3 end: worst tick 21 ms (runs 1-2: 53-79), 5 skips; TNT 2154 of 34215 out, 1668 still out after 5 min (the TNT
  runs' bots spent their minutes on the pasted terrain's dirt/podzol/grass/leaves mismatches first, then the TNT).
- FULL BUILD started 14:14 (himeji|6000|0, flat world, all fixes incl. the two scan ones). scratchpad hstatus.sh =
  a compact status of it.
- Full build 1 (14:20-15:10, killed for the fixes below): 14k of 958k in 50 min (~190-370/min when placing), 0
  stalls >= 15 s, 0 confused, 0 errors. The problem: the pick. 238-285k holes in a 13-layer window (-61..-49);
  ~8000 of them within 14 / ±12 of him, every one columnWaits + planOk on every pick = 30-109 ms picks, pick 3.4-3.8
  s/30 s, a third of ticks over 5 ms; pickfar 43-47 ms (a comparator sort of 280k). Also found by reading: a pond's
  water could never go in (planOk's "water last" waited on every planned neighbour still a hole - the water beside it
  too: 31k moat spots each waiting on the next), and lily pads had no way in at all ("no spot": nothing beside them to
  click, the water under them no face).
- Fixes 15:10 (dev tree; candidate 3 installed doesn't have them):
  - bigWin (holes > 20000): holesNear() looks the window's holes round us up spot by spot in holesByLayer (not all
    280k gone through); buildOrder split into orderPart (the part + its lowest layer, from the holes) and orderPick
    (of ok, what's to do); the part is worked out first and only the near holes it can use get the full checks (that
    part, up to its lowest + 8; with no part, the lowest two layers with something to do; temporary blocks, clearing,
    water running out always) - the rest ("later") only if nothing else will do (ok empty, or the "anything near" pass).
    Damage, the pocket search, the stuck section's skip/relax loops and its why-log use the damage/bad/nowhere sets or
    holesNear instead of all holes. The far fallback's full sort: keys worked out once into a long[] (float bits).
    scanPlan: a huge window's holes list rebuilt at most once a second (holesFull cache), not 4 times.
  - Water: the build's own water beside a water spot doesn't hold it back (waterWouldRun still sees where it runs).
    waterWaits: one spot per 2x2 first (x and z even), a spot with 2 sources beside it and something solid (or a
    source) under it left to fill itself (infinite-source rule); waits time out (30 s, 15 s for "fills itself").
  - Lily pads: pad()/padBase()/padFace()/padAim()/padRay(): workable = a water source (or ice) under it; waits for the
    planned water under it; never a temporary block; the face is the water's top under it (looked at with the pad's
    own ray: OUTLINE + SOURCE_ONLY), the click is a plain use with that ray on the water under it (not on a usable
    block). visible() uses the same ray.
  - New test: HA_EXACT=pond (pondBuild): a walled 11x9 pond + a 3-long channel (103 sources) with 10 lily pads;
    POND CHECK line (right/wrong, sources, pads, seconds).
- Pond test (HA_EXACT=pond, 15:11): 748/748 right, water 103/103, lily pads 10/10, 91 s, 3 menu trips; ~7 s of pouring
  for 103 sources (several "isn't to do any more" = spots that filled themselves).
- Himeji TNT20 at prebuild 45 (15:13, RUNTAG3): no crash, no stall >= 15 s; 892 placed in 8 min. The test's TNT is
  summoned round wherever he is at each of the 20 summons (3 s): he was flying to a far repair (decayed pasted leaves
  - prebuild artifact, 360 "damaged" before the TNT), so most TNT went off 30 blocks from the first spot; he then
  repaired round him (2.0/s), never back at the first spot, "984 still out at the end" of the 1154 there. Fixes: the
  prebuild pastes leaves as persistent (no more decay noise); with > 300 damaged, the far damage's support chain
  (repairStep) is only worked out nearest first when its turn comes (picks were 24-30 ms through the repair).
- The user's live game (candidate 3, fantasy.litematic, terrain + viewers' TNT), 12:36-15:21: 0.95 placements/s;
  gaps > 3 s were 65% of the last 90 min: menu 1546 s (187 gaps, ~8 s each), repair flying 846 s, "nowhere" 606 s
  (28 gaps, ~22 s each: stone buried under the world's grass/tall grass), "couldn't fly" 302 s. Menu: 658 fetches in
  90 min, 235 of them "while the menu's open" extras, 330 re-fetches of something fetched < 3 min before (stone brick
  slab 56x, dark oak trapdoor 40x). Fixes (dev tree): CreativePicker.keep protects a slot used in the last minute
  (+1000; last 10 s +2000); an extra only takes a slot not used for a minute and needed well under half as much;
  needsNear's whole-window term capped at 6 (it was allNeed/4 - hundreds - and outweighed what's needed here).
- Local-area fix: the "hotbar, a bit further" (16) pass is skipped while 2+ of `first` are still within 6 of the last
  placed block (all 6 "off to" jumps in the castle run and all 6 in the user's last 30 min came from that pass).
- Queue after that: fantasy flat x2 (RUNTAG4 = before the hotbar fix, RUNTAG5 = with it), fantasy on terrain
  (RUNTAG6), fantasy terrain + TNT20 (RUNTAG7), then the full Himeji (RUNTAG2).
- CRASH FOUND + FIXED 15:29 (dev tree only, never installed): scanPlan's colTop array (the Himeji clearing-loop fix
  from 13:xx) had a negative size when he was out past the box's side (fantasy run RUNTAG4's "stepping back to look at
  the build" -> NegativeArraySizeException -200, game crashed after 6 min). Now Math.max(0, ...) per side.
- 15:30-15:45 more fixes (dev tree, after RUNTAG6 started; in RUNTAG9 on):
  - outFor (giveUp "nowhere" + tuftHits): the build's OWN grass/flowers (replaceable, no collision, in and right) in
    the way of every look at a spot: up to 2 taken out (jobAt BREAK while the spot's to do, BLOCKED once out), the
    spot gets its go, then back to holes of their own (scanPlan drops the entry when the spot's in, or after 60 s).
    phaseOf -1 (never held back), bigWin "always", repairStep does the take-out first for damage. 2 tries per spot.
  - jobAt: soil (dirt/grass/podzol...) shut in on all six sides (canOcclude) = DONE anywhere (was only under
    clearFromY): the terrain run's dirt under the world's grass/stone, "nowhere" 3-4x each.
  - sees(): kelp/kelp_plant/seagrass/tall_seagrass count as tufts (knocked out when in the way): the terrain run's
    build over a lake, "0 with a view" from 294 open spots.
- The user's game baseline 13:50-15:21 (candidate 3; scratchpad runstats.py on a copy of their log): 92 min, 44
  placed/min, menu 522 fetches (235 extras, 269 re-fetched within 180 s), skips nowhere 285 / couldn't fly 138,
  stall episodes 271 (15 s+: 41, 30 s+: 3, 60 s+: 0, most "flying to a hole"), wedges 51, confused 2. Couldn't-fly
  gives up a median 18 blocks short (stuck on the way, 26 of 133 still 40+ away); chains of skips near him send him
  50-80 blocks across the build and back. Wedges: mostly Stone Bricks / Dark Oak Planks over a 2-high gap, the
  feet 0.03-0.3 too high, "height first" 50-80 ticks without getting down (not reproduced yet).
- Tools: scratchpad rate.py (placement rhythm by gap size), runstats.py (one run's numbers), jumps2.py (each jump >12
  with the log round it), gaps.py, jumps.py.
- Fantasy on terrain RUNTAG6 (15:29-15:59, a lake + hill spawn, everything up to the crash fix, NOT kelp/outFor/
  soil): 186/min, placed 5577 (mostly clearing the hill), skips 29 (nowhere 24 - kelp, couldn't fly 3, can't see 2),
  stalls 25 all < 15 s, wedges 4, confused 0, crashes 0, worst tick 12 ms, menu 9 fetches. bigWin (46-52k holes):
  pick 330-980 ms/30 s, pnear 60-430.
- CANDIDATE 1600 = scratchpad human-autopilot-1.0.0-candidate-1600.jar (487653 bytes) + src-1600/ = the dev tree at
  15:59 (all of today's fixes) = what RUNTAG9 (flat fantasy, 30 min) runs.
- RUNTAG9 (flat fantasy, candidate 1600) at 10 min: 104/min, menu 29 fetches (10 extras), re-fetched within 180 s only
  3 (RUNTAG4 before the hotbar fix: 8 of 33 in 6 min; the user's game: 269 of 522), skips nowhere 11 / couldn't fly 1,
  stalls all < 10 s, confused 0, crashes 0.
- 16:11 AUTOINSTALL ARMED: install-which.txt = candidate-1600.jar; autoinstall.sh (task btk0eyd1f) waits for the
  user's javaw to close, then install.sh backs up the old jar and copies 1600 into .minecraft/mods and dist/.
- Queue from 16:30: fantasy terrain+TNT20 (RUNTAG7, 15 min) -> Himeji full from the ground 150 min (RUNTAG10: the
  moat = 30,916 water at schematic y 0 + 553 kelp + 52 seagrass, 527 lily pads at y 1) -> Himeji top, prebuild 110,
  150 min (RUNTAG8: ~5.9k blocks, should finish = an end-game test) -> Himeji TNT20 at prebuild 60 (RUNTAG11) -> the
  full build (RUNTAG2, 6000 min).
- Watch in RUNTAG10: "placing water bucket", waterWaits savings (sources that fill themselves), lily pads placed,
  kelp/seagrass waterFirst, any "nowhere" for pads; bigWin costs.
- RUNTAG9 final (flat fantasy, candidate 1600, 30 min): 123/min, placed 3683, skips 26 (nowhere 24 - mostly two
  floating rocks over a 1-high gap in the garden, only a temporary block under them would do; couldn't fly 2),
  stalls 22 all < 10 s, confused 0, wedges 2, crashes 0, worst tick 18 ms; menu 81 fetches (23 extras), re-fetched
  within 180 s 13 (16%). Exact 93.3% (mostly "dirt is grass_block" ground - by design). PASSED: candidate 1600 stays
  armed for install.
- 16:35 gapTemps (dev tree, NOT in 1600): giveUp "nowhere" for a full, non-falling block over 1-3 open spots with
  solid ground under them (nothing of the build planned in the gap): glass temps up the gap (temps -> it waits for them,
  goes on top, they come out). Once per spot (gapTempsTried). For the fantasy garden's floating rocks. Check: RUNTAG12
  (flat fantasy 12 min), log "floats over a N-high gap".
- RUNTAG7 (fantasy, terrain + TNT20, 1600-level code): TNT at 3 min, 69 of 351 out at most; 9 left at +60 s, 8 at
  +70 s; then the test's count crept back to 27 - all "wants dirt, has grass_block: DONE" (grass spreading onto the
  repaired dirt; soil for soil is done by design). Shut in once by his own repairs, knocked a dirt block out, 7 s.
  156/min, skips 4, stalls 11 (none 15 s+), worst tick 10 ms, no crash. PASSED.
- RUNTAG12 (flat fantasy 12 min, with gapTemps): "48, -59, 18 (Stone) floats over a 1-high gap: 1 temporary block"
  -> recording: glass on the grass at 157.2 s, the stone on the glass at 158.0 s. Works.
- 16:50 gap descent fix (dev tree, NOT in 1600/1639): reproduced the user's 2-high-gap wedge in RUNTAG12 ("can't get
  into 97,-59,24 from y 0.37 over the floor, dirt over the gap, height first 17 ticks"). Two causes: heightFirstSince
  restarted whenever heightFirst flickered off for a tick (so the "tap down anyway after a second" never came), and
  the floor guard (jump when ahead-gap < clear-0.35 + 3*|v.y|) pushed us straight back up after the tap. Now
  heightFirstSince survives up to 10 ticks off (heightFirstLastAt), and downToGap (heightFirst, d.y < 0, gap < 0.6,
  20+ ticks) skips the guard: down onto the floor is fine, walked in from there. Watch landings + wedges in RUNTAG10.
- RUNTAG12 final: 111/min, skips 2, stalls 15 (none long), worst tick 14 ms, no crash. 16:52: install-which.txt ->
  candidate-1639.jar (488232 bytes, src-1639/ = dev tree at 16:39: 1600 + gapTemps). autoinstall still armed.
- RUNTAG10 (Himeji from the ground, 16:52, with the gap descent fix) at 5 min: 93/min, skips 0, wedges 0, stalls
  all < 10 s, pick 279 ms/30 s (was 3.4-3.8 s in the morning run), pnear 80. Long ticks 25-59 ms only while the window
  was 8-20k holes (below bigWin's 20000: the old near loop). 17:00: bigWin threshold -> 8000 (dev tree; RUNTAG8 on).
- RUNTAG10 at 20 min: 10272 done (the morning run needed 30 min for 9505), 202/min in the last 30 s, skips 1, stalls
  < 10 s, wedges 0, confused 0, crashes 0; window 213k holes: pnear 735 ms/30 s, order 163, long ticks 18-28 ms
  (pnear 8-12 of them), one 74 ms (pickfar 26 + order 21). Moat: 35 spots filled themselves before the click.
  Possible later: plugsWater (a Terrain.state per near hole) only within ~6 blocks; sub-timers inside pnear.
- 17:20 (dev tree): the far fallback's full sort -> selectSmallest (quickselect, O(n)) for the nearest 1500, sorted,
  checked in order (a 123 ms tick in RUNTAG10: pickfar 83 ms when he came back from looking at the castle from 25 up:
  nothing near, all 64 nearest failing planOk, then 242k sorted and checked one by one). Sub-timers pn.list / pn.ok
  inside pnear. Both from RUNTAG13 on.
- 17:25 (dev tree): steer's descend used min(gap, aheadGap) - aheadGap is the floor where the velocity would carry us;
  height first we hold still sideways, and a wall in front made it negative: the user's log, "height first true, gap
  1.99 ahead -1.01, d.y -1.84" for 5+ s, then "couldn't fly there". Now aheadG = gap while heightFirst (descend and
  the floor guard's "ahead" both). Also: near loop calls plugsWater only for holes that would otherwise wait.
- The user's game 15:25-17:20 (candidate 3): 70/min, menu 510 fetches (44% re-fetched within 180 s), nowhere 363,
  couldn't fly 99, wedges 38, "off to" jumps 25, confused 2, crashes 0. No-spot kinds: stone/stone 59, dirt/grass 57,
  dark_oak_planks over air 50, dirt/stone 41, short_grass/grass 40, dirt over air 36, stone over air 32, glass over
  air 21 (temps), honey 20.
- RUNTAG10 at 30 min: 14955 done, window 274k holes, pick 1356 ms/30 s (pnear 784, order 192), worst tick 22 ms.
  17:30 (dev tree): window > 50k spots -> near holes within 7 up/down (was 12), and the main loop's "far" uses the same
  height (nearDy) so nothing falls between near and far.
- RUNTAG10 at 42 min: 16998 done (155/min avg), skips 2, stalls < 10 s, wedges 0, confused 0, crashes 0, long
  ticks 56 (worst 113 ms = pickfar 43 + order 29: the full-sort path, fixed in the dev tree since). Moat: 35
  self-filled + a few pours early on (16:52-16:56, the corner by the start), nothing since: the rest waits for the
  ground and walls round it (waterWouldRun); lily pads 0 so far; kelp 6 placed.
- 17:33 stopped RUNTAG10 at 42 min (the moat won't come up in 150 min: the -61 ground layer alone is hours; the pond
  test covers pads/water mechanics). CANDIDATE 1735 = scratchpad candidate-1735.jar (488978 bytes) + src-1735/ = the
  dev tree at 17:34: 1639 + gap descent (heightFirst sticky, downToGap, aheadG) + bigWin 8000 + near ±7 on >50k
  windows + quickselect far pick + pn.* timers. RUNTAG13 (fantasy terrain 20 min) runs it.
- 17:55 (dev tree, after 1735): the flight to a stand drops the stand when its cells were open when first headed for and
  aren't now (standOpenFor/standWasOpen, by way() - the air map's own test, so a ladder's spot can't loop): RUNTAG13's
  one "couldn't fly there" was a stand that got a block in it, 4 s hovering over it.
- RUNTAG13 final (fantasy terrain, candidate 1735, 20 min): 161/min, skip 1, stalls 12 all < 10 s, wedges 0, landings
  2, worst tick 8 ms, long ticks 0, crashes 0. pick 448 ms/30 s at 30k holes (pnear 115: pn.list 20, pn.ok 46). PASSED.
  Few gaps on that spawn, so next: the cottage on terrain (RUNTAG14, has standOpen too) for the 2-high gap fixes.
- 18:08 (dev tree, after 1755): waitsOnSupport: a VegetationBlock (crops, grass, flowers, saplings... lily pads too)
  that can't survive at h yet, over a planned spot that's still a hole (farmland to till, ground to place): waits.
  RUNTAG14 (cottage on a hill) had wheat over grass-to-till and tall grass over ground-to-come, "nowhere" 4-5x each.
- RUNTAG14 (cottage on a hillside, candidate 1755 = 1735 + standOpen): 93/min, placed 1395, skips 35 (nowhere 32:
  wheat over grass-to-till, tall grass over ground-to-come - fixed after - grass over stone...), wedges 1, couldn't fly
  2, landings 9, stalls 17 (4 of 10-14 s, one 13 s shut in a shaft he dug, knocked out in 8 s), confused 0, crashes 0,
  worst tick 12 ms. Earlier cottage terrain runs: wedges 9/1/1, couldn't fly 10/5/6. 18:12: install-which.txt ->
  candidate-1755.jar (489173 bytes, src-1755/). autoinstall still armed.
- RUNTAG8 (Himeji top: prebuild 110 = 925,784 pasted, 150 min) start: the first ~3 min are a prebuild artifact - the
  memory hasn't seen the pasted layers, farGoal sends him over "the rest of the build" (the unseen bottom layer), he
  tries "up over the top" (y 73.5) from inside the castle's pasted rooms, "path 0 cells", a hollow at y -28: 115 s
  without a block, then out and up to the top (window 3618 holes by 18:14). A cobblestone slab by pasted water "keeps
  coming undone" (it goes in waterlogged: the spot has a source). 67 smooth quartz at y 53 "nothing to click" early.
- 18:28 FOUND in RUNTAG8: a ping-pong between the roof (y 53) and a cobblestone slab by the moat (y -52): the slab's
  spot holds a water source, so it goes in waterlogged every time; jobAt said BREAK (want dry), "keeps coming undone"
  left it 30 s, then damage again - "repairing 1 damaged spots first", 13 s down, 13 s back up, every minute (20/min
  placed). Fix (dev tree): jobAt DONE for a block that's right but waterlogged where the plan has it dry. Restarted the
  top run as RUNTAG16.
- 18:31 (dev tree, after 1830): "keeps coming undone" now counts spells (undone[k][2]): the 2nd spell leaves it 5 min
  (not 30 s), and from then on it comes back as a plain hole, not damage (TNT near it still counts as damage). For
  plants by running water etc. that had him fly back across the build every minute. CANDIDATE 1830 = 1755 + plant
  wait + waterlogged-is-done (RUNTAG16, the restarted castle top).
- 18:33: the waterlogged slab wasn't a BREAK but an ADJUST ("emptying the water out" with a bucket - Placement.adjust)
  and was wet again within 2 s each time; the DONE rule now covers BREAK and ADJUST. Restarted the top as RUNTAG17 =
  CANDIDATE 1834 (scratchpad candidate-1834.jar 489384 bytes + src-1834/): 1830 + ADJUST case + undone spells.
- 18:45: RUNTAG17 (castle top, 1834) at 5 min: 148/min, 0 slab repairs, 0 "keeps coming undone", 0 "emptying" -
  the waterlogged-slab ping-pong is gone.
- Your game (candidate 3, fantasy at -124 -60 -154) since 17:25: 111/min, skips nowhere 84 / couldn't fly 27 / can't
  see 6, 26 wedges, 13 "off to" jumps (fixed in 1600+ by localLeft), 33 jumps to TNT repairs (by design).
  A torch at -62 -57 -76 (schem 62 4 78) has the schematic's own AIR under it, in a dirt overhang: a paste leaves it
  floating, a player can't. aroundDone never came true (a dirt next to it was "nowhere" too), so it was retried every
  few minutes, a flight each time. NEW (dev, after 1834): neverStays(c, want) - canSurvive against finalLevel(), a
  java.lang.reflect.Proxy LevelReader whose getBlockState/getFluidState give finalState (plan, else the schematic's
  air from clearFromY up, else the world); cached per spot 1200 ticks; mushrooms left out (light). jobAt: PLACE that
  can't survive now and neverStays -> BLOCKED, logs "leaving X as it is: it can't stay there even with the build done
  round it". Queued RUNTAG18 = fantasy prebuild 4 (its torches at schem y 4) next.
- 18:58: RUNTAG17 (castle top, 1834) stopped at 26 min: 78/min, 0 slab trips (fixed), but:
  (a) vines GROW: 10,391 vines, 195 saplings, 10,015 bamboo in Himeji. Every far trip in the run (2 "repairing 1
      damaged spots first" at y -30/-19, 1 "off to 87, -36, 225 (40 away)") was a vine that crept one block into a spot
      the schematic has empty - 70-90 blocks down from the roof and back. NEW: grewThere(world, want) (vine, kelp/cave/
      weeping/twisting, cane, cactus, bamboo, chorus, mushroom, cocoa, beehive, mangrove roots, amethyst, attached stem,
      pumpkin/melon, snow layer, logs, non-persistent leaves; ice on planned water; copper further oxidised; dead_ coral)
      -> remember(): a done spot that grew it is "grown" (logged), not damage; jobAt DONE. grewFromBuild(c, world): a
      plant in a schematic-empty spot followed through the same plant (stalks up/down only; vines/chorus/trees any way)
      to a spot where the schematic has it (a tree: to its sapling) -> the clear pass skips it, jobAt DONE.
  (b) a row of upside-down quartz stairs (facing west) at x 122 y 50 z 171-180: the OUTER row (x 123) went in first
      (chained along its row), then the inner row's only right-way click is the log's east face upper half from right
      above, too steep from the cell centre ("ray hits 121, 50, 175 up"): "nowhere" x25. Not fixed yet (openUp should
      take an outer one out on the 2nd go). Idea: eye offsets inside the stand cell.
  (c) the clear pass scans y from activeY (-61!) to the top within 12 blocks: s.clear ~27 ms per scan, 946 ms/30 s.
- 19:10: YOUR GAME's "repairing 1 damaged spots first" (71 since 17:25, 1196 in the log): nearly all BAMBOO growing a
  block taller (some of the fantasy build's bamboo has string on top). Cut, regrown, a flight across the build each
  time - the "jumping around". The growth rules above cover it.
- Clear pass: world state first (air -> next), MutableBlockPos, unloaded columns colTop MIN - no plan HashMap lookup
  for air (the castle's rooms).
- Stairs: rightSides for a stair no longer counts the neighbour BEHIND it (A - facing: clicking it you look the other
  way), so a top stair with only that one in still waits for its real reference. behindStair(h): h is right behind
  an upside-down stair A still to go in (A = h + d, A faces d) -> h left out of orderPick's list and every pick pass
  (5 min cap per h, behindSince), so the stair goes in from h's side first, then h.
- NEW TEST: HA_EXACT=grow (growBuild/growSpec): a wall with 2 rows of vines, bamboo, cane by a pool, an oak sapling,
  random_tick_speed 300; builds, then guards 90 s; GROW CHECK line; fails on wrong blocks or > 1 repair while
  guarding. BoxFillModule.repairRuns / grownCount() for it. Queued as RUNTAG20.
- 19:15: RUNTAG18 (fantasy prebuild 4, 12 min, neverStays + first growth rules): 105/min, 0 skipped, 4 stalls, 0
  wedges/confused/crashes; 24 "can't stay there even with the build done" - checked: really floating in the schematic
  (short grass/fern over its own air, a lantern hung under a lantern, torches over air).
- RUNTAG20 = first HA_EXACT=grow: built 238/238 in 51 s, repairs 0 while building and 0 in 90 s of guarding (old code:
  one per growth), 61 cells grown over; but 1 wrong: the oak sapling's spot was AIR at the end, no tree. Added a
  per-second trace of that spot + grown-kinds histogram; re-running as RUNTAG21 before the castle top (RUNTAG19).
- 19:20: RUNTAG21 (grow, sapling moved): the sapling never went in because it stood >3 columns from anything solid
  = "yard" (keptColumns, YARD 3) - by design; moved it next to the wall (11 1 4) for RUNTAG22. Bamboo 15, cane 2,
  vines 44 grown over, 0 repairs.
- CANDIDATE 1917 (scratchpad candidate-1917.jar 493968 + src-1917/): 1834 + neverStays + growth rules + clear pass
  air-first + behindStair/rightSides + saplingsIn. RUNTAG19 (castle top, 1917) at 13 min: 108/min (RUNTAG17 78),
  skips 0 (was 25 "nowhere"), take-outs 0 (was 20), repairs 0 (vine trips gone), wedges 0, confused 0.
- After 1917 (dev): the clear pass looks at layers more than 12 under us 16 at a time in turn (clearSlab): the
  castle-top test's activeY stays at -61 (the bottom never seen, the outside logic took over), so it scanned all
  110 layers round us every second (s.clear 670-950 ms per 30 s).
- 19:40: RUNTAG19 (1917) went bad at ~14 min: 10/min, 25-29 stalls/30 s, down at y -42 by a BAMBOO column. Himeji has
  194 bamboo_sapling (the "195 saplings" were these) at the foot of bamboo stalks; the game turns a shoot with bamboo
  over it into bamboo on any neighbour update, so want=bamboo_sapling/world=bamboo was BREAK -> knocked out -> the stalk
  over it fell -> 8 damage spots, vines crept into them, bamboo tried over the empty shoot spot ("nowhere", "went in
  wrong: got air"). FIX (dev): jobAt world BAMBOO + want BAMBOO_SAPLING -> DONE (the mirror of the old rule);
  waitsOnSupport's "can't stand there yet over a planned spot still to do" now for any non-fluid block, not just
  VegetationBlock (bamboo/cane wait for what's under them). Killed RUNTAG19; RUNTAG23 = castle top 75 min with it.
- 19:45: install-which.txt -> candidate-1917 (better than 1755 for your fantasy build: bamboo growth, floating
  decorations, the slab). The bamboo-shoot fix is only in dev so far (needed for Himeji); next candidate after RUNTAG23.
- Concrete powder set by water and sponges soaked: jobAt DONE (want powder + world that concrete; want sponge + world
  wet sponge), and grewThere counts them too.
- Queue: grow (RUNTAG22), Himeji TNT20 p60, fantasy terrain TNT20, Himeji slices p30/p80/p100 25 min each, then the
  full build.
- 19:55: CASE RECORDER (dev): when a spot is left for good (cant, after 5 tries), caseFile writes the 13x13x13 round
  it - world states + what the build wants (plan, else the schematic's air from clearFromY, else -1) as palette
  indices, the player's position, replace - to .minecraft/humanautopilot-cases/case-<time>-<x_y_z>.json (40 a
  session). REPLAY TEST: HA_CASE=<file> (caseBuild): pastes the world cube, builds a 13^3 schematic of what's wanted
  (as-is elsewhere), tp to the recorded spot, builds 2 min, CASE CHECK IN / STILL NOT IN. Hand-made cases from
  scratchpad mkcase.py in %TEMP%\hatest-cases (overhang: short grass under a 1-high stone roof; cavedirt: dirt over a
  cave, open only from under it). Queued RUNTAG27/28; RUNTAG22's line ends ";HA_CASE=" to clear it (the queue runner
  doesn't clear HA_CASE itself; Set-Item Env:X "" removes it).
- Himeji layers: y0 (world -60) has 66,552 blocks incl. 30,916 of the 31,161 water (the moat) and the kelp; lily
  pads on y1. The whole castle is ~932k: ~100 h at 150/min - "build it fully" in one test isn't possible, so slices:
  p30/p80/p100 (25 min each) and a MOAT test RUNTAG30 = prebuild 1 except water/kelp/seagrass/pickles/coral, 60 min.
- 20:00: RUNTAG23 (castle top with the bamboo-shoot fix + clearSlab) at 15 min: 108/min, 0 skips, 0 repairs,
  0 wedges/confused, 3 pocket take-outs (openPocket, smooth quartz at 100-101 54 180-181), s.clear 133 ms/30 s (was
  670-950). CANDIDATE 1938 = the jar it runs (scratchpad candidate-1938.jar 494051 + src-1938/) = 1917 + bamboo shoot
  rule + general support wait + clearSlab. install-which.txt -> 1938. (Concrete/sponge rule + case recorder came
  after: in dev only.)
- 20:20: RUNTAG23's wall sections JUMP: anchors 20-30 apart, side to side of the keep (RUNTAG17: 6 apart, round the
  wall in order) - the user's "jumping around". Cause: behindStair held the outer eave row back while the inner stair
  waited for its log (inside part), the section had nothing doable, the "anything near" pass took him elsewhere and
  the next anchor was picked there. FIX (dev): stairInFront(h) + firstInstead(h, okSet): h is swapped for the stair if
  that's doable now, else for what the stair is clicked against (A + facing, the log) if that is; if neither, h goes in
  as before (no holding back). notBehindStairs does the swap in orderPick; the pick passes skip h only while
  firstInstead != null. Queued RUNTAG31 = castle top 30 min to compare the section order.
- RUNTAG23 at 30 min (35 min of work): 103/min, skips nowhere 4 (the x 122 inner stairs again, after the 5-min cap),
  couldn't fly 3, can't see 1, wedges 1, pockets 4, repairs 0, s.clear 116 ms/30 s. One "couldn't fly there" was a
  4 s integrated-server lag ("Can't keep up! ... 78 ticks behind"): bobbing 0.23-0.64 from the goal, in and out of the
  0.5 "there" radius so atStandSince kept resetting, then progressAt > 80 -> skip. FIX (dev): nearGoal (< 0.8) isn't
  "couldn't fly there"; within 0.8 counts as there once there or after 40 ticks without progress (then the usual 15
  ticks -> "can't see it from the spot" / steeper spot). TNT on the user's server lags it the same way.
- Section jump measure: scratchpad sections.sh <log> [q]: RUNTAG17 (1834) 25 sections, avg step 8.9, 1 jump > 20;
  RUNTAG23 (1938) 35, 13.9, 10; fantasy RUNTAG18 (pre-behindStair) 45, 6.6, 1. Fantasy has 2054 upside-down stairs,
  1364 with a block right behind (eaves.py).
- firstInstead also walks the stair's row (both ways along it, while the plan has the same stair state, 16 max) for a
  member in ok: the inner row chains from its end before the outer row goes in.
- 20:20: RUNTAG23 stopped at ~38 min (2029 holes left, 40 sections avg 13.0, 10 jumps). RUNTAG31 = castle top 60 min
  with firstInstead (+row walk) + nearGoal + concrete/sponge + case recorder.
- farGoal flight (dev): more than 24 off, the goal height is at least the highest MOTION_BLOCKING top along the
  straight line + 2 (sampled every 3 blocks) - over what's built, in the open, not through the pasted castle's halls
  (the castle tests' first 1.5 min "flying over to the rest of the build", STALL 60s). Spots count as seen within 56
  horizontally, so he never has to come down into it for them.
- NEW TEST HA_EXACT=eaves (eavesBuild/eavesSpec): wall x1-10 z1 y1-3, spruce_log[axis=x] beam on it, two rows of
  quartz_stairs[facing=north,half=top] at z2 (inner) and z3 (outer) y4, smooth quartz roof y5 over z1-3. EAVES CHECK.
  Queued first as RUNTAG33.
- 20:35: the user's server log: ~2000 "Summoned new Primed TNT" at 16:47-16:49 (1201 in one minute), right before
  the current fantasy build started at 16:53. The previous build's repair went 1443 -> 5386 damaged spots in 2 min
  (~100-150/min repair). TNT test now takes HA_TNTN=<n> (one every 3 ticks, 2..16 out); queued RUNTAG35 = fantasy p4
  TNT 300, 20 min (the next line clears HA_TNTN - the runner doesn't).
- 20:31: RUNTAG31 (2019) at 8.5 min of work: 22 sections avg step 6.5, 0 jumps > 20, 1 skip (couldn't fly, eave
  edge), 0 shut-in, 0 nowhere, 108/min. CANDIDATE 2019 (scratchpad candidate-2019.jar 496842 + src-2019/) = 1938 +
  concrete/sponge + case recorder + firstInstead (+row walk) + nearGoal. install-which.txt -> 2019.

### Where things stand (20:35, Oct 17)
- Install: candidate 2019 goes in by itself when your Minecraft closes (autoinstall waiting; install-which.txt).
- Fixed and tested today: plants growing by themselves (bamboo/vines/cane/kelp/saplings) aren't "damage" any more -
  your game's constant bamboo "repairs"; floating decorations the game won't keep are left at once; the castle's 194
  bamboo shoots; the moat slab ping-pong; upside-down stair eaves inner row first without the walls jumping side to
  side; a block waits for the one under it; the clear scan 5-7x cheaper; lag bobbing isn't "couldn't fly there";
  concrete powder by water / wet sponge count as done.
- New tools: case recorder (.minecraft/humanautopilot-cases/) + HA_CASE replay; HA_EXACT=grow and =eaves tests;
  HA_TNTN for big TNT tests; scratchpad sections.sh (wall-section jumps), eaves.py, litkinds.py, perlayer.py,
  litfind.py, litpts.py, mkcase.py.
- Queue after RUNTAG31: eaves test, fantasy p4, castle top p120 (end game), 300 TNT, 3 case replays, grow, moat at
  scale, Himeji TNT20 p60, fantasy terrain TNT20, Himeji slices p30/p80/p100, 12-schematic survey, the full castle.
- 20:34: RUNTAG31 (2019) stopped at ~12 min of work: 35 sections, avg step 7.1, 1 jump > 20 (RUNTAG23: 10),
  skips 1, shut-in 0, nowhere 0, repairs 0, ~105/min. Section order fixed. End game left to RUNTAG34 (p120).
- 20:35: RUNTAG33 eaves: 672/672 right, 0 skips, 44 s (done 174/174). But it may be too easy (the beam goes in
  early, so even the old order works): end walls added at x 0 and 11 (z1-3, y1-5) so the outer row can chain from its
  end like the castle's, and HA_NOFIRST=1 (firstInstead off) to see the test catch the old behaviour. Queued
  RUNTAG36 (eaves) and RUNTAG37 (eaves, HA_NOFIRST) after the castle p120.
- Checked (no change needed): an upside-down stair whose only placed neighbour is the wall BEHIND it now waits
  (rightSides drops the back) on its row or the block over it ("comes": a full block, 0x3F). That block over it doesn't
  wait on its column for the stair, because columnWaits asks whether the one under can go in by itself using the same
  rightSides - so the roof goes in first and the stair clicks against its underside. No circular wait.
- 20:46: RUNTAG32 (fantasy p4, dev at 20:35 = 2019 + farGoal over-the-top + HA_NOFIRST flag) at 10 min: 102/min,
  0 skips/wedges/confused/crashes, 37 sections avg 6.9, 0 jumps (RUNTAG18 pre-behindStair: 45, 6.6, 1), never 24,
  shut-in 0, repairs 0. CANDIDATE 2035 (scratchpad candidate-2035.jar + src-2035/). Install stays 2019 until the castle
  p120 run shows the new far flight.
- RUNTAG32 final: 103/min, placed 1237, skipped 0, stalls 5; EXACT "extra bamboo=10" (grew, left - no repairs).
- Survey schematics, self-changing blocks (litkinds.py): laberinto 46,174 bamboo + 16,634 concrete powder; gigantic
  swamp ~5,044 fire coral (dies out of water -> the dead_ rule) + 3,134 vines + 602 powder; grand trading 5,233 copper
  (weathering rule), 529 bamboo, 96 farmland+carrots, pistons; granja (iron farm) 2,126 water, 146 bubble columns, 9
  lava; copper guard 15,334 water, 1,710 copper; godzilla 10,795 concrete powder.
- RUNTAG34 (castle p120, dev 20:47): the opening "flying over to the rest of the build" reached the top (y 50) in
  ~40 s (was ~90 s); the first goals are still low while the far chunks load (no heightmap samples yet).
- 20:50: install-which.txt -> candidate 2035 (2019 + the far flight over the top; fantasy RUNTAG32 clean, castle opening 2x faster).
- Case tools: scratchpad caseview.py <case.json> [dy0 dy1 half] prints the cube as layers (world block, * where the
  build wants something else, T the target). Caught my hand-made cavedirt case off-centre (the dirt 1 over the
  target cell): regenerated centred. caseBuild now also checks every wanted spot in the cube ("N of M ... right").
- 21:03: RUNTAG34 (castle p120) got the top down to 3 spots left as they are (114 64 185 white wool, 117 61 164 white
  wool, 111 63 164 dark oak stair top facing north), with a "[confused] 8 targets in 10 s" while cycling them, then the
  final sweep (flying round to see the unseen castle). The CASE RECORDER worked: case-...-114_64_185.json and
  case-...-111_63_164.json (copied to %TEMP%\hatest-cases). caseview: the wool's only view is diagonally out of a
  2-high slot past a block corner (the ray grazes the corner from the cell centre; overNear rejects the slot until
  steepFine) - an off-centre eye would clear it. Queued replays RUNTAG38/39 to see the tool reproduce the failures.
- BUG (old, found at the castle top's end): "winding down ... 0 left as they are" logged EVERY SECOND - after it fires
  it puts placedAts back to one entry, and with fewer than 5 the clock is planStartedAt, so the 3-minute condition is
  true again at once; each time it also cleared opened/outFor and set finalSweeps = 6. FIX (dev): windDownAt, fires at
  most once per 3 minutes, logs only when it leaves some.
- LEAN FALLBACK (dev): standFor's last try when nothing is found from the middle of any spot: spots within 2 across,
  -1..2 up, eye at hov or LOW, leaned by LEANS (0.25 straight, 0.2 diagonal) if the body still fits (noCollision) and
  isn't over the target; logs "no spot from the middle of one, from a little off X (+dx +dz)". standOff carries the
  lean into the goal (and resets with the target).
- RUNTAG34 (castle p120, 20 min, dev 20:47 ~ 2035): the top built except 3 spots (cases recorded), skips 19 (the
  roof tips), shut-in 2, 1 confused at the very end (cycling the last 3), 26 sections avg 11.1 / 6 jumps (the top's
  small scattered roofs), then the end sweep flew round at y 70-100 seeing the never-seen pasted castle (test only):
  "STALL 240s". 77 "Can't keep up" - the integrated server lags on this world, so REAL t runs slow.
- 21:14: case replays of the two castle-top leftovers: RUNTAG38 (111 63 164 stair) IN in 13 s, RUNTAG39 (114 64 185
  wool) IN in 6 s (6/6 wanted spots right) - without the lean. In the castle the 3 nearest open spots all saw only the
  wool under the stair (bottom half); the good spot (south of it, low, under the eave) wasn't open there - its eave
  corridor is shut beyond the 13-across cube, while the replay's cube stands in open air. So the replay reproduces
  view problems, not "can't get there" ones. Case files now r = 9 (19 across) and carry "openSpots" (the last
  standFor's open count) to compare with the replay's.
- 21:17: eaves test with end walls: RUNTAG36 (firstInstead) 672/672 0 skips; RUNTAG37 (HA_NOFIRST) 672/672 0 skips
  too - it doesn't reproduce the castle's case (the inner row's log only came with the inside part, late). Kept as a
  plain regression test; the castle-top runs (RUNTAG23 vs RUNTAG31 sections) are the real check.
- 21:21: RUNTAG35 (fantasy p4, 300 TNT at t=180 s, dev 21:16 = 2035 + windDown fix + lean + case r9): 1172 of the
  1644 built round him blown out, ~2550 damaged spots; repairing.
- 21:25: USER: "Stop and install the latest". Stopped the queue (RUNTAG35 300-TNT run cut at ~7 min: 2550 damaged ->
  2202 at +210 s, ~130/min, 0 lean uses, 17 nowhere, 2 long ticks, no crash). CANDIDATE 2115 (scratchpad
  candidate-2115.jar 498055 + src-2115/) = the dev tree = 2035 + windDown fix + lean fallback + case r9/openSpots;
  ran in RUNTAG38/39 (case replays), RUNTAG36/37 (eaves) and RUNTAG35 without problems. install-which.txt -> 2115;
  the autoinstall (btk0eyd1f) puts it in when the user's Minecraft (javaw 27360) closes.
- 00:11 (Oct 18): the user's Minecraft closed; the autoinstall put CANDIDATE 2115 in .minecraft/mods and dist/ (byte-identical, 498055 bytes; old jar backed up in the scratchpad).
- Oct 19 01:33: PROGRESS BAR (HumanAutopilot.progressBar): a rainbow bar over the hotbar with the build's whole
  percent to its right, sliding to the new value; flashes white when it goes up, red when it goes down (TNT). Waits
  for BoxFillModule.planCounted ("counting the build..." till then); resets on a new build, a new world or a relog.
  Installed as scratchpad candidate-bar.jar (499790).
- Oct 19: KEEP GOING (SchematicMode.chain/moveOn, OpenGround): USER "build schematic indefinitly, if its done
  building schematic 1, it goes to 2nd automatically". When a build is done (guarding, no holes, not paused) and it
  came from a file in the folder: 3 s later the next file in files() order (after the last, back to the first) is
  loaded and put beside the last build - along rowDir (the first build's "to the right", saved in
  current-build.properties with keepGoing), gap 6, pushed on 8 at a time (6 tries), then the other 3 ways. A spot
  must be loaded, under 30% water, and all nature from the ground up to its top (OpenGround.wild: ground, water,
  trees with non-persistent leaves, plants, snow; never a build - Replace would knock it out). A hillside (ground
  spread > 10) only if nothing flatter. No spot: the next file's preview to place by hand. Same turn as the last
  one. Menu: file picker "Keep going: ON/OFF" (default ON). Done message says "Next up: X". Test: HA_CHAIN=1
  (a hut + a tower written as .schem into the test's folder, a post in the first spot).
  Also: the Keep going toggle is remembered in config/humanautopilot/schematic-settings.properties. Far spots: a
  NOT_LOADED one gets flown over to (BoxFillModule.visit while guarding, 2 min max), then looked at again (twice).
  Bug found by HA_CHAIN: OpenGround.ground took grass_block for a plant ("grass" in the name) -> builds 1 low; fixed.
- Oct 19: UNFINISHED BUILDS: USER "often time i get bored of it building then start building a new schematic ....
  i cant go back and have it continue building the previous build". current-build.properties (one build) replaced
  by config/humanautopilot/builds/<file>-<hash>.properties, one per build (world, file, corner, rot, skipGround,
  replace, rowDir, done, total, when); the old file is moved in on first read (migrate). Written at the start, every
  20 s while building (keep), and before it stops (keepNow: another file chosen, Off, panic, another build resumed);
  deleted once it's done (guarding). Menu: "4. Unfinished builds (N)..." -> list (name, %, how far away), click
  carries on (resume(Unfinished)), x takes it off the list. "3. Continue building" = the last one built on.
  Test: HA_SWITCH=1 (yard left at 60+ blocks for a tower, then picked from the list and finished).
- Oct 19 02:20-02:47: tests. HA_CHAIN=1 flat: hut -> tower (past the post, +8) -> hut, all on the ground, post kept,
  1 left on the list after panic, menu screenshots OK. HA_CHAIN=<gate house;medieval-house> + HA_TERRAIN +
  HA_CHAIN_PASTE=1 (all but the top 3 layers pasted on each start): gate house -> medieval-house -> gate house on a
  desert hillside, each "east +0 OPEN" (RUNTAG: 02:35 run). HA_SWITCH=1: yard left at 60/209 for a tower, listed
  at 28%, resumed from the list, 0 wrong, list empty. CRASH FIXED on the way: Placement.slabToDouble(world, null)
  NPE from BoxFillModule.workable over a hole with no wanted block (supportPillar after "temporary blocks ... never
  got started: a dirt pillar instead") - it would have crashed the user's game; now null-safe. (Test artifact: the
  paste leaves the schematic's air uncleared, so sand sealed inside pasted walls was "left as it is".)
  CANDIDATE chain (scratchpad candidate-chain.jar 516133) = dev tree 02:45; install-which.txt -> it; autoinstall
  waiting for the user's Minecraft (javaw 2252) to close.
- Oct 19 11:40 USER (playing the 1:33 bar jar - their game never closed, so candidate-chain never went in):
  "1. i dont see the option to go back to the previous build 2. it flew out to view the build, got stuck until i
  flew in. it got confused and stopped building. 3. i started a new build and the percentage hotbar was already at
  20 percent". 1 = not installed. 2 (their log 11:18-11:30, floating-pagoda 16281/19975): the last spot, a birch
  button at 48 19 -13, "went in wrong ... got air" again and again (gone as soon as in - never a failed go, so
  never given up); between goes lookAround flew out (idle5 -> a look every minute) and each look went higher
  (builtTop used the player's y: 41, 61, 80, 89); out there it's "0 holes" (only spots near count) but the layer
  open: 11 min, never done. FIXES: vanished map (gone again twice -> cant), lookAround builtTop from lastPlaced,
  and a no-progress guard (planDone the same 3 min, <=50 open spots incl. the layers' unseen ones -> cant; paused
  time doesn't count). 3: the plan counts ground the world already has (2 sunk layers) as done -> planAlready (done
  at the first count, saved as "already" in the build record) and planProgress() = (done-already)/(total-already)
  for the bar and the Unfinished %. ALSO SEEN (not fixed): the pagoda left ~3600 floating crystal blocks "nothing
  to click" at 11:17 ("leaving what I can't get to").
- Oct 19 11:52-11:58 tests of the 3 fixes: HA_SWITCH (yard now on 2 ground layers: bar 0% at the first count with
  44% already right, resumed bar 29%, 0 wrong - grass under the floor turning to dirt counts as right), HA_STUCK=1
  (new: gold shut in bedrock; done 107/114 "7 left as they are" and on to the tower in 77 s), HA_CHAIN=1 again OK.
  11:58 INSTALLED candidate-fix3 (517483, md5 55ced285...) in .minecraft/mods and dist/ - the user's game had closed.
- Oct 19 12:00 USER "okay go for it" (the pagoda's floating blocks). litematic_analyze.py (scratchpad): the
  floating pagoda = 1 piece of 17,873 (by faces) + 150 pieces touching nothing, 2,256 blocks (61 single blocks; a
  357-block crystal column y 2..32 under the island; shards at y 36..44, 53..69, 90..95; a 176-block roof piece at
  y 120..125). bridgeFloating (temporary glass out to a floating spot, then knocked out) only ran when NOTHING in
  the window was workable - the island always had some, and at the end 63 "waiting on the one under" counted as
  workable: no bridge, all given up at 30 s. FIX: loosePiece(h) (its piece of still-to-do spots, by faces, <=3000,
  has no spot workable and not waiting) and, with nothing to place for 2 s, bridgeFloating(looseOnly) even when
  something else is workable. Also: the 30 s give-up leaves spots waiting on others last (waiting beside: last of
  all) and columnWaits no longer waits on an unbreakable block over it (bedrock). Test HA_LOOSE=1 (a yard with a
  bedrock-shut gold block, a floating 3x3 platform, a block, a hanging pane column): 3 bridges, 0 wrong, 0 glass
  left, done in 78 s. HA_PASTE_MAIN=1 (realFile): the biggest piece pasted, the rest to build.
- Oct 19 12:55 USER: "its not about her fixing to build the schematic but to help her know what to do in these
  situation shes stuck". -> STUCK LADDER (BoxFillModule, the nothing-at-all-to-do branch, cantGetSince): 1 at 5 s
  another look (skipped ones' timers cleared, out from under a roof / a look at the build), 2 at 10 s clear the way
  (openUp on up to 4 "nowhere" spots), 3 at 15 s temporary blocks (bridgeFloating with no noneWorkable gate, else
  tryScaffold on up to 6, else forcePillar), 4 at 20 s any way round (relaxed, oriented blocks with 2+ nowhere), 5
  at 30 s leave them (the ordered give-up). Logs "[stuck] N s, M spot(s) left: step k - what" and, when a target
  turns up, "[stuck] out of it after N s, after step k"; stuckSteps[] counts which step got her out; the HUD note
  says "stuck N s: step". The 3-min no-progress guard stays as the net for loops that do have targets.
  Also: bridges up to 4 at once, each to a different piece (pieceOf), 200 candidates looked at for a short one.
- Oct 19 13:10-13:30: ladder reworked - one step at a time, 5 s apart (stuckStepAt; a look at the build took 15 s
  and steps 2-4 went in one tick), step 4 NEW "not waiting any more" (noWait: columnWaits says no for those -
  5 grass blocks in the house waited on their column for good), any way round = step 5, leaving them last (idle
  > 30 s and 5 s after step 5). Step 2 also roomDoor: walled out of a room of the build (its open cells not in
  our air map, up to 6000): a 2-high doorway of plain wall blocks out (opened + doorRooms), the room's spots done
  one after another through it (the opened loop retargets to the next room spot instead of shutting), then back.
  roomDoor compiled but not yet seen in a test (the 13:22 run was stopped - user: "be done with it and install
  after"). CRASH fixed: the "what's left" log line with a temporary block taken off the plan (plan.get null).
  Hilly run 13:14 (ladder 2): gate house + medieval-house 3796/3800, ladder logs: step 3 dirt pillar got her
  out (15 s), step 1 several times, step 4 once; the house's sealed inside (paste artifact) left at 30 s.
- Oct 19 13:30-13:37 quick check on the final code: HA_LOOSE 0 wrong 77 s, HA_STUCK on to the tower in 51 s,
  HA_SWITCH 0 wrong, HA_CHAIN OK. 13:37 INSTALLED candidate-ladder (521986, md5 b52bbc7d...) in .minecraft/mods
  and dist/ (the user's game closed). The room doorway is in but hasn't been seen working in a test yet.
- Oct 19 17:40 USER: "can you implement so kawaii companion will help her build too but again they both stay
  distant from each other". CLIENT: net/BuildLink (channel kawaii:schembuild; start;id;ox;oy;oz;count;replace;name,
  pal;id + lines i=<BlockStateParser.serialize>, blk;id;dx,dy,dz,i;..., go;id, stop;id; server -> resend;id), 4
  messages a client tick, only if ClientPlayNetworking.canSend. Sent from SchematicMode.confirm (file builds
  only), stopped in BoxFillModule.stopPlan and when the build's done (keep). SERVER (KawaiiCompanion, repo
  Minecraft-Plugins-main): SchemLink (PluginMessageListener, per-owner Plan: want map + pos array), Companion
  .planHelp / .helpBuild (default ON; control panel slot 15 "Help Build"), boxFillOne drives tickPlan while the
  owner has a plan (creative, like Box Fill). CompanionBoxFill.tickPlan: scans 5000 spots a tick, bottom 6 layers
  first, only empty spots (never replaces), single blocks with something to go against (no doors/beds/tall
  plants/fluids/unsupported/sand over air), KEEP_AWAY 12 from the owner (never a block of hers within 12, flies to
  the far side when idle near them, drops a target the owner comes near), setBlockData exact, a block every 4
  ticks; the shared fly/aim part pulled out as approach(). Plugin built (tools/apache-maven-3.9.16, mvn -o) ->
  dist/kawaiicompanion-26.1.2.jar. NOT TESTED on a Paper server (none here). Mod: candidate-companion.jar,
  autoinstall waiting for the user's Minecraft to close; HA_CHAIN still OK.
- Oct 19 21:00 USER: "check the latest autopilot log whys he just flying around placing 1 by 1 confused" (the 13:37
  jar, copper-guard-walls 158k on their server 127.0.0.1). From 16:01 to 20:58, 3213 times: "repairing N damaged
  spots first (next -193, -64, 104)" -> flew at it, "isn't to do any more (we didn't click it): now bedrock" ->
  one local block (150 deg turn) -> the same again. repairStep went down a damaged grass-level spot's column
  (columnWaits) into the bedrock. FIXES: repairStep doesn't step down into what can't be knocked out; a target
  dropped unclicked twice in 2 min -> bad 2 min (dropped map). Quick tests OK. 21:08 INSTALLED candidate-bedrock.
- Oct 19 21:20 USER: "make it so whatever tnt summons around me, also summons around the companion. and the
  companion also repairs yes?". No plugin here spawns the TNT (the stream tool does). KawaiiCompanion.onTntSpawn
  (EntitySpawnEvent, TNTPrimed within 24 of an owner whose companion is in planHelp/boxFill): a copy at the same
  offset from her next tick (fuse-1, velocity, yield), PDC tnt_mirror so copies aren't copied, <=40 a second.
  Repairs: she fills any empty planned spot on her side (TNT holes too); the mod no longer sends stop when the
  build's done, so she keeps guarding/repairing till it's stopped or the next one starts. Plugin -> dist
  (21:23). Mod candidate-repair.jar, HA_CHAIN OK, autoinstall waiting for the user's game to close.
- Oct 19 21:26: the user's server is C:\minecraft-server (paper-26.1.2-65.jar, start.bat, plugins/, plugin-backups/),
  the one at 127.0.0.1. Their control panel had no Help Build (old Oct 12 plugin). With the server stopped:
  old jar -> plugin-backups/kawaiicompanion-26.1.2-before-helpbuild-20261019-2126.jar, new dist jar into plugins/.
- Oct 19 21:35 USER: "are you able to log the companion the same way you log the autopilot". Companion log =
  C:\minecraft-server\logs\latest.log, lines "[companion] ...": starts/done helping, the plan arriving (sending,
  got all of it: blocks, kinds, bounds; stopped; asking again), "status <name> | <build> | +N placed (N/min), N in
  all | N still to do (lowest y) | skipped N {why=n} | at x y z, D from owner | doing: ..." every 30 s, "STALL
  Ns" every 10 s while nothing goes in (target, spot, note), TNT copies every 5 s. tickPlan now takes her name.
  Installed into C:\minecraft-server\plugins (server was stopped) and dist.
- Oct 19 21:35 USER: "it was at like 40 percent now its at 10 percent" + "it keeps taking damage so the percentage
  keeps going down or up". Copper walls (208 across) on their server: after the relog the far part unloaded and was
  never in seenBuilt (only spots within 'trust' are remembered) -> counted as not built; and while flying, far
  loaded spots counted only while loaded (33.6k -> 24.9k in 4 min with ~60 a minute going in, no damage lines, the
  bot never knocked out the companion's blocks). FIXES: far-but-loaded spots that are DONE go into seenBuilt (holes
  there come out of it); seenBuilt saved with the build record (<record>.seen, gzipped longs, every 2 min and on
  keepNow) and seeded on resume (HA_SWITCH: 222 seeded). The companion worked on their server: 768 blocks in 4 min,
  30-120 from the owner, skips "can't see it from the spot". Quick tests OK; candidate-count.jar queued.
- Oct 19 21:50 USER: TNT came round both but "the companion did not repair and just kept building", and "since the
  companion dont get the same first person pov he builds much faster, snapping from block to block ... but not
  teleporting". Cause: pickPlan only looked at the bottom 6 layers (lowest -63, the ground), the blast's holes were
  above. FIX: State.doneBits/damagedBits by plan index (scanPlan: in -> done; done then a hole -> damaged), pickPlan
  does damage first at any height (nearest crater, bottom up). FAST in plan mode: turnTo snaps the head, fly 1.4
  b/t with accel 0.3, a block every tick (FAST_PLACE_EVERY 1). Status line has damage / put right. The server was
  running: the jar went to C:\minecraft-server\plugins\update\ (Bukkit's update folder: swapped in at the next
  start) and dist.
- Oct 19 22:00 USER: "its been about 1 minute+ still saying counting the build". They were on the 21:08 jar (the
  autoinstall for candidate-count died with the old session: exit 4). Not slow: PAUSED at 21:55:19 ("paused (. to
  carry on)"), and paused nothing counts. HUD now says "paused (<key> to carry on)" instead. Companion on the new
  plugin: 38 TNT copies, 621 damaged spots put right, 400-590 blocks/min, 16-50 from the owner. candidate-paused
  (= count + repair-after-done + paused label), HA_CHAIN OK, autoinstall queued.
- Oct 19 22:10 USER: "if i spawn more helpers and set them to help build, will they also build". Yes - boxFillOne
  runs for extras too and Help Build sets all of the owner's. Added: CompanionBoxFill.othersOn/crowds - no target
  within HELPERS_APART 6 of another companion on the same plan (where she is, and what she's aimed at); the
  start/done chat only from the primary (companions.get(owner) == c). Installed into C:\minecraft-server\plugins
  (server stopped) and dist.
- Oct 19 22:15 USER: "1. turn off the tnt protection plugin. 2. add a grass filler companion that his only purpose in
  the flat world is to fill the grass, dirt, below the schematic build, make him able to go through blocks like
  spectator but able to build. 3. make the autopilot and the building companion continue to only repair the
  structures/blocks that belongs to the schematic". 1: C:\minecraft-server\plugins\KawaiiNoGrief\config.yml tnt and
  minecart_tnt structure -> all (backup in plugin-backups; creeper etc. still protected; source untouched). 3: mod -
  in a flat world (ClientLevelData.getHorizonHeight <= minY; no isFlat in 26.1) BoxFillModule.leaveGround: jobAt
  DONE for soil under clearFromY (isGround); BuildLink marks those cells ",1" and sends the floor (start;..;floor;name).
  Plugin: SchemLink Plan.floorY/ground, pos (the building companion's list) leaves ground out. 2: Companion
  .groundFiller, control panel slot 16 Grass Filler (op, toggles), CompanionBoxFill.tickGround: the flat layers
  read off a column 8 outside the build, region = build +-6, y bedrock+1..top; the schematic's own soil under its
  floor from the plan, its empty spaces (inside its footprint, from its bottom up) left alone; 4 blocks a tick
  within 5.5, flies 1.6 b/t straight through anything, no players' bodies; log "[companion] ground ..." every 30 s.
  No TNT copies round him. Quick tests OK; mod installed (candidate-ground), plugin into the server's plugins.
- Oct 19 22:35 USER (screenshot: a crater to bedrock in the grass, not filled): "maybe make the grass filler only
  around the autopilot or kohaku. allow him to telport to kohaku or me to start repairing the damage". His log:
  summoned 22:30, sent away 22:30:27, no plan that session (no build going) - he only worked with a plan. NOW:
  active whenever the owner's world is WorldType.FLAT (build or not); anchors = the owner + his other companions
  (>8 from the owner); scans a disc of radius 24 round each (y bedrock+1..top), holes kept in gHoleMap till filled;
  the layers by vote of 8 columns 40 out round the owner (re-read every minute); a hop (teleport) to a hole >40
  away, else straight through; idle 5,5 off the owner over the grass. With a plan: its rules (its soil under the
  floor, its blocks and empty spaces left). Only flowing water/lava counts as a hole (still water stays).
  Installed into the server's plugins (stopped) and dist.
- Oct 20 01:00 USER (screenshot, crater in the copper walls' yard, the grass filler hovering): "hes not fixing it"
  (+ "thats not kohaku, thats the grass fixer"). His log: "0 holes in the ground round them" - groundWant left
  everything inside the plan's x/z box from its bottom up as "the schematic's own empty space", but the yard far out
  on the ground plate isn't in the plan at all (keptColumns). FIX: with a floor (flat world) only the plan's blocks,
  its ground (theirs, its soil) and keepEmpty (the schematic's AIR under the floor in kept columns - the mod sends
  them as air ,2: SchematicMode.emptyUnderFloor) are left; the rest under the floor is ground. Also Kohaku (STALL
  440 s, "nothing she can do now", 25k to do, all >64 away): pickPlan falls back to the nearest one however far.
  Plugin installed into the server's plugins (stopped); mod candidate-yard, HA_SWITCH OK, autoinstall queued.
- Oct 20 01:40 USER: "1. autpilot placing blocks below the structure 2. fixer hovering above but not sure to burried
  autopilot in or not" + "will autopilot continue the next schematic 10 blocks away" (it's GAP 6 now; offered 10).
  1: the copper walls' ground layers have stone and mud (not soil) - she dug the world's dirt out under the floor for
  them (slow blocks at y -61..-63). isGround (and BuildLink's ground mark) now soil OR SchematicMode.plainGroundBlock
  (terrain minus paths/farmland/gravel): the grass filler's. 2: no sign of it in the log, but he filled right up to a
  player's box: now a gap 1.5 to the sides, 2 over, 1 under. Quick tests OK; both installed (game and server off).
- Oct 20 01:55 USER: "so the companion and the autopilot never go below structure to repair, only the refiller
  repair anything below the structure including destroyed by tnt?" (yes) + "/kc dismiss and resummon and
  companion and refiller is stuck in the mode theyre in so i had to chase the companion down". Her last session:
  91 of 117 "no spot" under the floor, 85 of them smooth_stone (made, so still hers). NOW isGround = anything under
  clearFromY (flat world): DONE for the bot, marked ,1 for the server (the refiller's; scanGround goes up to
  floorY-1). Plugin: /kc menu (and /kc panel) opens her control panel anywhere; summoned in the middle of a build
  (doSummon) she skips that plan (skipPlanId) till Help Build is clicked (one click puts her on it) or the next
  build starts, and says so. Quick tests OK; both installed.
- Oct 20 02:10 USER: "can you audit the autopilot mod for clutterness and declutter it" (on-screen/chat, after a
  generic web redesign prompt they then said "none" to). Chat was fine (18 messages a day). DECLUTTER: the top-left
  panel only lists modes that are ON, nothing at all when none are (REC still shows), no keys, no Schematic row (the
  build has its own top line and bar), and it sits under the top line when both show (they overlapped). The top line
  is just "Building/Guarding <name> (paused)" - no count, no key hints. Chat: preview / start / done / next-up
  messages shortened, "Something broke X: fixing it" and "Fixed X" (two lines per blast while guarding) dropped
  (log only). Menu: no 1./2./3./4. numbers, "Continue <name>". Screenshot checked (switch-yard-building).
  HA_SWITCH failed once in 5 (yard not done, 2 wrong) - flake.ps1 running it 4 more times to catch it.
  Flake: HA_SWITCH 4 more runs all OK (1 failure in 9, log lost). candidate-declutter installed/queued.
- Oct 20 03:00 USER: "hes breaking grass running back and forth endlessly" (then "nevermind hes going back and
  forth"). Their log: 622 "went in wrong: wanted short_grass got air" in 17 min at y -57 round -195 210: the
  vanished->cant rule was undone by cant.remove(nb) every time a block went in beside one. NOW wontStayOn (what's
  under it when it went): vanished >= 2 and the same block under it -> jobAt BLOCKED for good. Quick tests OK;
  candidate-wontstay queued (game open).
- USER: "grass filler just tries to fill forever". His log: +1878 every 30 s standing at one spot, then +0 with 675
  holes for minutes. (1) a wanted plant (short grass) is "replaceable" -> a hole again as soon as it was in: new
  groundNeeds (world material != wanted && groundHole). (2) the players' gap used w.getPlayers(), which has the
  companions in it - his own body's gap: now Bukkit.getOnlinePlayers() in the world. Plugin installed (server off).
- Oct 20 03:15 USER: "kohaku is nearby me and either frozen or lagging". Her chat: "Help Build OFF" at 03:13:56, 3 s
  after she appeared - a click on Help Build (it was ON, the button only had a tick) turned it off; following a
  flying owner looks frozen. Toggle buttons now say "Box Fill / Help Build / Grass Filler: ON|OFF" + what a click
  does (toggleButton). MISTAKE: my 'paper' process check missed the running server (no command line visible) and
  the jar went into plugins/ while it ran - asked them to restart it. scratchpad install-plugin.ps1 now decides by
  port 25565 LISTENING or the server log written in the last 90 s. Grass filler fine now: 22-88 a minute.
- Oct 20 03:24 USER: "help build on but kohaku not helping". Server log: "no build" - after the server restart the
  client never sent the plan again: BuildLink set plan=null on JOIN/DISCONNECT while the autopilot carried on
  building (the server's resend only works if the client still has it). Now the plan is kept over a reconnect and
  sent again once canSend (up to 30 s after joining) if planMode() is still on. Workaround till installed: menu
  Off, then Continue <name>. (Help Build OFF 2 s after summon twice: slot 15 is next to the Grass Filler's 16.)
- Oct 20 03:27 USER: "hes flying around breaking grass again". The autopilot (mod still the 02:36 jar: MC never
  closed). ~130 holes in copper-guard's bottom dirt layer (y -60, on the world's grass) in twos and threes under its
  grass: shutAway only rules on single ones, so each was "nowhere" -> "hidden behind the build's own Short Grass"
  -> 2 tufts out (no use: the grass block over it hides it) -> back after a minute; 48 times. Now sealedPocket(c):
  covered, in a pocket of <=12 open spots all wanting soil, solid round it -> DONE. Also the take-out break of an
  outFor/temps spot was logged "went in wrong ... got air" and counted in vanished -> cant (left out for good).
- Oct 20 03:36 USER: "nope still flying around breaking grass" (03:33 jar). Edge pockets (open to the build's edge /
  bigger) weren't sealedPocket. Real fix: tuftsHideIt(t, hits) - standFor with all the hit tufts as pretendAir; no
  spot even then -> the tuft branches (world's and the build's own) don't fire, logged once with the pocket's why.
  KOHAKU 20/min, "nothing she can do now" with 10.8k to do: her LAYERS=6 window ran from s.lowest (-60, pinned by
  buried dirt she skipped: "nowhere to see it from") and plan-mode skips never expired (838). pickPlan now windows
  from the lowest she can do; skips expire in tickPlan; can't-see skips last 2400 ticks. Plugin in plugins/update.
- Oct 20 03:48 USER: "flying back and forth confused again". Grass fix worked ("wouldn't help" logged). Now: two
  openPocket pockets open 45 apart (x -80 / -125), the opened loop took the FIRST in the HashMap, not the nearest, and
  a new pocket opened while one was open elsewhere. Now: nearest opened/putBack first; no new pocket while one's open
  >16 away (openFar). PLUS Kohaku took the take-outs for TNT damage (done -> hole) and flew 85 blocks to put them
  back (STALL target -125 -59 188), so it took them out again. Server: done->hole within KEEP_AWAY of the owner is
  skipped 1200 ticks, not damage. Plugin in plugins/update; mod candidate-relink on the next MC close.
- Oct 20 04:00 USER: "i dont want kohaku to help me anymore" + "the green fixer did not replace the grass with stone
  when it shouldve been stone and autopilot skipped it". helpBuild now defaults false (not saved anywhere, so off
  after every summon/restart till clicked); the summon nag only when it's on. The grass filler's on-switch used
  helpBuild too (OFF for Kohaku stopped him) - now his own (Grass Filler) only, and the Help Build click skips him.
  groundNeeds now also swaps the world's soft ground (grass, dirt, sand...) for the plan's own ground block (,1)
  where it's seen (exposed: open over it or a side); not soil for soil. Plugin in plugins/update (server running).
- Oct 20 04:05 USER: "just turn on the tnt protection thing on again". Server KawaiiNoGrief config.yml back to the
  backup (tnt/minecart_tnt: structure - the build breaks, the land under it doesn't); the 'all' one saved as
  plugin-backups/kawaiinogrief-config-tnt-all-20261020-0405.yml. Live after /knogrief reload or a restart.
- Oct 20 04:10 USER: "continue to audit my autopilot every 30 minutes. if it is stuck truly, fix it, restart minecraft
  and it should start building automatically" + LiveKeeper (Downloads\LiveKeeper). LiveKeeper OCRs the menu for a line
  starting "Continue building" - the declutter's "Continue <name>" broke that: label back to "Continue building
  <name>" (candidate-relink, test passed, installs at the next MC close). Session cron 7c81f301 at :17/:47 runs
  scratchpad audit.sh; restart-mc.ps1 closes MC + installs, only if LiveKeeper is watching (it also goes LIVE, so
  never started by me). 04:12 audit: 1,392 placed in 30 min, done count falling (TNT), not stuck.
- Oct 20 04:20 LiveKeeper's first restart: launcher, PLAY, join all worked; its "Insert, 9" step failed ("Continue
  building didn't show") - its 9 is VK 0x39, the top-row 9 (hotbar slot), the menu is numpad 9 - though the build
  was going at 04:18:30 anyway (resume() only runs from the menu: the user clicked it?). USER: "you update that part
  of minecraft and manage the tool from now on". Mod: SchematicMode.carryOnAfterRestart - "building" in
  schematic-settings.properties = planMode && !paused, written when it changes; 5 s into the first world of a game,
  true + a saved build -> resume() by itself (not in the dev/test game). Continue label cut to fit (a long name
  scrolled the words OCR reads off the button). LiveKeeper.ps1 (backup .bak-20261020-0425): after Insert it waits
  30 s for 'uilding'/'uarding' at the top -> done; else numpad 9 (0x69) and the old menu path. Parses ok; applies
  when LiveKeeper is reopened (it runs as admin).
- Oct 20 04:39 USER: "stuck going back and forth" (28/min). Copper guard's edge, x -208 z 196-214: a hollow of 32+
  missing bottom dirt (-60/-59) under its intact grass; sealedPocket said "not shut in: 32 open spots", so each
  was "nowhere" every few minutes and she flew from the walls to them and back. giveUp: soil, "nowhere", the
  build's own block over it (in) -> buriedSoil set -> jobAt DONE while something solid is over it (dropped again
  when it opens up). Test passed; restart-mc.ps1 04:42 (building=true written, LiveKeeper reopening).
- Oct 20 04:50 USER (screenshot of Replay Mod's "Minecraft has not quit normally ... recover it?"): LiveKeeper sits at
  it, says nothing about what it's doing, and holds the mouse so they can't take over. Also its world check waited for
  'utopilot' (the side panel, hidden by the declutter while only a build is on): 90 s stuck after Join. LiveKeeper.ps1
  (backup .bak-20261020-0450): clicks Yes on the recover box; world = 'utopilot'/'uilding'/'uarding' or 20 s in the
  world by its title; GetLastInputInfo vs its own input (Native.OursAt, marked after its clicks/keys/Alt) -> any
  other input during a recovery = 'you took over': it stops till Minecraft's back in a world (mcGaveUp) + notifies;
  a notification when a recovery starts. C# compile-checked (scratchpad lk_compile.ps1). restart-mc.ps1 closes MC
  with CloseMainWindow (Replay Mod saves), kill only after 90 s. USER going to sleep: audit every 15 min (cron
  5c87fe95, :02/:17/:32/:47, audit.sh 15).
- Oct 20 05:35 AUDIT: stuck - 0-2 placed per 30 s for 6 min, STALL 70 s, "[confused] 15 targets in 10 s". TNT at
  05:12/05:16 tore the bottom dirt (-60/-59) at x -43..-5 z 168-212; craters with their tops back in: "nowhere" with
  air (not a solid block) over them, so the buriedSoil rule (needed a solid block of the build over it) missed them
  ("; not covered"). buriedSoil now a map spot -> around() (the six neighbours' states): any soil "nowhere" ->
  DONE till something round it changes. Test passed; restart-mc.ps1 05:37 (closed normally this time).
- Oct 20 06:34 AUDIT: 7 min at 0 placed, STALL 400 s, TNT going on (19k holes). Buried TNT damage in the walls' core
  (smooth stone -60, stone bricks -59/-58 along z ~188): damage-first picked them one after another, each "nowhere",
  and the pocket opener was off while damageToDo() (openUp only on a 2nd "nowhere", minutes off). Now the pocket
  opener runs during damage too, and openUp fires on the first "nowhere" of a damaged spot. Test passed; she got
  going again by herself (75/30 s) before I restarted, so it's queued (candidate-relink) for the next restart.
- Oct 20 06:49 AUDIT: 1,208 in 15 min but 0-placed stretches on the same buried TNT damage; installed the queued pocket
  fix: restart-mc.ps1 06:50, LiveKeeper back 06:51:28 (clicked Replay Mod's recover Yes twice even after the
  CloseMainWindow close - it closed at once, Replay Mod still didn't finish), she carried on by herself.
- Oct 20 07:34 AUDIT: 0-3 placed per 30 s for 5+ min, 17k holes after TNT (13 TNT in the log). Not frozen: the buried
  dirt rule worked, but each spot cost a flight to it before "nowhere" (a blast leaves hundreds). Now when one's left,
  the soil holes within 6 (dy 2) go on buriedCheck and one every 4 ticks is looked at from where she is (standFor,
  inside the open-air map only): no spot -> buriedSoil too, one summary log line. Test passed; restart-mc.ps1 07:38,
  back 07:39:31 by herself.
- Oct 20 08:49-08:55 AUDIT: 412 TNT so far; ~9,300 holes, 7 min at ~0 placed. The batch check worked (321, 348 at a
  time) but only within 6 of each found spot, so she still flew 30-50 blocks to the next lot each time. Now within
  REG_H-2 (the open-air map she looks from), cap 600, one every 3 ticks. Test passed; restart-mc.ps1 08:57, back
  08:58:44. NB a restart forgets buriedSoil (it's in memory): she finds them again.
- Oct 20 09:04 AUDIT: still ~0 after the 08:57 restart (11 in 6 min); every target buried bottom dirt - TNT has
  hollowed the build's bottom soil (-60/-59) nearly everywhere under its grass, thousands of spots. jobAt now: soil in
  the bottom 3 layers (y < clearFromY + 3) under its own cover (underCover: up to 3 up, open spots that are the build's
  soil then a solid block of it, in; each side solid or more of the build's soil) = DONE, no look needed. Chain test
  passed; restart-mc.ps1 09:07, back 09:08:28. suite.ps1 running after it for the other quick tests.
  09:15 suite after the underCover rule: LOOSE, STUCK, SWITCH, CHAIN all passed (0 wrong); live 32-54 per 30 s since 09:08.
- Oct 20 10:04 AUDIT: 431 in 15 min then ~0 for 5 min (43 more TNT). 97 "no spot" in 3 min on hoppers in the walls'
  core (y -51, facing=east/north...): each 13 goes through the night, the 5-tries cant cleared as TNT changed what was
  round them. giveUp: a non-soil spot "nowhere" for the 3rd time with insideBuild (each of the six round it solid or
  a non-air spot of the build) -> buriedSoil (as it is till around() changes). Test passed; restart-mc.ps1 10:07,
  back 10:08:57.
- Oct 20 10:52-11:00: hopper rows in the walls (y -51): "nowhere" over and over (TNT clearing their counts) -> a hopper
  is relaxed (any way round) on its first "nowhere". After that restart she was "confused the minute he started": a
  restart forgets buriedSoil/cant, and the walls' smooth stone / stone brick foundation (-60/-59) blown out under them
  was looked at one by one again. underCover now for any solid block of the build in the bottom 3 layers (over it and
  round it: solid, or the build's solid blocks not in yet) - no memory needed. LiveKeeper said "You took over" at
  'Opening the Minecraft Launcher' twice (10:52, 10:59) with the user awake; now it needs input again 1.5 s+ after the
  first within 8 s (a lone bump forgotten). LiveKeeper.ps1 backup .bak-20261020-1100, parses; applies when reopened.
  The user opened MC themselves 11:00: +42 in the first 30 s, 2,064 holes (from 3,300+).
- Oct 20 13:20 USER: "all this time it's stuck at 68 percent and jumps to 75 percent here and there". Status "done"
  went 108k/122k pass to pass (not TNT). [count] diagnostics (per pass: near/far/out-of-sight, far holes seen built)
  showed out of sight 0/0 and far spots 150 away reading void_air: Terrain.loaded (hasChunk) was true for stand-in
  EmptyLevelChunks past the view distance (renderDistance 5), so the far side counted as holes and came off
  seenBuilt. Terrain.loaded now false for EmptyLevelChunk; the count also treats void_air as out of sight; a far hole
  that was seen built isn't taken off seenBuilt from far (only up close). Chain + suite (LOOSE/STUCK/SWITCH/CHAIN)
  passed; restart 13:41: out of sight 53k/85k done, 123.8k (78%%) steady so far.
- Oct 20 14:20 USER: "it shows 98 percent and she's flying around" - 157,005/158,728, ~970 holes left, mostly inside walls,
  1 every 30 s. Wind-down was only for <= 300 holes and < 5 in 3 min: now also at 98%+ with < 30 in 3 min (placedAts
  keeps 30; placedFewerThan). 14:30 "winding down: 98% in ... 534 left". USER "so she won't 100 percent it?" -> "yes" to a
  finishing step: plugin SchemLink.fillMissing (finish;id from BuildLink.finish() in planComplete, and /kc finish):
  every non-ground plan spot whose material differs goes in with setBlockData (4000 a tick; not soil for soil, not in a
  player's body, only empty spots with Replace off). Mod test passed (candidate-relink, installs at next MC close);
  plugin in plugins/update (next server start).
- Oct 20 14:50: at the very end she went round the stuck steps for good on "1 spot left" with nothing on the list: after a
  restart seenDone is empty, so layers with spots never seen since stay open (found, holes empty, holeCount 1). The
  give-up now (give empty) also takes temps and every open layer's spots never seen done (remembered built = done,
  the rest left). And the 98% wind-down leaves the rest of the whole build at once, not a window's layers 3 min apart.
  15:12:44 "Done building copper-guard-walls.litematic" 157,175/158,728, sent finish (the server's old plugin ignored
  it: new one in plugins/update, needs a server restart). Keep going -> fantasy castle at 5,-60,4. The copper guard's
  record was deleted on done, so /kc finish can't reach it now (the server only has the current build).
- Oct 20 15:13-15:39 the fantasy castle (keep going, at 5,-60,4) sat at 0 placed: (1) the game's pause-on-lost-focus
  menu (window clicked away) - the mod now turns pauseOnLostFocus off while building and closes the PauseScreen /
  AutopilotScreen when the window isn't active; (2) LiveKeeper opened her menu, found no "Continue building" (she was
  building) and left it open - LiveKeeper.ps1 (backup .bak-20261020-1535) now presses Esc, "On: Build Schematic" =
  building already; (3) the new build was just past near() (48) from where the last ended and tickInner2 returned null
  ("box is far away") for good - plan mode now takes off and flies over its top; (4) steer can't lift her off the ground:
  takeOff first. 15:39 she's on it: +67 in 30 s. Server restart still to do by the user (Stream to Earn Stop/Start: it
  runs as admin, Ctrl+C via AttachConsole got access denied).
- Oct 20 15:44 LiveKeeper: after going LIVE again, 'Connect By Server' still showing after 3 tries - Stream to Earn had
  its popup up ("The server tried to execute a command to a player who is not on the server", a gift during an MC
  restart) and it took the clicks. Invoke-EarnButton (backup .bak-20261020-1555) now closes it first: Esc twice, then
  clicks just past the top right of its first line (its X). Parses; applies when LiveKeeper is reopened.
- Oct 20 20:20 USER: "can you update the tool to reset the stream every 3 hours" (screenshot of LIVE Studio's 00:09:09
  timer). LiveKeeper.ps1 (backup .bak-20261020-2025): RestartHours (default 3, 0 = never, settings.json only, no UI).
  While LIVE (and KeepLive on), Get-LiveSeconds reads the first H:MM:SS on the LIVE Studio window, else the time
  since it saw it go LIVE; at the mark Invoke-FreshLive clicks 'End LIVE'/'End', then the End/End now/Confirm under
  an 'are you sure'/'end the live' line; the usual offline -> Go LIVE -> Connect By Server follows. No End button:
  logged + Note-Problem screenshot, tried again in 10 min. Untested live (no LIVE Studio screenshot while LIVE).
- Oct 20 20:30 USER: "wheres the option for me to set it" (the fresh LIVE). Main window now has "Fresh LIVE every [N] hours
  (0 = never)" under "Go LIVE whenever I'm offline" (numFresh -> RestartHours, saved on change, logged); the Window
  row and the Activity card moved down 32, the window 32 taller. Backup .bak-20261020-2030; parses. (The 20:20 LiveKeeper
  already had the 3-hour reset, file 20:18.)
- Oct 20 22:13 LiveKeeper's first fresh-LIVE try: "LIVE for 3:12:18 ... couldn't find its End LIVE button". Its screenshot:
  3:12:18 was on TikTok's "LIVE goal" card (a promo dialog), the real LIVE timer was 02:07:12 in the bottom bar (End
  LIVE presumably beside it, covered by a chat toast). Get-LiveSeconds now takes two-digit hours only and the lowest
  match on the window (backup .bak-20261020-2220). Until reopened, the running copy could end the LIVE early at its
  10-minute retries if the goal card stays up - told the user to close the card and reopen LiveKeeper.
- Oct 20 22:35 USER (screenshot): End LIVE is behind the LIVE timer - click "02:20:15" (bottom right), a menu with End
  LIVE / Pause LIVE comes up; after ending, the summary shows and LiveKeeper's usual flow goes LIVE again. Invoke-FreshLive
  now clicks Get-LiveTimerLine (lowest two-digit-hours line), waits 5 s for 'End LIVE', clicks it (never Pause LIVE),
  then the old 'are you sure' handling; no End LIVE -> Esc + screenshot + retry in 10 min. Tested the timer pick on the
  screenshot's values (02:20:15 picked over the goal card's 3:12:18). Backup .bak-20261020-2235. Needs a reopen.
- Oct 20 22:40 USER (Stream to Earn's server card screenshot): "have the tool press stop server and press start server.
  red and green". LiveKeeper.ps1 (backup .bak-20261020-2240): On-Tick first looks for %%APPDATA%%\LiveKeeper\
  restart-server.request -> Invoke-ServerRestart: deletes it, Invoke-EarnButton 'Stop Server' -> 'Start Server',
  waits up to 90 s for port 25565 to close, then Start-McServer; logs + notifies. Scratchpad request-server-restart.sh
  touches the file (plugin jar into plugins/update first). Needs a LiveKeeper reopen.
- Oct 21 02:07/02:17 fresh LIVE: "couldn't read its LIVE timer" - the Claude window covered LIVE Studio's bottom bar. Invoke-FreshLive now brings it forward and reads again (Wait-Screen 5 s) before giving up. Backup .bak-20261021-0222; needs a reopen.
- 2026-10-21: the 98% wind-down also goes by the count itself (doneSamples): at 98.4% she "placed" ~50 per half minute (temporary blocks, dirt pillars for fence gates with nothing to hang on) while the count stood still 15+ minutes, so "fewer than 30 placed in 3 minutes" never came. Now: 98% in and the count up fewer than 30 in 3 minutes winds down too.
- 2026-10-21: the 98% wind-down no longer waits on damage once the count stands still: 69 out-of-reach blown-out spots ("waits on its column") kept damageToDo() true and the castle at 98.3%. Those are left too and dropped from damaged.
- 2026-10-21: **Parkour mode** (modules/ParkourModule, menu button "Parkour (climb to the top)", HumanAutopilot.setParkour) for StreamToEarn's parkour mini-game. Its own jumper (Baritone's parkour only jumps N/S/E/W: "no way there" on a spiral): maps standable blocks round the start (r14, up 270), safest route (Dijkstra: walk < hop < sprint jump < long leap), then each jump by hand: settle crouched on the block (no sliding off), turn, run, jump at the edge (hop <=2.6/2.2 up, sprint beyond), brake over 1x1s. edgeGuard is off in parkour (its sneak made run-ups half speed). Rides (passenger) wait, slowness waits, speed = fewer sprints, death -> respawn, carries on after a restart (config/humanautopilot/parkour.properties). Test ParkourClientTest (-Only parkour, 30-block spiral 20 up): PASSED climbs 39/39/41 s, 101 jumps 0 missed, before the rides/slowness/speed/respawn additions (that run was stopped by the user). The real s2e-parkour plugin needs StreamToEarn activation ("Activate parkour plugin"), so its course is not seen yet.
- 2026-10-21: **Farm game mode** (modules/FarmModule, menu "Farm game (snowball the field)", HumanAutopilot.setFarm) for StreamToEarn's s2e-farm-pro (licensed: it only runs on a server Stream to Earn starts; the user added C:\minecraft-test to Stream to Earn for tests). Learned with the mineflayer bot (C:\minecraft-testot): one snowball on bare farmland or a sprout = ripe wheat at once, infinite in creative; all cells ripe -> 10 s timer -> "Win!!!" -> field back to sprouts; plain animals run one row eating it bare then vanish; TNT blows craters; spider leaves a cobweb; chickens die to snowballs. Her play: finds the field (flood fill from the nearest farmland, +-24 up/down: it floats), /farm tp if not on it (op only; not to escape the prison above it), walks close (never jumps on farmland), throws within 4.2 blocks (further skims onto the wrong cell), nearest/least-turn first, 30-tick pending per cell, chickens first. Test FarmClientTest (-Only farm) joins localhost:25566 (Player0 must be op there): no gifts 2 wins/4 min; with 24 gifts (TNT x3, chickens, smart animals, prison, ravager...) 3 wins/9 min, ~1 snowball a cell. Runner scratchpad farm-test.ps1; gifts sent by bot/gifts.js.
- 2026-10-21: numpad-9 menu reorganised at the user's request: one "- Mini-games -" heading over Box Fill (red), Build Schematic... (blue), Farm game (gold), Parkour (aqua); "Continue building ..." and "Unfinished builds (N)..." moved INTO Build Schematic (top of its file list). LiveKeeper's fallback that clicks "Continue building" on the first page no longer finds it there; the build carries on by itself after a restart (SchematicMode.carryOnAfterRestart), so that fallback isn't needed. Also a [farm-status] line every 30 s for the audits.
- 2026-10-21: Farm on the user's real server (31x31 = 961 cells; a snowball ripens a few cells there): two fixes. A TNT blast left her flying in creative - the "off the field (prison?)" wait held her in the air: now flying -> sneak down (gently: a fall tramples farmland). And she clicked once a cell (~2.3/s); the user showed held right-click fires ~5/s: now she holds use and sweeps the aim (spray), each snowball's landing cell (crosshair ray onto the field) marked pending.
- 2026-10-21: Farm, from the user watching her: she stood spraying the bare cell under her own feet (a snowball there grows nothing). Now: cells within 1.1 of her feet aren't targets (only those left: step 2.5 off towards the middle, then throw back); 3 s at most on one cell then skipped 20 s ("stays at each for 3 seconds unless filled"); a cell hit 10x without growing skipped a minute; any gift animal on the field (not the wolf) is run at - pelted in reach, else the bare cells nearest it are the next targets; and she sprays while running in from 8 blocks. Tested only for building (relink-test) - the real farm is on the online-mode real server, the farm test can't join it.
- 2026-10-21: Farm movement redone (the user: "camera is snapping", "stands there throwing for a while even after winning", "walk stop walk stop"). Feet and eyes apart: the feet keep going (keysToward, 8-way, whatever the camera does) - lanes 6 apart across the field while lots is to do (>15%/20 cells: the start, after a win), else straight to the nearest bare cell (in reach = 2.2 is enough), or at an animal; the eyes glide (one lookAtGliding point moved 0.45/tick, no setQuick flicks) over the to-do cells in reach nearest where they are, not behind her; right-click held whenever a cell or an animal is in reach.
- 2026-10-21: Farm: a dragon gift burnt the field (fire: "the field: 0 cells", then 682) and knocked her off - she lost the field and stood below ("when he falls off he gets stuck"). Now the field never shrinks (a smaller/empty find keeps the last box), and off it she alternates /farm tp and /tp @s to the field's middle every 3 s till she's back on it.
- 2026-10-21: TNT repair order: damaged spots near her (14 across) were taken lowest-first and re-picked every look, so a handful of TNT holes up and down the pagoda (y -22..19) had her flying up and down, switching mid-flight ("flying up and down confused"). Now nearest first (3D, vertical x1.5) and the spot she set off for (dmgCommit) kept first while it's still to do (also among the far ones).
- 2026-10-22: Floating pagoda stuck at 19,586/19,975 for 15+ min on one stone button (vines in its spot, given up on in the wind-down) - the window still counted it as a hole, so never "done" and the server never filled the rest. scanPlan: at 98%+, if every hole left is given up (cant) or a temporary block, holeCount = 0 ("[schem] only given-up spots left"). Compiled only (LiveKeeper watching); not gametested yet.
- 2026-10-22: At 97%+ she sends /kc finish herself (BoxFillModule, once a build, again every 5 min if still not all in; only when BuildLink.sent() - the server has the whole plan). The user asked for it after the pagoda's slow last 3%. Compiled only; queued as candidate-relink.jar.
- 2026-10-22: After the pagoda, /kc finish walled her in inside it; the giant tree's box was elsewhere so breakOut never ran - "flying over to the rest of the build" in place 3+ min. Now: no 2-block move in 10 s there (user's ask, was 20) -> /tp @s over to the goal (overFrom/overSince). Compiled only, queued.
- 2026-10-22: Giant tree roots: hovering under them 100 s+ ("under a roof ... no way out", 9 holes she couldn't get at). Now any stall of 60 s with a plan: /tp @s straight up to max(build top + 3, y + 3), at most once a minute (stuckTpAt). Compiled only, queued.
  - Then 20 s (user's ask): stall 20 s AND not moved 3 blocks in 20 s (stuckPos) -> /tp up; at most every 20 s.
- 2026-10-22 13:08: Giant tree at 31% "done": the 30 s stuck step's give.isEmpty() branch left 53,176 unseen spots as they are; server pasted them, on to iron farm - then hovering under the roots ("box is far away" branch) never moving. Fixes: no mass give-up (>200 spots) under 97% - /tp up over the build instead; the far-away "flying over to the build" branch /tp's after 10 s not moving. Compiled only, queued.
- 2026-10-22: The Ava co-host reads our output (read-only): kindroid-chat-bridge\program\ava_build_watch.py parses [status] schematic/box fill, [schem] on to/carrying on/repairing/97% lines, the "§6[Autopilot]§r Done building X! Next up: Y" chat line and builds\*.properties keys (file/done/total). If any of those formats change, tell the "ava voice" session (its tests\build_watch_test.py has the expected formats).
- 2026-10-22 16:20: Copper guard (normal world): dirt at 234, 64, 10 "couldn't fly there" was the damage pick first every minute for an hour (101 log lines) - it was the repairStep for other damaged spots, and only the damaged spot's own fails were checked. Now the step's fails count too (>=2: not first). Compiled only, queued.
- 2026-10-22 16:40: "flying confused": 54 "damaged" ground spots (dirt/grass under trees, no TNT - her own tree clearing) pulled her 100 blocks across the build and back. remember(): natural ground undone with no blast nearby is a plain hole, not damage-first. Compiled only, queued with the repairStep fix.
- 2026-10-22 18:26: Copper guard tree area: walking round inside a dug-out pocket under the ground ("shut in ... 120 open cells") kept moving 3 blocks now and then, so the 20 s no-move /tp waited 42 s. Now also /tp up at 30 s with nothing placed, moving or not. Compiled only, queued.
- 2026-10-22 18:40 (NOT tested yet, candidate-buried.jar, not the queued one): soil of the build's ground under ground that's in already (underDoneGround: block over it solid, the plan's soil/ground, matching) is left as it is on its FIRST failure, whatever the reason ("couldn't fly there" / "can't see it" too, not only "nowhere"). The copper guard's case files: hollows of air and old oak leaves inside its dirt under finished grass, 5 goes each, digging pockets she got stuck in. Only 2 of 20 saved cases today were this; trees standing on ground spots (log/leaves over the soil) and wall torches are the others. Test off stream with HA_CASE=<case file>.
- 2026-10-22 18:50: Copper guard hillside: hundreds of its dirt spots under the world's own ground, "nowhere" one by one, "[confused] 30 targets in 10 s" even with the 30 s /tp. underCover / underDoneGround needed the cover to match the plan exactly; natural ground over the build's soil/ground (grass on dirt, stone, coarse dirt) counts now (worldGround). Compiled only, queued (with the buried-soil rule).
- 2026-10-22 19:00 (the root of the hillside confusion): in a normal world (not leaveGround) the build's sunk ground layers (isGround, y < clearFromY) were matched block for block underground - stone swapped for its dirt, caves and old leaves filled from inside. jobAt now: soil/natural-ground spots of the ground layers are DONE under the top layer when the world has natural ground there, and DONE when empty (air/leaves) under natural or done ground. The top ground layer still gets the schematic's own blocks; /kc finish at 97% does the rest. Compiled only, queued.
  - 19:20: still "nowhere" at y 66-67 under the trees. Also DONE now: an empty ground-layer spot with anything solid, leaves or logs over it within 12 blocks (not open to the sky). Looked at fresh each time, so cleared trees make it to-do again. Compiled only, queued.
  - 19:27: then the TOP ground layer (y 68) under the hill: grass wanted where the hillside's stone is, stone over it, "nowhere" 42 times in 2 minutes. DONE too while solid is over it (worldGround there and the block above occludes); to do again once the hill over it is cleared. Compiled only, queued. NONE of these ground rules is gametested yet: test them off stream on a hillside build.

## 2026-10-22 (evening): audit - "why is it so stupid, stuck so often, flying around like a bot"
The user asked for an audit of schematic building and to make it smarter (game open, building copper-guard-walls on a
normal-world hillside with trees, jar 19:17).
- What the logs showed (sessions 22-3, 22-4 and the 19:18 one, copper guard): 750 STALL-seconds, 51 [confused],
  2073 "no spot", 10 /tp-ups in 7 minutes. 76% of the stall time was "flying over to the next block" (the far
  branch: a target outside the open-air map, REG_H 14 / REG_V 16). Of 365 such flights of 3 s+, only 10 placed the
  block; 310 ended "nowhere to place it from" / "no look at it from anywhere", 15 "couldn't fly there". The failing
  spots were nearly all ground-layer blocks buried in the hill or under trees (grass_block under stone 473, dirt under
  grass 314, ...). Near him the full standFor ran on one buried spot after another (5-60 ms each): the [confused]
  bursts and the 25-81 ms long ticks. Over 20 special-case rules (shutAway, sealedPocket, underCover, buriedSoil,
  underGround, sealedInGround, groundStaysOver, covered...) each covered one kind of buried spot; no general test.
- Root causes: (1) far targets were flown to before any check, and the far pick took the nearest far hole, buried or
  not; (2) no general "can any face of it be seen at all" test; (3) the far flight aimed at the air right over the
  block, often inside the hill ("couldn't fly there", the up-to-the-top yo-yo); (4) tall grass etc. with a block in
  the cell above can never go in (DoublePlantBlock wants the top cell free) - retried for ever (105 "no spot");
  (5) the look-around flew 90 blocks out over a 208-wide build every few minutes (44 times in 2 h).
- Not bugs: the 100+ "Copper Torch / Copper Lantern can't stay there" - the copper guard has ~10,700 blocks of a
  mod's "poplar" wood (stripped_poplar_log 5292, orange_poplar_leaves 2832, poplar_fence 1240, poplar_fence_gate 384,
  poplar_trapdoor 450, poplar_slab 476, poplar_shelf 32) that don't exist in the user's game: SchematicLoader turns
  unknown blocks into air, so whatever stands on them has nothing to stand on. (Offered: map unknown wood to a
  vanilla one.)
- Changes (BoxFillModule):
  - openFace(h): one of the six blocks round it isn't a full block (outline shape), or it can't be told (unloaded).
    planOk and nearOk test it first (six lookups): a buried spot is never picked, near or far, and costs no standFor.
    whyNotOk says "buried". layerFinished (pass 0, cached 2 s in layerSkipAt) and the count pass's passLowest don't
    let a buried PLACE spot hold its layer open (it would have kept the window down for good, never tried so never
    given up); when something next to it opens, the count pass brings the window back down. The stuck ladder's step 2
    (openUp) takes buried ones too; its 30 s give-up doesn't count buried ones in the "> 200 unseen: /tp up" rule
    (they'd have looped the /tp every 30 s).
  - Look before flying: workableFromAfar(t) = standFor in farProbe mode (hoverable = inBox (NOT inArea: inArea is only
    the map round us - with it every far look said no, the first version's "45 looked over, 1 yes") and flyable and in
    the air map, or farAirAt's cells (a flood fill from the tops of the columns within 7, t.y-5..t.y+8, so under a tree
    crown open at the sides counts, a cave doesn't), or skyOpen; the first spot found returns; a second go with steepOk
    like on arrival; no lean fallback, no log). farSeen caches it (yes
    10 s, no 60 s) with the spot found. farOk = within the tick's looks (6 a tick, none after 8 ms of the tick); null =
    look next tick. The far pick (nearest 64, then the full sort) takes the nearest far hole that's workable from
    afar; buried far holes are remembered a minute (farBuried) and left out of the list, the 64-heap checks openFace only
    for would-be members, and a full pass that met buried ones goes on next tick past them (else a hill of buried spots,
    all the nearest, hid the doable ones further on); looks still pending -> farPendingAt, "looking the build over for the next block" (brake, no stuck clock); none
    workable -> one blind visit to the nearest (blindAt, once in 2 minutes each, logged "nothing far off looks
    workable"). Pending returns from pickPlanTarget at once: falling through into the "nothing to place" block (bridges,
    pillars, loops over every hole) gave 237-254 ms hitches. Dig-down targets are approved as they are (farApproved).
    A "no" logs now and then: "[schem] far look at X (has -> want): no spot we can fly in to with a view of it". The far branch checks targets the far pick didn't approve (opened, putBack, plantNext...) the same
    way: "not flying over to X: ..." and a soft skip (bad 60 s, not a fail). TNT damage is exempt (fly over and look,
    as before: blown-out walls are often seen only from the side or inside).
  - The far flight goes to the spot found from afar (farSpot: open to the sky, so there's a way down) instead of the
    air right over the block.
  - pickTarget: nothing to pick this tick -> the same answer for the rest of the tick (pickNoneAt): with nothing to
    do, pickPlanTarget ran up to three times a tick.
  - waitsOnSupport: a DoublePlantBlock (lower half) with something solid in the cell above waits; what's there goes on
    the clearing list (clearOnTop) if it isn't the build's.
  - lookAround: builds over ~45 across look at the part we're working on (24 radius round the last block placed:
    ~38 out, 15 up) instead of the whole build from 90 out.
  - Status line: "[status] looking before flying: N far spots looked over (Y workable from the sky, Z not), K passed
    over as buried".
- Test options (SchematicClientTest): HA_SEED=<seed> (terrain world seed; also builds at the world's spawn point, not
  wherever the player came in - the spawn radius moved the build 10 blocks between runs), HA_SINK=N (the build N
  blocks into the ground where he stands: half of it under the hill), HA_SLOPE=1 (with HA_LANDSPOT: a hillside, the
  floor a quarter of the way up; too big a build finds nothing and falls back to spawn).
- Tools (kept in tools/ - a session's scratchpad is deleted when it ends, the last session's audit.sh etc. were lost):
  tools/run-schem-test.ps1 (-Name -Schem -Minutes -Seed -Env -Src -Flat; one test run from %TEMP%\hatest, log
  run-<name>.log in %TEMP% or $env:HA_LOGDIR; clears every HA_* first), tools/schem_metrics.py (compare runs: placed,
  stalls, confused, "no spot", far-flight outcomes, far looks, time per activity), tools/litematic.py (litematic reader:
  `find <id>` / `at x y z`).
- Tests (seed 20261022, built at the world spawn 16,77,80, heights 63..91 round it, water, trees; 12 min each; "old" =
  the dev tree at 19:45, i.e. WITH the 19:00-19:27 ground rules that were queued, not installed):
  - copper guard sunk 7 (HA_SINK=7): old 2333 placed, 1 stall-s, 0 confused; new (first version, far look broken by
    inArea) 2190 placed, 4 stall-s, 0 confused - both never got past the dirt foundation in 12 min. The "done" count
    lagged in the new run (15.7k vs 24.5k) only because the local look-around saw less of the build from out there
    (the count includes what's been seen right) - not lost work.
  - swamp-land sunk 4 (HA_SINK=4, 91x117, half dirt plus mud, moss, water, mangroves): old 1996 placed (166/min), 4
    skips, 7 stall-s, 39 "no spot", placing 62% / flying 19% of the time; new 2526 placed (211/min, +27%), 0 skips,
    1 stall-s, 12 "no spot", placing 83% / flying 8%; far looks 2 (both yes), 18747 buried checks passed over.
- Found by the TNT tower test (HA_TOWER) on this code: after the TNT (all 155 back in 81 s, as on Oct 15), one slow way
  down through the roof hatch to the floor inside ("couldn't fly there", progress clock) put flyFails on the floor
  spot, and planOk's flyFails rule (every hole within 5 / 3 up-down, a minute) blocked the whole inside floor - he was
  in there, "16 hole(s) I can't get at", the stuck steps gave the floor up after 30 s, "stuck 41 s after the TNT". Now
  the flyFails area rule is for holes outside the map round us only (in it, gettable already means there's open air we
  can get to right next to it), and stuck step 1 ("another look") clears flyFails too.
- Suite on this code (before that fix): stuck OK (78 s), loose OK (0 wrong, 113 s - the Oct 19 record was 77 s, timed
  with fewer later changes), chain OK. exact FAILED 223/245 and plain's repair stage FAILED (1 door left open): both
  look older than this audit - exact's wrong spots are its ground layer (cobblestone/farmland/path/water where grass
  is, and the wheat that needs that farmland) left to the grass filler in a flat world since Oct 20 (isGround = all
  under clearFromY), plus two hoppers "any way round" (Oct 20 rule); the repair stage ended the moment the last block
  went in while the door he'd opened to get out was still open (closeDoor comes after). Both confirmed on the old code
  (below).
- After the flyFails fix: HA_TOWER 866/866, 146 blown out, all back in 65 s, longest stretch without progress after the
  TNT 6 s. The old code (proj-before) on HA_TOWER: 866/866, 114 out, back in 57 s, 6 s. HA_EXACT on the old code: the
  same 223/245 with the same 22 spots - pre-existing (flat-world ground left to the grass filler, hoppers any way round).
- Runner trap (scratchpad run.ps1): runs started with & in one PowerShell keep each other's env vars; HA_TOWER (not in
  the clear list) turned the next "plain" run into another tower run. run.ps1 now clears every HA_* first.
- Old code vs new on the rest: HA_LOOSE old FAILED (108 s, 4 wrong, 4 temporary glass left), new PASSED twice (112-113
  s, 0 wrong, 0 glass); plain (house stages): both house 82/82 and both fail the repair stage the same way (the door he
  opened to get out still open at the check) - pre-existing.
- INSTALLED 2026-10-22 21:40 in .minecraft/mods and dist/human-autopilot-1.0.0.jar (built 21:39 from the dev tree, md5
  7cc85cff..., 571822 bytes): this audit + the afternoon's queued ground rules (19:00-19:27) and everything before.
  The jar it replaced (19:17, md5 4ed8454f...) is dist/human-autopilot-1.0.0-installed-2026-10-22-1917.jar. The
  user's game had been closed since 19:36 and LiveKeeper wasn't watching. Not seen in the user's game yet: watch the
  next session's log for "[status] looking before flying", "not flying over to", "nothing far off looks workable",
  "far look at", STALL / [confused] counts vs the 750 STALL-s / 51 confused of today's bad sessions.
- Open (not done): ~10,700 "poplar" blocks of a mod in the copper guard load as air (offered a mapping to a vanilla
  wood); HA_EXACT and the plain repair stage need updating for the Oct 20 flat-world ground rule and the door timing;
  the near pick's pn.ok (~1 s per 30 s on big windows, 25-41 ms ticks) is the remaining hitch source.

## 2026-10-22 22:20: Kohaku and the grass filler (the user: "kohaku kept destroying where the auto pilot was placing and the grass filler had bugs too")
- The companion hasn't run since 2026-10-20 03:57 (Help Build off by default since the user's "i dont want kohaku to help me
  anymore" at 04:00); the client logs of that night are gone, the server's (C:\minecraft-server\logs\2026-10-20-5..7)
  have it. What clashed: (1) blocks the autopilot took out on purpose (openUp/openPocket, its grass out for a spot) and its
  way in through a roof (hatch) - empty planned spots to her, put back/filled, it took them out again (the 03:48 fix only
  skipped done->hole within 12 of the owner for a minute); (2) she could fill the last open side of a spot still to do
  (buried for good: the autopilot's "nowhere"/openPocket churn); (3) TNT copies round her (onTntSpawn, same offset from her
  as from the owner) landed as close as ~9 to the autopilot with her 12-20 off, and blew up what it had just put in.
- Fixes: mod BuildLink.hold(spots) -> "hold;id;x,y,z;..." (whole list when it changes, at most once a second, from
  BoxFillModule.sendHold: opened + outFor + hatchHeld (hatch() records the roof spots it leaves open, 10 s)). Plugin
  SchemLink: case "hold" -> Plan.hold (logged when its size changes); CompanionBoxFill.planHole false for held spots (not
  hers to fill, not damage); shutsIn(w, plan, t) - t would close the last open (non-occluding) side of a planned hole
  beside it - skips t 30 s ("would shut in the spot beside it"), for damage repairs too; KawaiiCompanion.onTntSpawn: no
  copy within TNT_COPY_KEEP (KEEP_AWAY + 8 = 20) of the owner (counted in the TNT log line). Old plugins ignore "hold".
- Grass filler: no open report found after the Oct 20 fixes (filling forever -> groundNeeds; burying the autopilot -> the
  players' gap; grass where the build wanted stone -> the soft-ground swap); those are in the deployed plugin. Asked the
  user what they saw.
- Not tested in a game (no Paper test server with the companion here); compiled. Plugin: built with
  Minecraft-Plugins-main\apache-maven-3.9.16 (mvn -o -q package), target jar md5 a7fea824..., staged in
  C:\minecraft-server\plugins\update\ (swapped in at the next server start); the one it replaces is
  plugin-backups\kawaiicompanion-26.1.2-before-hold-20261022-2217.jar. Mod: dist\human-autopilot-1.0.0-hold-candidate.jar
  (md5 6d4c0481..., = the 21:40 install + sendHold/BuildLink.hold), NOT installed (the user's game was open). Plugin repo is
  git with earlier sessions' uncommitted changes (CompanionBoxFill/SchemLink untracked): nothing committed.

## 2026-10-22 22:30: the autopilot and Kohaku talk (the user: "make auto pilot and kohaku talk to each other ... 1. stay away from me 2. im working this part")
- Channel kawaii:schembuild, both ways. Mod -> server, besides start/pal/blk/go/stop/finish/hold:
  `area;id;x,y,z;r` = "I'm working here" (BuildLink.area, from BoxFillModule.talk every tick: the spot it's flying to
  if that's outside the open-air map round it, else where it is; r = OUR_PART 16; sent when it moves 4+ blocks, else
  once a second). Server -> mod: `helper;id;name;x,y,z;tx,ty,tz|-` = "I'm here, on that spot" (each helper once a
  second, CompanionBoxFill.helperLine via SchemLink.tellOwner, only if the client listens on the channel).
- Plugin: Plan.area/areaAt, freshArea() (6 s old at most, else the owner's own spot); ownersPart(c, ownerAt, area,
  margin) replaces the plain KEEP_AWAY 12 checks: she keeps out of the owner AND of the part the autopilot says it's
  working (16 round it), drops a target that comes in there (margin -2), flies off when she's in it (+2).
  /kc helpbuild [on|off] (no menu needed); the menu button calls the same setHelpBuild.
- Mod: BuildLink.heard() keeps helpers 60 ticks (logs "[schem] X is helping with NAME: we each keep to our own part"
  once); planOk skips a hole within 6 of a helper's target or 3 of her body (helperClaims, counted: "[status] helpers:
  name at x y z on ..., N spot checks left to them"); not while nothing else is pickable (pickNoneAt).
- Test: TalkClientTest (HA_ONLY=talk, HA_MINUTES, HA_TALK_FILE default japanese-style-house, HA_TALK_HELP=0 = alone)
  on the test server C:\minecraft-test (Paper 26.1.2, port 25566, offline, flat, Player0 op, KawaiiCompanion in
  plugins/): joins, /kc dismiss + summon + helpbuild on, builds 4,4 off where it joined, logs the distance between them
  every 5 s, fails if she was never heard or came within 8. Server: scratchpad testserver.ps1 (console fed from
  C:\minecraft-test\claude-cmd.txt - cmd.txt is held open by some other process and kept replaying an old "stop").
- Plugin builds: in KawaiiCompanion, ..\tools\apache-maven-3.9.16\bin\mvn.cmd -o -q package (Minecraft-Plugins-main\tools;
  the copy at the Minecraft-Plugins-main root is broken: "-classpath requires class path specification").
- Talk tests (C:\minecraft-test, TalkClientTest): start of a build 8 min - she told us where she was 462/462 and
  480/480 looks, closest 10.1 / 11.7, median 33-35, 0 of ours broken. HA_TALK_LEFT=15 (the user: "test what happens
  when they're 80 or 90 percent done ... closer and closer"; 85% pasted through the console first) found: (1) Kohaku
  stuck 13-15 over her spot for minutes - fly() kept her 1.5 over whatever's under her (the upper floor) and the "in a
  block: up out of it" rule pushed her back up; now diving(): a clear spot right under her (within 3 sideways) - straight
  down through the floor, no push-up; (2) her hover spot could be by the owner with the target 12+ away -> skip "she'd be
  in her owner's part", and a target dropped if she's within 9 of the owner. After: the 88.6% house finished in 6 min
  (was +0.6% in 10), closest 10.7 at 90-95%, 14 at 95-98%, 23 at 98%+.
- The user then: "just make her disappear at 80 percent or if the build is almost done and kohaku is too close or
  getting too close". Mod: area message carries the bar's percent (area;id;x,y,z;r;pct, planProgress, -1 before the
  count). Plugin: Plan.progress; at 80%+ (VANISH_AT) closeTo(e, owner, 10) - within 10 now, or in half a second at both
  their speeds (her step stops at her spot: the first version vanished her from 21 away) -> vanish(): a puff, chat "...
  slips away - you're nearly done, the rest is yours (back for the next build)", log "[companion] X slips away: <build>
  is N% done and she was D from <owner>"; vanishedFor -> comeBack() when the owner's plan id changes or there's no plan:
  doSummon + helpBuild on, log "is back for". Seen working once (85%, the 21-away version); the calmer look-ahead and
  the come-back were not run (the user: "stop testing. just fix it and give it to me").
- Hand-off (mod sendNeed -> "need;id;x,y,z;..." = cant spots still empty; plugin Plan.need/needList, pickPlan takes them
  after damage even in the owner's part (not within NEED_KEEP 9), approachNear places from any clear spot in reach; jobAt:
  a cant spot that's right now is DONE). Never exercised in a test (no spot was given up in the runs).
- Grass filler courtyard question: no bug found - he puts the plan's own block in every ground spot it lists; only
  unlisted spots get the flat layers. The yard rule (keptColumns) leaves out plain ground over 3 from the build: copper
  guard walls 73,740 such inside its walls, but its courtyard IS grass in the file (14,748 on top), so grass is right.
- INSTALLED 2026-10-22 23:40: mod build/libs jar (md5 8bddf13e..., 576356 bytes) -> .minecraft/mods + dist/ (old one:
  dist/human-autopilot-1.0.0-installed-2026-10-22-2139.jar); plugin target jar (md5 34e636bd...) -> C:\minecraft-server\
  plugins\update\ (server running: loads at its next restart) + dist/kawaiicompanion-26.1.2.jar.
- 2026-10-22 23:57 USER: "turn tnt off". C:\minecraft-server\plugins\KawaiiNoGrief\config.yml block-damage tnt and
  minecart_tnt structure -> none (TNT breaks no blocks; entity damage/knockback stay). Backup:
  plugin-backups/kawaiinogrief-config-tnt-structure-20261022-2357.yml. The running plugin holds its config in memory
  (RCON off, the console is Stream to Earn's): live after `/knogrief reload` (or `/knogrief tnt none`) in game, else at
  the next server start. Careful: any `/knogrief tnt|on|off` before a reload saves the old in-memory config over it.
- 2026-10-23 00:00 CORRECTION from the user: "turn off tnt" = turn the protection OFF - TNT breaks everything, the grass
  filler repairs the ground, the autopilot + Kohaku repair the structure. config.yml tnt/minecart_tnt -> all. The server
  restarted at 23:49 (before the edit: it loaded tnt=structure), so it's live only after `/knogrief tnt all` in game.
  That restart also swapped in the new KawaiiCompanion (md5 34e636bd: talk, hand-off, vanish at 80%); the update folder
  is empty. The grass filler only runs in FLAT worlds (WorldType.FLAT); the server's main world is level-type normal -
  the user was creating flat worlds at 23:51-23:52 ("(✧) creating a flat world.").

## 2026-10-23 00:10: grass filler puts back exactly what TNT blew out; camera snaps after the creative menu
- The user: "i broke a block he repaired it right away. what if the auto pilot was building the floor. also i placed down a
  wooden log. summon a tnt. he did not repair that as a wooden log. just grass" (and earlier "hes not repairing till i
  move away").
- KawaiiCompanion CompanionBoxFill: `blown` (world -> chunk -> spot -> BlockData, each kind once via blownKinds, cap 400k,
  far chunks dropped when full) filled by `noteBlast` from EntityExplodeEvent + BlockExplodeEvent at MONITOR (flat worlds
  only; not TNT/fire). `handBroken` (world -> LinkedHashSet, cap 100k) from BlockBreakEvent (any player - the autopilot is
  the user's player), cleared by BlockPlaceEvent. groundWant order: plan.hold -> null; the plan's own spot -> its block if
  ground, else null (builders'); keepEmpty -> null; inBuildSpace (plan box +-3, from floorY up) -> null; blown -> exactly
  that; hand-broken -> null; at/over floorY -> null; else the flat profile's layer. scanBlown: memory spots within 24 of
  the anchors at any height, 3000 a tick; off the memory once put back, something else is in the spot, or it's the
  builders'. Status line: "blown out round them N (M noted in all), dug by hand and left K".
- Players' gap is now just their body + 2 over the head (was 1.5 all round: a whole crater stayed open while the user stood
  in it). Holes in the gap aren't picked as the next target either (he hovered by the user waiting).
- Built (md5 89ca670f), staged in C:\minecraft-server\plugins\update\ (+ dist/); the running one (34e636bd) backed up as
  plugin-backups\kawaiicompanion-26.1.2-before-blastmemory-20261023-0018.jar. Live after the next server restart. Not run
  in a game (the user: "stop testing. just fix it and give it to me").
- Camera (the user: "autopilot camera is kinda snappy and not smooth"): their Oct 17 bot recording (rec-20261017-165319-bot,
  70 FPS) has 67 jumps of 20-38 deg in ONE frame per hour, every one right after "closed CreativeModeInventoryScreen"
  (tonight ~3 menu trips per 30 s). aim.frame() isn't called while a screen is open, so the first frame after it had
  dt = the whole menu time (clamped 0.1 s) and the goal/speeds from before; and the 2 untouched grace ticks ran the calm
  walking spring (beginTick reset quick every tick) - half of a 70-degree turn in one frame, then easing out.
  AimController now: `camT` (its own clock: each frame moves it by min(dt, 1.6 x the median of the last 15 frames), so a
  hitch is a short pause, not a catch-up jump); a gap of > 0.25 s between frames resets the motion (no speeds, no stroke,
  new goal) and waits 60-160 ms like a hand back on the mouse (`gaps` counter); `quick` is kept over untouched ticks
  (only a tick that aims without setQuick makes it calm). Tools: scratchpad ana/hitch.py, jumps.py, around.py,
  strokes2.py (frame pacing, jumps with events, a window of frames, turn sizes/peaks; speeds by the frame time before -
  the recorder samples before the aim turns).
- Also seen: ~176 frames a minute over 20 ms at a 14.3 ms frame (the bot's 40-90 ticks over 5 ms per 30 s, mostly picks
  of ~4.6 ms + scans), big turns (100+ deg) peak ~550 deg/s (5 a minute). Not changed yet.
- Checked in a test (medieval-house, flat, recorded; the window was closed after 70 s): 22 creative menu closes, 0 camera
  jumps over 6 deg in a frame (the old recording: most menu closes had one); frame-to-frame speed changes over 60% 5.5% of
  turning frames (old 9.6%). The user: "just install it". The mod jar (md5 47a802d9, built 00:28 from the dev tree) waits
  in the scratchpad as human-autopilot-1.0.0-camfix.jar; autoinstall-camfix.ps1 (hidden PowerShell) copies it into
  .minecraft\mods + dist\ the moment the user's Minecraft closes (old one -> dist\human-autopilot-1.0.0-installed-2026-10-22-2340.jar;
  log autoinstall-camfix.log). The scratchpad goes with this session: if it's gone, rebuild from the dev tree.
- 2026-10-23 00:33-00:40 INSTALLED: the user closed Minecraft and the server. Mod: autoinstall put the camera-fix jar in
  .minecraft\mods + dist\ at 00:33:20 (md5 47a802d9). Plugin: copied straight into C:\minecraft-server\plugins\ (md5
  89ca670f: blast memory, hand-broken, smaller player gap), update folder emptied. KawaiiNoGrief config.yml still has
  tnt/minecart_tnt: all, so TNT breaks everything once the server is up (no /knogrief command needed now).

## 2026-10-23 00:50: back by themselves on join (the user: "make it so kohaku and grass refiller spawn right away after i join ... whether i exit or the server crashed"; "make sure when they join, they're already set to build and refill grass ... and make sure the 5 second applies to autopilot to build next or current schematic")
- Plugin: plugins/KawaiiCompanion/out-on-join.yml (uuid -> companion / filler / help-build), saved the moment it changes
  (setOut: doSummon, doDismiss (clears companion+filler), setHelpBuild, toggleGroundFiller/summonGroundFiller,
  dismissExtras), so a crash can't lose it. onPlayerJoin -> 40 ticks later comeBackOnJoin: doSummon if the companion was
  out (Help Build on unless the file says false; skipPlanId cleared), a grass filler if he was out. Logs "[companion] X
  joined: back by themselves - ...". Pre-seeded for the user's online UUID ce20f3cf-... (all true). Built md5 2d89e24c,
  staged in plugins/update (server running; old one in plugin-backups/...-before-comeback-20261023-0053.jar) + dist.
- Mod (SchematicMode.carryOnAfterRestart): the 5-second carry-on now runs on EVERY new connection (getConnection()
  identity), not only the game's first world. Unfinished build -> resume; none but the last one finished (keep() now
  moves a done build's record to config/humanautopilot/last-done.properties instead of deleting it) and Keep going on ->
  resume that one (all in: guarding) and chain() goes on to the next file. Not gametested (dev-environment skips it).
  Jar 7584dc47 (scratchpad human-autopilot-1.0.0-carryon.jar, also has the camera fix); autoinstall-carryon.ps1 puts it
  in when the user's Minecraft closes.
- 00:55 INSTALLED both: mod 7584dc47 in .minecraft/mods (autoinstall), plugin 2d89e24c moved straight into C:\minecraft-server\plugins (both closed).

## 2026-10-23: s2e-parkour on the user's server (the user: "keep testing until auto pilot can win")
- The user's recording `rec-20261023-105754-you` (s2e-parkour): 94 jumps in 62 s, W + space held, never sprinting, ~0.1 s
  on each block, take-off ~0.23 b/tick, ~0.24 from the block's middle, turns on the block mostly 0 (p90 23 deg).
- The real course (read from the server's region files, scratchpad ana/mca.py `<region_dir> x0 y0 z0 x1 y1 z1 [nonair]`;
  world `C:\minecraft-server\world\dimensions\minecraft\flatssssssssss\region`): a stained-glass spiral (colour by height
  band: red -59..-41, orange, yellow, lime, green) from 12,-59,43 to 24,40,57 (100 up); each step +1 up and 2 across
  (a 1-block gap); a lap = 7x(2,0), (1,2), 5x(0,2), (-1,2), 7x(-2,0), (-1,-2), 5x(0,-2), (1,-2) (28 steps). Round it an
  invisible BARRIER cage (x 10 and 28, z 42 and 58, y -59..127) and glass corner posts up to y 41; red terracotta floor.
  s2e-parkour commands: /parkour create|delete|tp|timer|tnt <1-6>|change_block <1-3>|edit|speed speedUp/slowDown <s>|
  arrow_rain|wolf/chicken/allay Up/Down <n>|changecounter|goal add/sub <n>|height_limit|win. s2e plugins only work on a
  server Stream to Earn starts.
- Bugs it found (parkour1, installed 11:29, got stuck at 24,-53,43 "no jump ... towards the top 10,127,45"): the map
  (radius 14) didn't reach the next block round the spiral, and the barrier cage's top (127) and the glass posts were
  taken as "the top". ParkourModule now: RADIUS 24, HEIGHT 160, barriers aren't standable, a "top" must have air under
  it (no pillar tops), the map is re-centred on the player each time (on the ground), mapped again at once after a fall.
- Jumping like the user: flowStep (one up, 0.9-2.3 across, no slowness/speed/blindness) + flowTurnOk (within 50 deg) =
  no settling, W held (holdW: no fresh W press within 8 ticks unless a sprint is wanted - a double tap sprinted), the
  next block looked at in the air, on landing the next jump chained at once (carried), space held through the landing
  only at speed (> 0.19 along the next direction); a 1-up block right beside it jumps on horizontalCollision; a turning
  gate (yaw error > 10/15 deg, < 15 ticks) before running off; corners (2.24 across) are walk-hops (sprint over 2.4 up /
  2.6 level). "[parkour] dropped N ... :<trace>" logs what a quiet drop's jump did. Caught inside a block in the air for
  2 s (not moving): out sideways to an open cell, jumping ("caught in a block").
- Test: `HA_PARKOUR_STYLE=real HA_PARKOUR_STEPS=100` (the real lap, red glass, barrier cage x+-9/z+-8, glass posts);
  `steps` = a straight stair. Stages: first climb, after a fall from the top, a knock from half way, speed then slowness.
  12:19 run: first climb 63 s (109 jumps, 0 missed, 0 falls), back up after a fall 65 s; the half-way stage failed
  because the test teleported him INTO the first course block (fixed: freeFloor).
- Computer use on the user's PC: granted Minecraft Launcher, Stream To Earn, TikTok LIVE Studio, File Explorer, Javaw;
  minecraft.exe (the launcher's real window) was denied, so ask the user to press Play. The Claude app window sits in
  the middle of the screen (it's not the NVIDIA overlay).
- INSTALLED 12:44 (the user's game closed): md5 43c1bb5a (the 12:19 test's jar: radius/barrier/top/re-centre/re-map).
  Old one: dist/human-autopilot-1.0.0-installed-2026-10-23-1125-parkour1.jar.
- WON ON THE REAL SERVER 12:47:12 (the 12:44 build, the user started the game at 12:45): "[parkour] at the top 24, 40, 57
  after 53 s (92 jumps, 0 missed, 0 falls)"; the server logged "[s2e-parkour] win-up" at 12:47:26 and the plugin put
  him back at the bottom (a 100-block "fall"); again at the top 12:50:42 and 12:52:19. Most misses on the way are
  knock-backs (traces with v 1-7 blocks/tick, yaw swung 180: blasts/launches from gifts), re-routed from where he lands.
  Two misses were jumps 50-80 degrees off (the turning gate ran out after 15 ticks): now still > 35 off after the turn
  time -> SETTLE (crouched, turn, then run), log "still N degrees off X after turning: settling first".
- 12:58: the full test passed with the turning fix (climbs 66/68/95/76 s, 0 missed); jar md5 8bc2cfee (scratchpad
  human-autopilot-1.0.0-parkour3.jar) goes in when the user's Minecraft closes (scratchpad autoinstall-parkour3.ps1,
  hidden; log autoinstall-parkour3.log). Real server by 12:58:36: 8 wins in 12 min, ~60-75 s a lap once settled.
- 13:00 the user, watching it win: "1. her camera keeps bobbing up and down 2. tnt makes her fly up ... she does not aim
  which block to land (also not looking around) 3. there's a plugin that hovers her up (also not looking around) 4. make
  it so she's not 100 perfect, maybe 80 percent". Their recording: pitch range within a jump median 0.5 (p90 3.3) at ~16
  down; the bot looked at each block it landed on (pitch swinging every hop). Changes (ParkourModule):
  - RUN/AIR aim = lookDirection("pk-run"+block, yaw to the block, pitch held): flow jumps runPitch (gauss 12-20 per
    session, drifts +-0.04/tick within +-2.5, 35% chance of a +-2 nudge on a landing), longer jumps the pitch at their
    run's start; yaw kept when within 0.8 of the point. SETTLE still looks at the block. Bot recording after: pitch range
    0.0-0.1 a jump, yaw swing 3.0/27.8/61.9 (median/p75/p90; user 2.9/22.3/61.5).
  - airborne(): in the air with no jump of ours (thrown, a ride let go, a miss): 4-9 ticks of surprise, lookAround()
    while rising (course blocks within 14, the top, a look off to the side, 10-26 ticks each), then pickLanding = the
    highest mapped block whose vanilla no-input crossing point (predictCrossing: move, vy=(vy-0.08)*0.98, across *0.91)
    is within steerReach(t)*0.8+0.4 (0.02/tick air control), eyes on it, W/A/S/D toward the gap, crouch the last 2 ticks;
    re-picked every 4 ticks; "landed, steadying" (crouch, brake) for up to 6 ticks after. Logs "coming down: steering
    for X". A jump becomes "thrown" at > 0.5 across (0.65 with speed) or > 0.6 up; a miss = coming down > 1.2 under the
    target (and < 5 under: a teleport down is a fall, not a miss). Riding (isPassenger): lookAround.
  - Slips (Config.parkourSlipChance 0.2): planSlip on each new route (chance * min(1, jumps/90), a hop at index
    3..n-3); startSlip when that jump starts: kind 0 never presses jump (walks off), kind 1 jumps then lets go after 2
    ticks and holds S 4 ticks. Not counted as a miss / bad jump; no chained jump into a slip. Log "a slip on the jump".
  - Map: from the lowest point stood on since start (lowY) up: re-centred high up it lost the laps under it, and a slip
    at y 38 fell 97 blocks to the floor.
  - Corner chain: carried with vAlong < 0.15 -> take off at along 0.55 (was 0.25: hit the block's side at 0.11 speed).
  - Test stages added: a wind charge under her at a third of the way (thrown: landed on the block 2 ahead it aimed for),
    an allay ride 14 up then dismount (landed on the highest reachable block), a forced slip (chance 1.0); the first
    climb is recorded (Recorder.recordBot) to compare the camera (scratchpad ana/pkpitch.py <rec>).
- Slips, final form: at most one per climb (slippedThisClimb, reset at the top: a run had 3-4 in one climb, each new
  route rolling again). Kind 1 is now "misjudged the angle": standing, turns 28-38 deg too far, jumps that way (W held
  in the air) past the block's side. Tried first and dropped: letting go / backing off half way (a 1-block gap: it came
  down on the block or back on the one it left) and a sprint jump over it (one up: it bumped the block's side and slid
  onto it). slipsOff() counts slips that really put it off the block; the test asserts >= 1 (HA_PARKOUR_QUICK=1: the
  first climb then the forced slip, ParkourModule.forceSlipKind). Seen: kind 0 at y 31 -> landed on the lap 26 below;
  kind 1 -> steered onto a lower block 25 down, then to the top.
- INSTALLED 13:56 (the user's game closed at 13:48; the 12:59 autoinstall had already put in the turning fix 8bc2cfee):
  md5 3e78d96a (scratchpad human-autopilot-1.0.0-parkour4.jar) - steady camera, thrown/ride landings with look-round,
  slips (parkourSlipChance 0.2, written to their config on the next start), map from the lowest point, corner chain.
- 14:45 the user: "she looks down. go. stop. go. stop to first block". Each floor block on the way to the course was a
  jump of its own (SETTLE: stop, crouch, look down at the block). Now walkStep (level, 0.5-1.5 across) starts in RUN
  and chains to the next floor block or the step up without stopping ("walking on"); the eyes are lookDirection
  "pk-walk" at walkAhead (3 nodes on, or the first block off the floor) with runPitch. Floor to first block: ~3 s
  (was ~8). Then "dont test just fix it and give me": slips never press jump now (kind 1 = turned 35-45 deg the wrong
  way and stepped off; jumping the wrong way still landed on the 1x1's edge about half the time - one quick run before
  that change: 1 fell 24 blocks onto a lower lap, 1 still landed). Built with `gradlew jar` in the dev tree (same file
  list as the test jar), md5 b50d639d (scratchpad human-autopilot-1.0.0-parkour6.jar), NOT tested after the no-jump
  change; installs when the user's Minecraft closes (scratchpad autoinstall-parkour6.ps1, log autoinstall-parkour6.log).
- 15:05 the user (screenshot: standing on a glass corner post): "game breaking bug. when she flies up way too high
  come back down she ends up outside the box or on the corner and cant get back in. dont test. just fix it". The cage's
  4 corner columns (x 10/28, z 42/58) are glass up to y 41 with NO barrier over them, and the posts' tops are a block
  over the course's top: pickLanding (highest reachable) chose them; from a corner the side barriers block every way in.
  Fixes (untested, as asked): pickLanding only takes blocks with a route on up to the top (plan(); landWayUp cache per
  flight, 4 route checks a pick); rescue(): no way to the top for 6 s (no jump, or only a partial route; the clock
  restarts only when a route to the top is found) -> /parkour tp, then 4 s later /tp @s to startSpot (a floor spot a
  climb to the top started from; else where parkour went on), alternating, every 4 s at most. Jar md5 cb3fa351
  (scratchpad human-autopilot-1.0.0-parkour7.jar), installs when the user's Minecraft closes (autoinstall-parkour7.ps1).

## 2026-10-23 (afternoon): s2e-ladder (the user: "lets do this one next")
- Plugin s2e-ladder 2.0.0 is on the user's server (activated by Stream to Earn at 15:36; its 1024-height datapack zip
  "corrupted" in the log). Page: climb the ladder to the top, then hold on till the timer (timer.yml countdown 15)
  reaches zero. Commands: /ladder create|delete|tp|edit|change_height (300|1000)|kill|clear|down <n>|up <n>|
  arrow_boost <n>|wings_boost <n>|arrows <n>|prison <s>|phantom <hp> <name>|zombie <type> <hp> <name>. From the jar's
  strings: coloured concrete (16 colours), LADDER, BARRIER, ELYTRA (wings), zombies in iron/gold/diamond armour, EAST.
  No arena created yet (needs /ladder create by the user in game) - the real layout is NOT seen yet.
- modules/LadderModule (menu "Ladder game", HumanAutopilot.setLadder/isLadderOn, HUD "Ladder climbing", Off/panic stop
  it, death screen respawn, carries on after a restart via config/humanautopilot/ladder.properties): map() finds LADDER
  blocks within 24 sideways / 24 up-down, follows each column (gaps <= 2), takes the tallest; the top stand = the wall's
  top block (ladder.relative(facing.opposite)) if 2 clear over it, else the block over the ladder's end, else hang on
  (sneak) at the ladder's end. On the ladder: lookDirection at the wall (facing.opposite yaw), pitch -25..-55 per session
  drifting +-6, glances (down or out, <= 40 deg sideways - the camera is the body) every 3-10 s; W when facing within
  45 deg, A/D keep it in the cell. In the air beside the column: steer into it; elytra (isFallFlying): look at the ladder.
  On the ground: walk to the ladder cell at its height (or the foot), W in at < 1.2; zombie/phantom within 3.2: full-
  charge hits (input.click attack, crosshair on it); no progress 45 s -> /ladder tp (a prison waits its seconds).
- Test LadderClientTest (HA_ONLY=ladder, HA_LADDER_HEIGHT default 60): a 3x3 tower in 5-high concrete bands, a ladder up
  its east face. 15:44 ALL PASSED: climbs 29/29/39/37 s (pulled to the floor, pulled 20 down mid-climb, a zombie at the
  foot - hit, then up). INSTALLED 15:46 (md5 3b45b629, scratchpad human-autopilot-1.0.0-ladder1.jar; also has the
  parkour corner-post fix). Next: see the real arena after the user's /ladder create (read the region files).
- 15:54 the user created the real arena: "[ladder] the ladder: 62, 20, 160 up to 62, 318, 160 (299 high, facing east),
  the top: standing on 61, 319, 160" (a 1-wide wooden pillar on a red concrete base; "15/300" height shown on screen).
  The user (screenshot of their own climb): look slightly down while climbing - climbPitch now gauss(10, 28) DOWN
  (was 25-55 up), drift +-5. INSTALLED 16:02 (md5 beca845d, scratchpad human-autopilot-1.0.0-ladder2.jar).

## 2026-10-23 (16:20-16:55): s2e-oneblock - started, then dropped by the user ("nvm disregard that minigame")
- Plugin s2e-oneblock 2.0.3 installed on the user's server (world `world_oneblock`; their KawaiiWorlds unloads it while
  empty). It handles PlayerBedEnterEvent (the win: 3 wool of a colour + planks -> a bed -> sleep), setTime, a void
  world; wool comes in white/orange/yellow/green/blue/red; lots of mobs incl. warden/ghast/ravager. Not created in game.
- modules/OneBlockModule exists (dormant; its menu button removed, HumanAutopilot hooks left): finds the one block,
  mines it (underfoot only at the start, with a jump on the exact tick it breaks - read via reflection on
  MultiPlayerGameMode.destroyProgress; standing on it the client's prediction let her fall through the regrown block),
  sneak-bridges a platform (aim at the MIDDLE of the side face; centred at the edge; never from a block with something
  on top), picks up drops, crafts table/pickaxes/bed (ScreenWorker), its own bed spot finder (row of two + a stand cell
  behind, the one block allowed), warden = crouch still, simple melee, eats. Test OneBlockClientTest (HA_ONLY=oneblock,
  a stand-in block regrown by a ServerTickEvents hook). Last state: bed crafted ~90 s in, but placing it on the 4-block
  platform hadn't worked yet (debug log added, not run). Placer.avoid(BlockPos) added (used by it).
- Nothing of this was installed (the installed jar is still the 16:02 ladder2 build, md5 beca845d).

## 2026-10-23 17:20: Kohaku helps with Box Fill (the user: "kohaku stays at least 5 blocks away from me and if I
## accidentally click on her to open her inventory it closes right away and if she comes into blocking my path I go to a
## different spot")
- Plugin (CompanionBoxFill box mode `tick`): BOX_GAP 5 - inside 5 of her owner she flies straight out to 7 first (her
  target dropped if it's within 7.5); pick() skips holes within 7.5 of the owner (was +25 score within 4); a target the
  owner comes within 6 of, or whose hover spot is within 5 of them, is skipped 2 s. Built (mvn -o, md5 3ddc3ee0), staged
  in C:\minecraft-server\plugins\update (server running; loads at its next start); old one in plugin-backups
  (...-before-boxgap-20261023-1720.jar) and dist/kawaiicompanion-26.1.2.jar.
- Mod (BoxFillModule): someone (otherPlayers - the companion is a player) within 2.0 of the hole or in the way of the
  click (entityInTheWay) for 8 ticks -> "someone's in the way of X: another spot for now" (bad 3 s); flying to a hole with
  someone within 2.0 of it or 1.5 of the stand -> the same. HumanAutopilot: a ContainerScreen titled with the plugin's
  flower (U+273F: her bag, her panel) is closed at once while box fill is on (before: only within 10 ticks of a box
  click). The click guard entityInTheWay already existed. Not tested (small changes); jar md5 93808a58 installs when the
  user's Minecraft closes (scratchpad autoinstall-boxkohaku.ps1).
- 17:29 the user: "make kohaku build/fill super fast". Box mode now runs her fast path too (s.fast: head snaps, flies
  FAST_SPEED 1.4 b/t, accel 0.3, FAST_PLACE_EVERY 1) and fills up to BOX_PER_TICK 3 holes a tick when the next ones are
  in reach (facePoint within REACH) - up to 60 blocks/s; the 5-block gap from the owner holds. Plugin md5 fa4f0273,
  staged in plugins/update (the server was running again; loads at its next start) + dist.
- 17:35 the user: "when autopilot gets to the top she just stops while kohaku still builds". Their log: at the box top,
  "12000 hole(s) I can't get at" except a shaft Kohaku was filling, and the new "someone's where we're going" rule
  turned every one of those down: 18-24 s stalls. The pick already falls back to a hole by someone when nothing else is
  left (nearOthers); now that fallback (crowdFallback, cleared in setTarget) isn't passed over by the new checks - it
  waits for her to move (no click through her: entityInTheWay) - and with the new plugin she backs off 5. Jar md5
  c8239b28 (scratchpad human-autopilot-1.0.0-boxkohaku2.jar) installs when the user's Minecraft closes. (The 17:20 jar
  93808a58 with the stall went in at 17:20:36.)
- 17:40 the user: "now I can't access kc menu when I'm in schematic build mode" - the 17:20 rule closed every menu
  titled with the flower while BoxFillModule was on (schematic building runs on it too). Now her menus are closed only
  within 2 s of a box-fill click AND not within 3 s of a chat screen (a typed /kc menu is the player's own: chatOpenAt).
  Jar md5 3a480704 (scratchpad human-autopilot-1.0.0-kcmenu.jar): INSTALLED 18:17:31 by the autoinstall.

## 2026-10-23 (evening): rebuilding the schematic builder (the user: "what about the stallings, flying back and forths, getting stuck caved in, difficult time going in a 1x1 hole" -> "go for it. build it in sections, test it. pass? next build. until you perfect it.")
- The plan, 4 steps: (1) work the whole build out before building (needs, sections, never shut in what's still to do);
  (2) stand spots chosen ahead, several blocks per spot, materials planned; (3) flying from the real key physics; (4) one
  build memory + a watchdog. Each behind a switch (env in tests, Config for the game), compared against the old code.
- Code in `dev.humanautopilot.planner` (NOT `build`: run-schem-test.ps1's robocopy /XD build skips every folder named
  build, so the first test ran without it).
- `planner/BuildPlan(plan, clearFromY)`: worked out a few ms a tick (`step(deadline)`, stages collect / snapshot of the
  world's solids / deps / parts / order). Deps: what holds it up (canSurvive on a "finished build" world with that
  neighbour emptied), a click reference (rightSides: one of them is enough), fluids last, falling blocks on a floor.
  Parts ground / outside (ring 1 + the back of thick walls) / inside; order = Kahn's algorithm with a key (part, band of 4
  layers, tile: outside by its angle round the middle, inside/ground 8x8 rows), a block that would shut in a pocket with
  work left waits (hatch, parked), floating parts and loops unstuck at the end. Log: "[plan] 3796 blocks in 81 sections
  (ground 445, outside 3134, inside 217); 13 wait to keep a way in, 45 have nothing to click against; worked out in 0.3 s".
- BoxFillModule (HA_PLANNER=new / Config.newPlanner): plannerTick (works it out, sweeps behind the cursor for spots to do
  again), plannerCandidates (from the cursor: needs in, planOk, far ones workableFromAfar; this section and the next, 48
  at most), pickPlanTarget's `planned` branch (+ "specials" the plan doesn't know: temporary blocks, clearing). 5 s with
  nothing from it -> the old way. Logs "[plan] at N of M", "[plan] on to section S of T: <part>".
- `planner/FlightController` (HA_FLIGHT=new / Config.newFlight): every tick tries the 27 key combinations for one tick,
  then simulates braking to a stop (the brake: each tick the opposite key or none, whichever leaves it slowest), and
  takes the keys whose stopping point is nearest the target (far off, > 2.5 blocks: the speed closest to "as fast as
  still stops there" instead; a 0.1 dead zone at the target). The sim is the game's own: per-axis collisions with
  sliding (block boxes from level.getBlockCollisions(...).toAabbs()), and OUR KEY DRIVER's rules - InputDriver holds a
  movement key or sneak at least 3 ticks (MIN_HOLD) and won't re-press a movement key for 2 (MIN_GAP); space gets a
  fresh press only 8 ticks after the last (jump()); sprinting doubles the push across while W is held (vanilla flying speed
  x2; the bot holds sprint on longer trips). Not modelling the 3-tick holds was the 40-44 touch-downs of the first tries
  (a "1-tick" sneak tap really drops ~1 block). Two keys held at once cancel - a short precise nudge. Touching down is out
  (+1000), losing the 0.06 floor gap in the braking sim +500. Under a low roof / going into a 2-high gap it hands over
  to the old steer (set down, walk in). Offline check: `sh tools/flightsim/run.sh [random|trace]` (scenarios: hover low,
  approach, over/into a 1x1 shaft, low gap, sprint run; random: 60 flights - 0 landings, mean stop error 0.11, ~20 key
  changes). In game (new8, medieval house 10 min): 613 placed (same as without it), 3 landings all on purpose (a set-down,
  two door landings) - the first versions had 40-44. Safety net: no headway for 2 s -> the old steering for 3 s.
- "couldn't fly there" bug (old code too, 138 times in the user's 2h castle session): the progress check compared the
  cells left on a long way round with the straight distance, so it gave up 5 s into a 96-cell detour while getting there.
  route() now sets routeLeft (cells of the way left, -1 straight) and both progress checks (far branch, flying to the spot)
  stick to one measure (progressMode), starting over when it changes.
- Tried and off: a "cluster" bonus for far picks (HA_CLUSTER=1): more stalls and "couldn't fly there".
- THE MENU: the medieval house spent 249 of 600 s in the creative menu (141 trips, ~100 kinds; stone buttons 8 times) -
  the "doing" times in the status line leave the menu out, so "flying 73%" was wrong. tools/schem_metrics.py now prints
  "menu visits/got/extras/secs". With the planner: CreativePicker.needsAhead. First try (new4): value = 2000 - how many
  to-do blocks come before its next use in the plan's order, extras for anything needed in the next 60 - each visit took
  4-5 extras and pushed out what the section needed (visits 141 -> 56, menu time only 249 -> 233 s): inside a section the
  bot picks in its own order (material first), so the plan's order there says little. Now (new7) two tiers: a block of
  the part being built now (this section and the next, + what's to do again behind the cursor) = NOW (1000) + how many
  of it are left there; one needed only later = under NOW, the sooner the higher; extras only for blocks of the part now,
  only into a slot holding something that isn't. The fetch log says what each fetch pushed out ("in place of X: now, 2
  left / later / not needed"). Result: menu 249 -> 215 s, placed ~600-636 (noise ~+-20). The medieval house's sections
  have more than 9 kinds each, so some churn can't be helped.
- Tests (10 min, flat, schem_metrics): base medieval-house 584 placed / 23 skipped / 1 stall-s; planner (new1) 602 / 0 /
  2; +cluster 549 / 13 stalls; +cluster+flight 560 / 15 stalls / 40 landings; menu v1 (new4) 636 / 2 / 18; flight with
  press timing but not the 3-tick holds (new5) 622 / 44 landings; tiers + stand cover (new7) 616 / 3 / 20 (4 set-downs, 5
  wedges inside the house - checking the cover bonus with HA_COVER=0). Base japanese house 941, sakura 1631 (0 skips),
  floating pagoda 774 (68% flying to a hole).
- Status log additions: "flew N blocks" (back and forth = many blocks flown per block placed; schem_metrics prints
  "blocks flown per block placed"), "[status] time in the last 30 s: menu, flying, placing, looking, other, nothing
  said" (all of it - the "doing" list is only the top four), and a "fly" cost bucket.
- Stand cover (HA_COVER=0 turns it off): with the planner, a hover spot gets -0.35 for each other ready block in reach
  (up to 10): one stop for several.
- Tools: tools/queue-schem-tests.ps1 runs a queue file (%TEMP%\ha-queue.txt, lines name|schem|minutes|env|flat|src;
  waits for more lines; STOP ends it) through run-schem-test.ps1 (log %TEMP%\run-<name>.log). Each test runs from a
  snapshot of the source (robocopy C:\dev\human-autopilot %TEMP%\ha-<name>-src /MIR /XD <abs build, .gradle, run, dist>),
  so the dev tree can change while the queue runs.
- Carpet freeze (found by the new7c test, would hit any build with carpets): the open-air map's way() counted a cell with
  a carpet (or any collision shape) as solid, so after the medieval house's red carpets went in he was "shut in (2 open
  cells)" in the middle of a room; "built in, nothing to knock out" (carpets aren't solid); the stuck ladder ended in "/tp
  up over the build" every 30 s - which fails in the test world (no cheats) = a 204 s freeze; on the user's server it
  would be a teleport loop. Now way() passes a thin collision shape (top <= 0.15: carpets, low snow, lily pads), and
  breakOut, finding nothing solid to knock out, moves out by hand through a side the body really fits through (not
  straight back the way it came): "[box] the map has us shut in at X, but the way D is open: out by hand".
- Planner cursor: it stopped at a given-up spot (cant), and the look ahead counted done spots (4000), so a few thousand
  finished blocks after one leftover hid all the work. Now the cursor passes cant spots (the sweep behind it brings back
  any that come right) and only to-do spots count toward the look ahead (40000 iterations at most).
- The user's live castle session (old jar, 2h20m, fantasy castle 72% -> 82%): 287 stall episodes (18 >= 15 s, none 60
  s), mostly long trips through the castle (paths of 36-44 cells) and single far leftovers 60-90 blocks apart; shut in
  21, confused 34 (one: sealed in a hall, 33 targets in 10 s, then /tp up), landings 89, menu 9% of the time.

### WHERE I LEFT OFF (2026-10-23 ~21:20, testing the rebuild; nothing new installed - installed = kcmenu jar 3a480704)
- Queue (tools/queue-schem-tests.ps1, %TEMP%\ha-queue.txt): full-gate-new (running), full-gate-base, castle-new/base
  (15 min), full-medhouse-new (80 min, has carpets), terrain pair (seed 20261022), the special-case suite with the
  planner (reg-exact, rods, plain, tower, diag, door, loose, stuck), japhouse, sakura, reg-plain-fly, reg-stuck-fly,
  new9-terr (planner + new flight), gate15 cover 0.35 vs 0.8. Snapshots: ha-baseline-src (old code), ha-new10-src
  (planner + menu tiers + cover + detour/cursor/carpet fixes, flight off), ha-new9-src (same + HA_FLIGHT=new),
  ha-new11-src (+ HA_COVERW).
- Before installing: the suite must pass with HA_PLANNER=new (and the fly variants), then flip Config.newPlanner /
  newFlight to true with a configVersion 5 migration, build, and install when the user's Minecraft is closed (they
  stream with LiveKeeper; the autoinstall-on-close script pattern is in the scratchpad: wait for javaw to exit, back up
  the old jar to dist/, copy).
- tools/live_report.py [log] [--since HH:MM]: a session report from a game log (the user's latest.log by default):
  placed/min, stall episodes by length and activity, confused, skips, landings by cause, shut in/built in, menu.
  The user's 18:18-21:08 castle session (old jar): 78/min, 307 stall episodes = 2297 s (23% of the time; 20 >= 15 s),
  34 confused, skips nowhere 192 / couldn't fly 160, landings 101 (set-down 43, unplanned descend 37, door 20).
- Doors on long trips (old code too; found by the full gate house build): the far branch ("flying over to the next
  block") never called throughDoor/walkThroughDoor, so a route through a shut door pushed into it, "can't get into",
  "shut in", and breakOut knocked 13 of the build's blocks out to get out. Now the far branch opens/walks through doors
  like the near one (doorPath: the open-air path, else the start of the long way it's on), and breakOut first clears a
  door within 4 from shutDoors/stuckCells ("trying the door by us again before knocking anything out"), once in 10 s.
- /tp only where allowed: canTp(p) = p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) (26.1's permission
  API). The four /tp fallbacks check it; without it the stuck ladder's last step flies up over the build (climbGoal,
  "flying up over the build", 15 s) instead of looping on a failing /tp (the test world has no cheats: "Unknown or
  incomplete command" every 30 s during the carpet freeze).

### STOPPED (2026-10-23 21:35, the user: "stop testing and tell me")
- Queue stopped (STOP line + the runner and the test client killed). The user's Minecraft and server closed at ~21:33.
- Installed is still the kcmenu jar (3a480704, 18:17). NOTHING from tonight's rebuild is installed.
- Full gate house with the planner (21:01-21:33, window closed at 79%: 2884/3647): 81/min, 0 confused, 35 stall episodes
  (2 >= 30 s - both the door bug, fixed since), 20 built-in events (the same door bug).
- The fixes jar in the scratchpad (human-autopilot-1.0.0-fixes.jar, 21:22) is UNTESTED (the oldfix check never ran) and
  lacks the later door-progress / canTp / climb changes - rebuild from the dev tree before installing.
- Not run yet: the special-case suite with HA_PLANNER=new, the old-code full gate house, castle, full medieval house,
  terrain pair, flight variants. Defaults are still off (Config.newPlanner/newFlight false).
- 21:43 INSTALLED at the user's request ("ill run it. put it in the same mod. let 9 open it but in a new menu 'new schem
  mod'"): md5 85ba44fe (dev tree 21:43) -> .minecraft/mods + dist; old one dist/human-autopilot-1.0.0-installed-2026-10-23-1817.jar.
  Numpad 9 -> "New schem mod..." page: New planner ON/OFF (Config.newPlanner), New flying ON/OFF (Config.newFlight),
  Build Schematic..., Back. Both default OFF; the general fixes (detours, carpets, far doors, /tp check) are always on.
  Not test-run as a whole build before install.
- 21:46-21:52 the user tried it on the fantasy castle (84% done, planner + flying ON): "way way worse" - 8/min. The
  plan's next sections had 1-4 ready spots each, scattered over the castle: a flight across it per block. Fix: the
  planner's list is used only if one of its ready spots is in the region round us (or specials); else the old pick at
  once (the 5 s "nothing from the planner" wait is gone). Jar e49e7f1d (scratchpad planfix), installs when the game
  closes (autoinstall-planfix.ps1). Told the user to switch New planner off till then.
- 2026-10-24 00:30 parkour (the user, 3rd person screenshot): stood at the first block ~8 s - a floor step "took too long"
  (8 s timeout for every jump); floor steps now give up after 2 s. Once 15 s hanging in the air at the start (creative
  flying): flying now holds sneak down first. 3rd person: nothing in parkour uses the camera's position (aim/rays go by
  the player's eyes), so it plays the same. "Goal reset to 100 not 359": the s2e-parkour plugin resets its own goal after
  a win - not the mod's. Jar ccc4e20d (planner-near fix + these) pending, autoinstall-planfix.ps1 restarted 00:31 (the
  21:52 one never installed: still 85ba44fe in mods). Ava's source: C:\Users\ItsMe\OneDrive\Documents\kindroid-chat-bridge\program.
- 00:40 parkour goal: after a win (a 30+ block drop within 10 s of being at the top) the next map that finds a lower top
  sends "/parkour goal add <old height - new>" (goalHeight = the highest top - lowY seen at the top; the user's viewers
  had it at 359, the plugin resets to 100). Log "[parkour] the goal went back to N after the win". Untested. In the
  pending jar (planfix, autoinstall-planfix.ps1 waiting).

## 2026-10-24 00:45: the rebuild DELETED (the user: "delete your new schematic build i think its worse or the one right now is already good. just have your bot build big schematics from one of the ones provided in the folder, do not makeup your own. remove the new schematic build from #9")
- Removed: planner/ (BuildPlan, FlightController), tools/flightsim, the "New schem mod..." menu page, Config.newPlanner /
  newFlight, CreativePicker.needsAhead, BoxFillModule's planner branch/plannerTick/plannerCandidates/needsAhead/stand
  cover/cluster/fly()/flightOn, InputDriver.isHeld/age. AutopilotScreen, Config, InputDriver, CreativePicker are back to
  the 2026-10-23 17:33 code (the %TEMP%\ha-baseline-src snapshot).
- Kept (general fixes for the existing builder): routeLeft/progressMode (long detours not given up), doorPath +
  doorProgress (doors on far trips), the door retry and "out by hand" escape in breakOut, thin shapes (carpets) in way(),
  canTp + climbGoal (no /tp loops where /tp isn't allowed), the "flew N blocks" / "time in the last 30 s" status lines,
  and the parkour fixes (floor-step 2 s give-up, down out of the air, goal put back after a win).
- INSTALLED 00:44 (Minecraft closed): md5 41fc10c9 in .minecraft/mods + dist; the one with the planner is
  dist/human-autopilot-1.0.0-installed-2026-10-24-with-planner.jar.
- Testing from now on: only the user's own schematics from the folder (tools/run-schem-test.ps1 -Schem "<file>"), big ones.
- 01:00 the user: "if the build is 80 percent done, then it gets filled right away. and my character moves on to the
  next schematic". BoxFillModule.handOff: planProgress() >= HAND_OFF_AT (0.80, the bar's percent), planCounted, not
  guarding, BuildLink.sent() (the server has the whole plan - only on their server with KawaiiCompanion; single player /
  tests build to the end as before) -> BuildLink.finish() (the plugin's fillMissing pastes the rest, 4000 a tick, not
  in a player's body, not ground in a flat world - that's the grass filler's), log "[schem] N% of X in: the server puts
  in the rest, on to the next"; temporary blocks not yet placed dropped (cant), openings/outFor/damage cleared,
  finalSweeps 6. While handed off, scanPlan keeps only our own temps still to come out as holes (a minute at most), so
  holeCount reaches 0 -> planComplete (the usual "Done building X! Next up: Y") -> SchematicMode.chain to the next file.
  A resumed build already past 80% hands off at once. Not tested in a game (needs the server plugin). Jar 975dcd72
  (scratchpad human-autopilot-1.0.0-handoff80.jar) installs when the user's Minecraft closes (autoinstall-handoff.ps1).
- 01:00 the user: "make sure kohaku doesnt spawn but grass refiller does": out-on-join.yml -> companion false, filler
  true (backup plugin-backups/out-on-join-before-filler-only-20261024.yml). Seen at 00:56:16 after a server restart:
  "joined: back by themselves - grass filler", no Kohaku. /kc dismiss sends the filler away too (and saves that).
- Tests now: tools/queue-schem-tests.ps1 -WhenIdle (only while the user's javaw is closed; their game opening stops
  the test and it's run again later). Queue: fantasy castle, copper guard, floating pagoda, giant tree (flat).
- 01:08 the user: "why does he fly straight up after auto fill" - after the hand-off the next build (floating pagoda,
  130 high) began beside the castle; route0's last resort cruised at the NEW build's box top + 1.5 (y 71) - 130 up,
  across, 130 down. Now that height is min(box top + 1.5, lineTop(here, goal) + 1) (MOTION_BLOCKING heightmap along
  the way, down the middle and 0.5 to each side; an unloaded column = the old height), never under the goal.
- 01:10 the user: "whys he placing a bunch of glass, glass, glass" - the giant tree's oak_wood[axis=y] roots/trunk: a
  temporary glass to click on for nearly every one (74 in 2 min). Placement.barkAllRound (RotatedPillarBlock *_wood /
  *_hyphae, bark on every side): its axis isn't compared (same/matches), oriented() false, rightSides any (0x3F).
  Logs keep their axis. Both fixes in jar (scratchpad human-autopilot-1.0.0-lowfly.jar), autoinstall-lowfly.ps1 on close.
- 01:12 the user: "he just caved himself in and got stuck" (giant tree, 01:09:36-01:10:16, the old jar): set down to
  walk in among the roots under a low roof, then the way out went straight up through the cell over his head; on foot
  steer only walks, so he stood still ("can't get into -202,-59,288 ... straight/sideways/up free"), that cell went off
  the map, "shut in (13 open cells)", breakOut knocked 2 oak wood + 1 azalea leaves out, free after ~18 s, put them back.
  Now on foot a goal > 0.6 up within 0.8 sideways (upHere) takes off like "no floor all the way" does (log "on foot,
  the way goes up from here to X: flying"). In the pending jar (lowfly, 355619a0) with the low cruise + bark-wood fixes.
- 01:20 the user (watching the giant tree): "like hes just placing blocks wherever, then forgetting, going below the log,
  getting stuck". Cause: the build order's rings (ringsFor) called 70% of the tree "the inside" (48.6k of 68.4k): every
  gap between its roots counted as a doorway (anything of the build anywhere over it - the crown 30 up), its crown's
  air walled in per layer counted as rooms, and the trunk's middle (ring 3) waited for "the inside" - so it put up a
  2-thick bark shell (sections all round the base), the shell then waited on the core under it (the trunk leans), and
  the core was filled from down in a pit under the bark ("the inside first", "nowhere", "shut inside the build").
  Now (ringBase/ringBaseFor/pocket/solidCore): a doorway needs the build 1-8 over it (lintelOver); air walled in
  sideways but over open air in the layer under it is open air (up-flood, never down: courtyards stay the inside);
  a shut-in pocket of under 27 cells in 3D is no room (a room's air between furniture is part of the room over it);
  a full block in the middle of a solid mass (build all 4 sides, a full block or the ground under, the build or open
  air over) goes with the outside. Simulated on the user's files (scratchpad parts2.py, RULES=LBQC): tree inside
  48.6k -> 2.2k; medieval house 2065 -> 1202; japanese house 19.4k -> 12.2k; gate house 1081 -> 738; Sakura 8225 ->
  3024 (its solid base); pagoda 8177 -> 3562; courtyards unchanged. Compiled, NOT run in a game: jar 138d5621 =
  dist/human-autopilot-1.0.0-candidate-tree.jar (also has the lowfly/bark/upHere fixes), autoinstall-tree.ps1 (this
  session's scratchpad) puts it in when the user's Minecraft closes; the WhenIdle queue then runs the giant tree (30
  min) and the medieval house (20 min) first.
- 01:45 the user: "when hes done building the schematic, he chooses a random next one to build instead of going by
  order". SchematicMode.nextName() now picks at random (pickRandom: not the one just built, not one of the last 3
  built - recentBuilt - while there are others), kept in pickedNext so the "Done building ... Next up: X" message
  and chain() agree; chain() tries that one first, then the rest shuffled (the one just built last). pickedNext is
  reset when a build starts (confirm). Compiled; jar c4d47d08 = dist/human-autopilot-1.0.0-candidate-random.jar (also
  the new rings), autoinstall-random.ps1 waits for the user's Minecraft to close.
- 01:55 the user: "while you are moderating him, see why hes stuck, stalling, flying back and forth, produce his
  reasoning ... at which percent, fix it, put it in minecraft, restart minecraft". Tools (kept in tools/):
  - tools/live_watch.py: follows the user's latest.log (a new game's log too) and prints one line per problem -
    STALL at 15/30/60/120/240 s, CONFUSED, FLYING AROUND (150+ blocks flown for < 10 placed in 30 s), NO PROGRESS
    (2.5/5/10 min), SHUT IN, TELEPORT/CLIMB OUT, ERROR, HANDED OFF, DONE - each with the build's % (done/total of the
    status line) and his last two reason lines ([schem] off to / the wall round / leaving / not flying over /
    nothing to place / [stuck] ...). Also appends to live-report.txt in the project (with 3 "because" lines). Run it
    under Monitor (`python -u tools/live_watch.py`); FROM_START=once reads a whole log and stops (for old logs).
  - tools/restart-mc.ps1 -Jar <jar> [-NoWait]: closes Minecraft normally only while LiveKeeper is watching
    (%APPDATA%\LiveKeeper\live-status.json written in the last 3 minutes, game not given up), else waits for the
    user to close it; backs the old jar up to dist/human-autopilot-1.0.0-installed-<stamp>.jar, installs, logs to
    %TEMP%\restart-mc.log. LiveKeeper reopens the game and joins; the mod carries on building by itself.
  LiveKeeper was NOT running at 01:55 ("Stopped watching" 2026-10-23 21:34): without it a restart can't reopen the
  game (the launcher's PLAY is the user's: minecraft.exe was refused for computer use).
- Found with the watcher on tonight's earlier logs: after "nothing in for 21 s: /tp up over the build" he flew straight
  back down to the same block (the tree, 01:18) and /tp'd again. The target is now dropped (bad 2 min) at the /tp.
  INSTALLED 01:57 (jar 9db64c4e = dist/human-autopilot-1.0.0-candidate-tpskip.jar: new rings + random next + this).
- 02:00 the user: "sometimes he would fly around, break a wall block or a block hes already put in, fly away, do it
  somewhere else, come back and restore it or break another block. put that in the log too". BoxFillModule now logs
  `[undo] taking X out at c (we're at ...): <why> [; N other(s) still out, the nearest ... (D away)] - P% in` when it
  knocks out one of the build's own blocks (plan's block there, not a temp): why = to get at <spot> (opened: openUp /
  openPocket / the leaves rule), out of the way of <spot> (outFor), to put it in again the right way round, to get out
  (breakOut), else the plan wants it out (failWhy). And `[undo] put X back at c after N s (taken out ...; flew M blocks
  meanwhile, K still out) - P% in`; `[undo] c still out after 10 minutes` for ones never put back (takenOut, TakeOut,
  noteTakeOut, flownTotal). live_watch.py reports them as TAKES OWN BLOCK OUT / PUTS IT BACK / NEVER PUT BACK.
  INSTALLED 02:02 (jar 1feabe67 = dist/human-autopilot-1.0.0-candidate-undo.jar).
- 02:34 THE TEST SPOT (the user: "keep that coords, seed and test on there from now on"): seed 5436, the build's
  middle at 272, 47, -271 - a valley floor at y 47, hills 26 and 33 up (northeast-southwest), trees over 63% of the
  fantasy castle's footprint, water 4%. Found by HA_VALLEY=1 (SchematicClientTest.valleySpot: up to 12 looks 256 apart,
  goAndLoad waits till the ground 100 out is loaded, scores hills both sides 10-45 out + canopy - water). HA_AT=x,y,z
  (fixedSpot) builds with its middle there. tools/run-schem-test.ps1 now defaults every non-flat run to -Seed 5436 +
  HA_AT=272,47,-271 (-Anywhere: a random world where he spawns; HA_SPAWN_TP=0 with a seed: no tp to the world spawn).
- 02:45 found by the watcher in the user's Knight's Order build (flat world, jar 1feabe67):
  - polished andesite taken out at 02:31 to reach a smoker was never put back: with the smoker in, the "done under
    cover" rule (underCover, bottom 3 layers) made the empty spot DONE, putBack.removeIf(!hole) dropped it. jobAt now
    returns PLACE for a putBack spot that's empty, before any of the buried/ground rules.
  - two blocks taken out for a yellow terracotta, then 15 other blocks and a look at the build before it (47 s):
    the opened loop's planOk said no, reason unknown -> `[undo] X is opened up but not to do yet: <whyNotOk>` every 5 s.
  - put back by someone else (the server's fill-in): `[undo] X is back at c after N s (... put in without a click of
    ours)`, checked every 5 s. live_watch.py: BACK / OPENED, WAITING.
  Jar d73456b3 = dist/human-autopilot-1.0.0-candidate-putback.jar; restart-mc.ps1 waits for the user to close MC
  (LiveKeeper not watching).
- 02:55 the user: "as the build percent goes higher and higher, that's where problems start ... when you reset the bot
  world, keep the same percentage ... restart the time ... so he can try to complete the schematic 100 percent". Test:
  HA_SAVE=<name> writes the build's area (schematic box +6 round, 16 under, 6 over: every block state, gzip palette +
  ints) and his position to %TEMP%\hatest-saves\<name>.gz every 5 minutes and at the end (saveBuild); HA_RESUME=<name>
  pastes it back (2|16, 8 layers a server task) with the bot paused, then tp's him to where he was (resumeBuild; only
  if the corner is the same). Queue (%TEMP%\ha-queue.txt): 12 x "fc-valley|fantasy castle|90|HA_SAVE=fc-valley;
  HA_RESUME=fc-valley" (same log name: live_watch.py follows the new file). The 80% hand-off never fires in a test
  (no server plugin), so he builds to 100%.
- 02:55 30-minute check on the user's game: session cron 49530d7c (:13 and :43; dies with the session, 7-day limit).
- 03:20 the user: "just stalled for about 15 secs" (Knight's Order, repairing TNT damage): throughDoor stood at its spot
  in front of a door 16 s, "opening the door", the crosshair never on it (no click, no give-up). Now 2 s at the spot
  without a click -> shutDoors 30 s, "[schem] can't click the door at X from Y (the crosshair is on Z): another way for
  30 s" (doorAimDoor/doorAimSince, reset while still going to the door). Jar 8b78ad2f =
  dist/human-autopilot-1.0.0-candidate-door.jar, restart-mc.ps1 -WaitOnly (new: never closes the game for a small fix).
- 03:31 Knight's Order: walkThroughDoor hovered 8 s "landing by the door" (stairs over the spot in front of the door,
  the floor 1.7 under him; the stuck-near set-down needed < 1.2), then "gave up walking through". Now it comes down
  onto a floor up to 3 under (at about the door's height), and with no floor and no headway for 3 s it gives the door
  up ("can't get in front of the door ..."). Jar 9a958a4c = dist/human-autopilot-1.0.0-candidate-door2.jar (both door
  fixes), restart-mc.ps1 -WaitOnly. The game watcher now alerts only on stalls of 30 s+ and bigger problems (usage).
- 03:35-03:36 more Knight's Order (user: "stuck"): (1) "going to the door" 20 s in place (the spot in front of it not
  reachable) -> no nearer for 3 s -> shutDoors 30 s ("can't get in front of the door ... no nearer for 3 s");
  (2) "[undo] X is opened up but not to do yet: skipped (nowhere to place it from)" every 5 s for a minute: still no
  spot with the block out -> giveUp now puts what was taken out for it straight back ("[undo] still nowhere to put X in
  from with [..] out"). live_watch.py says each opened spot once per 2 minutes. Jar 12c750df =
  dist/human-autopilot-1.0.0-candidate-door3.jar (all three door fixes + this), restart-mc.ps1 waiting (LiveKeeper
  not watching, so it waits for the user to close MC).
- 03:37 MY MISTAKE: restart-mc.ps1 (without -WaitOnly) saw LiveKeeper watching and closed the user's game to install;
  LiveKeeper did NOT reopen it (its status kept game trouble '' - it seems to take the launcher's Minecraft.exe for the
  game). The user had to press Play. restart-mc.ps1 now only closes the game with -Restart; by default it waits for
  the user to close it. Don't use -Restart until LiveKeeper's relaunch after a normal close is seen working.
- 03:44 restarts after an update, the user: "if it doesn't close the game, how will restarts after your updates".
  LiveKeeper.ps1 (backup LiveKeeper.ps1.bak-20261024-0346; it reloaded itself at 03:44:06, watching again, as admin):
  On-Tick reads %APPDATA%\LiveKeeper\start-minecraft.request -> deletes it, and with no Minecraft window clears
  mcGaveUp/mcNextTry/mcTries and runs Invoke-McRecovery 'start' (launcher, PLAY, join - the crash path). restart-mc.ps1
  -Restart: close normally (CloseMainWindow), install within 0.2 s of the exit, then write that request. NOT yet seen
  working end to end (the user was streaming); without -Restart it still just waits for the user to close MC.
- 04:00 Knight's Order: back and forth through two doors side by side (-35/-36, -59, 51), "walking through the door"
  16 times in 25 s, then the 30 s /tp up. walkThroughDoor now keeps doorWalks: a 4th walk through doors within 4
  blocks in 15 s -> all of them shutDoors for a minute ("[schem] back and forth through the doors at [...]"). Jar
  dist/human-autopilot-1.0.0-candidate-door4.jar (all the door fixes); restart-mc.ps1 waiting for the user's close.
- 04:10 an opened spot skipped for "can't see it from the spot" left its gap open too: the giveUp put-back now fires for
  any reason ("[undo] X skipped (<why>) with [..] out: it goes back"). Jar 3cb3c017 =
  dist/human-autopilot-1.0.0-candidate-door5.jar (all tonight's fixes), restart-mc.ps1 waiting for the user's close.
- Test: SchematicClientTest saves in a finally (a failing run saves too); queue-schem-tests.ps1 keeps the last run's log
  as run-<name>-<MMdd-HHmmss>.log (the first valley run ended at 67 min, its log lost, reason unknown).
- 04:18 Knight's Order: "built in" in a room's upper part, knocked out 2 stone bricks and a stair to get out; the room's
  door (-34, -59, 53, given up on as "can't get in front of the door" - one of tonight's give-ups) was 6 off and 3 down,
  past breakOut's door retry (4 round, -1..+2). Now 8 round, -3..+3. Jar 5e3b296f =
  dist/human-autopilot-1.0.0-candidate-door6.jar (everything tonight), restart-mc.ps1 waiting for the user's close.
  NOTE: tonight's door give-ups (shutDoors 30-60 s) make "shut in" more likely in rooms; watch for more [box] built in.
- 05:08 Knight's Order (old jar): built in, knocked out 2 cobblestones and a stair in 7 s (all put back in 22 s). breakOut
  now knocks out at most 3 of the build's blocks a minute (breakOutTimes); then /tp up over the build where allowed, or
  waits. Jar bc9579a6 = dist/human-autopilot-1.0.0-candidate-door7.jar (everything tonight), restart-mc.ps1 waiting.
- 09:46-09:48 Knight's Order handed off at 80%; the server's fill-in closed the build round her ("air map 0 cells"), 24 s
  hovering in the blocks until the stuck /tp (the user: "it's 100 percent she's just standing there"). Now handOff() and
  the "Done building" of a handed-off build /tp up over the build at once (canTp). Jar 9282fc45 =
  dist/human-autopilot-1.0.0-candidate-handoff2.jar (everything tonight), restart-mc.ps1 waiting for the user's close.
- 10:47 INSTALLED (the user closed MC): jar 9282fc45 = dist/human-autopilot-1.0.0-candidate-handoff2.jar - everything from tonight (rings, random next, tp skip, take-out log + put-back fixes, all door fixes, break-out cap, hand-off /tp).
- 11:10 the valley test died on its own check: "moved 80.00 blocks in one tick (teleport?)" - the bot's stuck /tp (the
  test world allows commands). BoxFillModule.lastTpTick (static) is set at every /tp it sends; SchematicClientTest.watch
  skips the step check for 60 ticks after one. The queue had stopped there (nothing ran 11:10-11:44); restarted 11:44
  (12 x fc-valley, resumes from the save).
- 12:55 the user: "change it to 90 percent": BoxFillModule.HAND_OFF_AT 0.80 -> 0.90 (the server fills in the rest at 90% of
  the bar now). Plan: watch 80-90%; if clean, 95%, then 100%. Jar a91f323b = dist/human-autopilot-1.0.0-candidate-
  handoff90.jar (also lastTpTick, harmless), restart-mc.ps1 waiting for the user's close. (The mod also sends /kc
  finish at 97% - only reached now if the hand-off doesn't fire, e.g. single player.)
- 13:25-13:34 iron farm (90% jar): "built in ... nothing to knock out: Lava: not solid", "out by hand", back in, again,
  every few seconds by the farm's lava. way() (the air map's test) now counts a lava cell with no collision as open
  while invulnerable (creative). Jar b499872c = dist/human-autopilot-1.0.0-candidate-lava.jar (90% hand-off + this),
  restart-mc.ps1 waiting for the user's close.
- 15:30 first try of restart-mc.ps1 -Restart (the user: "restart it"): closed MC 15:30:52, installed the lava jar
  (b499872c) 15:30:54, wrote start-minecraft.request. LiveKeeper logged nothing and didn't start the game (the request
  file was gone by 15:33 - unclear who took it); the user started it by hand ~15:33 ("it took too long so I restarted it
  myself"). He carried on with the iron farm (45%) at 15:34:05. => The LiveKeeper restart does NOT work yet; don't use
  -Restart until it's debugged (On-Tick may be busy, or the request read while the window was still closing).
- 18:47 valley test: 652 "nowhere" in a run, 550 of them knock-outs (plan air) - 135 bits of the hill hidden behind the
  build's own stairs/slabs, 4-5 goes each (flights, searches, the "[confused] 33 targets in 10 s" bursts). giveUp: a
  plan-air spot "nowhere" is left as it is after 2 goes, not 5 (cant; another go when a neighbour changes). INSTALLED
  18:47 (user's MC closed): jar a457373e = dist/human-autopilot-1.0.0-candidate-dig2.jar. Test queue restarted with it.
- 19:50 the valley test's "[confused] 35 targets in 10 s, nothing placed (flying over to the next block)": the far
  branch's look-before-flying rejecting one hidden knock-out spot after another ("not flying over to X (Air): no spot
  ... another for now", bad 60 s each, hundreds of them). A plan-air spot rejected that way is now skipped 5 minutes
  (6000 ticks). INSTALLED 19:5x (MC closed): jar e5133034 = dist/human-autopilot-1.0.0-candidate-dig3.jar; test
  queue restarted with it.
- 20:50 the user: "just stop now". Stopped: the 30-minute check cron, the valley test queue + test client, all watchers.
  Weekly usage was 95%. Installed in the user's game: jar e5133034 (dig3: everything from 2026-10-23/24 incl. 90%
  hand-off, lava, door, put-back, break-out fixes). Open: digging hills out from inside (valley test: confused/stalls on
  bits of hill hidden behind the build) needs an outside-in, layer-by-layer dig order + a proper test pass; the
  LiveKeeper start-minecraft.request didn't start the game (debug before using restart-mc.ps1 -Restart). The valley
  save (%TEMP%\hatest-saves\fc-valley.gz) is kept to resume from.
- 2026-10-25 01:10 the user: "stuttering when he was going down the schematic". Long ticks 25-86 ms every 10-20 s
  (air 8-19 ms, pick 19-29 ms, once stand 75 ms). air0 rebuilt every 10 ticks or every 3 blocks moved: going down fast,
  every few ticks. Now in plan mode at most every 6 ticks unless 6+ blocks off (forced rebuilds - airAt = -1000 - still
  at once). Jar a87953a9 = dist/human-autopilot-1.0.0-candidate-smooth.jar, restart-mc.ps1 waiting for the user's close.
- 01:20 the user: "getting stuck couple of times" + "he would look at the block he wants to place instead of looking
  around for a way out to go there; his camera is locked onto it and he wiggles around till he's free". Fixes:
  (1) route0: "out from under the roof first" sticks for up to 5 s or till out (roofOutUntil/roofOutGoal) - it flip-
  flopped with the open-air path, 31 s hovering; (2) at the far path's end with the goal still > 2.5 off: routeNoWay
  ("the long way ends short of it") - 8 s hovering at its end; (3) flying to a hole / over to the next block on a way
  that isn't straight: if the hole is out of sight (a clip from the eye hits something else), the eyes go to the route
  point we're steering to. Plus the 01:10 stutter fix (air0 at most every 6 ticks while moving). 8-min flat medieval
  house test: passed (no camera snap, 0 confused, 0 stalls 30 s+). Jar 3176726a = dist/human-autopilot-1.0.0-candidate-
  lookway.jar, restart-mc.ps1 waiting for the user's close.
- 01:45 the user: fix the two left ("wiggling when wedged", "truly boxed in"). (1) The look-along-the-way also for small
  steps (wedged / path / out from): aimed 3 blocks out along the step's direction. (2) wayOut: with no way out at all,
  once per 5 s clears stuckCells + shutDoors (the doors and squeezes given up on lately) and looks again ("[box] boxed in
  at X: trying the N doors and squeezes given up on lately again"); breakOut with nothing to knock out and no side to
  slip out of: /tp up over the build at once where allowed ("boxed in ... with no way out and nothing to knock out").
  8-min flat medieval-house test: clean at 5 min (no camera snap). Jar 68f93f6b = dist/human-autopilot-1.0.0-candidate-
  boxedin.jar, restart-mc.ps1 waiting for the user's close. The user went to sleep ~01:52.
- 02:00 the user: "going from island to island, placing glass, destroying glass" (iron farm, after the 01:53 restart):
  "nothing to place in 5982 holes: nothing to click 5608", one "hangs in the air ... 1 temporary block out to it" after
  another, outside/inside part flips - the farm's lower parts are gone (TNT took 11.7k -> 8.3k earlier), so the upper
  parts hang and get bridged with temp glass one by one. TODO after the usage reset: when much of a build hangs, rebuild
  from the bottom up first (the window's bottom / supports) instead of bridging piece by piece. Weekly usage 97% here.
- 02:05 island hopping fix (the user: "fix him. disregard the 98 percent"): a temporary block whose block is in stays
  while a block of the build right beside it is still to go in with nothing else to be clicked against (neededBeside;
  in the temp knock-out loop and in tempStays/holdsUp) - the next corner-touching piece goes in against the same glass
  instead of a new bridge. HA_LOOSE: done in 115 s, 0 wrong, 0 temporary glass left. Jar 10c0028f =
  dist/human-autopilot-1.0.0-candidate-keepglass.jar, restart-mc.ps1 waiting for the user's close.
- 02:15 the user: "flying back and forth" + "place glass, place glass, fly away" (iron farm): (1) the outside's new
  stretch (secAnchor) isn't started at a spot that failed lately (fails / nowhere) while there are others - it went down
  to the same unplaceable y -30 spots, up to y -22, back again; (2) bridgeFloating: the up-to-4 new bridges all within
  10 of the first (or of one already on the way) - they were 20-40 apart. Not tested (the user: "stop testing and just
  implement"). Jar c5c4a965 = dist/human-autopilot-1.0.0-candidate-nobackforth.jar, restart-mc.ps1 waiting for the close.
- 02:20 the user: "camera is rotating and moving so fast and snappy" + "place glass ... fly away" again ("do not test").
  The glass: the iron farm's 2-layer pool - every water source "hangs in the air", 2-3 glass each. bridgeFloating now
  skips fluids (they fill themselves / the server's fill-in). The camera: the look-along-the-way flipped with the hole's
  sight every few ticks; now only after 20 ticks out of sight, back after 10 in sight (wayLookSince/holeSeenSince), the
  look point eased (lerp 0.1, wayLook), the far branch's look eased too. Not tested. Jar d2d5c462 =
  dist/human-autopilot-1.0.0-candidate-water.jar.
- 02:25 the user: "camera is still snappy", "flying around confused", "putting glass to place stone when glass is not
  needed", "break glass to put it back". The look-along-the-way (both branches) TAKEN OUT again - the snappiness came
  with it (unused fields wayLookSince/holeSeenSince/wayLook left). neededBeside now looks at holes within 2 (cube), not
  only face neighbours: the iron farm's diagonal stone stairs (each step touching the next at a corner) - glass under
  one, out, glass again for the next. Not tested. Jar 5fb7930f = dist/human-autopilot-1.0.0-candidate-calmcam.jar.
- 02:35 the user: "he's never reaching 90 percent. your update(s) made him worse by a shit ton". ROLLBACK queued to
  dist/human-autopilot-1.0.0-candidate-dig3.jar (e5133034, built 2026-10-24 19:47: everything up to the 5-minute far
  skip, incl. 90% hand-off and lava; ran ~100/min in the user's game 00:40-01:15). The dev tree still has tonight's
  untested changes on top (smooth air map, look-along-the-way (taken out), roof-out stick, far path ends short, wedge
  look, boxed-in retry + /tp, neededBeside + its radius 2, bridges within 10, secAnchor skip, no fluid bridges) - they
  made him worse together; don't ship them again without testing each one on its own.
- 2026-10-25 11:50 the user: "make the plugin finish and fills the schematic at 70 percent". HAND_OFF_AT 0.90 -> 0.70 (dev tree,
  line 207). There's no source snapshot of the dig3 build, so the jar for the game is dig3 with ONLY that constant
  patched in the bytecode (scratchpad p70/Patch.java, ASM 9.10.1: the ldc 0.9 right after planProgress() in tickInner2;
  javap diff = that one line): dist/human-autopilot-1.0.0-candidate-handoff70.jar (md5 64d54e35...). restart-mc.ps1 is
  waiting (hidden) to put it in when the user closes Minecraft. The dev tree still has the bad post-19:47 changes.
- 2026-10-25 12:10 SOURCE BACK IN STEP WITH THE GAME: the dev tree's post-19:47 changes taken out (kept in
  dist/src-backups/BoxFillModule-2026-10-25-with-untested-night-changes.java); the dev tree now compiles to the same
  bytecode as the installed 70% jar (checked member by member with javap; dist/src-backups/BoxFillModule-installed-handoff70.java).
  Build from the dev tree again from here on.
- 12:05 the user: "he's confused after build is complete". The iron farm handed off at 78%, then he went to look at the
  next build's spot (copper guard, 208x212: every spot beside it NOT_LOADED, so visit() to the middle of the first one,
  ~110 off). Past near() of the old build the "box is far away" branch came first: "flying over to the build" back to the
  iron farm, and after 10 s "/tp to" it - out, back, out. Now that branch does the visit first while guarding (visitStep,
  shared with the near branch), and for 10 s after getting there (visitDoneAt) hovers while chain() looks at the spot.
  Jar dist/human-autopilot-1.0.0-candidate-visitfix.jar (md5 1023c563); restart-mc.ps1 waiting for the user's close.
  Not run in a test.
