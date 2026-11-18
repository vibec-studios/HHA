# H&H Armors

**Two rival endgame armor sets — Heaven & Hell — each with its own weapons, abilities and playstyle.**

A Fabric mod for **Minecraft 1.21.11**, written in Kotlin.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-green)
![Loader](https://img.shields.io/badge/Loader-Fabric-blue)
![License](https://img.shields.io/badge/License-Apache--2.0-blue)

Choose your side: the radiant **Heaven Set** with golden trims and holy light, or the **Hell Set** — blackened netherite burning with dark-blue soul fire. Both sets come with a full ability kit, a unique sword and mace, animated textures and custom particles.

➡ Full feature overview: [MODRINTH.md](MODRINTH.md)

## Highlights

- **Heaven**: Halo regeneration aura, double jumps, fall shockwave, light trail that slows enemies, light beam with ally healing, glide, and combo-based auto-crits
- **Hell**: undying rage, lava walking, magma stomp, Fire Camp with Piglin Brute vassals and a reverting **soul camp** (soul campfire, soul soil, torches & lanterns), blade rush with guaranteed crits
- **Weapons**: throwable lightning mace (Heaven) and a chain mace with enemy pull & grappling hook (Hell)
- **Boss fight**: earn the Heaven armor from Hermes, messenger of the gods: four stages, cutscenes and custom models ([docs](docs/hermes.md)); since the second overhaul Hermes is the first of five Olympus bosses, see below
- **3D effects**: volumetric beams, seraph wings, shockwaves and real chains instead of flat particles
- **Shared**: +10 hearts set bonus, Ultra Mode, cooldown HUD, faction lock, `/trust` system
- **Fully configurable**: every ability can be toggled and tuned live via `/hha`; crafting recipes can be disabled or replaced through an auto-generated datapack in `config/hha/datapack/`

## Olympus and Tartarus

- **Olympus is its own dimension**: floating islands above a sea of clouds, reached through a **cloud gate** (a glowstone frame from 2 × 3 to 9 × 9 lit with a water bucket, like the Aether portal). Falling off drops you into the Overworld sky with slow falling
- **The island path**: Hermes at the Messenger's temple, **Hekate** at the crossroads (she guards the gate to Tartarus), **Brontes** the Cyclops in his forge, **Kratos and Bia** on the steps of might and finally **Zeus** at his throne. Sealed bridges close after each victory, a golden beacon points to the next goal
- **Tartarus**: five guardians hold the **chain** on Hades' palace; every guardian you beat breaks a link and makes the others stronger. Only at five of five does the seal open for Hades and Kronos. Hermes no longer hunts you there, he shows the way
- **Readable duels**: every attack is announced on the floor, gold always means safe or open, at most one room hazard at a time, no summoned adds, no loss of control. Bosses scale with the number of players and **counter repeated mace smashes** from above
- **Relics**: every piece of the Heaven and Hell sets drops exactly once per world, spread over the bosses; a second win gives ingots instead. Smithing the set pieces is off by default (`relic_crafting`)
- **Enemies of Olympus**: Curetes with shields, griffins diving from their columns and golden automatons that wake up when you come close
- **Boss presentation (third overhaul)**: every attack has its own smooth effect (light trails, rifts in the sky, real flying floor blocks, glowing cracks), hits turn the camera slightly instead of shaking it, and intros, phase changes and defeats play as staged, skippable cutscenes with a guided camera. Each boss has a theme per phase with smooth crossfades and a victory stinger. Screen effects such as Hekate's eclipse, Kronos' time stop and his gravity lens can be turned off (`/hhac toggle screen_fx`). Fight numbers are unchanged
- Docs: [Olympus](docs/olymp.md), [Tartarus](docs/tartaros.md), [bosses in general](docs/bosse-allgemein.md) (German)

## RPG mode

- **HHA RPG** in the main menu starts a group adventure as its own game ([docs](docs/rpg.md), German): the lobby is a tunnel of light in which your group floats with skins and the armor of the chosen path (Heaven or Hell); friends join with a six-letter code
- **No loading screens**: after "Depart" the tunnel keeps running over world creation and connection, everyone waits in the tunnel and lands together in an arrival cutscene
- **A story in chapters** with goals, a direction arrow, chapter cards and island cutscenes, from Hermes to Zeus and down through Tartarus to Kronos, ending with credits
- **Own HUD, journal (J), pause menu, death and rest screens** in the style of the control center; rests heal you and set your respawn, no hunger by default, bosses slightly gentler (`rpg_boss_health`, `rpg_boss_damage`)
- Only in RPG worlds; normal worlds play exactly as before. Addons can add their own paths (`HhaRpg.Path` with costume and arrival)

## Requirements

| Dependency | Note |
|---|---|
| [Fabric Loader](https://fabricmc.net/) ≥ 0.19.3 | |
| [Fabric API](https://modrinth.com/mod/fabric-api) | required |
| [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) | required |

Works in singleplayer and on dedicated servers.

## Installation

1. Install Fabric Loader for Minecraft 1.21.11
2. Drop **Fabric API**, **Fabric Language Kotlin** and the **HHA jar** into your `mods/` folder

## Building from source

```bash
./gradlew build
```

Requires JDK 21. The remapped jar is written to `build/libs/`.

## Commands

| Command | Description |
|---|---|
| **K** or `/hhac menu` | Control center: every setting, live cooldowns, trust, kits and admin tools in one menu |
| `/hha list` | Show all toggles and values (OP) |
| `/hha toggle <key> <bool>` | Enable/disable an ability (OP) |
| `/hha set <key> <value>` | Tune damage, cooldowns, thresholds (OP) |
| `/hha recipes reload` | Re-sync custom recipes from `config/hha/datapack/` (OP) |
| `/hha kit hell\|heaven` | Full PvP kit (requires `kit_mode`) |
| `/hha kit hell\|heaven` in creative | Always works in creative mode, even with `kit_mode` off |
| `/hha relics` | Which set pieces already dropped in this world (OP; `drop`, `mark`, `reset`) |
| `/hha progress [set <key> <bool>]` | Olympus path and Tartarus chain (OP) |
| `/hha boss start\|skip\|stop <boss>` | Start, skip or stop a boss fight at its island (OP) |
| `/hha tp olymp\|tempel\|dreiweg\|schmiede\|stufen\|thron\|tartaros` | Travel to the stations (OP) |
| `/hha olymp spawn <kurete\|griffin\|automaton>`, `/hha olymp bridges` | Test post in front of you, close all sealed bridges (OP) |
| `/trust <player>` / `/untrust <player>` | Your abilities never harm trusted players |

## Good to know

- The mod retextures the **Piglin Brute**'s dark tunic into soul-blue (applies to all brutes — it fits the theme)
- Vanilla tag additions are non-destructive (`"replace": false`)
- Temporary blocks (soul camp, magma stomp lava) revert automatically; a hard server crash while they are active can leave them behind
- `HEAVEN_ARMOR_LOCK.md` documents assets whose look is intentionally frozen

## License

**Code:** [Apache License 2.0](LICENSE), including the generators in `tools/` and the lobby server in `backend/`.
You may fork, change and share it. Releases published before 2026-11-18 stay under the MIT License they were released
with.

**Not** covered by the code license (applies to all repositories of Vibe Craft Studios,
[LICENSING.md](https://github.com/vibec-studios/.github/blob/main/LICENSING.md)):

- **Names and logos** ("H&H Armors", "Vibe Craft Studios", the seal): forks need their own name, mod ID and logo –
  [TRADEMARKS.md](https://github.com/vibec-studios/.github/blob/main/TRADEMARKS.md).
- **Artwork, models and sounds** (textures, item and block models, bosses and creatures, sounds, music, voice lines,
  icon): all rights reserved – [ASSETS-POLICY.md](https://github.com/vibec-studios/.github/blob/main/ASSETS-POLICY.md); paths in [NOTICE](NOTICE).
- **Vibe services** (the RPG lobby `hha-rpg.henny.dev`, `vibe.henny.dev`, `vibey.henny.dev`): official builds only –
  [SERVICES.md](https://github.com/vibec-studios/.github/blob/main/SERVICES.md). Forks run their own lobby from
  `backend/rpg-lobby`.

Third-party parts keep their own terms: the Silkscreen font (SIL OFL 1.1), Minecraft textures the mod builds on, and
the voice lines made with ElevenLabs. Details in [NOTICE](NOTICE).
