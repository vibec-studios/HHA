# HHA-Dokumentation

Referenz für Addon-Entwickler. Den Schnellstart mit vollständigem Beispiel
gibt es in [ADDONS.md](../ADDONS.md) im Repo-Root.

| Seite | Inhalt |
| --- | --- |
| [Sets](sets.md) | Rüstungssets registrieren, Voll-Set-Erkennung, Tick-Hooks |
| [Abilities](abilities.md) | Aktive Fähigkeiten auf den HHA-Tasten |
| [Events](events.md) | Hooks in Ability-Casts, Spieler-Ticks und Targeting |
| [Config, Lore & HUD](config-und-hud.md) | Eigene Config-Schlüssel, dynamische Lore und Cooldown-HUD-Slots |
| [Bossfight Hermes](hermes.md) | Story, Stages, Belohnung, Einstellungen und Aufbau des Bossfights |

## Wie ein Addon aufgebaut ist

Ein Addon ist eine normale Fabric-Mod mit HHA als Abhängigkeit. Statt eigener
Init-Logik deklariert es die HHA-Entrypoints:

```json
{
  "entrypoints": {
    "hha": ["com.example.MyAddon"],
    "hha_client": ["com.example.MyAddonClient"]
  },
  "depends": { "hha": "*" }
}
```

- `"hha"` → implementiert `dev.henny.hha.api.HhaAddon`; läuft serverseitig/common,
  nach den HHA-Registrierungen und **vor** dem Config-Load.
- `"hha_client"` → implementiert `dev.henny.hha.api.client.HhaClientAddon`;
  läuft nach der HHA-Client-Initialisierung.

Alles Registrieren geht über den gereichten Kontext (`HhaAddonContext`) —
dadurch sind Config-Schlüssel automatisch genamespaced und alles taucht in
den `/hha`-Commands, dem HUD und der Persistenz auf.

## Ladereihenfolge

```
HHA: Items/Entities/Partikel → eingebaute Sets & Abilities
  → Addon-Entrypoints ("hha")
  → Config laden (inkl. Addon-Defaults)
  → Commands, Networking, FactionLock, Combat, Tick-Loop
Client: HHA-HUD & Keybinds → Addon-Client-Entrypoints ("hha_client")
```

## Grenzen (Stand 0.1.0)

- FactionLock kennt nur Heaven↔Hell; Addon-Sets sind fraktionsneutral.
- Sechs Ability-Slots (1–3 belegt durch HHA, 4–6 frei für Addons) plus
  Bewegungs-Trigger; darüber hinausgehende Keybinds baut das Addon selbst.
- Assets (Modelle, Texturen, `equipment/*.json`, Lang) liefert das Addon im
  eigenen Namespace mit.
