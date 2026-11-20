# H&H Armors

Adds two rival armor sets: **Heaven** and **Hell**. Both are full endgame sets with their own abilities, a sword and a mace each. I originally built this for a two-faction SMP, but it works fine in normal survival too.

The Heaven set is white netherite with golden trim. The Hell set is dark netherite burning with blue soul fire (the item textures are animated). You craft both by upgrading diamond gear in a smithing table, similar to netherite upgrades (off by default since the second overhaul: the pieces are relics of the bosses, see below).

## Abilities

Each armor piece has its own passive, wearing the full set unlocks more. A cooldown bar above the hotbar shows what's ready.

**Heaven:**
- Halo: you and nearby players slowly regenerate
- Double jump
- Long falls release a shockwave instead of hurting you
- Below 5 hearts you leave a light trail that slows enemies walking through it
- A beam attack that damages enemies but heals and cleanses teammates
- Sneak in the air to glide
- Land your hit combo and every following hit is an auto crit, until another player hits you

**Hell:**
- Walk on lava, and get Strength while burning
- Below 3 hearts: extra speed plus a fire burst
- Heavy landings crack the ground into temporary lava
- Fire Camp: summons three piglin brute bodyguards. A soul campfire spawns with soul soil, torches and lanterns around it, and the whole camp disappears again once the brutes are dead
- Sword ability: 20 seconds of 50% faster attacks where every hit crits, and crits give you golden apple absorption

**Maces** (both factions get one):
- Heaven's Mace can be thrown. It flies up to 50 blocks and strikes everything in its path with lightning, then comes back and hits everything again on the return
- Hell's Mace pulls enemies to you on a chain from up to 16 blocks. Right-click a block instead and it works as a grappling hook. You can even latch onto your own wind charges or ender pearls mid-flight

Full sets also give +10 hearts and fall damage immunity, and there's an Ultra Mode with its own cooldown.

## Olympus and Tartarus

**Olympus** is its own dimension: floating islands above a sea of clouds. Build a glowstone frame (inside 2 × 3 up to 9 × 9), pour a water bucket into it and step through the cloud gate. If you fall off, you drop into the Overworld sky with slow falling.

A fixed path of six islands leads upward, each with its own fight:
- **Hermes**, the messenger: solve the riddle in his temple, then catch him in four stages
- **Hekate**, goddess of thresholds: a crossroads with three roads, a turning key, three forms of which only one carries the fire. She guards the gate to Tartarus
- **Brontes** the Cyclops: let his hammer hit a glowing ingot and the sparks blind him; dodge cleanly and his hammer gets stuck; hide behind the anvil until it cracks and breaks
- **Kratos and Bia**, the throne guard: obey his commands (back off, come close, kneel), steer Bia's charge into him and shatter her while she staggers
- **Zeus**: lure his lightning into the storm pillars and pull the grounding grip, look away from the Gorgon on his shield, step into the golden pan of his scales, and keep your footing while his island breaks apart

**Tartarus**: five guardians hold the chain on the palace of Hades. Each one you beat breaks a link and makes the others stronger; at five of five the seal opens for Hades and Kronos. A ghostly Hermes points the way.

The bridges between the islands are **battle ways**: each has its own theme, gates, a narrow pass and a pulse that grows louder towards the boss. Hades' palace is reached through a closed approach past the judges of the dead to the hound gate.

Every arena **takes part in the fight** (inspired by HHA Blood and Void): pillars, gates and standards give cover against named attacks, terraces protect only from ground attacks, floes and galleries break away at set moments, and devices like the messenger bells or the Jailer's chain posts turn the fight. Every arena is restored from its blueprint after the fight. Hekate's *Dark Hour* is a short, fair stealth phase; Hades' fetters always leave three ways out. 35 new decorative blocks (Olympus marble, storm marble, bronze; Tartarus basalt, soul vein and ember basalt) decorate the buildings and can be cut in the stonecutter.

Every attack is announced on the floor, gold always means safe or open, no boss summons adds, and nothing takes away your control. Bosses grow with the number of players and counter repeated mace smashes from above. Every piece of the Heaven and Hell sets drops **once per world** from its boss; a second win gives ingots. Curetes, griffins and golden automatons guard the islands.

Since the third overhaul every attack has its own smooth effect, hits turn the camera slightly instead of shaking it, and intros, phase changes and defeats are staged cutscenes with a guided camera and their own music per phase. Screen effects can be switched off with `/hhac toggle screen_fx`.

## RPG mode

**HHA RPG** in the main menu turns the adventure into its own game for a group. The lobby is a tunnel of light where your group floats with their skins and the armor of the chosen path; friends join with a six-letter code. After departing, the tunnel keeps running instead of loading screens and everyone lands together in an arrival cutscene. A story in chapters leads from Hermes to Zeus and down through Tartarus to Kronos, with goals, a direction arrow, chapter cards, island cutscenes and credits at the end. RPG worlds have their own HUD, a journal (J), a pause menu, rests that heal you and set your respawn, and no hunger by default. Normal worlds stay exactly as they are.

## Config

Basically everything can be turned off or tuned: `/hha toggle` and `/hha set` change abilities, damage values and cooldowns live, no restart needed.

Recipes are editable too. On first launch the mod puts template files into `config/hha/datapack/`. Rename a `.json.example` to `.json`, edit it, run `/hha recipes reload` and your version replaces the built-in recipe. New files add completely new recipes.

Other stuff:
- `/trust <player>` makes sure your abilities, trails and brutes never hurt your friends
- `/hha kit hell|heaven` gives out a fully enchanted PvP kit (needs `kit_mode` enabled, or creative mode)
- Banner patterns for both factions

## Requirements

Fabric API and Fabric Language Kotlin. Client and server.

## Notes

- Piglin brutes get a soul-blue recolor of their tunic. This applies to all brutes, including bastions, since entity textures can't be swapped per mob
- Temporary blocks (lava, soul camp) clean themselves up. If the server hard-crashes while they exist they can stay behind
- Bug reports and ideas: [GitHub](https://github.com/Henny263462/HHA/issues)
