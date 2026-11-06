# Changelog

## Unreleased

### Added
- **Bossfight "The Last Delivery" against Hermes** (`docs/hermes.md`): four stages with their own attacks, arena look, sky and music, six cutscenes with camera moves and dialogue, and the Divine Parcel as reward (full Heaven armor plus 4 Feathers of the Messenger). Started with the new Sealed Messenger Letter. Dying in the arena never costs items
- New entities with custom animated models: Hermes in double pixel density with articulated feathered wings that fold and spread per pose (plus illusions and dash afterimages), two Caduceus serpents, Herma stones, Divine Parcel, feather and soul-lance projectiles
- New 3D effect engine with its own shader instead of flat particles: volumetric beams, shockwaves with flying debris, crescent slashes, branching lightning, light pillars, six-winged seraph wings made of individual feathers, shattering shields, real chain links, portals, tornadoes, rune circles, camera shake and screen flash
- **The real sky sets the mood**: abilities and boss stages tint the actual Minecraft sky, fog and clouds, move the sun and stars, and bring vanilla rain and lightning. Custom 3D clouds surround the arena: a sea below, a deck above, and when Zeus answers a thunderstorm rolls in from the horizon with lightning glowing inside the clouds; at the finale it tears open and god rays fall through
- Light Beam, Lava Beam, Light Wave, Fire Camp, Ultra Mode, Divine Shield, Shockwave, Magma Stomp, Blade Rush, double jump, thrown Heaven's Mace and the Hell's Mace chain all use the new effects; Heaven and Hell helmets show a 3D halo and soul-fire crown
- **Hermes glows**: the orb, staff, halo, eyes, chest sun, wing edges and sandals carry live light effects read from his bones. The colour follows the stage (gold, dusk violet, soul blue, storm white with lightning arcs), it flares when he casts and nearly goes out while he is exhausted, so the glow also shows when he can be hit. His model gained an ornate caduceus with a crystal orb and two animated serpents of light that climb the staff, rear up at the top and flick their tongues, plus glowing inlays, a courier satchel, a nose and more hair
- **Smooth feathers instead of pixelated ones**: wings, feather projectiles, the parcel, particles and all feather effects now use high-resolution curved feathers with a real quill, split barbs and a downy base
- **The serpents became dragons**: long snout, curved horns, rows of teeth, feather mane, scaled body with belly plates and back fins, two pairs of flapping wings and a tail fan. The body is longer, slithers in a running wave and no longer disappears when the head leaves the screen
- **New orchestral battle music**: four stereo themes (72 to 100 seconds) with strings, brass, choir, harp and taiko that share one leitmotif and loop seamlessly
- **Better sounds**: all boss effects were rebuilt and joined by new ones for charging, blinking, deflected blows, hits on Hermes, feather volleys, light lances, and the serpents' roar, hiss and bite, replacing the vanilla placeholders
- **New biome: Olympus.** The warm stony peaks of the Overworld become marble mountains with ruined columns, and a temple on the summit holds a riddle: four offering stones want the gifts of Hermes' first day, in the order of the sun. Solving it starts the fight without a letter
- **New cave biome: Realm of Hades**, with soul soil, basalt and soul fire. A domed hall lies beneath it, reached by a spiral shaft from the surface
- **New boss: the Shade of the Messenger.** Pay the ferryman a gold nugget at the altar to fight the evil Hermes in three phases, with his own texture, green effects, music and cutscenes. Beating him fills the black gate with green light
- **New dimension: Tartarus**, an open ash wasteland under a blood-red sky with its own blocks and huge leaning pale crystals, reached through a round gate with a 3D tunnel effect that pulls you in
- **Hermes guides you through Tartarus**: he keeps appearing ahead as a ghost, waits, and flies on when you get close, leading to the sealed gate of Hades (a placeholder for what comes next)
- **Map to Hades**: reward from the Hermes fight that turns into a real map marking the nearest hall
- **Zeus got his moment**: two blazing eyes open in the storm clouds above the forum, framed by a brow and beard of cloud, and lightning falls from them
- **Spoken dialogue**: all 46 lines are voiced in German and English with ElevenLabs, and scenes wait for a sentence to finish (`tools/hha_assets/voice_lines.py`)
- `/hha kit diamond` and `/hha kit netherite`: the same kits with normal armor and weapons
- `/hha vfx <name>` previews every effect, `/hha hermes start|stop|skip` for admins, `/hhac vfx` and `/hhac shake` client switches
- Config: `hermes_fight`, `heaven_ingot_free_recipe`, `hermes_damage`, `hermes_toughness`, `hermes_max_hit`
- `runClientGameTest` runs the real game and takes screenshots of effects and the whole fight

### Changed
- **Serpents can be hit along their whole body**, not just at the head; bites are measured from the mouth, and the whole body flashes when struck
- **Hermes scales with the number of players**: every extra player adds health and damage (`hermes_health_per_player`, `hermes_damage_per_player`), and serpents and herma stones get tougher too. His feather bolts now also respect `hermes_damage`. This replaces the old hidden 50% toughness per player
- **Arenas cannot be changed**: during a fight blocks can't be broken or placed and explosions leave them intact; the Hades hall is protected all the time
- **Heaven's Ingot now needs a Feather of the Messenger**, so new Heaven gear runs through the boss. `/hha toggle heaven_ingot_free_recipe true` restores the old recipe

### Changed
- HUD cooldown icons redrawn so each glyph matches the ability it represents: holy pillar + purify plus (light beam), molten lance (lava beam), soul camp with brute faces (Fire Camp), fire blade with haste streaks and a crit burst (Blade Rush), expanding triple shockwave (Light Wave), gold mace struck by cyan lightning (thrown mace), steel chain ending in a fire hook (pull / grapple), and a gold/fire overdrive burst (Ultra)
- The Ultra timer now shows the Ultra icon beside the countdown

## 0.8.0 — Particles & HUD icons redesign (2026-08-04)

### Changed
- All 12 particle sprite sets rebuilt procedurally from scratch on one unified palette (flames, sparks, motes, bursts, soot, chain links, feathers) at the same frame counts, with flickering flame tips, breathing motes, pulsing sparks and expanding burst rings
- HUD cooldown icons snapped onto the same master palette so every on-screen element shares one art direction (heaven gold, hell soul-teal, fire cores)

## 0.7.0 — Visual overhaul & client display settings (2026-08-04)

### Added
- New client-only display settings, persisted in `config/hha/client.json` and adjustable live with the new `/hhac` client command: HUD on/off (`hud`), ready-slot pulse (`pulse`), slot background (`background`), ultra bar (`ultrabar`), HUD scale 0.5–2.0 (`scale`), HUD position offset (`offset <x> <y>`) and client-side particle density (`density`, 0–1 — thins out HHA particles for weaker PCs; the grapple chain stays untouched). `/hhac list` shows all values, `/hhac reset` restores defaults
- Heaven's and Hell's Ingots now have a soft animated gleam — four interpolated frames gently pulsing the gold/teal accents

### Changed
- All particle effects play noticeably smoother: every HHA particle sprite set now runs at double the frame count with crossfaded in-between frames (flames, bursts, sparks and motes went from 4 to 8 frames, chain links and feathers from 2 to 4)
- All flat item models render with front-facing GUI lighting, so heaven/hell items appear fully lit in inventories instead of side-shaded
- Redesigned HUD cooldown icons across the board (beams, camp, chain, mace, sword, ultra, wave)

## 0.6.2 — Sneak to let go of the chain (2026-07-16)

### Added
- Sneaking during a grapple releases the chain: your momentum is cut and you drop — works mid-flight on block grapples and while riding a projectile grapple. Letting go also disarms the pending bounce dash. Sneaking while firing doesn't cancel; the release only arms once you let go of sneak

## 0.6.1 — Chain charges cover the grapple, sword texture polish (2026-07-16)

### Fixed
- The 2-quick-uses-then-20s-cooldown charge system now covers **all** Hell's Mace chain uses — block grapple, projectile grapple and enemy pull share one pool. Previously only the enemy pull was charged; grappling still ran on its own flat 4s cooldown, which is why "2 quick grapples" never worked. New config keys: `chain_charges` (2), `chain_cooldown` (2 s), `chain_recharge` (20 s) — the old `pull_cooldown`/`pull_recharge`/`pull_charges`/`grapple_cooldown` keys are retired, so stale saved values no longer interfere

### Changed
- Heaven's Sword texture: the gold energy groove now runs much further up the blade (to just below the tip) in all three animation frames, and the gold band across the guard connects properly at the bottom middle instead of leaving a gap

## 0.6.0 — Auto-generated kits & addon management (2026-07-16)

### Added
- Kits are now auto-generated from every registered armor set — including addon sets: `/hha kit <set>` (tab completion; addon sets as `<addonid>.<path>`) equips the fully enchanted armor, the set weapons by role convention (first weapon = sword enchants, second = mace enchants) and the standard SMP loadout
- `/hha addons` lists all loaded HHA addons with version and what they registered (sets, abilities, lore items, config keys); backed by the new `api.HhaAddons` registry

## 0.5.1 — Fire Camp & chain-charge fixes, ability-matched icons (2026-07-16)

### Fixed
- Fire Camp no longer vanishes while Brutes are alive: the camp had a hard 95-second cap, and a Brute in an unloaded chunk counted as dead. Brutes and camp now last 10 minutes; the camp only reverts when all its Brutes are dead (tracked via BruteAllies, which survives chunk unloads) or the 10 minutes run out
- Hell's Mace chain charges refill properly: charges now fully restore 20 s after the last use — previously a single pull used long ago still counted, so the next pull triggered the long cooldown immediately

### Changed
- Heaven's Sword cooldown icon now shows the actual ability: an expanding light wave (three arcs) instead of a generic sword
- Heaven's Mace icon redrawn: clean round flanged gold head, straight handle, lightning accents
- Lava beam icon redrawn: glow origin, white-hot horizontal beam, impact star
- Allied Brutes' Sharpness V diamond axe shows its enchantment glint again

## 0.5.0 — Six ability slots & complete addon API (2026-07-16)

### Added
- Six generic ability keybinds ("Ability 1–6"): 1–3 keep their defaults (G/H/U — set beam, Fire Camp, Ultra), 4–6 start unbound and are free for addons — no custom keybinds or network packets needed
- Lore API (`api.lore.HhaLore` / `context.registerLore`): addons define dynamic tooltip lines with `num`/`seconds`/`hearts` placeholders that render the live, server-synced config values — the built-in items use the same registry now
- `/hha list` groups addon config keys under their own "Addon: <id>" headers

### Changed
- Ability model restructured to **ability key → equipment check → function**: `AbilityTrigger` is now `AbilitySlot` (`ABILITY_1..6`, `MOVEMENT`), and the faction gate moved from the slot to the individual ability (`factionGateSlot`) — e.g. Ability 1 + full Hell set → lava beam
- Keybind IDs renamed to `key.hha.ability_1..6`; custom rebinds of the old G/H/U keys reset to defaults once

## 0.4.1 — Config files consolidated under config/hha/ (2026-07-15)

### Changed
- All HHA config files now live under `config/hha/`: `config/hha/hha.json` (settings), `config/hha/factions.json` and `config/hha/trust.json` (joining the custom recipes already in `config/hha/datapack/`). Legacy files (`config/hha.json`, `config/hha_factions.json`, `config/hha_trust.json`) are migrated automatically on first launch — no settings are lost.

## 0.4.0 — Chain charges, new beam icons & tankier Brutes (2026-07-15)

### Added
- Hell's Mace chain-pull is now a charge system: `pull_charges` quick chains (default 2, `pull_cooldown` 2 s between them), then a long `pull_recharge` cooldown (default 20 s) before the charges refill
- Dedicated HUD cooldown icons for each beam — a radiant light pillar for Heaven, a molten lance for Hell — plus a brand-new Heaven's Mace icon (golden mace struck by lightning)

### Changed
- Allied Piglin Brutes: 250 HP (up from 100), Sharpness V axe (up from IV), permanent Strength II, and invisible armor (armor + toughness attributes ≈ a Protection II set) that never covers the piglin look
- Light Wave now follows your full look direction: the particle line and hit arc travel up or down where you aim instead of always going flat, and the knockback carries that vertical aim

### Fixed
- Light Wave particles no longer always shoot straight ahead when looking up or down

## 0.3.3 — Sharper HUD icons & stronger Brutes (2026-07-15)

### Changed
- HUD cooldown icons sharpened: deeper shadows, brighter highlights and a theme-tinted dark outline around every glyph — the chain icon especially is much more readable now
- Allied Piglin Brutes now have 100 HP (up from 50) and wield a diamond axe with Sharpness IV in the Hell's Sword soul-fire look: no enchantment glint, soul flames licking from the axe hand

## 0.3.2 — Split particle settings & /hha settings (2026-07-15)

### Changed
- The particle setting is now split in two: `particle_player` scales only the idle particles on the player (orbit glow, armor/hand flames, halo ring, lava sparks, grace feathers, Undying Rage aura), `particle_effects` scales ability/impact effects. Functional visuals like the grapple/fire chain are never thinned. Replaces `particle_multiplier` from 0.3.0.
- New `/hha settings` menu: `/hha settings particles player|effects <0..1>` shows or sets the multipliers (also available via `/hha set`)

## 0.3.1 — Magma Stomp lava actually disappears (2026-07-15)

### Fixed
- Magma Stomp lava pools really do remove themselves now. The cleanup used `removeBlock()`, which in vanilla re-places the fluid at that position ("break the block, keep the liquid") — for a lava block that is a no-op, so the pool stayed forever. The cleanup now sets air explicitly.

### Added
- `stomp_lava_lifetime` config value (ticks, default 40 = 2 s): how long the Magma Stomp lava pool lasts before it removes itself

## 0.3.0 — Particle settings, live item lore & debug commands (2026-07-15)

### Added
- `particle_multiplier` config value (default 1): scales all HHA particle effects, e.g. `/hha set particle_multiplier 0.3` for 30% density, 0 turns them off; small emitters are thinned probabilistically
- Debug commands: `/hha save` (write config to disk), `/hha load` (re-read config/hha.json), `/hha reload` (config + custom recipes + client sync)

### Changed
- Item lore is now built live on the client and shows the actual config values — damage, cooldowns (in seconds) and thresholds (in hearts); the server syncs its numbers on join and after `/hha set|reset|load`, so tooltips are always accurate
- Chestplates gained a lore line describing the full-set beam (damage, healing, cooldown)

## 0.2.1 — Light trail fix & rebalanced defaults (2026-07-15)

### Fixed
- Heaven's Step (light trail) now triggers from actual player movement. It previously checked server-side velocity, which stays at zero for normal walking and only gets set by external pushes — so the trail only appeared when another player was shoving you.

### Changed
- Rebalanced default config: kit mode on by default; harder-hitting abilities (stomp 60, beam 60, shockwave base 12 / max 5000, light wave 20 dmg / 5 knockback, pull arrival 40, bounce 16, purify heal 16) with longer cooldowns (beam 1000, divine shield 1600, mace throw 420, pull 120, grapple 80) and Magma Stomp from 10+ blocks; sword buff cooldown down to 1200. Existing `config/hha.json` files keep their saved values — use `/hha reset` to adopt the new defaults.

## 0.2.0 — Ability keybinds, configurable throw damage & fixes (2026-07-15)

### Changed
- Keybinds are now listed as "Ability 1" (set beam), "Ability 2" (Fire Camp) and "Ability 3" (Ultra) in the controls menu; lore and `/info` texts reference them accordingly
- Heaven's Mace throw damage is now configurable via `mace_throw_damage` (default 5, i.e. the old lightning damage); the lightning strikes are purely visual now, targets still catch fire
- Halo (Heaven's Helmet) now grants Regeneration only to trusted players in range instead of everyone

### Fixed
- Magma Stomp lava pools now clean themselves up reliably: on expiry the placed sources and any lava that flowed away from them are removed (natural lava sources are left untouched)

## 0.1.0 — Addon system (2026-07-15)

### Addon system
- Addons are regular Fabric mods that hook into HHA via the new `"hha"` entrypoint (optional `"hha_client"` for HUD) — see `ADDONS.md` and `docs/`
- Public API under `dev.henny.hha.api`: armor-set registry with builder DSL and tick hooks, ability registry bound to the existing keybinds, item registration helpers
- Events: `ABILITY_CAST` (cancellable), `PLAYER_TICK`, `FULL_SET_CHANGED`, `SHOULD_HARM`/`IS_FRIENDLY` overrides
- Addon config keys (`<addonid>.<key>`) live in `config/hha.json` and work with `/hha list|toggle|set|get`
- Addons can add slots to the cooldown HUD with their own icon and accent color
- Internally, the built-in Heaven/Hell sets and abilities now register through the same registries

## 0.0.1 — Initial release (2026-07-14)

First public release for Minecraft 1.21.11 (Fabric).

### Sets & abilities
- Heaven Set: Halo, Ascension (double jump), Shockwave, Heaven's Step light trail, Light Beam + Purify, Grace glide, Divine Shield
- Hell Set: Undying Rage, Warlord's Barrier, lava walking, Magma Stomp + Volcanic, Ember Trail, Hellforged, Fire Camp with Piglin Brute vassals and reverting soul camp (soul campfire, soul soil/sand, torches, lanterns)
- Full-set bonus (+10 hearts), fall damage immunity, Ultra Mode, faction lock, trust system

### Weapons
- Heaven's Sword: combo auto-crits; after the combo lands, every hit crits until another player hits you
- Hell's Sword: Blade Rush active (Haste V ≙ +50% attack speed, guaranteed crits, absorption blessing on crit), animated soul-fire blade
- Heaven's Mace: throwable, calls lightning along its path — outbound and on return
- Hell's Mace: chain pull (enemies are reeled all the way in), block grapple, wind charge / ender pearl chain-ride

### Content & UX
- Animated item textures, soul/holy equipment flames, custom particles, cooldown HUD with custom icons
- Hell/Heaven ingots, smithing upgrades via Jötunheim's Upgrade Template, banner patterns
- PvP kit mode (`/hha kit`), fully enchanted kits with strength & weaving splash potions
- Live config via `/hha`, per-recipe toggles, customizable recipes through `config/hha/datapack/`
- Soul-blue retexture of the Piglin Brute tunic
