# Textur- und Modell-Generatoren (Heaven & Hell)

Einzelne Java-Quelldateien, ohne Build-Schritt mit `java Datei.java` startbar (JDK 21). Immer **aus dem
Projektordner** aufrufen; das erste Argument ist der Projektordner (`.`). Vorbild und Gerüst sind die Generatoren der
Schwester-Mods HHA Blood (`GenTrimArmour.java`, `GenMace.java`, `GenSwordIcon.java`) und HHA Void
(`GenVoidArmour.java`, `GenVoidModels.java`, Notizen `ARMOR_NOTES.md`, `MACE_NOTES.md`).

Die Vanilla-Texturen (Netherit-Rüstung, Trim-Muster, Trim-Paletten, Netherit-Icons, Schmiedevorlage) kommen aus
`~/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar`; ein anderer Pfad kann als letztes Argument folgen.
Es wird nichts heruntergeladen.

| Generator | Schreibt | Vorschau |
| --- | --- | --- |
| `GenHeavenHellArmour.java` | `textures/entity/equipment/humanoid(_leggings)/{heaven,hell}(_glow).png`, `textures/item/{heaven,hell}_{helmet,chestplate,leggings,boots}.png` (+ `.mcmeta`, 4 Bilder) | `--preview <dir>` → `puppe_heaven.png`, `puppe_hell.png` (Papierpuppe vorn/hinten, Tag/Nacht, Ebenen, Icons) |
| `GenHeavenHellModels.java` | `models/item/<waffe>_3d.json`, `textures/item/<waffe>_3d.png`, Icons `textures/item/<waffe>.png` (32×32, 8 Bilder); Maces zusätzlich `<mace>_ench_3d.json`, `_ench_3d.png`, `_ench.png`; `heavens_mace_thrown.json` | `--preview <dir>` → `<waffe>_sheet.png`, `<waffe>_atlas.png`; `--hand <dir>` → `<waffe>_hand_1st.png`, `<waffe>_hand_3rd.png` |
| `GenHeavenHellItems.java` | `textures/item/{heaven,hell}_ingot.png` (+ `.mcmeta`), `hermes_feather`, `hades_map`, `hermes_summons`, `jotunheims_upgrade_template`, `{heaven,hell}_banner_pattern`; `textures/entity/{banner,shield}/{heaven_star,hell_flame}.png` | `--preview <dir>` → `items_sheet.png` |

Waffen: `heavens_sword`, `hells_sword`, `heavens_mace`, `hells_mace`.

```sh
java tools/textures/GenHeavenHellArmour.java .
java tools/textures/GenHeavenHellModels.java .
java tools/textures/GenHeavenHellItems.java .

# nur ansehen, nichts schreiben
java tools/textures/GenHeavenHellArmour.java . --preview tmp/overhaul2/previews/items
java tools/textures/GenHeavenHellModels.java . --preview tmp/overhaul2/previews/items
java tools/textures/GenHeavenHellModels.java . --hand tmp/overhaul2/previews/items
java tools/textures/GenHeavenHellItems.java . --preview tmp/overhaul2/previews/items
```

## Von Hand geschrieben (nicht generiert)

- `items/heavens_sword.json`, `hells_sword.json`: `minecraft:select` auf `display_context`: 2D-Icon für `gui`,
  `ground`, `fixed`, `on_shelf`, sonst `<waffe>_3d`.
- `items/hells_mace.json`: Verzauberungs-Bedingung (`has_component enchantments`) außen, darin je dieselbe Auswahl
  (`hells_mace` / `hells_mace_3d` bzw. `hells_mace_ench` / `hells_mace_ench_3d`).
- `items/heavens_mace.json`: zuerst `custom_model_data` (geworfener Hammer → `heavens_mace_thrown`, das 3D-Modell mit
  eigenem `ground`-Wert für `HeavensMaceEntityRenderer`), dann wie beim Hells Mace.
- `models/item/<waffe>.json` und `<mace>_ench.json`: 2D-Modelle (`minecraft:item/handheld`, `layer0` = Icon).
- `equipment/heaven.json`, `hell.json`: je zwei Ebenen (`hha:<set>`, `hha:<set>_glow`).

## Stellschrauben

| Was | Wo |
| --- | --- |
| Farben Rüstung (Metall, Trim, Glow) | `GenHeavenHellArmour`: `METAL_HEAVEN`, `METAL_GAMMA`, `TRIM_HEAVEN`, `TRIM_HELL`, `GLOW_HEAVEN`, `GLOW_HELL` |
| Wie viel vom Trim leuchtet | `GLOW_FROM` (0..1, Anteil der Trim-Stufen; höher = nur die hellsten Linien) |
| Spreizung der Trim-Stufen | `TRIM_CURVE` (höher = dunklere Grundstufen, mehr Kontrast) |
| Atemzug der Rüstungs-Icons | `ICON_BREATH` |
| Trim-Muster je Teil | `HEAVEN` / `HELL` (Helm, Brust, Hose, Schuhe) |
| Puls der Glow-Ebene (3,2 s, 75–100 %) | `src/main/java/dev/henny/hha/mixin/client/EquipmentRendererMixin.java` (`HHA_PULSE_MS`, `0.75 + 0.25 * …`) |
| Größe der Waffen in der Hand | `GenHeavenHellModels`: `SWORD_3RD`, `MACE_3RD`, `MACE_1ST`; Ego-Sicht der Schwerter `HEAVEN_SWORD_FP`, `HELL_SWORD_FP` |
| Haltung | `SWORD_TP`, `MACE_TP` (z-Winkel: positiv kippt nach unten), `MACE_FP`, `pose(lean, pitch, roll, Hand, Größe, Griff)` |
| Griffpunkte | `SWORD_GRIP`, `MACE_GRIP`, `MACE_GRIP_3RD` |
| geworfener Heavens Mace | `THROWN_SCALE` |
| Verzauberungs-Fassung der Maces | `CHARGE` (Aufhellung der Leuchtteile) |

Varianten der Haltung ohne Spielstart vergleichen (mehrere durch `;`, Kurzform mit `p` = `pose`-Werte):

```sh
java -Donly=heavens_sword -Dheavens_sword.fp="p6,52,50,4.6,-4.5,0.3,0.88;p6,58,38,4.2,-3.6,0.5,1.0" tools/textures/GenHeavenHellModels.java . --hand <dir>
```

Hinweis: Die Generatoren schreiben Dateien mit LF-Zeilenenden; ein erneuter Lauf ändert sonst nichts, solange die
Stellschrauben gleich bleiben.
