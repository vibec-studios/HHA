# Config, Lore & HUD

## Config-Schlüssel

Addons legen ihre Einstellungen über den Kontext an — im `"hha"`-Entrypoint,
**bevor** HHA die Config lädt:

```kotlin
context.registerToggle("frost_nova", default = true)    // → "myaddon.frost_nova"
context.registerNumber("nova_damage", default = 8.0)    // → "myaddon.nova_damage"
context.registerNumber("nova_cooldown", default = 200.0)
```

Die Schlüssel landen als `<addonid>.<key>` in `config/hha/hha.json` und verhalten
sich wie eingebaute:

- `/hha list` zeigt sie gruppiert unter „Addon: <id>", `/hha toggle|set|get`
  ändern sie live (OP Level 2)
- Werte persistieren und überleben `/hha reset` als Default
- Zahlenwerte werden automatisch an alle Clients gesynct (für die Lore)

Lesen (überall außer im Entrypoint selbst — dort ist die Datei noch nicht
geladen):

```kotlin
context.toggle("frost_nova")        // kurzer Key
context.number("nova_damage")
HhaConfig.enabled("myaddon.frost_nova")  // voller Key, z. B. aus statischem Kontext
```

Konvention: Cooldown-Schlüssel enthalten die Dauer in **Ticks** und werden
über `Cooldowns.set(player, stack, "myaddon.nova_cooldown")` angewendet.

## Dynamische Lore

`context.registerLore(item) { ... }` (Registry: `api.lore.HhaLore`) definiert
Tooltip-Zeilen, die clientseitig mit den echten, vom Server gesyncten
Config-Werten gerendert werden — genau wie bei den HHA-Items:

```kotlin
context.registerLore(FROST_BLADE) {
    line("item.frost.frost_blade.lore1")                            // statisch
    line("item.frost.frost_blade.lore2",                            // mit %s-Platzhaltern
         num("frost.nova_damage"), seconds("frost.nova_cooldown"))
}
```

- `num(key)` — Wert unverändert (Schaden, Blöcke, Anzahl)
- `seconds(key)` — Ticks → Sekunden
- `hearts(key)` — Half-Hearts → Herzen

Die Zeilen erscheinen goldfarben direkt unter dem Itemnamen und sind nach
`/hha set` sofort aktuell. Config-Schlüssel sind volle Keys (`addonid.key`
oder eingebaute wie `beam_damage`).

## Cooldown-HUD

Über den `"hha_client"`-Entrypoint (`HhaClientAddon`) hängen Addons Slots in
die HHA-Leiste über der Hotbar:

```kotlin
class MyAddonClient : HhaClientAddon {
    override fun onInitializeClient(context: HhaClientAddonContext) {
        context.registerHudCooldown(
            Identifier.of("myaddon", "hud/nova"),   // GUI-Sprite
            0x6FD9FF,                               // Akzentfarbe 0xRRGGBB
        ) { player ->
            player.getEquippedStack(EquipmentSlot.CHEST)
                .takeIf { stack -> frostSet.isSetItem(stack) }
        }
    }
}
```

- Der Provider läuft pro Frame; `null`/leerer Stack blendet den Slot aus
  (z. B. wenn das Setteil nicht getragen wird).
- Angezeigt wird der **Item-Cooldown** des gelieferten Stacks
  (`itemCooldownManager`) — genau das, was `Cooldowns.set` setzt.
- Das Icon ist ein GUI-Sprite: `assets/<ns>/textures/gui/sprites/<pfad>.png`
  (im Beispiel `assets/myaddon/textures/gui/sprites/hud/nova.png`).
- Bereite Fähigkeiten pulsieren in der Akzentfarbe, laufende Cooldowns
  dunkeln ab und zeigen einen Fortschrittsbalken — wie die eingebauten Slots.

## Kontrollzentrum (Taste K)

Alles aus `/hha` und `/hhac` gibt es auch als Menü: Taste **K** (unter *Steuerung* änderbar) oder `/hhac menu`.

| Tab | Inhalt |
| --- | --- |
| Übersicht | Spieler in 3D mit Rüstung (ziehen zum Drehen), getragenes Set, Cooldowns live, Ultra-Modus |
| Fähigkeiten | Alle Server-Schalter in Gruppen; Addon-Schlüssel landen in einer eigenen Gruppe je Addon |
| Werte | Alle Server-Zahlen als Regler mit Einheit (Sekunden, Herzen, Blöcke), Zurücksetzen per ↺ |
| Darstellung | Die Client-Einstellungen unten, mit Vorschau der Cooldown-Leiste |
| Werkzeuge | Trust per Schalter, Kits als Karten, für Admins: Hermes starten/überspringen/abbrechen, Hades abbrechen, Config speichern/laden/zurücksetzen, Rezepte laden |

Server-Einstellungen ändern nur Spieler mit OP-Stufe 2 (wie `/hha`), alle anderen sehen sie schreibgeschützt. Ohne HHA
auf dem Server bleibt nur der Tab *Darstellung* nutzbar. Beschriftungen kommen aus `hha.gui.cfg.<key>` und
`hha.gui.cfg.<key>.desc`; Addons können dort eigene Namen für ihre Schlüssel (`<addonid>.<key>`) mitliefern, sonst
zeigt das Menü den Schlüssel lesbar formatiert. Netzwerk: `GuiRequestPayload`, `GuiActionPayload`, `GuiStatePayload`
in `net/GuiPayloads.kt`, Serverlogik in `logic/GuiServer.kt`, das Menü in `client/gui/`.

## Client-Einstellungen (`/hhac`)

Rein clientseitige Darstellungsoptionen — sie werden **nie** vom Server
überschrieben und liegen in `config/hha/client.json`. Der Clientbefehl
`/hhac` ändert sie live:

| Befehl | Schlüssel | Standard | Wirkung |
| --- | --- | --- | --- |
| `/hhac hud on\|off` | `hud_enabled` | `on` | komplette Cooldown-Leiste ein/aus |
| `/hhac pulse on\|off` | `hud_pulse` | `on` | Pulsieren bereiter Slots |
| `/hhac background on\|off` | `hud_background` | `on` | dunkler Slot-Hintergrund |
| `/hhac ultrabar on\|off` | `ultra_bar` | `on` | Ultra-Statusleiste oben |
| `/hhac vfx true\|false` | `vfx_3d` | `true` | 3D-Effekte (Strahlen, Flügel, Schockwellen …) |
| `/hhac shake true\|false` | `screen_shake` | `true` | Kameraschütteln |
| `/hhac scale <0.5–2.0>` | `hud_scale` | `1.0` | HUD-Skalierung |
| `/hhac offset <x> <y>` | `hud_offset_x/y` | `0` | HUD-Position verschieben (Pixel) |
| `/hhac density <0.0–1.0>` | `particle_density` | `1.0` | HHA-Partikel clientseitig ausdünnen (0 = aus; die Grapple-Kette bleibt immer sichtbar) |

`/hhac list` zeigt alle aktuellen Werte, `/hhac reset` stellt die Defaults
wieder her.

Seit der Überarbeitung 2 gibt es dazu den Schalter für das Fenster der Tore (in der Schalterliste des Menüs fehlt er
noch, `/hhac toggle` geht):

| Befehl | Schlüssel | Standard | Wirkung |
| --- | --- | --- | --- |
| `/hhac toggle portal_shader true\|false` | `portal_shader` | `true` | Wolkentor und Höllentor zeigen ein Fenster mit Tiefe (eigener Shader); aus = nur die animierte Blocktextur |
| `/hhac toggle tartaros_shader true\|false` | `tartaros_shader` | `true` | Bildfilter im Tartaros |

## Neue Server-Schalter und Zahlen (Überarbeitung 2)

Für Bosse, Reliquien und Tore; alle über `/hha toggle` bzw. `/hha set` (OP). Bedeutung und Formeln stehen in
[Bosse allgemein](bosse-allgemein.md#einstellungen).

| Schlüssel | Art | Standard | Kurz |
| --- | --- | --- | --- |
| `olympus_portal` | Schalter | `true` | Wolkentore zünden |
| `tartaros_chain` | Schalter | `true` | Palastsiegel verlangt 5/5 Glieder |
| `relic_drops` | Schalter | `true` | Reliquien je Welt einmal |
| `relic_crafting` | Schalter | `false` | Schmiederezepte der Set-Teile (löst einen Datapack-Reload aus) |
| `boss_anti_air` | Schalter | `true` | Smash-Folge und Konter |
| `boss_strength` | Zahl | `1.0` | Leben aller Bosse |
| `boss_tempo_per_player` | Zahl | `0.05` | kürzere Pausen je weiterem Spieler |
| `boss_damage_player_cap` | Zahl | `1.75` | Deckel des Schadensfaktors |
| `boss_smash_decay` | Zahl (Ticks) | `120` | Abbau des Smash-Zählers |
| `hermes_health_per_player` | Zahl | `0.75` (vorher 0,6) | Leben je weiterem Spieler, für alle Bosse |
| `hermes_damage_per_player` | Zahl | `0.15` (vorher 0,2) | Schaden je weiterem Spieler, für alle Bosse |

Die neuen Schlüssel stehen noch nicht in den Listen des Kontrollzentrums (`client/gui/ConfigMeta.kt`); bis dahin gehen sie
über `/hha`. Die Beschriftungen `hha.gui.cfg.relic_drops`, `hha.gui.cfg.relic_crafting` und `hha.gui.client.portal_shader`
liegen in den Sprachdateien bereit.

## Neue Schalter (Überarbeitung 3)

Client (`config/hha/client.json`, `/hhac toggle <schlüssel>`, im Kontrollzentrum unter *Darstellung*):

| Schlüssel | Standard | Wirkung |
| --- | --- | --- |
| `screen_fx` | `true` | Bildschirm-Pässe der Bosse (Stoßring, Hitzeflimmern, dunkle Stunde der Hekate, Zeitstopp, Gravitationslinse). Aus = 2D-Abdunklung/Tönung mit gleicher Dauer |
| `cine_handheld` | `true` | leichtes Schwanken der Szenenkamera wie aus der Hand |
| `rpg_hud` | `true` | eigenes HUD und Pausenmenü in RPG-Welten; aus = Vanilla (wirkt sofort) |

Die Vanilla-Option *Barrierefreiheit → Verzerrungseffekte* dämpft Ruck, Rütteln, Handkamera und Pässe mit; bei 0 % laufen
die Pässe als 2D-Ersatz. Mehr in [Bosse allgemein](bosse-allgemein.md#effekte-kamera-szenen).

Server (`/hha toggle` bzw. `/hha set`, im Kontrollzentrum in der Gruppe **RPG**); sie wirken nur in RPG-Welten:

| Schlüssel | Art | Standard | Kurz |
| --- | --- | --- | --- |
| `rpg_hunger` | Schalter | `false` | Hunger in RPG-Welten |
| `rpg_boss_health` | Zahl (0,25–2,0) | `0.8` | Leben der Bosse |
| `rpg_boss_damage` | Zahl (0,25–2,0) | `0.85` | Schaden der Bosse |
| `rpg_wait_ticks` | Zahl (100–1200 Ticks) | `400` | Frist der gemeinsamen Ankunft |

Neue Taste: **Tagebuch** (`key.hha.journal`, Standard J, Kategorie H&H Armors) – nur in RPG-Welten. Details:
[RPG-Modus](rpg.md).

## Neue Schalter und Befehle (Überarbeitung 4)

Server (`/hha toggle <schlüssel> <true|false>`, HHA-Einstellungen):

| Schlüssel | Standard | Wirkung |
| --- | --- | --- |
| `world_retrofit` | `true` | Olymp-Inseln und -Brücken in alten Welten automatisch nach dem neuen Bauplan nachrüsten (beim Näherkommen, in Scheiben; Truhen bleiben). Aus = nichts ändert sich von selbst; `/hha world retrofit run <teil>` wirkt trotzdem |

Befehle (OP 2, `config/Overhaul4Commands.kt`):

| Befehl | Wirkung |
| --- | --- |
| `/hha arena list` | alle Arenen mit Bauplan |
| `/hha arena show <id> cover\|levels\|parts\|devices` | Deckung, Ebenen, bewegliche Teile bzw. Geräte 10 s als Partikel |
| `/hha arena repair <id>` | Arena gegen den Bauplan reparieren (im Tartaros im Bauwerk stehen) |
| `/hha arena restore` | alle Änderungen des laufenden Kampfs zurücksetzen |
| `/hha arena part <teil>` | ein Teil im laufenden Kampf abwerfen |
| `/hha world retrofit status\|run <teil>\|all` | Stand bzw. Nachrüstung der Pfad-Teile `isle_A` … `isle_F`, `bridge_ab` … `bridge_ef` |
| `/hha sub test <0–4>` | Unterleisten zur Probe |
| `/hha device <0\|1\|2>` | Gerät im Blick umschalten |
| `/hha approach` | Weg, Fortschritt, nächster Wegpunkt |
| `/hha deco give` | je 64 aller 35 Deko- und Geräte-Blöcke |

Neuer Kreativ-Tab **„H&H Welt“** mit allen Deko-Blöcken und Geräten ([Blöcke](bloecke.md)). Die Statuszeile zeigt in
Kämpfen „gedeckt“/„gesehen“ und warum ein Gerät gerade nichts tut. Arenen und Begriffe: [Bosse allgemein](bosse-allgemein.md#arena).
