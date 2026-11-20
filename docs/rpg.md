# RPG-Modus

Gruppenabenteuer als eigenes Spiel, direkt aus dem Hauptmenü: **HHA RPG** (rechts neben „Einzelspieler“). Eine
RPG-Welt hat eigene Regeln, eine Geschichte in Kapiteln, ein eigenes HUD und eigene Menüs. Alles davon gilt **nur in
RPG-Welten**; normale Welten bleiben genau wie vorher.

## Lobby

Die Lobby ist ein Ort: Die Gruppe schwebt gemeinsam in einem Lichttunnel, jede Figur mit ihrem Skin und der Rüstung des
gewählten Wegs, ein Lichtring unter den Füßen. Die Bedienung liegt im Stil des Kontrollzentrums darüber.

1. **Weg wählen:** *Weg des Himmels* (Olymp: Hermes → Brontes → Kratos & Bia → Zeus, danach weiter in den Tartaros) oder
   *Weg der Unterwelt* (Höllentor → Tartaros: fünf Wächter → Hades → Kronos). Der Tunnel ist golden bzw. seelenblau mit
   Rippen und Glut; Addons können eigene Wege beisteuern.
2. **Addons wählen:** Nur Addons, die den RPG-Modus unterstützen, lassen sich einschalten.
3. **Lobby erstellen** und den sechsstelligen **Code** weitergeben (vier Spieler empfohlen, bis 16 möglich). Freie Plätze
   zeigen „+ FREI“, ein Klick kopiert den Code. Mitspieler treten mit dem Code bei und melden sich **Bereit**.
4. **Aufbrechen** (Host): Die Oberfläche gleitet weg, die Figuren drehen sich in Flugrichtung, der Tunnel rast.

## Reise und Ankunft

- Statt Lade- und Verbindungsbildschirmen läuft der Tunnel weiter (kurz ein weißgoldenes Standbild, während die Welt
  erzeugt wird). Der Host erzeugt die Welt auf seinem Rechner, die anderen werden automatisch verbunden.
- In der Welt liegt der Tunnel weiter über dem Bild: „Warte auf die Gruppe · 1/2 … 2/2“. Der Server wartet, bis alle
  Erwarteten bereit sind – höchstens `rpg_wait_ticks` (Standard 400 = 20 s, ab der Bereit-Meldung des Hosts; einmal
  +5 s für Verbundene, die noch nicht bereit sind). In dieser **Halte** steht jeder auf seiner Marke, unverwundbar und
  ohne Steuerung.
- Dann öffnet sich das Tunnelende, dahinter läuft schon die **Ankunftsszene** für alle gemeinsam:
  - *Himmel* (`rpg.arrival.heaven`): Die Gruppe schwebt herab, landet am Wolkentor der Horen, Hermes landet auf der
    Herme, die geliehene Rüstung löst sich in Licht auf, Flug zum Tempel des Boten, Karte „Kapitel I · Das Wort“.
  - *Unterwelt* (`rpg.arrival.hell`): Nacht-Tönung, Hekate mit Fackeln, die Rüstung zerfällt in Glut, Wirbel im
    Höllentor, Karte „Kapitel I · Die Kette“.
- **Nachzügler** (später Beitretende) bekommen eine kurze eigene Ankunft (`rpg.arrival.late`). Wer schon einmal
  angekommen ist, startet bei späteren Sitzungen ohne Szene an seiner Rast.
- Jede Szene lässt sich überspringen (Taste halten; alle müssen halten). Danach steht alles so, als wäre sie
  durchgelaufen.

## Kapitel

| Weg | Kapitel |
| --- | --- |
| Himmel | Prolog *Die Ankunft* · I *Das Wort* (Hermes) · II *Das Werk* (Brontes) · III *Der Zwang* (Kratos und Bia) · IV *Die Furcht* (Zeus) · Zwischenspiel *Die Schwelle* (Hekate; entfällt, wenn sie schon besiegt ist) · V *Die Kette* (fünf Glieder) · VI *Der Herr der Tiefe* (Hades) · VII *Der Verschlinger* (Kronos) · Epilog *Licht* |
| Unterwelt | Prolog · I *Die Kette* · II *Der Herr der Tiefe* · III *Der Verschlinger* · Epilog |
| Addon-Weg | *Freier Weg* ohne Ziel |

Ein Kapitel ist fertig, wenn sein Boss besiegt ist (bzw. alle fünf Glieder der Kette gesprengt sind). Jedes Kapitel hat
Ziel-Schritte (Insel erreichen → Rätsel/Siegel → Kampf), das HUD zeigt Ziel, Pfeil und Entfernung.

**Wegpunkte (Überarbeitung 4).** Pfeil und Zieltext folgen den neuen Wegen Punkt für Punkt:

| Wo | Zielkette |
| --- | --- |
| Olymp, Brücke zur nächsten Insel | Brückenkopf → Engstelle (Brückenmitte) → Kopftor → Inseleingang → Altar; Texte `hha.rpg.story.<kapitel>.way.<n>` („Zum Brückenkopf …“, „Über den Weg der Boten“, „Durch das Flügeltor“ …) |
| Olymp, Rückweg (Zwischenspiel *Die Schwelle* von F zum Dreiweg) | dieselben Punkte rückwärts über E–F, D–E, C–D |
| Tartaros, Wächter (Kapitel *Die Kette*) | Beginn des Zuwegs (Feuerpfosten) → Wegzeichen → Eingang → Kampfplatz; beim Titanenkerker über den Randweg zum Rampenkopf im Westen |
| Tartaros, Palast | Alleeanfang (zwischen den Kolossen) → Propylon → Gang der Schatten → Halle der Richter → Styx-Brücke → Hundetor → Siegel |

Erreicht ist ein Punkt, wenn man in seiner Nähe war oder ihn auf dem Weg schon überholt hat; der Pfeil springt nie auf
einen früheren Punkt zurück. Beim ersten Betreten jeder Brücke erscheint einmal je Welt eine Erzählerzeile über der
Schnellleiste (`hha.rpg.way.bridge_ab` … `_ef`). Am Alleeanfang spielt einmal die Palastszene. Auch im normalen Modus zeigt
die goldene Fernsäule zuerst auf den fernen Brückenkopf und ab dem Kopftor auf den Altar; Hermes' Geist und die Säulen im
Tartaros zeigen auf den Beginn des Zuwegs bzw. den nächsten Punkt des Palast-Anmarschs. Wer im RPG bei einem Wächter
stirbt, steht wieder am Eingang des Zuwegs, nicht in der Arena.

Szenen der Geschichte (alle über die Bühne, überspringbar):

| Szene | Wann |
| --- | --- |
| `rpg.isle.b` … `rpg.isle.f` | erstes Betreten der Inseln B–F (nur Himmelsweg): Fahrt zum Ziel der Insel, goldene Säule, Ortskarte bzw. Kapitelkarte |
| `rpg.act_end` | nach Zeus: Zeus weist hinab, Blick in die Tiefe, „Ende des ersten Akts“ |
| `rpg.tartaros` | erstes Betreten des Tartaros; der Seelenführer erscheint |
| `rpg.palace` | nach dem fünften Glied bzw. am Alleeanfang des Palasts: Blick durch den Rundbogen den Gang hinab, grünes Glühen am Siegel, dann die Kuppel über dem Torhaus |
| `rpg.end` | nach Kronos: Stille, Licht, Aufstieg, danach der Abspann |

Spieler weiter als 48 Blöcke weg sehen eine Szene nicht; während einer Szene ist man unverwundbar.

## HUD und Menüs

- **HUD** (Website-Stil statt Vanilla-Leisten): Lebensplatte über der linken Hälfte der Schnellleiste (Zahl, zehn
  Segmente in der Wegfarbe, Rüstungswert), Schnellleiste mit gleitender Auswahl, oben links Kapitel, Ziel, Pfeil und
  Entfernung (blendet nach 6 s auf 60 %), links Mitte die Mitspieler mit Lebenslinie. Kein Hunger, keine Erfahrung.
- **Ereignis-Karten** (oben rechts, unter den Statuseffekten): Rast, Kapitel, Ziel, Tagebuch. Fortschritts- und
  Rezept-Toasts von Vanilla erscheinen in RPG-Welten nicht.
- **Tagebuch** (Taste **J**, änderbar): Tabs *Kapitel* (mit Haken und Zielen), *Wissen* (je besiegtem Boss ein Eintrag,
  nach dem Sieg ein zweiter Absatz), *Gruppe* (Mitglieder, Lobby-Code zum Kopieren). Das Spiel läuft dabei weiter.
- **Pausenmenü** (Esc): eigenes Menü ohne Unschärfe (Pfeiltasten + Enter oder 1–6): Weiter, Tagebuch, Gruppe,
  Optionen, HHA-Menü, Zum Hauptmenü. F3+Esc pausiert wie Vanilla ohne Menü.
- **Abspann** nach dem Epilog: Rolle mit Weg, Gruppe, Spielzeit, Toden, Dank; Esc schließt.
- Rückfall: `/hhac toggle rpg_hud` schaltet HUD und Pausenmenü sofort auf Vanilla zurück (Tagebuch, Tod, Karten und
  Abspann bleiben).

## Regeln der RPG-Welt

- Die Welt beginnt direkt im Olymp (Weltspawn am Anfang des Wegs).
- Keine Fortschritts- und Todesmeldungen, sofortiger Wiedereinstieg, Inventar bleibt, keine natürliche Regeneration,
  keine Locator-Leiste, keine Phantome, Händler und Patrouillen. Die Spielregeln werden bei jedem Start gesetzt.
- Hunger aus (`rpg_hunger`); eigene Heilung: 6 s ohne Schaden → 1 Leben je Sekunde, nicht im Bosskampf.
- Bosse: Leben × `rpg_boss_health` (0,8), Schaden × `rpg_boss_damage` (0,85).
- In RPG-Welten schweigt die Vanilla-Musik; im Olymp läuft leise `music.olympus`, im Tartaros `music.tartaros`.

## Gepäck, Rast, Tod

- **Pilger-Gepäck** einmal je Spieler nach der Ankunft: Diamantrüstung (Schutz II), Diamantschwert (Schärfe III), Bogen
  (Stärke III, Unendlichkeit) mit einem Pfeil, Schild, 6 goldene Äpfel, 16 Brot nur mit `rpg_hunger`.
- **Rast:** bei der Ankunft, beim ersten Betreten jeder Insel B–F und des Tartaros, nach jedem Sieg. Rast heißt:
  Wiedereinstieg dort, volle Heilung, goldene Äpfel auf 6. Karte „RAST · *Ort* · Leben voll“.
- **Tod:** dunkle Blende in der Wegfarbe, „GEFALLEN“, zurück an die letzte Rast, 3 s Resistenz, Inventar bleibt; kein
  Todesbildschirm.
- **Sturz** vom Olymp (unter y 64): kurze Blende, zurück an die Rast, 4 Schaden, nie tödlich.

## Einstellungen

| Schlüssel | Art | Standard | Wirkung |
| --- | --- | --- | --- |
| `rpg_hunger` | Schalter | `false` | Hunger in RPG-Welten |
| `rpg_boss_health` | Zahl | `0.8` | Leben der Bosse in RPG-Welten (Faktor, 0,25–2,0) |
| `rpg_boss_damage` | Zahl | `0.85` | Schaden der Bosse in RPG-Welten (Faktor, 0,25–2,0) |
| `rpg_wait_ticks` | Zahl | `400` | Frist der Halte in Ticks (100–1200) |
| `rpg_hud` (Client, `/hhac`) | Schalter | `true` | eigenes HUD und Pausenmenü; aus = Vanilla |

Im Kontrollzentrum (Taste K) stehen die Server-Schlüssel in der Gruppe **RPG**, `rpg_hud` unter *Darstellung*.

## Befehle (OP)

| Befehl | Wirkung |
| --- | --- |
| `/hha rpg state` | Weg, Kapitel, Ziel, Rast, Tagebuch, Halte-Liste |
| `/hha rpg chapter <id>` | Kapitel setzen (z. B. `h2_work`, `u1_chain`); löst keine Fertig-Szenen aus |
| `/hha rpg arrive` | Ankunft erneut abspielen (Gepäck nicht doppelt) |
| `/hha rpg rest` | Rast an die eigene Stelle setzen |
| `/hha rpg scene <id>` | eine `rpg.*`-Szene mit ihrem richtigen Anker abspielen (nicht für `rpg.arrival.*`) |
| `/hha stage play <id>` | jede Szene mit dem Spieler als Anker (zum Ansehen der Kamerafahrt) |

## Rückfälle

| Störung | Verhalten |
| --- | --- |
| Tunnel-Shader ungültig | Lobby mit 2D-Hintergrund und einzeln gezeichneten Figuren; alles bleibt bedienbar |
| Welt-Erzeugung oder Verbindung scheitert | Tunnel sofort weg, zurück in die Lobby (oder ins Hauptmenü) mit roter Meldung; nie liegt der Tunnel über einer Fehlermeldung |
| Esc beim Verbinden (Mitspieler) | zurück in die Lobby, „Reise abgebrochen.“ |
| Halte dauert am Client länger als 30 s | der Client öffnet das Tunnelende selbst und spielt die kurze Ankunft |
| Host meldet nie „bereit“ | die Frist beginnt 8 s nach seinem Beitritt (gesamt höchstens 28 s) |
| Ein Wartender trennt | er zählt nicht mehr als erwartet |
| Host verlässt die Welt in der Halte | die Welt endet, die anderen fallen zurück in die Lobby |
| Ankunftsskript fehlt oder wirft | kurzer Titel statt Szene; die Kamera wird frei |
| Wiederstart einer RPG-Welt aus dem Einzelspieler-Menü | keine Halte, kein zweites Gepäck, Spawn an der letzten Stelle |

## Technik

- Lobby und Verbindung laufen über den Dienst `wss://hha-rpg.henny.dev/ws` (Quelle: `backend/rpg-lobby`, deployt mit
  Dokploy, Projekt „HHA RPG“). Dort läuft **keine Welt**: Der Host öffnet seine Welt nur lokal für LAN, die Mods auf
  beiden Seiten reichen die Minecraft-Verbindung über den WebSocket weiter (Tunnel).
- Andere Adresse: `config/hha/rpg.json` mit `{"backend": "wss://…/ws"}` oder JVM-Option `-Dhha.rpgBackend=…`.
- Alle Spieler brauchen ein echtes Minecraft-Konto (die Welt des Hosts prüft die Anmeldung wie bei LAN üblich).
- Code: Server `rpg/` (`RpgWorld`, `RpgServer`, `RpgArrival`, `RpgStory`, `RpgRests`, `RpgRules`, `RpgKit`,
  `RpgScenes`, `RpgDebug`), Client `client/rpg/` (Lobby, `RpgJourney`, `RpgScreens`, `tunnel/`) und
  `client/rpg/game/` (HUD, Tagebuch, Pause, Tod, Karten, Abspann), Szenen `stage/scripts/RpgStages.kt`,
  `RpgStoryStages.kt`. Jede RPG-Funktion hängt am Schalter `RpgWorld.of(server).enabled` bzw. `RpgClientState.enabled`.

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

Seit der Überarbeitung 3 hat `HhaRpg.Path` hinten drei optionale Parameter; bestehende Aufrufe kompilieren unverändert:

| Parameter | Standard | Wirkung |
| --- | --- | --- |
| `costume: () -> List<ItemStack>` | `{ emptyList() }` | Rüstung der Figuren in Lobby und Ankunftsszene (leer = Heaven-Set) |
| `arrival: ((MinecraftServer) -> Arrival)?` | `null` | eigener Ankunftsort (`Arrival(welt, pos, yaw)`); dann gibt es Halte, gemeinsame Ankunft und Pilger-Gepäck, und `start` läuft **einmal je Spieler nach seiner Ankunftsszene** |
| `arrivalStage: String?` | `null` | Skript-ID der Ankunftsszene (Standard `rpg.arrival.<id>`; gibt es kein solches Skript, kommt ein kurzer Titel) |

Ohne `arrival` bleibt alles wie bisher: `start` läuft 20 Ticks nach dem ersten Betreten, kein Pilger-Gepäck.

In der Welt fragt das Addon mit `HhaRpg.isAddonActive(server, addonId)`, ob seine Inhalte laufen dürfen (außerhalb des
RPG-Modus immer `true`). `HhaRpg.isRpgWorld(server)` und `HhaRpg.currentPath(server)` geben Auskunft über die Welt.
Die vollständigen Verträge stehen in `tmp/overhaul3/API.md`, Abschnitt 6.6 und „Nachträge“.
