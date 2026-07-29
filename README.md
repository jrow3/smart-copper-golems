# Smart Copper Golems

Turns vanilla Copper Golems into item sorters. Label a chest with an item frame and golems will
carry that item to it, instead of shuffling things one at a time to wherever they happen to wander.

Minecraft **26.2**, on **Fabric** and **NeoForge**.

> This is a fork of Smart Copper Golems by **GameMech007**. See [Credits](#credits) and
> [Changes in this fork](#changes-in-this-fork).

## How it works

Put an item frame on a chest holding the item you want stored there. A golem carrying that item
will path to that chest and deposit it.

* **Diamond in the frame** → golems bring diamonds there
* **Iron ingot in the frame** → golems bring iron there
* **Empty frame** → a catch-all: anything with nowhere else to go
* **No frame** → also a catch-all by default, configurable via `fallbackMode`

A golem that finds nothing suitable carries the item back to the chest it came from rather than
dropping it. Double chests are handled as one container, and golems lock a chest while working on
it so two of them cannot fight over the same slot.

If a golem is holding something no chest will take, it stops reconsidering that item for a while
(`unroutableItemCooldownTicks`) instead of shuttling it back and forth forever.

## Installation

1. Install Fabric or NeoForge for Minecraft 26.2. The Fabric build also needs Fabric API.
2. Drop the `.jar` into your `mods` folder.
3. Launch. A config file is written on first run.

Fabric and NeoForge builds share the same config filename, so settings carry over if you switch
loaders.

## Configuration

Settings live in `config/coppergolem.json`, created with defaults on first launch. Edit the file
and run `/coppergolem reload`, or change values live with `/coppergolem set`.

| Key | Default | Range | What it does |
|---|---|---|---|
| `horizontalSearchDistance` | `32` | 1–64 | How far out a golem looks for chests, in blocks |
| `verticalSearchDistance` | `8` | 1–64 | How far up and down it looks |
| `haulSpeed` | `1.0` | 0.1–5.0 | Movement speed multiplier while hauling |
| `interactionOpenTicks` | `9` | 1–12000 | Ticks spent at a chest before acting (vanilla: 9) |
| `interactionCloseTicks` | `60` | must exceed open | Ticks before leaving a chest (vanilla: 60) |
| `matchStrictness` | `EXACT` | `EXACT`, `ITEM_ONLY` | Whether components/NBT must match the frame too |
| `fallbackMode` | `UNFRAMED_OR_BLANK` | see below | Which unlabelled chests may be used |
| `unroutableItemCooldownTicks` | `200` | 0–12000 | How long an unroutable item is ignored; 0 disables |
| `baseHealth` | `-1.0` | any, or `-1` | Golem max health; `-1` leaves vanilla alone |
| `baseMoveSpeed` | `-1.0` | any, or `-1` | Golem base speed; `-1` leaves vanilla alone |
| `debug` | `false` | | Verbose routing logs |

**`fallbackMode`** decides where an item goes when no frame matches it:

* `UNFRAMED_OR_BLANK` — any chest without a non-empty frame, labelled or not (default)
* `BLANK_FRAME_ONLY` — only chests carrying a deliberately empty frame
* `NONE` — no fallback; unmatched items go back to their source chest

A chest whose frame shows a *different* item is never used as a fallback, in any mode.

Out-of-range values are clamped rather than rejected, so a bad edit cannot break the game. If the
file cannot be parsed at all it is moved to `coppergolem.json.bak` and defaults are written, so a
stray comma does not silently cost you your settings.

## Commands

`/coppergolem` requires permission level 2 (gamemasters).

| Command | Effect |
|---|---|
| `/coppergolem list` | Show every setting and its current value |
| `/coppergolem get <key>` | Show one setting |
| `/coppergolem set <key> <value>` | Change a setting, clamp it, and save |
| `/coppergolem toggle debug` | Flip debug logging |
| `/coppergolem reload` | Re-read `coppergolem.json` from disk |

Search and sorting changes apply from the next golem tick. `baseHealth` and `baseMoveSpeed` are
applied to each golem as it loads, so existing golems need a chunk reload.

## Changes in this fork

Per the Apache License 2.0, this is a modified version of the original work. The substantive
changes are:

* **Configuration.** A JSON config file and the `/coppergolem` command tree, neither of which
  existed upstream — the search, sorting, and golem-stat values were all hardcoded.
* **Sorting fix.** Golems could strand items and mishandle overflow into unframed chests.
* **Golem stats** are applied as named attribute modifiers rather than written into the entity's
  saved base values, so setting a knob back to `-1` actually restores vanilla instead of leaving
  every golem it ever touched permanently altered.
* **NeoForge.** The NeoForge module did not compile at all. It now builds and carries the same
  features as the Fabric build, and CI builds both loaders.
* **Internals.** The transport behaviour was substantially restructured, and unit tests were added
  for config clamping and fallback selection.

## Building

Each loader is its own Gradle project:

```
cd Fabric   && ./gradlew build     # jars land in Fabric/build/libs
cd NeoForge && ./gradlew build     # jars land in NeoForge/build/libs
```

Requires a JDK 26 toolchain. Bytecode targets Java 25, which is what Minecraft 26.2 ships to
players — the compiler and the target are deliberately different versions.

`cd Fabric && ./gradlew test` runs the unit tests. They cover pure logic only; nothing there starts
a world or a server.

## Credits

Originally created by **GameMech007**. This fork is maintained by **John Rowe**.

Licensed under the [Apache License 2.0](LICENSE), as was the original.
