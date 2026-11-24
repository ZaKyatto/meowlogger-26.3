# MeowLogger

A server-side block and action logger for Fabric (Minecraft 26.3). It logs player actions to a
database, lets staff inspect them in-game, and rolls back griefing. Vanilla clients can join; no
client mod is needed.

## What is logged

| Action | Source | Player |
|---|---|---|
| Breaking and placing blocks, including both halves of doors, beds and tall plants, and blocks that pop off with them (torches, rails, signs) | `player` | the player |
| Changing a block by using it: books on lecterns, discs in jukebox, flower pots, decorated pots, chiseled bookshelves, stripping logs, tilling dirt | `player` | the player |
| Lighting fires with flint and steel or fire charges | `player` | the player |
| Emptying and filling buckets | `player` | the player |
| Blocks destroyed by explosions, and blocks that pop off with them | the exploding entity, e.g. `tnt`, `creeper`, `end_crystal` | whoever lit or caused it, if known |
| Blocks burnt by fire | `fire` | whoever lit the fire, if a player did |
| Items put into or taken out of chests, barrels, shulker boxes, furnaces and other block containers, and chest or hopper minecarts and chest boats | `player` | the player |
| Animals, villagers, pets and other non-hostile mobs dying, plus name-tagged hostile mobs | `player`, the weapon (e.g. `arrow`), the attacking mob, or the cause (e.g. `lava`, `fall`) | the killer, if a player |
| Armor stands, item frames, paintings, boats and minecarts placed or broken | `player`, the weapon or explosion, or `meowlogger:support_broken` when the block a frame or painting hung on is gone | the player, if any |
| Items put into, taken out of or rotated in item frames, and armor stand equipment swapped | `player` | the player |

Block entity data (chest contents, sign text, banner patterns) is saved with each change, so a
rolled-back chest comes back with its items. Items that dropped when it broke are not removed.

Rolling back an item action puts the items back (or takes them out again), but only if all of them
fit, or are all still there; otherwise it is skipped and reported.

Rolling back a kill brings the mob back where it died, with full health, its name, equipment,
owner and inventory (what it dropped when it died is not removed). Restoring a kill removes it again (without drops), as long as it is still
loaded. Players and ordinary hostile mobs are not logged.

Decorations work the same way: rolling back a break brings the armor stand, frame, painting or
vehicle back with its contents, rolling back a placement removes it, and rolling back a change puts
the old item or equipment back.

Not logged yet: water and lava flow (including cobblestone and obsidian it creates), sand and
gravel falling, and signs edited after placing them.

## Usage

`/meowlogger` has the alias `/ml`.

| Command | What it does |
|---|---|
| `/ml inspect` (`/ml i`) | Toggle inspector mode. Left-click a block to see its history; right-click a block face to see the block in front of it (for blocks that were broken). |
| `/ml lookup <params>` (`/ml l`) | Search the log, newest first. |
| `/ml page <n>` | Show another page of your last lookup or inspection. |
| `/ml rollback <params>` | Undo matching actions. Needs a `radius:` or `player:` parameter. |
| `/ml restore <params>` | Redo matching actions that were rolled back. Same parameters as rollback. |
| `/ml teleport <world> <pos>` | Teleport to a logged position (what clicking a lookup's coordinates runs). |
| `/ml purge <params>` | Permanently delete matching actions, e.g. `/ml purge before:90d`. Shows how many match and asks you to confirm with `/ml purge confirm` within 60 seconds. |
| `/ml reload` | Reload `config/meowlogger.json`. |

Rollback and restore skip an action if someone else changed the same block afterwards (according to
the log), so later work by other players is never overwritten. Changes the log doesn't know about,
like grass spreading or water flowing in, are overwritten. Up to 250,000 actions can be rolled back at
once (configurable), applied over several ticks so the server doesn't freeze. Hover over the result message to see how
many actions of each type were undone; it also has an **[Undo]** button.

Lookup parameters can be combined; lists are comma-separated:

| Parameter | Example | Meaning |
|---|---|---|
| `radius:` / `r:` | `r:10` | Within 10 blocks of you (up to `maxRadius`, 1000 by default), in your current world |
| `player:` / `p:` | `p:Steve,Alex` | Actions these players did or caused (e.g. TNT they lit) |
| `source:` / `s:` | `s:creeper,tnt` | Actions with this cause (`player`, `fire`, an entity such as `creeper`) |
| `action:` / `a:` | `a:item-remove` | `block-break`, `block-place`, `block-change`, `item-insert`, `item-remove`, `entity-kill`, `entity-break`, `entity-place`, `entity-change` |
| `block:` / `b:` | `b:diamond_ore` | Actions involving this block (before or after), or this container |
| `item:` / `i:` | `i:diamond` | Item actions moving this item |
| `entity:` / `e:` | `e:cow,armor_stand` | Actions on this kind of mob, decoration or vehicle |
| `time:` / `t:` | `t:1d12h` | Within this time (`s`, `m`, `h`, `d`, `w`) |
| `before:` | `before:30d` | Longer ago than this |
| `world:` / `w:` | `w:the_nether` | Only this dimension |

Example: `/ml l r:20 a:block-break b:chest t:2d`

### Permissions

The permission nodes `meowlogger:lookup`, `meowlogger:inspect`, `meowlogger:rollback`,
`meowlogger:teleport`, `meowlogger:reload` and `meowlogger:purge` can be granted by a permission mod.
Without one, reload requires operator level 3, purge level 4, and the others level 2.

## Configuration

`config/meowlogger.json` is created with the defaults on first start. Edit it and run `/ml reload`
(or restart). It lets you skip actions, worlds, causes, blocks, items and mobs you don't want
logged, delete old actions automatically, and tune rollback and lookup limits.

Every option is explained in comments inside the file itself.

## Development

- Run a dev server: `./gradlew runServer`
- Build the jar: `./gradlew build` (output in `build/libs`)

Requires JDK 25.

## License

MIT, see [LICENSE](LICENSE).
