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
