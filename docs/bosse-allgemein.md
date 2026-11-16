# Bosse allgemein

Was für alle Bosse, Wächter und Gegner gilt: Stärke und Skalierung, der Deckel je Treffer, die Abwehr gegen Mace-Sprünge,
die Reliquien, die Befehle und der Aufbau im Code. Die Kämpfe selbst stehen in [Olymp](olymp.md) und
[Tartaros](tartaros.md).

## Regeln jedes Kampfes

- **Ansage → Treffer → Fenster.** Jeder Angriff wird am Boden angesagt (Kreis, Ring, Linie, Kegel). Der Rand steht sofort
  (dunkle Kontur, helle Linie), die Füllung wird bis zum Treffer kräftiger und blitzt in den letzten 4 Ticks weiß auf.
  Ansagen werden auch mit `/hhac vfx false` gezeichnet. Keine Ansage ist kürzer als die Mindestzeiten (schneller Hieb 10
  Ticks, schwerer Hieb und Wurf 16, Fläche ab Radius 6 20, saalweit 40); muss man zu einem sicheren Ort laufen, wird die
  Ansage so lange, dass man ihn zu Fuß erreicht (12 Ticks Reaktion plus 0,24 Blöcke je Tick).
- **Gold** heißt immer sicher, offen oder lohnendes Ziel, nie Gefahr. Sichere Flächen haben einen doppelten Rand. Eine
  goldene Rune unter dem Boss heißt offen: Treffer zählen 1,8-fach (Wächter 1,5-fach).
- Höchstens **eine Saal-Gefahr** zur selben Zeit; kein Boss ruft Gegner mit eigener KI herbei.
- **Kein Kontrollverlust:** keine Blindheit, keine Abbaulähmung, kein Schweben, Langsamkeit höchstens Stufe I. „Knien“
  bei Kratos ist eine Eingabe (Schleichen), kein Effekt.
- **Ruhigere Effekte:** höchstens 64 Effekte gleichzeitig, Kameraschütteln bei Treffern höchstens 3, saalweit 5,
  außerhalb von Kämpfen 2; einen Bildschirmblitz gibt es nur in Cutscenes und bei Zeus' Phasenwechsel (höchstens 25 %).

## Stärke und Skalierung

`n` = Mitkämpfer (lebend, nicht Kreativ, nicht Zuschauer), höchstens 8. Alles läuft über `logic/boss/BossScaling.kt`.

| Größe | Formel | Standard: solo / 3 Spieler |
| --- | --- | --- |
| Leben `H(n)` | `boss_strength · (1 + hermes_health_per_player · (n − 1))` | 1,0 / 2,5 |
| Schaden `D(n)` | `min(1 + hermes_damage_per_player · (n − 1), boss_damage_player_cap)` × `hermes_damage` (Olymp) bzw. `hades_damage` (Tartaros) | 1,0 / 1,3 |
| Pausen | `× max(0,75, 1 − boss_tempo_per_player · (n − 1))` | 1,0 / 0,9 |

Ansagen werden mit mehr Spielern nie kürzer, nur die Pausen. Effektives Grundleben (solo):

| Boss | Effektiv | Phasen |
| --- | --- | --- |
| Hermes | 1400 | Stages bei 70 / 40 / 15 % |
| Hekate | 1300 | 66 / 33 % |
| Brontes | 1500 | 50 % |
| Kratos | 1700 | 50 % |
| Zeus | 3300 (drei Leisten zu 1100) | je Leiste |
| Hades | 1600 je Phase, drei Phasen | |
| Kronos | 4160 je Phase, zwei Phasen | |
| Wächter | Kerkermeister 800, Kampe 920, Sisyphos 750, Ixion 660, Tantalos 700, dazu der Kettenbonus | |

Die neuen Bosse haben keine Vanilla-Rüstung, damit „effektives Leben“ stimmt.

## Der Deckel je Treffer

Kein einzelner Treffer nimmt mehr als **4 %** des effektiven Grundlebens eines Bosses (in offenen Fenstern 6 %), bei
Wächtern das 1,5-Fache (6 % / 9 %). Bezugsgröße ist das Leben **solo**: Mit mehr Spielern nimmt ein Treffer anteilig
weniger von der größeren Leiste. Normale Gegner haben keinen Deckel. Der Wert steht in Promille in `hermes_max_hit`
(Standard 40).

Beispiel Hermes solo: 1400 × 40 / 1000 = 56 effektiv, das sind 40 von 1000 Leistenpunkten (offen 84 = 60 Punkte).

## Abwehr gegen Mace-Sprünge

Drei Stufen, für alle Bosse und Wächter (`logic/boss/BossDefense.kt`):

1. **Abnehmender Smash-Schaden:** Jeder Sturztreffer zählt mit. Der erste zählt voll, der zweite 60 %, der dritte 30 %,
   jeder weitere 10 %. Ohne Sturztreffer sinkt der Zähler alle `boss_smash_decay` Ticks (Standard 120) um eins. In
   **offenen Fenstern** zählt ein Smash immer voll und erhöht den Zähler nicht.
2. **Konter:** Nach dem zweiten Sturztreffer kommt nach dem laufenden Angriff ein angesagter Konter: Kreis um den Boss für
   12 Ticks, dann ein Schlag nach oben mit Abwurf (er trifft auch in der Luft). Danach ist er 100 Ticks **wachsam**:
   Sturztreffer prallen ab (Ton, einmal der Hinweis). Abklingzeit 200 Ticks ab Konterbeginn. Jeder Boss hat seine eigene
   Konterbewegung (Hermes duckt sich und schießt hoch, Hekate heult, Brontes greift mit der Zange nach oben, Kratos hebt
   den Schild, Zeus die Aigis, die Wächter recken Beil, Stachel, Fels, Rad oder Dreizack).
3. **Höhenregel:** Jeder Angriff hat eine Reichweite. `SKY` (Säulen, Blitze, Einschläge, Würfe auf das Ziel, Konter)
   trifft auch in der Luft, `BODY` bis etwa 5 Blöcke über dem Boden, `LOW` (Bodenwellen, Ringe, Feger) lässt sich
   überspringen.

`/hha toggle boss_anti_air false` schaltet Stufe 1 und 2 ab (die Höhenregel bleibt).

## Reliquien

Rüstung und Waffen der beiden Sets sind **Reliquien**: Jedes Teil fällt in einer Welt genau einmal, verteilt über die
Bosse. Es liegt leuchtend beim Boss und verschwindet erst nach 10 Minuten; alle Mitkämpfer bekommen die Meldung mit dem
Namen in Set-Farbe. Wer denselben Boss noch einmal besiegt, bekommt statt der Reliquie den **Ersatz**. Die persönliche Beute und
die Erfahrung bekommt jeder Mitkämpfer bei jedem Sieg.

| Boss | Reliquie | Ersatz | Persönliche Beute | Level |
| --- | --- | --- | --- | --- |
| Hermes | Heaven Boots | 2 Heavens Barren | 3 Federn des Götterboten, 1 goldener Apfel, der Schwarze Brief (wenn man keinen trägt) | 30 |
| Hekate | – | – | Schmiedevorlage, 4 Netheritschrott, 2 goldene Äpfel | 30 |
| Brontes | Heavens Mace, Heaven Leggings | 3 Heavens Barren | 2 Heavens Barren, 8 Kupferblöcke, 1 verzauberter goldener Apfel | 40 |
| Kratos | Heaven's Sword | 2 Heavens Barren | 2 Heavens Barren, 4 Diamanten, 1 verzauberter goldener Apfel | 40 |
| Zeus | Heaven Helmet, Heaven Chestplate | 4 Heavens Barren | 3 Heavens Barren, Netherstern, 2 verzauberte goldene Äpfel | 80 |
| Kerkermeister | Hells Mace | 2 Hell-Barren | 2 Hell-Barren, 2–4 Diamanten, 1–2 goldene Äpfel | 30 |
| Kampe | Hell Chestplate | 3 Hell-Barren | wie Kerkermeister, dazu 2 Netheritschrott | 30 |
| Sisyphos | Hell Boots | 2 Hell-Barren | wie Kerkermeister | 30 |
| Ixion | Hells Sword | 2 Hell-Barren | wie Kerkermeister | 30 |
| Tantalos | Hell Leggings | 2 Hell-Barren | wie Kerkermeister | 30 |
| Hades (mit Kronos) | Hell Helmet | 4 Hell-Barren | 3 Hell-Barren, Netherstern, 2 verzauberte goldene Äpfel | 80 |

- Der Zustand liegt in der Welt (`hha_relics`), nicht beim Spieler.
- **Schmieden der Set-Teile ist standardmäßig aus** (`relic_crafting false`), sonst wäre die Einmaligkeit wertlos. Barren
  und Schmiedevorlage lassen sich weiter herstellen. `/hha toggle relic_crafting true` holt die Rezepte zurück (mit
  Datapack-Reload).
- `/hha toggle relic_drops false`: Reliquien fallen bei jedem Sieg neu.
- **Kits** (`/hha kit heaven|hell`) gehen weiter, wenn `kit_mode` an ist, und im Kreativmodus immer; sie ändern das
  Register nicht.

## Befehle

| Befehl (OP, außer `kit`) | Wirkung |
| --- | --- |
| `/hha relics` | alle zwölf Reliquien: frei (fällt bei …) oder gefallen (wann, bei wem, nächster Spieler) |
| `/hha relics drop <id> [spieler]`, `mark <id> <true\|false>`, `reset <id\|all>` | Reliquie geben, eintragen, freigeben |
| `/hha progress`, `/hha progress set <schlüssel> <true\|false>` | Olymp-Pfad (`hermes`, `hekate`, `brontes`, `kratos`, `zeus`) und Tartaros-Kette (`warden`, `kampe`, `sisyphus`, `ixion`, `tantalus`, `hades`) |
| `/hha boss start <boss>`, `skip [boss]`, `stop [boss]` | Kampf an seiner Insel starten, nächste Phase, Abbruch ohne Sieg |
| `/hha tp olymp\|tempel\|dreiweg\|schmiede\|stufen\|thron\|tartaros` | Schnellreise |
| `/hha olymp spawn <kurete\|griffin\|automaton>`, `/hha olymp bridges` | Testposten, Siegelbrücken sofort schließen |
| `/hha hermes start\|stop\|skip` | alter Hermes-Start am eigenen Standort |
| `/hha kit heaven\|hell` | Kit, wenn `kit_mode` an ist oder im Kreativmodus |
| `/hha vfx <effekt> [stil]` | jeden 3D-Effekt einzeln zeigen, z. B. `/hha vfx telegraph_styles` |

## Einstellungen

| Schlüssel | Standard | Bedeutung |
| --- | --- | --- |
| `olympus_portal` | `true` | Wolkentore lassen sich zünden |
| `tartaros_chain` | `true` | Das Palastsiegel verlangt alle fünf Glieder |
| `relic_drops` | `true` | Reliquien fallen je Welt nur einmal |
| `relic_crafting` | `false` | Schmiederezepte der zwölf Set-Teile |
| `boss_anti_air` | `true` | Abnehmender Smash-Schaden und Konter |
| `boss_strength` | `1.0` | Leben aller Bosse, Wächter und Gegner (Faktor) |
| `boss_tempo_per_player` | `0.05` | Pausen je weiterem Spieler kürzer (höchstens auf 75 %) |
| `boss_damage_player_cap` | `1.75` | Höchster Schadensfaktor durch Spielerzahl |
| `boss_smash_decay` | `120` | Ticks, bis der Smash-Zähler um eins sinkt |
| `hermes_health_per_player` | `0.75` | Leben je weiterem Spieler (gilt für alle Bosse) |
| `hermes_damage_per_player` | `0.15` | Schaden je weiterem Spieler (gilt für alle Bosse) |
| `hermes_max_hit` | `40` | Deckel je Treffer in Promille des effektiven Grundlebens |
| `hermes_damage`, `hades_damage` | `1.0` | Schaden aller Gegner im Olymp bzw. im Tartaros |
| `hermes_toughness` | `1.0` | Zähigkeit des Hermes |
| `hermes_fight` | `true` | Hermes-Kampf erlaubt |

Die Schlüssel `hermes_*` heißen aus Rücksicht auf gespeicherte Configs weiter so, obwohl sie für alle Bosse gelten. Alte
Config-Dateien behalten ihre gespeicherten Werte (z. B. 0,6 / 0,2); neue Standards greifen erst nach `/hha reset`.

## Cutscenes überspringen

Jede Cutscene lässt sich mit **gehaltener Eingabetaste** überspringen (unter *Steuerung* änderbar). Rechts unten zeigt
ein Hinweis Taste und Fortschritt; spielen mehrere zusammen, müssen alle halten. Das Drehbuch läuft intern trotzdem durch:
Danach stehen Arena, Boss und Belohnung so, wie sie stehen sollen.

## Für Entwickler

| Pfad | Inhalt |
| --- | --- |
| `logic/boss/BossScaling.kt`, `BossDefense.kt`, `Arenas.kt` | Skalierung und Deckel; Smash-Folge, Konter, Höhenregel; Arena, Blockschutz, Bö, K. o. |
| `logic/boss/BossRewards.kt`, `logic/world/RelicLedger.kt` | Reliquien-Tabelle und das Register je Welt (`hha_relics`) |
| `logic/world/OlympusProgress.kt`, `TartarosChain.kt` | Fortschritt des Olymp-Pfads und der Kette |
| `entity/boss/DuelBossEntity.kt` | Basis der neuen Bosse: Phasen, geteilte Leiste in der Gefahrfarbe, Start, Abbruch, Sieg |
| `entity/boss/olymp/*Entity.kt`, `logic/boss/ZeusScenes.kt` | Hekate, Brontes, Kratos und Bia, Zeus |
| `logic/boss/timings/<Boss>Timings.kt` | Zeiten und Maße je Boss; Server und Animation lesen dieselben Konstanten |
| `entity/mob/OlympMobs.kt`, `logic/world/OlympusLife.kt` | Gegner des Olymp und ihre Posten |
| `entity/miniboss/GuardianEntity.kt`, `MiniBosses.kt`, `logic/world/TartarosMinibosses.kt`, `TartarosGuide.kt` | Wächter, Kette, Wegweiser |
| `logic/boss/HermesFight.kt`, `HermesAttacks.kt`, `HermesCutscenes.kt`, `HadesFight.kt`, `HadesAttacks.kt`, `KronosAttacks.kt` | Hermes, Hades, Kronos |
| `logic/boss/Telegraph.kt` | Alle Ansagen: Formen, Mindestzeiten, Budget, Trefferprüfung, Rütteln; `Group.cancel()` nimmt die Ansagen eines abgebrochenen Angriffs zurück |
| `logic/boss/Cine.kt`, `client/cine/CinePlayer.kt` | Cutscene-Protokoll, Sprecher (Hekate 6, Brontes 7, Kratos 8) und Wiedergabe |
| `world/olymp/` | `OlympusLayout` (alle Koordinaten), Inseln, Brücken, Baupläne, Nebeninseln |
| `logic/world/PortalFrames.kt`, `OlympusGate.kt`, `HadesGate.kt`, `PortalLinks.kt`, `client/vfx/PortalVfx.kt`, `shaders/core/portal.*` | Wolkentor und Höllentor, Rahmen, Reise, Parallaxe-Fenster |
| `logic/Vfx.kt`, `client/vfx/` | 3D-Effekte (höchstens 64 gleichzeitig), `SkyMood` (Himmel), `BossGlow` (Dauerlicht) |
| `tools/hha_assets/<name>_model.py`, `client/model/<Name>Model.kt` | Je Boss und Gegner ein Modell; `olymp_props_model.py` für Keraunos, Adler, Rohling, Fackel, Keil, Klinge, Schild, Kranz |

`client/model/HhaModelData.kt` ist generiert: `python tools/hha_assets/boss_models.py --only <name>` erzeugt ein Modell neu,
ohne `--only` alle. Ansehen ohne Spiel: `python tools/hha_assets/preview.py <name> bild.png [--poses a,b] [--silhouette|--glow]`
(für Bia liest es `POSES_BIA`). `gradlew runVerifyWorldgen` prüft Weltdaten, Baupläne, Kampfflächen, die Rahmenerkennung des
Wolkentors und die Ankunft, ohne eine Welt zu laden.

### Sprachaufnahmen

`tools/hha_assets/voice_lines.py` erzeugt Dialogzeilen mit ElevenLabs (`ELEVENLABS_API_KEY` in der Umgebung, wird nirgends
gespeichert), trägt sie in `sounds.json` ein und schreibt die Sprechdauer nach `logic/boss/HhaVoiceLengths.kt`. Die Zeilen
von Zeus, Hekate, Brontes und Kratos sind vorbereitet, aber **nicht erzeugt**; ihre Stimmen (`hekate`, `brontes`, `kratos`)
sind Platzhalter. `--dry-run` zeigt vorher, was ein Lauf kosten würde. Die vorhandenen Aufnahmen stammen aus dem kostenlosen
Plan: **Stimmen von ElevenLabs (elevenlabs.io), nur nicht-kommerzielle Nutzung.**
