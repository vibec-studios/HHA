# H&H Armors

**Two rival endgame armor sets — Heaven & Hell — each with its own weapons, abilities and playstyle.**

A Fabric mod for **Minecraft 1.21.11**, written in Kotlin.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-green)
![Loader](https://img.shields.io/badge/Loader-Fabric-blue)
![License](https://img.shields.io/badge/License-MIT-yellow)

Choose your side: the radiant **Heaven Set** with golden trims and holy light, or the **Hell Set** — blackened netherite burning with dark-blue soul fire. Both sets come with a full ability kit, a unique sword and mace, animated textures and custom particles.

➡ Full feature overview: [MODRINTH.md](MODRINTH.md)

## Highlights

- **Heaven**: Halo regeneration aura, double jumps, fall shockwave, light trail that slows enemies, light beam with ally healing, glide, and combo-based auto-crits
- **Hell**: undying rage, lava walking, magma stomp, Fire Camp with Piglin Brute vassals and a reverting **soul camp** (soul campfire, soul soil, torches & lanterns), blade rush with guaranteed crits
- **Weapons**: throwable lightning mace (Heaven) and a chain mace with enemy pull & grappling hook (Hell)
- **Boss fight**: earn the Heaven armor from Hermes, messenger of the gods: four stages, cutscenes and custom models ([docs](docs/hermes.md))
- **3D effects**: volumetric beams, seraph wings, shockwaves and real chains instead of flat particles
- **Shared**: +10 hearts set bonus, Ultra Mode, cooldown HUD, faction lock, `/trust` system
- **Fully configurable**: every ability can be toggled and tuned live via `/hha`; crafting recipes can be disabled or replaced through an auto-generated datapack in `config/hha/datapack/`

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
| `/hha list` | Show all toggles and values (OP) |
| `/hha toggle <key> <bool>` | Enable/disable an ability (OP) |
| `/hha set <key> <value>` | Tune damage, cooldowns, thresholds (OP) |
| `/hha recipes reload` | Re-sync custom recipes from `config/hha/datapack/` (OP) |
| `/hha kit hell\|heaven` | Full PvP kit (requires `kit_mode`) |
| `/trust <player>` / `/untrust <player>` | Your abilities never harm trusted players |

## Good to know

- The mod retextures the **Piglin Brute**'s dark tunic into soul-blue (applies to all brutes — it fits the theme)
- Vanilla tag additions are non-destructive (`"replace": false`)
- Temporary blocks (soul camp, magma stomp lava) revert automatically; a hard server crash while they are active can leave them behind
- `HEAVEN_ARMOR_LOCK.md` documents assets whose look is intentionally frozen

## License

[MIT](LICENSE.txt)
