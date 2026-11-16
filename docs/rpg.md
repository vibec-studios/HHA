# RPG-Modus

Gruppenabenteuer direkt aus dem Hauptmenü: **HHA RPG** (rechts neben „Einzelspieler“).

1. **Weg wählen:** *Weg des Himmels* (Olymp: Hermes → Brontes → Kratos & Bia → Zeus) oder *Weg der Unterwelt*
   (Höllentor → Tartaros: fünf Wächter → Hades → Kronos). Addons können eigene Wege beisteuern.
2. **Addons wählen:** Nur Addons, die den RPG-Modus unterstützen, lassen sich einschalten; die anderen sind grau.
3. **Lobby erstellen** und den sechsstelligen **Code** an Freunde geben (vier Spieler empfohlen, bis 16 möglich).
   Freunde geben den Code unter „mit Code beitreten“ ein und melden sich **bereit**.
4. **Starten** (Host): Alle sehen dieselbe Startanimation. Der Host erzeugt die Welt auf seinem Rechner, die anderen
   werden automatisch verbunden; statt Ladebalken läuft die Animation, im Spiel blendet sie weich aus.
5. Jeder Spieler landet beim ersten Betreten am Anfang des gewählten Wegs.

## Technik

- Lobby und Verbindung laufen über den Dienst `wss://hha-rpg.henny.dev/ws` (Quelle: `backend/rpg-lobby`, deployt mit
  Dokploy, Projekt „HHA RPG“). Dort läuft **keine Welt**: Der Host öffnet seine Welt nur lokal für LAN, die Mods auf
  beiden Seiten reichen die Minecraft-Verbindung über den WebSocket weiter (Tunnel).
- Andere Adresse: `config/hha/rpg.json` mit `{"backend": "wss://…/ws"}` oder JVM-Option `-Dhha.rpgBackend=…`.
- Alle Spieler brauchen ein echtes Minecraft-Konto (die Welt des Hosts prüft die Anmeldung wie bei LAN üblich).

## Für Addon-Entwickler

Ein Addon unterstützt den RPG-Modus, wenn es eins davon tut:

```json
"custom": { "hha:rpg": true }
```

oder in `HhaAddon.onInitialize`:

```kotlin
HhaRpg.declareSupport(context.addonId)
HhaRpg.registerPath(HhaRpg.Path("blood", context.addonId, "hha_blood.rpg.path", "hha_blood.rpg.path.desc", 0xFFE0483C.toInt()) { player ->
    // Teleport zum Anfang, Startausrüstung …
})
```

In der Welt fragt das Addon mit `HhaRpg.isAddonActive(server, addonId)`, ob seine Inhalte laufen dürfen (außerhalb des
RPG-Modus immer `true`). `HhaRpg.isRpgWorld(server)` und `HhaRpg.currentPath(server)` geben Auskunft über die Welt.
