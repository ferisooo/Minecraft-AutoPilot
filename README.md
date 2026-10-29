# Human Autopilot (Fabric, Minecraft 26.1.2, client-side)

Plays for you using only real key presses (W/A/S/D, jump, sprint, sneak, left/right click, hotbar
number keys) and smooth per-frame mouse turning. No teleporting and nothing instant.

## Commands
| Command | What it does |
|---|---|
| `/goto x z` or `/goto x y z` | Pathfind there on foot |
| `/goto stop` | Cancel the destination |
| `/automine diamond iron coal` | Mine those ores (coal iron copper gold redstone lapis diamond emerald quartz debris) |
| `/automine` | Mine the default ores from the config |
| `/automine stop` | Stop mining |
| `/autopilot` | Status |
| `/autopilot reload` | Reload `config/humanautopilot.json` |

## Keys (rebind under Options > Controls > Human Autopilot)
| Key | Toggle |
|---|---|
| Numpad 0 | **PANIC**: stop everything and release every key |
| Numpad 1 | Path: pause/resume `/goto`, or explore (wander) when no `/goto` is set. With Mining on, explores until it spots ore |
| Numpad 2 | Combat |
| Numpad 3 | Mining |
| Numpad 4 | Auto-eat |
| Numpad 5 | Idle looking |
| Insert | Show / hide the HUD |
| Numpad 7 | **Survival mode**: plays the game on its own from an empty inventory (see below) |
| Numpad 6 | **Box fill**: keeps the StreamToEarn Bedrock Box filled (see below) |

## Box fill (Numpad 6)
Keeps the s2e Bedrock Box filled while viewers blow holes in it, the way a player in flight would: fly over,
hover, look at the block the hole sits on, right-click. Real keys and the smooth camera only.

- On a server with the **KawaiiBedrockPilot** plugin, the plugin sends the box and its layers, keeps the layer
  blocks in your inventory and lets you fly near the box while box fill is on. The plugin shows
  "Autopilot - filling the box" above the hotbar while it's on.
- Singleplayer / no plugin: `/autopilot box set x1 y1 z1 x2 y2 z2 stone,stone,diamond_block` (layers bottom up,
  the last one repeats), then Numpad 6. You need flight (creative) and the blocks in your hotbar.
- Fills the lowest layer first, in rows. Only hovers in columns open to the sky, so it can't build itself in.
  Holes it can't see (sealed inside the box) are left alone.
- Lit TNT within ~7 blocks: it gets up and away, then comes back and mends the crater.
- `/autopilot box` shows the status; `/autopilot box on|off|clear`.

## Survival mode (Numpad 7)
Plays like a person starting a new world. Every few moments it looks at the inventory and picks
the next step, so it carries on after fights, eating, or dying:

1. Chops trees (only real trees with natural leaves, never logs from builds), crafts planks, sticks,
   a crafting table and a wooden pickaxe.
2. Digs a staircase down to stone; stone pickaxe and sword.
3. Furnace. Hunts cows/pigs/sheep/chickens (never named or baby animals), cooks the meat.
4. Mines iron and coal (branch mining underground), smelts it, iron pickaxe, iron sword, shield, iron armor.
5. Gets wool from sheep and makes a bed; at night it goes up, puts the bed down and sleeps,
   and packs it up in the morning.
6. Goes deep and branch-mines for diamonds; diamond pickaxe and sword.

Along the way it places torches in dark tunnels, packs up its crafting table and furnace after
using them, tosses junk (dirt, gravel, granite...) when the bags fill up, keeps tools and food in
the hotbar, and respawns if it dies. Crafting, smelting and sorting are done by opening the real
screens and clicking slots at human speed.

Survival mode breaks blocks and digs tunnels, so use it in a world where that's fine (a new world is best).

## Notes
- The game keeps running when you click another window (no pause menu), so the autopilot carries on in the
  background. Set `keepRunningUnfocused` to `false` in `config/humanautopilot.json` for the normal pause.
- Keep the game window focused. Vanilla only keeps mining while the mouse is captured, and the
  game pauses when it loses focus.
- Food and tools are used from the **hotbar** only.
- Mining only targets ores with an open face by default, meaning ones you could actually see.
  Set `mineOnlyExposedOres` to `false` to target hidden ones too; it will tunnel to them.
- Everything else (reaction times, delays, pause frequency, aim wobble, health thresholds, ranges)
  is in `config/humanautopilot.json`.

## Build / test
```
gradlew build              # jar in build/libs
gradlew runClientGameTest  # automated in-world test of goto, mining, eating, combat
```
If the project sits in a very long folder path on Windows, `runClientGameTest` can fail with
"could not open ...argFiles". Move the project to a shorter path.
