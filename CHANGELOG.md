# Changelog

## 0.8.5 — Olympus, RPG mode, boss presentation (2026-11-17)

### Added — Third overhaul: boss presentation, RPG as its own game, tunnel lobby
- **New effect system** for all bosses: smooth shader effects (light trails, shells, rifts, flames, water walls, runes) in their own render pipelines with a simple fallback, real floor blocks that fly and land, glowing ground cracks, projectile trails; every attack of Hermes, Hekate, Brontes, Kratos and Bia, Zeus, Hades, Kronos, the five Tartarus wardens and the Olympus enemies has its own look ([docs](docs/bosse-allgemein.md#effekte-kamera-szenen))
- **Screen passes** at fixed moments: shock ripple (Zeus' lightning, Brontes' wedge), heat haze (bellows, parched Tantalus), Hekate's lunar eclipse, Kronos' time stop (colour drains, 3D effects freeze) and his gravity lens while devouring (the danger circle stays undistorted). At most one at a time, ending on every exit
- **Staged cutscenes** with timeline, guided camera (curves, follow, collision guard, light handheld sway), puppets, poses and costumes: intros, phase changes and defeats of every boss (45 scripts together with the RPG scenes), all skippable with a fixed end state
- **Music per phase** for every boss with smooth crossfades and victory stingers, area music in RPG worlds, 20 new pieces and 25 new sounds
- **RPG mode as its own game** ([docs](docs/rpg.md)): shared arrival cutscene after a hold in which everyone waits, chapters with goals and a direction arrow, chapter cards, island cutscenes, act end, Tartarus and palace scenes, an ending with credits; own HUD, journal (key J), pause menu, death screen, event cards; rests, pilgrim kit, fall protection, no hunger by default
- **Tunnel lobby**: the group floats in a tunnel of light with skins and the armor of the path; after departing the tunnel keeps running over loading and connection screens and opens into the arrival scene
- New client switches `screen_fx`, `cine_handheld`, `rpg_hud`; server keys `rpg_hunger`, `rpg_boss_health`, `rpg_boss_damage`, `rpg_wait_ticks` (RPG group in the control center)
- New commands (OP): `/hha fx list|play|screen|kick`, `/hha stage list|play|stop`, `/hha rpg state|chapter|arrive|rest|scene`
- Addon API: `HhaRpg.Path` takes optional `costume`, `arrival` and `arrivalStage` (existing calls compile unchanged)

### Changed — Third overhaul
- Camera shake is now a short rotation (kick) instead of moving the camera; with *Distortion Effects* at 0 % there is no kick, no shake and the screen passes become a 2D tint
- Bosses blend smoothly out of the last shown pose; capes, beards, wings, tails and chains swing with springs
- Tartarus wardens use the HHA health bar with their own theme instead of the vanilla boss bar
- Cutscenes hide the block outline; dialogue lines stay readable for a minimum time
- The RPG lobby and start animation are replaced by the tunnel lobby; old lobby texts removed
- Fight numbers (announcements, damage, health, windows, attack choice) are unchanged

### Added — RPG mode, cutscenes, new weapons
- **RPG mode** ([docs](docs/rpg.md)): new **HHA RPG** button in the main menu. Pick a path (Heaven: Olympus to Zeus, Hell: through the hell gate to Hades) and the addons that take part, create a lobby and let friends join with a six-letter code (four recommended, up to 16). The host presses start, everyone sees the same start animation instead of loading screens and lands at the start of the chosen path. The host runs the world locally; friends connect through a relay (`backend/rpg-lobby`, no worlds on the server). Addons opt in with `"hha:rpg": true` or `HhaRpg.declareSupport`/`registerPath`
- **Cutscenes for Hekate, Brontes, Kratos and the five Tartarus wardens**: camera flight on arrival, camera orbit at phase changes and on defeat; skippable like all cutscenes
- **Last Stand**: at 20 % health every Olympus duel boss fights harder (shorter pauses, harder hits) with its own title card
- **New weapon models** after the author's references: Heaven's Sword with a long glowing golden blade and golden wings at the guard, Heavens Mace as a flanged mace with gold-trimmed flanges, Hells Sword as a broad skull blade with horns, a channel full of skulls and a forked tip
- **Chain physics**: the chains of the Hells Mace (pull and grapple), the Jailer and Hades now hang and swing as a simulated rope
- **Hells Mace in hand**: the chain between handle and head is physically simulated in first and third person; the head hangs, swings when you walk, turn or strike, and bounces off the handle
- **RPG lobby redesign** in the style of the control center: your group stands as 3D skin figures on a stage with light rays in the path color, joins and leaves get a light-beam animation, path tiles with weapons, addon switches, the code as tiles, toasts; on start the lobby sinks away, the light swells and an iris closes, and the start animation reopens it (portal rings, light streaks, feathers or embers, the group's names, progress) and reveals the world through the portal

### Changed
- All bosses and wardens are much tougher (×1.6 health) and hit harder (×1.35), on top of player scaling
- Heaven armor is noticeably brighter (the gold trims keep their previous colors)
- Both maces are larger in first person; the Heavens Mace is smaller in third person
- RPG mode: dying sends you back to the start of your path (Olympus or the hell gate) instead of the Overworld spawn, unless you set your own bed
- Control center: more breathing room (taller rows, descriptions on their own line, wider column) and a switcher between H&H Armors and each addon in Abilities and Values


### Added — Second overhaul: Olympus and Tartarus
- **Olympus dimension** (`hha:olympus`, [docs](docs/olymp.md)): floating islands above a sea of clouds with a fixed island path of six large islands and five bridges, plus random sky islets with meadows, ruins (with an Olympus loot chest), cloud banks and Curete posts. No rain, no monsters, day and night, beds work
- **Cloud gate**: a glowstone frame (inside 2 × 3 to 9 × 9, corners optional) lit with a water bucket, like the Aether portal. Every gate leads to the arrival island, the way back leads to your own gate; falling off Olympus drops you into the Overworld sky with slow falling. The gate shows a parallax window with sky, cloud banks and a temple island that shift with the view (`/hhac toggle portal_shader`)
- **Sealed bridges** that close after the boss before them is beaten, a golden far beacon to the next goal and steles 6–15 that tell each island's story
- **Hekate**, goddess of thresholds, at the crossroads: torch sweeps and throws, the turning key that leaves one golden road, the threshold step, three forms of which only one carries the fire, and the lunar eclipse. Beating her opens the gate to Tartarus
- **Brontes the Cyclops** in his forge: hammer blows with cracks, the anvil as cover, glowing ingots that blind him when his hammer hits them, burning grates with cool golden sectors and "The Wedge"
- **Kratos and Bia**, the throne guard: a shield that blocks from the front, the commands BACK OFF!, COME HERE! and KNEEL! (hold sneak), Bia's charge across the plaza and the clash when you steer her into him. Bia never takes damage and has no health bar
- **Zeus** at his throne: grounding on six copper pillars, the air rule, the Gorgon gaze, eagle and storm wall, three chains named after the Cyclopes and the scales with a golden pan; intro, phase and ending cutscenes
- **Enemies of Olympus** at fixed posts: Curetes (shield and spear, in groups only one attacks at a time), griffins (dive from their column) and golden automatons (statues that wake up, weak valve on the back)
- **Relics**: every piece of the Heaven and Hell sets drops exactly once per world, spread over the bosses; a second win gives ingots instead (`/hha relics`)
- **New commands**: `/hha relics [drop|mark|reset]`, `/hha progress [set]`, `/hha boss start|skip|stop <boss>`, `/hha tp <station>`, `/hha olymp spawn <kurete|griffin|automaton>`, `/hha olymp bridges`
- New switches and numbers: `olympus_portal`, `tartaros_chain`, `relic_drops`, `relic_crafting`, `boss_anti_air`, `boss_strength`, `boss_tempo_per_player`, `boss_damage_player_cap`, `boss_smash_decay`; client switch `portal_shader`
- Speaker names and voice blips for Hekate, Brontes and Kratos; their lines (and Zeus') are prepared in `voice_lines.py` but not recorded yet

### Changed — Second overhaul
- **Stronger bosses** ([docs](docs/bosse-allgemein.md)): every boss and guardian is tougher and hits harder, +75 % health and +15 % damage per extra player (capped at 1.75), shorter pauses with more players; announcements never get shorter
- **One scaling for all fights** (`BossScaling`): a hit cap of 4 % of the base health (6 % while open, guardians 1.5×) so no single blow skips a phase
- **Defense against mace smashes**: repeated smashes deal 100 / 60 / 30 / 10 %, then the boss answers with an announced counter that throws you off, and smashes bounce off for 5 seconds. A smash into an open window always counts fully. Pillars, lightning and impacts also hit in the air; ground waves can be jumped. `/hha toggle boss_anti_air false` turns it off
- **Tartarus chain** ([docs](docs/tartaros.md)): five guardians hold the chain on the palace; every win breaks a link and makes the rest stronger, the palace seal only opens at five of five. Guardians are much stronger and have their own counters; neighbouring punishment sites now always show different sinners
- **Hermes** is the first boss of the Olympus path: started by the riddle in his temple on island B, rewards only through the relic table, throws you the Black Letter at the end
- **Hermes in Tartarus** only shows the way as a ghost (next link, distance, golden beacon)
- **Hades and Kronos** are tougher (Hades 1600, Kronos 4160 effective health per phase) and hit 20 % harder; Kronos' time stop is Slowness I without a screen flash
- **Effects calmer, same look**: at most 64 effects at once, less glow, fewer particles, camera shake capped at 3 (room-wide 5, outside fights 2), screen flash only in cutscenes and Zeus' phase changes; Divine Shield and Ultra Mode no longer flash the screen. Announcements are drawn opaque with a dark outline, safe gold areas have a double border
- **Bosses' health bars** use the boss' danger colour instead of gold
- **Items and armor** redrawn in the style of HHA Blood and HHA Void: animated icons, Heaven's Sword as a winged sword, both swords and maces as 3D hand models with 32 px icons, and a glow layer on the worn armor
- **Portals**: the Tartarus gate opens row by row and shows a parallax window into the red sky with the palace dome and chains
- **Smithing the set pieces is off by default** (`relic_crafting`); ingots and the upgrade template stay craftable
- `/hha kit` always works in creative mode, even with `kit_mode` off
- Default `hermes_health_per_player` 0.6 → 0.75 and `hermes_damage_per_player` 0.2 → 0.15 (saved configs keep their values)

### Removed — Second overhaul
- The Olympus biome and the Hades hall no longer generate in the Overworld (old worlds keep theirs; old offering stones and altars only show a hint)
- The Shade of the Messenger, with its code, textures, voice lines and music
- Hermes hunting you through Tartarus, with his voice lines
- The recipe of the Sealed Messenger Letter; the letter only points to the cloud gate now

### Added — Control center
- **New in-game menu (key K or `/hhac menu`)** with its own look instead of vanilla buttons: glass panels, animated switches and sliders, sliding tabs, toasts and floating sparks behind a blurred world
- **Overview**: your character in 3D with the worn armor, slowly turning and draggable, flanked by spinning weapons; the worn set with its pieces, every ability cooldown live and the Ultra Mode timer
- **Abilities and values**: every server toggle and number in groups (Heaven, Hell, set, bosses, particles, addons) with names, short descriptions and units (seconds, hearts, blocks); changed values glow and reset with one click. Only admins (OP level 2) can change them, everyone else sees them read-only
- **Visuals**: all client options (3D effects, camera shake, Tartarus filter, cooldown bar, Ultra bar, scale, offset, particle density) with a live preview of the cooldown bar
- **Tools**: trust or untrust online players with one switch, take kits from cards with spinning 3D weapons, and for admins start, skip or stop the Hermes fight, stop a Hades fight, save, reload or reset the config and reload recipes

### Changed — Boss overhaul
- **Every fight is a readable duel now.** Each attack is announced on the floor (circle, ring, line or the new cone) with a border that is there from the first tick, a fill that grows until the hit and a short white flash right before it. Announcements have minimum durations that no phase undercuts; later phases only shorten the pauses. After each attack the boss stands open for a moment, and a **golden rune** under him means every hit counts almost double. Gold always means "safe" or "open", danger always carries the enemy's colour
- **Far fewer effects at once**: one area effect per hit, no camera shake during an announcement, at most one room hazard at a time per player, 96 instead of 256 client effects, and the permanent glow on the bosses is cut down to two or three spots each. Announcements are drawn even with `/hhac vfx false`, because they are game information
- **Hermes**: fixed attack order per stage. Catch him: hitting him while he dashes past you deals 1.5× and makes him stumble. The theft no longer shuffles your hotbar; he holds your buffs in an orb and you get them back by hitting him. Herma stones no longer make him invulnerable; smash the glowing one before he lands on it and he crashes. The tornadoes are gone. The Caduceus serpents are no longer enemies but one announced attack, "The Twins", with no health bar, no poison and no hunting
- **Shade of the Messenger**: always vulnerable and faster, with his own tricks: smoke step (three smoke columns, only the real one shows glowing eyes), the ferryman's wave and the coin toss (heads: the centre is safe, tails: the edge)
- **Hades**: no more hell serpents, imps, rift shield or brood. Phase I is a pure duel (three-hit combo, lunge into a pillar to stagger him, slam and bident throw leave him open, his gaze), phase II lets the hall join in with one hazard at a time plus soul chains and the shadow step, phase III pairs him with Hermes in announced pincer attacks, the judgement and a floor that collapses at fixed health thresholds
- **Kronos** is the hardest fight and fights in four fixed, learnable chains. Clock hands, the time stop and the rewind are planned highlights instead of random rolls; solo players smash two hourglasses instead of three. No more Slowness V
- **Guardians fight from a script** instead of vanilla AI with timers: approach, pick an attack by distance, recover. No more summoned wither skeleton guards. Each one has a trick from its myth: the Warden's lantern and his chain hook catching on walls, Sisyphus hit by his own rolling boulder, Ixion falling off his wheel after three loosened fetters, Tantalus losing his goblet while he drinks. Kampe is her own creature now and fights on the floor of the pit
- **Imps** no longer fly through walls, come in pairs at most and announce their stab and fireball
- **Hermes in Tartarus** never shows up during a guardian fight or inside a building, waits at least 45 seconds between visits and swaps your hotbar at most once every five minutes
- **New models for every boss**, built for clear silhouettes: Hermes as a lean runner with petasos and an asymmetric chlamys, the Shade with its own burnt model, Hades as a king with the helm of invisibility, Kerberos shoulder heads and a bident, Kronos as a hunched stone colossus with an hourglass in his body and a sky ring, the four-armed bronze Warden, Sisyphus under his boulder, Ixion spread on his burning wheel, Tantalus with the fruit he never reaches, Kampe as a serpent woman with a scorpion tail, the imp, the Caduceus serpents and the Herma stones. Every attack has a windup, strike and recovery pose; bident, harpe and boulder fly as real models
- **Tartarus biomes** are larger and calmer, each with its own ground palette and a rare feature (basalt vents, ice shards, obsidian steles, root stumps); still flat, no rivers or lava
- **Every building has its own colour and silhouette**: a stepped bronze fortress with square towers, four leaning chain pylons over the Titan prison, a lower Sisyphus hill (14 instead of 32 blocks), a red-black wheel for Ixion, a fruit tree over the Tantalus basin, a palace with colonnaded towers and a propylon instead of cone roofs, a coin with Charon's boat in the Hades hall, and a temple with frieze, pediment and bronze roof. Only visible in newly generated chunks

### Added
- **Bossfight "The Last Delivery" against Hermes** (`docs/hermes.md`): four stages with their own attacks, arena look, sky and music, six cutscenes with camera moves and dialogue, and the Divine Parcel as reward (full Heaven armor plus 4 Feathers of the Messenger). Started with the new Sealed Messenger Letter. Dying in the arena never costs items
- New entities with custom animated models: Hermes in double pixel density with articulated feathered wings that fold and spread per pose (plus illusions and dash afterimages), two Caduceus serpents, Herma stones, Divine Parcel, feather and soul-lance projectiles
- New 3D effect engine with its own shader instead of flat particles: volumetric beams, shockwaves with flying debris, crescent slashes, branching lightning, light pillars, six-winged seraph wings made of individual feathers, shattering shields, real chain links, portals, tornadoes, rune circles, camera shake and screen flash
- **The real sky sets the mood**: abilities and boss stages tint the actual Minecraft sky, fog and clouds, move the sun and stars, and bring vanilla rain and lightning. Custom 3D clouds surround the arena: a sea below, a deck above, and when Zeus answers a thunderstorm rolls in from the horizon with lightning glowing inside the clouds; at the finale it tears open and god rays fall through
- Light Beam, Lava Beam, Light Wave, Fire Camp, Ultra Mode, Divine Shield, Shockwave, Magma Stomp, Blade Rush, double jump, thrown Heaven's Mace and the Hell's Mace chain all use the new effects; Heaven and Hell helmets show a 3D halo and soul-fire crown
- **Hermes glows**: the orb, staff, halo, eyes, chest sun, wing edges and sandals carry live light effects read from his bones. The colour follows the stage (gold, dusk violet, soul blue, storm white with lightning arcs), it flares when he casts and nearly goes out while he is exhausted, so the glow also shows when he can be hit. His model gained an ornate caduceus with a crystal orb and two animated serpents of light that climb the staff, rear up at the top and flick their tongues, plus glowing inlays, a courier satchel, a nose and more hair
- **Smooth feathers instead of pixelated ones**: wings, feather projectiles, the parcel, particles and all feather effects now use high-resolution curved feathers with a real quill, split barbs and a downy base
- **Caduceus serpents**: slender serpents with feathered heads and a body that slithers in a running wave (see the boss overhaul above)
- **New orchestral battle music**: four stereo themes (72 to 100 seconds) with strings, brass, choir, harp and taiko that share one leitmotif and loop seamlessly
- **Better sounds**: all boss effects were rebuilt and joined by new ones for charging, blinking, deflected blows, hits on Hermes, feather volleys, light lances, and the serpents' roar, hiss and bite, replacing the vanilla placeholders
- **New biome: Olympus.** The warm stony peaks of the Overworld become marble mountains with ruined columns, and a temple on the summit holds a riddle: four offering stones want the gifts of Hermes' first day, in the order of the sun. Solving it starts the fight without a letter
- **New cave biome: Realm of Hades**, with soul soil, basalt and soul fire. A domed hall lies beneath it, reached by a spiral shaft from the surface
- **New boss: the Shade of the Messenger.** Pay the ferryman a gold nugget at the altar to fight the evil Hermes in three phases, with his own texture, green effects, music and cutscenes. Beating him fills the black gate with green light
- **New dimension: Tartarus**, an open blackstone wasteland under a blood-red sky with huge leaning spires of green froglight, reached through a round gate with a 3D tunnel effect that pulls you in
- **Tartarus from myth**, built entirely from vanilla blocks: copper fortresses with loot and wither skeleton guards, the sunken prison of the Titans with a chained giant, and the punishment sites of Sisyphus, Ixion and Tantalus, each with a stele that tells the story
- **Hermes hunts you through Tartarus**: he is embarrassed that you got this far, so he shoots feathers, dives at you, blows you back, calls up the dead, swaps two hotbar slots now and then and even bends the pillar on the horizon. It gets worse the closer you are. Hitting him drives him off, and a lethal blow from him sends you back to your arrival instead of killing you
- **New boss: Hades.** Knock on the seal of his palace for a fight with his own model, music and cutscenes. He hits very hard and his attacks are his alone: real fissures in the floor, a severed soul you must run to, a march of the dead with one gap, and a judgement only the golden circles survive. He fakes his death twice: the first time the fight seems won, with the gate open and a chest waiting; the second time he rises and fights on with Hermes as a ghost at his side. The reward is the Hell armor
- **Hades rebuilt from the ground up**: new model, cutscenes and a three-phase fight (see the boss overhaul above)
- **The Hades fight is a real fight now**: he chases you, picks attacks by distance and keeps swinging while hazards run on the floor. Punish windows reward you: a stuck bident, an empty hand, a lunge into a pillar, a broken stance. His gaze kills unless a pillar stands between you, and pillars break. In phase 3 the floor collapses ring by ring
- **Tartarus became a world**: six biomes (Ash Wastes, Fields of Asphodel, Phlegethon, Cocytus, Erebus, Marshes of the Styx) with their own fog, ground and inhabitants, ruins with loot and the skeletons of giants
- **All Hades buildings redrawn** from a new kit of vanilla blocks: the palace with curtain wall, corner towers, a Styx moat, flying buttresses and a ribbed dome over the throne hall; a bronze fortress with a real keep; a Titan who actually looks like one; proper Sisyphus, Ixion and Tantalus sites; a temple over the entrance to the hall
- **Tartarus has its own look**: a post-processing filter with cold green shadows, ember-warm highlights, ash haze in the distance, heat shimmer, soft glow and vignette (`/hhac toggle tartaros_shader`)
- **Imps**: little ash goblins with bat wings and a fork fly through Tartarus, stab, set you on fire and throw fireballs
- **Guardians for every Tartarus building**: the Warden of Chains in the bronze fortress, Kampe in the Titan prison, and Sisyphus, Ixion and Tantalus at their punishment sites, each with their own attacks, boss bar and loot. A defeated guardian stays defeated
- **The Hades fight has three phases**: a duel, then the hall joins in through two hell rifts, then the alliance with Hermes (see the boss overhaul above)
- **One boss bar for all phases**: a split health bar shows every phase of the fight at once
- **New final boss: Kronos.** When Hades yields, the Titan breaks through the dome. A two-phase fight with his own model (hunched stone colossus with an hourglass in his body, starry robe, golden harpe and a sky ring): scythe sweeps, a thrown harpe, aeon rings, clock hands sweeping the floor, a time stop, falling sand, hourglasses that must be smashed before he rewinds time, an ageing floor and a devouring pull. In his second form Hades fights at your side
- **Tartarus is a barren wasteland now**: an almost flat plain of ash with a few dead trees and rare ruins, no mountains, chasms, rivers or floating rocks, so the structures stand out
- **Every boss has its own model** instead of a vanilla mob look (see the boss overhaul above for the current designs)
- **A bigger throne hall**: the Hades palace was rebuilt around a hall with a 46-block radius (was 34), with a larger dome, moat and court
- **Harder Kronos**: Kronos takes about twice as much damage to beat, hits 50 % harder and staggers less often
- **The Shade of the Messenger** now drops Heaven's Sword and Heaven's Mace, and everyone in the hall sees his intro cutscene and joins the fight
- `/hha kit diamond` and `/hha kit netherite` carry three splash potions each of Fire Resistance and Swiftness (8:00) instead of Weaving and two Strength II
- **Cutscenes can be skipped**: hold Enter (rebindable). In multiplayer everyone has to hold it. The story still happens, it just happens at once
- **Map to Hades**: reward from the Hermes fight that turns into a real map marking the nearest hall
- **Zeus got his moment**: two blazing eyes open in the storm clouds above the forum, framed by a brow and beard of cloud, and lightning falls from them
- **Spoken dialogue**: all 46 lines are voiced in German and English with ElevenLabs, and scenes wait for a sentence to finish (`tools/hha_assets/voice_lines.py`)
- `/hha kit diamond` and `/hha kit netherite`: the same kits with normal armor and weapons
- `/hha vfx <name>` previews every effect, `/hha hermes start|stop|skip` for admins, `/hhac vfx` and `/hhac shake` client switches
- Config: `hermes_fight`, `heaven_ingot_free_recipe`, `hermes_damage`, `hermes_toughness`, `hermes_max_hit`
- `runClientGameTest` runs the real game and takes screenshots of effects and the whole fight

### Changed
- **Serpents can be hit along their whole body**, not just at the head; bites are measured from the mouth, and the whole body flashes when struck
- **Hermes scales with the number of players**: every extra player adds health and damage (`hermes_health_per_player`, `hermes_damage_per_player`), and herma stones get tougher too. His feather bolts now also respect `hermes_damage`. This replaces the old hidden 50% toughness per player
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
