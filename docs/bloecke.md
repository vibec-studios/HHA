# Blöcke der Welt (Überarbeitung 4)

Seit Überarbeitung 4 hat HHA eigene **Deko-Blöcke** für Olymp und Tartaros, im Stil von HHA Blood: 32 × 32 Pixel, je
Material eine Farbrampe aus acht Stufen, Licht von oben links. Vanilla bleibt das Grundmaterial der Bauwerke; die
Deko-Blöcke setzen Akzente (Bänder, Leitlinien, Arenaböden, Kanten von Deckung und Ebenen, Wahrzeichen). Dazu kommen vier
**Geräte** für die Kämpfe. Alle 35 Blöcke stehen im Kreativ-Tab **„H&H Welt“**; `/hha deco give` (OP) gibt je 64 Stück.

## Im Überleben

- Deko-Blöcke baut man mit der **Spitzhacke** ab (Härte 1,8, Sprengfestigkeit 6); ohne Spitzhacke fällt nichts.
- Es gibt **kein Grundrezept**: Man findet sie in den Bauwerken. Die Varianten entstehen in der **Steinsäge** (siehe unten).
- **Sturmwolke** und **Geräte** lassen nichts fallen. Geräte sind unzerstörbar (wie der Altar der Prüfung).
- Klänge: Stein; Bronze klingt nach Kupfer, die Wolke nach Wolle.

## Olymp

| Block | ID | Art | Licht | Varianten |
| --- | --- | --- | --- | --- |
| Olymp-Marmor | `olympus_marble` | Naturstein mit Klumpen | – | – |
| Olymp-Marmorziegel | `olympus_marble_bricks` | Ziegel 15 × 7 | – | Treppe, Stufe, Mauer |
| Polierter Olymp-Marmor | `polished_olympus_marble` | poliert mit Fase, ruhige Fläche | – | Treppe, Stufe |
| Gemeißelter Olymp-Marmor | `chiseled_olympus_marble` | Mäanderrahmen mit Lorbeerkranz, stumpfes Gold-Inlay | – | – |
| Olymp-Marmorsäule | `olympus_marble_pillar` | kannelierte Säule (wie Quarzsäule ausrichtbar) | – | – |
| Vergoldeter Olymp-Marmor | `gilded_olympus_marble` | poliert mit Goldfuge (Leitlinien, Kanten) | – | – |
| Sturmmarmor | `storm_marble` | dunkler Marmor mit Blitzadern, Wetterleuchten (8 Bilder, weich) | 6 | – |
| Olymp-Bronze | `olympus_bronze` | Bronzeplatten mit Nieten | – | Treppe, Stufe |
| Gewitterwolke | `storm_cloud` | halb durchsichtig, ohne Kollision, ziehende Schlieren (16 Bilder) | – | – |

## Tartaros

| Block | ID | Art | Licht | Varianten |
| --- | --- | --- | --- | --- |
| Tartaros-Basalt | `tartarus_basalt` | Naturstein | – | – |
| Tartaros-Basaltziegel | `tartarus_basalt_bricks` | Ziegel | – | Treppe, Stufe, Mauer |
| Polierter Tartaros-Basalt | `polished_tartarus_basalt` | poliert mit Fase | – | Treppe, Stufe |
| Gemeißelter Tartaros-Basalt | `chiseled_tartarus_basalt` | Ring mit Bident, stumpfes grünes Inlay | – | – |
| Tartaros-Basaltsäule | `tartarus_basalt_pillar` | Säule | – | – |
| Seelenader-Basalt | `soul_vein_basalt` | grüne Adern, pulsieren als Doppelschlag („lub-dub“) | 7 | – |
| Glutbasalt | `ember_basalt` | Glutrisse, ruhiges Glühen | 10 | – |
| Tartaros-Bronze | `tartarus_bronze` | dunkle Bronze mit Patina | – | Treppe, Stufe |

## Geräte

Funktionsblöcke der Kämpfe mit eigenem Modell. Zustand 0 = ruhend, 1 = **golden** (bewirkt jetzt etwas, Licht 9),
2 = verbraucht. Außerhalb eines Kampfs ruhen sie; im Kampf bedienen sie nur Teilnehmer, per Rechtsklick (die Glocke auch
per Schlag). Im Zustand 0 oder 2 sagt die Statuszeile, warum nichts geschieht.

| Gerät | ID | Kampf | Wirkung |
| --- | --- | --- | --- |
| Botenglocke | `messenger_bell` | Hermes (Himmelsforum) | Glocken schlagen → Windstoß, Hermes taumelt |
| Erdungsstab | `grounding_rod` | Zeus (Thron F) | Griff ziehen → Blitz springt zu Zeus, Entladung |
| Kettenpfahl | `chain_post` | Kerkermeister (Bronzefeste) | eingehakte Kette → Pfahl umlegen, er stürzt |
| Schleusenrad | `sluice_wheel` | Tantalos | Schleuse schließen → Trinken bricht ab |

Zum Ausprobieren: Gerät setzen, darauf schauen und `/hha device 0|1|2`.

## Steinsäge

Rezepte gibt es nur innerhalb einer Familie, immer vom Naturstein bzw. vom Grundblock aus (32 Rezepte). Stufen ergeben
je 2 Stück.

| Aus | wird |
| --- | --- |
| Olymp-Marmor | Marmorziegel, polierter Marmor, gemeißelter Marmor, Marmorsäule; Ziegeltreppe, -stufe, -mauer; polierte Treppe, Stufe |
| Olymp-Marmorziegel | Ziegeltreppe, Ziegelstufe, Ziegelmauer |
| Polierter Olymp-Marmor | polierte Treppe, polierte Stufe |
| Olymp-Bronze | Bronzetreppe, Bronzestufe |
| Tartaros-Basalt | Basaltziegel, polierter Basalt, gemeißelter Basalt, Basaltsäule; Ziegeltreppe, -stufe, -mauer; polierte Treppe, Stufe |
| Tartaros-Basaltziegel | Ziegeltreppe, Ziegelstufe, Ziegelmauer |
| Polierter Tartaros-Basalt | polierte Treppe, polierte Stufe |
| Tartaros-Bronze | Bronzetreppe, Bronzestufe |

Vergoldeter Marmor, Sturmmarmor, Seelenader- und Glutbasalt haben kein Rezept.

## Bilder

Texturblätter und Proben (frisch aus dem Endstand der Überarbeitung 4):

- `tmp/overhaul4/previews/abnahme/sheet_olymp.png`, `sheet_tartaros.png`: alle Texturen je Familie, achtfach vergrößert.
- `tiling_3x3.png`: Kachelprobe, `anim_frames.png`: die acht Bilder der pulsierenden Blöcke,
  `neben_vanilla.png`: neben Quarz/Kalzit bzw. Schwarzstein/Tiefenschiefer.
- `tmp/overhaul4/previews/abnahme/geraete/`: die vier Geräte in allen Zuständen und die Gewitterwolke.

## Für Entwickler

| Pfad | Inhalt |
| --- | --- |
| `HhaDecoBlocks.kt` | Registrierung aller 35 Blöcke, `ALL` (Reihenfolge im Tab), `DECO_IDS` |
| `block/StormCloudBlock.kt`, `block/ArenaDeviceBlock.kt` | Wolke (durchsichtig, ohne Kollision) und Geräte (`MODE`, `FACING`) |
| `world/DecoPalettes.kt` | Mischungen aus Deko und Vanilla für die Baupläne (Marmorwand, Marmorboden, Basaltwand, Basaltboden, Bronze) |
| `tools/hha_assets/stonekit.py` | Textur-Bausteine: Hash, periodisches Rauschen, Index-Raster (`stone_idx`, `bricks`, `polished_idx`, `bevel`, `inset`, `chiseled`, `vein`, `colour`), die neun Rampen |
| `tools/hha_assets/deco_textures.py` | ein Eintrag je Textur; `--check` prüft Rampen, Kachelnähte, ΔE gegen Vanilla und Puls; `--preview <ordner>` schreibt die Belege |
| `tools/hha_assets/device_blocks.py` | Modelle und Texturen der Geräte und der Wolke; `--preview` |
| `tools/hha_assets/deco_registry.py` | Blockstates, Modelle, Items, Loot, Tags, Steinsäge-Rezepte; `--check` prüft Vollständigkeit |

Regeln der Texturen: nur Rampen-Indizes (Fremdfarben nur aus der Akzentrampe), Licht oben links an jedem Element,
nahtlos durch Periodizität, Animation nur über Helligkeit mit `frametime` ≥ 3 und `interpolate`.
