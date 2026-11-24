# MeowLogger

A server-side block and action logger for Fabric (Minecraft 26.3). It logs player actions to a
database, lets staff inspect them in-game, and rolls back griefing. Vanilla clients can join; no
client mod is needed.

## What is logged

| Action | Source | Player |
|---|---|---|
| Breaking and placing blocks, including both halves of doors, beds and tall plants | `player` | the player |
| Emptying and filling buckets | `player` | the player |
| Blocks destroyed by explosions | the exploding entity, e.g. `tnt`, `creeper`, `end_crystal` | whoever lit or caused it, if known |
| Blocks burnt by fire | `fire` | none |
| Items put into or taken out of chests, barrels, shulker boxes, furnaces and other block containers | `player` | the player |

Block entity data (chest contents, sign text, banner patterns) is saved with each change, so a
rolled-back chest comes back with its items. Items that dropped when it broke are not removed.

Rolling back an item action puts the items back (or takes them out again), but only if all of them
fit, or are all still there; otherwise it is skipped and reported. Not logged yet: water and lava
flow (including cobblestone and obsidian it creates), entities, and books put into lecterns or
chiseled bookshelves.

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

Rollback and restore skip an action if someone else changed the same block afterwards (according to
the log), so later work by other players is never overwritten. Changes the log doesn't know about,
like grass spreading or water flowing in, are overwritten. Up to 250,000 actions can be rolled back at
once, applied over several ticks so the server doesn't freeze; the result message has an
**[Undo]** button.

Lookup parameters can be combined; lists are comma-separated:

| Parameter | Example | Meaning |
|---|---|---|
| `radius:` / `r:` | `r:10` | Within 10 blocks of you (0–1000), in your current world |
| `player:` / `p:` | `p:Steve,Alex` | Actions these players did or caused (e.g. TNT they lit) |
| `source:` / `s:` | `s:creeper,tnt` | Actions with this cause (`player`, `fire`, an entity such as `creeper`) |
| `action:` / `a:` | `a:item-remove` | `block-break`, `block-place`, `item-insert`, `item-remove` |
| `block:` / `b:` | `b:diamond_ore` | Actions involving this block (before or after), or this container |
| `item:` / `i:` | `i:diamond` | Item actions moving this item |
| `time:` / `t:` | `t:1d12h` | Within this time (`s`, `m`, `h`, `d`, `w`) |
| `world:` / `w:` | `w:the_nether` | Only this dimension |

Example: `/ml l r:20 a:block-break b:chest t:2d`

### Permissions

The permission nodes `meowlogger:lookup`, `meowlogger:inspect`, `meowlogger:rollback` and
`meowlogger:teleport` can be granted by a permission mod. Without one, they require operator level 2.

## Development

- Run a dev server: `./gradlew runServer`
- Build the jar: `./gradlew build` (output in `build/libs`)

Requires JDK 25.

## License

MIT, see [LICENSE](LICENSE).
