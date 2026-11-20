# Der Olymp

Der Olymp ist eine eigene Dimension (`hha:olympus`): Leere über einem Wolkenmeer, darin ein fest geplanter **Inselpfad**
aus sechs großen Inseln und fünf Brücken, ringsum zufällige kleine Himmelsinseln. Auf dem Pfad warten vier Kämpfe
nacheinander, am Dreiweg dazwischen Hekate und das Tor zur Hölle. Wie stark die Bosse sind, was sie fallen lassen und wie
die Abwehr gegen Mace-Sprünge funktioniert, steht in [Bosse allgemein](bosse-allgemein.md).

```
A Wolkentor ── B Tempel des Boten (Hermes) ══ C Dreiweg (Hekate, Höllentor) ══ D Schmiede (Brontes)
                                                                                 ║
                                       F Thron des Zeus (Zeus) ══ E Stufen der Macht (Kratos und Bia)
── offene Brücke   ══ Siegelbrücke: schließt sich nach dem Sieg über den Boss davor
```

## Das Wolkentor

Ein Rahmen aus **Glowstone** (innen 2 × 3 bis 9 × 9 Blöcke, Ecken dürfen fehlen, längs der x- oder z-Achse), dann mit
einem **Wassereimer** an eine Innenseite klicken: Das Tor füllt sich mit Wolkenlicht, Wasser fließt keins, der Eimer ist
danach leer (im Kreativmodus nicht). Im Nether zischt es nur. Wird ein Glowstone des Rahmens abgebaut, zerfällt das Tor.

| Was | Wie |
| --- | --- |
| Hinweg | 2 Sekunden im Tor stehen (Kreativ sofort): Das Bild blendet in Weißblau über, man steht auf **Insel A** vor dem Ankunftstor, Blick nach Norden zum Weg nach B |
| Rückweg | durch das Ankunftstor: zurück vor das eigene Tor. Ist es weg, zu einem Wolkentor in der Nähe, sonst entsteht ein neues kleines Tor an einer sicheren Stelle |
| Sturz | Wer vom Olymp fällt (unter y 8, nicht in einer Arena, nicht fliegend), stürzt hoch über dem eigenen Tor in den Himmel der Oberwelt, mit mindestens 30 Sekunden Sanftem Fall |
| Aussehen | Im Tor sieht man Himmel, Wolkenbänke und eine Insel mit Tempel, die sich mit dem Blickwinkel gegeneinander verschieben. `/hhac toggle portal_shader false` zeigt nur die Blocktextur |

Alle Wolkentore führen zur selben Ankunftsinsel. Mit `/hha toggle olympus_portal false` lassen sich keine neuen Tore mehr
zünden.

## Der Pfad

| Insel | Ort (Füße) | Was dort ist | Schnellreise |
| --- | --- | --- | --- |
| A Wolkentor der Horen | 0 120 −11 | Ehrenbogen mit dem Ankunftstor, die drei Horen als Statuen, Stelen 6 und 7, keine Gegner | `/hha tp olymp` |
| B Tempel des Boten | 0 129 −98 | Der Tempel mit den vier Opfersteinen: Hermes | `/hha tp tempel` |
| C Dreiweg der Hekate | 0 141 −246 | Platz mit drei Wegen, drei Torbauten mit Fallgittern, drei Fackeltürmen und drei Terrassen (+3), in der Mitte das 7 hohe Hekataion; im Nordwesten das vermauerte runde **Höllentor** (−36 135 −301), der Weg dorthin wird in gestuften Bändern zu Basalt | `/hha tp dreiweg` |
| D Schmiede der Kyklopen | 126 161 −371 | Amboss, drei Kokillen (Gussformen), sechs Roste über Glut, zwei Essebühnen (+2) am Feuermaul; dahinter die begehbare Schmiedehalle mit Herd, Blasebalg und Löschbecken, im Hof eine Galerie: Brontes | `/hha tp schmiede` |
| E Stufen der Macht | 150 193 −528 | Platz vor einer Freitreppe mit Treppenabsatz (+4), zwei Sockel, vier Standarten; Unterbau mit Lisenen, Blendbögen und Bronzeband: Kratos und Bia | `/hha tp stufen` |
| F Thron des Zeus | 32 233 −655 | Ebener Kern mit Hexagramm, darum sechs Schollen als Terrasse (+2) mit je einem Blitzpfeiler (3 × 3, Kupferkern) und Erdungsgriff, im Norden die Thronbühne (+3); außen ein Gewitterkranz aus Sturmwolken statt des alten Deckels: Zeus | `/hha tp thron` |

- **Schlachtwege (Überarbeitung 4):** Jede Brücke ist ein Weg mit eigenem Thema: ein **Kopftor** an beiden Enden, eine
  **Engstelle** (3 breit), drei Ereignisse, die zum Ziel dichter werden, und ein leiser **Puls**, der zum Boss hin
  schneller und lauter wird. Er schweigt im Kampf, in Szenen, im Flug hoch über der Brücke und nach dem Sieg über den Boss
  dieses Wegs. `/hha approach` zeigt Weg, Fortschritt und den nächsten Wegpunkt.

  | Brücke | Name | Was man sieht | Klang |
  | --- | --- | --- | --- |
  | A → B | Weg der Boten | Hermen-Allee, Windbögen mit Glocken, Aussicht mit Botenstatue, **Flügeltor** am Kopf von B | Wind |
  | B → C | Weg der Schwelle | Marmor wird in drei Bändern zu Basalt, Seelenlaternen, drei Bögen der Trivia, **zwei Hunde** am Kopf von C | Heulen |
  | C → D | Schlackenweg | rußiger Marmor, Bronze-Feuerschalen, Kettenkräne mit Schlackekübeln, **Wachturm am Knick**, Glutrisse vor D | Amboss |
  | D → E | Heerstraße | roter Läufer, Standarten, zwei Wachtürme, Tropaia, **Torhaus** als Engstelle | Kriegstrommel |
  | E → F | Himmelstreppe | Sturmmarmor, drei Götterstatuen (zum Thron hin höher), Blitzableiter, Wolkenfetzen, **Tor der Blitze** | Donner |

  Insel A hat zwei Pylone am Wolkentor und einen Aussichtsplatz am Nordrand mit Blick den Weg entlang.
- **Siegelbrücken:** In der Mitte der Brücken B→C und C→D (nach Hermes), D→E (nach Brontes) und E→F (nach Kratos) fehlt
  ein Stück. Ist der Boss davor besiegt und jemand näher als 96 Blöcke, schließt sich die Lücke mit hellem Klang und
  goldenen Federn, einmal je Welt. An den Sperren steht, wen man vorher besiegen muss. Vor und hinter jeder Lücke steht
  ein Torrahmen (auch bei offener Lücke).
- **Goldene Fernsäule:** Nach der Ankunft und nach jedem Sieg zeigt eine goldene Säule am Horizont zum nächsten Ziel:
  zuerst auf den fernen Brückenkopf, ab dem Kopftor auf den Altar.
- **Stelen** an jeder Insel erzählen, wer dort wartet und worauf es ankommt (Stelen 6–13 auf dem Pfad, 14 und 15 auf
  Nebeninseln).
- **Nebeninseln** zwischen y 96 und 190: Wiesen mit einem Baum, Ruinen mit drei Säulen und einer Truhe (Olymp-Beute:
  Gold, Kupfer, Federn, Bücher, selten ein goldener Apfel), Wolkenbänke und Kureten-Posten.
- Im Olymp regnet es nie, es spawnen keine Monster, Betten funktionieren. Wer fliegend abkürzt, spart nur Laufweg: Die
  Altäre prüfen den Fortschritt.

Grundmaterial sind Vanilla-Blöcke. Seit Überarbeitung 4 setzen eigene **Deko-Blöcke** Akzente: Olymp-Marmor in
Ziegeln, poliert, gemeißelt, vergoldet und als Säule, Olymp-Bronze, Sturmmarmor und Sturmwolken (siehe
[Blöcke](bloecke.md)). Funktionsblöcke sind Wolkentor, Höllentor, Altar der Prüfung (`boss_altar`), Stelen und die
Arena-Geräte (Botenglocke, Erdungsgriff).

**Bestehende Welten:** Der Olymp hat feste Koordinaten. Darum richtet sich der Pfad in alten Welten von selbst neu auf:
Sobald jemand einer Insel oder Brücke näher als 160 Blöcke kommt und alle ihre Chunks geladen sind, wird sie gegen den
Bauplan geprüft und, wenn weniger als 97 % stimmen, in Scheiben neu gezeichnet (nur abweichende Blöcke; Truhen und andere
Blöcke mit Inventar bleiben stehen; wer in entstehenden Blöcken steht, wird nach oben gesetzt). Eigene Bauten auf den
Inseln und Brücken werden dabei überschrieben. Abschalten: `world_retrofit` (HHA-Einstellungen bzw.
`/hha toggle world_retrofit false`). Stand: `/hha world retrofit status`.

## Die Kämpfe

Jeder Kampf ist ein lesbares Duell: Jeder Angriff wird am Boden angesagt (Kreis, Ring, Linie oder Kegel), der Rand steht
sofort, die Fläche füllt sich bis zum Treffer und blitzt in den letzten Ticks weiß auf. **Gold heißt immer sicher, offen
oder lohnendes Ziel**, Gefahr trägt die Farbe des Bosses. Eine **goldene Rune** unter dem Boss heißt: offen, jeder
Treffer zählt 1,8-fach. Höchstens eine Saal-Gefahr läuft zur selben Zeit, und kein Boss ruft Gegner mit eigener KI herbei.

Gestartet wird am **Altar der Prüfung** der Insel (Rechtsklick), für Tests mit `/hha boss start <boss>`. Die Kampffläche
ist geschützt: Blöcke bleiben heil, Fallschaden gibt es nicht, wer über den Rand fällt, wird von einer Bö zurückgeworfen
(4 Schaden). Wer k. o. geht, wacht mit 6 Leben vor der Arena auf und behält sein Inventar. Gehen alle, verschwindet der
Boss, und der Altar ist wieder frei.

**Die Arena spielt mit (Überarbeitung 4).** Jede Arena hat eine ebene **Kernfläche**, auf der der Boss läuft, und am Rand
**Ebenen** (Terrassen, Bühnen, Empore). Erhöht ist man nur vor Bodenangriffen sicher; jeder Boss hat Angriffe, die die
Ebenen erreichen (die Ansage liegt dann auch oben). **Deckung ist Sichtlinie:** Säulen, Tore, Pfeiler und Standarten decken
gegen bestimmte Angriffe; wer gedeckt ist, sieht eine goldene Rune an der Deckung und „gedeckt“ in der Statuszeile.
Manche Deckung wird verbraucht und kehrt erst zum Phasenbeginn oder nach dem Kampf zurück. Teile der Arena brechen an
festen Stellen weg (Schollen, Emporenbögen); nach jedem Kampfende, Abbruch oder Neustart steht die Arena wieder genau nach
Bauplan. Neue Mechaniken je Boss: Botenglocken und brechende Säulen (Hermes), die dunkle Stunde (Hekate), Hammer steckt
und Amboss bricht (Brontes), Bia als Teilziel (Kratos), Schollen und Erdungsgriff (Zeus). Allgemeines dazu in
[Bosse allgemein](bosse-allgemein.md#arena).

### Hermes: „Die letzte Zustellung“ (Insel B)

**Start:** das Rätsel im Tempel. Ein Rechtsklick mit leerer Hand auf einen Opferstein zeigt sein Rätsel; gesucht sind die
Gaben von Hermes' erstem Lebenstag, in der Reihenfolge des Sonnenlaufs:

| Stein | Tageszeit | Gabe |
| --- | --- | --- |
| Osten | Morgen: Er wird geboren und fliegt aus der Wiege | Feder |
| Süden | Mittag: Er baut das erste Ding, das klingt | Notenblock |
| Westen | Abend: Er stiehlt Apollons Rinder | Leder oder Rindfleisch |
| Norden | Nacht: Zeus macht ihn zum Boten | Papier |

Eine falsche Gabe ruft einen Blitz und setzt die Steine zurück, die vierte startet den Kampf. Über dem Tempel entsteht das
**Himmelsforum**: ein Kern (r 22) mit zwei gemeißelten Ringbändern und Speichen, acht
wuchtige **Botensäulen** auf Ring 20 mit je einer **Botenglocke** davor und ringsum eine **Empore** zwei Blöcke höher
(vier Bögen, Aufgänge im Osten, Süden, Westen und Norden). Nach dem Kampf ist es wieder weg. Der versiegelte Botenbrief ruft Hermes nicht mehr, er zeigt nur noch
den Weg zum Wolkentor. Alte Opfersteine in der Oberwelt antworten mit einem Hinweis.

Die Kernidee: **Fang den Boten.** Triffst du ihn, während er im Kurierstoß an dir vorbeifliegt, zählt der Treffer
anderthalbfach und er stolpert.

| Stage | Leiste | Angriffe (feste Reihenfolge) | Worauf es ankommt |
| --- | --- | --- | --- |
| I – Der Bote | 1000 → 700 | Kurierstoß × 3 → Federfächer × 2 → Sturmschlag mit Bodenwelle | Seitlich aus der Linie oder ihn im Vorbeiflug abfangen; zwischen die Federn; raus aus dem Kreis, dann über die Welle springen, solange der Stab steckt |
| II – Der Dieb | 700 → 400 | Spiegel-Stoß (drei Körper) → Diebesgriff → **Botenlauf** → Spiegel-Stoß → Herma-Sprung | Nur der Echte hat den goldenen Kreis. Nach dem Diebesgriff hält er deine Effekte als Kugel: triff ihn in 12 s, dann bekommst du sie zurück. Beim Botenlauf die goldenen Glocken schlagen. Zerschlag den glühenden Herma-Stein, bevor er landet |
| III – Der Seelenführer | 400 → 150 | Die Zwillinge → **Botenlauf** → Seelensäulen → Lanzenreihe → Styx-Flut → Kurierstoß × 2 | Zwischen den Bahnen der Schlangen bleiben; Glocken schlagen; Säulen: in Bewegung bleiben; Lanzen: quer laufen; Flut: in den goldenen Bereich |
| IV – Der Götterbote | 150 → 0 | Sturzflug × 3, Speere des Olymp × 2, Letztes Wort | Verwundbar nur in der Erschöpfung danach (goldene Rune). Über die Strahlen des Letzten Worts springen |

In der Luft treffen Seelensäulen, Speere, Sturzflug und Konter weiter; über Bodenwellen und die Strahlen des Letzten
Worts springt man. Wer k. o. geht, landet am Eingang der Insel B. **Finale:** Paket öffnen (Beute, siehe Reliquien),
dann wirft Hermes dem Öffner den **Schwarzen Brief** zu und geht. Den Brief braucht man für Hekate.

**Die Arena spielt mit (Überarbeitung 4).**

- **Botenlauf:** Hermes sagt drei Stoßlinien nacheinander an (Linie 3 breit, 24 Ticks Ansage, 10 Schaden). Mit der
  ersten Ansage leuchten zwei Botenglocken golden, je eine auf jeder Seite der dritten Linie. Wer beide anschlägt
  (Rechtsklick oder Schlag; allein genügt eine), bevor die dritte Linie fällt, ruft einen Windstoß: Er läuft die dritte
  Linie entlang, wirft Hermes aus der Bahn und lässt ihn 40 Ticks offen taumeln. Die Glocken bedienen nur Kampfteilnehmer.
- **Säule als Köder:** Stell dich hinter eine Botensäule, wenn er zum Kurierstoß oder Botenlauf ansetzt. Führt seine
  Bahn durch die Säule, endet sie dort, er kracht hinein, die Säule bricht bis auf den Stumpf, und er taumelt 40 Ticks
  offen. Jede Säule bricht höchstens einmal je Stage (ihre Glocke schweigt dann); zum Stage-Beginn stehen alle wieder.
- **Deckung:** Die Federn des Federfächers enden an den Säulen. Wer gedeckt ist, sieht die goldene Rune an der Säule und
  „gedeckt“ in der Statuszeile.
- **Empore:** Bodenwellen (Sturmschlag-Welle, Wellen nach Sturzflug und Speer), Lanzenreihe, die Schlangen und das
  Letzte Wort treffen sie nicht. Kurierstoß, Botenlauf, Spiegel-Stoß, Diebesgriff, Sturmschlag, Sturzflug, Speere,
  Seelensäulen und Styx-Flut erreichen sie; ihre Ansage erscheint auch oben.
- **Die Empore stürzt ab:** zu Beginn von Stage III die Bögen im Osten und Westen, zu Beginn von Stage IV die übrigen,
  jeweils in der Szene mit goldenem Kern als Ansage. Wer hinunterfällt, wird von einer Bö zurück aufs Forum geworfen.
- **Leiste:** Hermes hat jetzt die HHA-Leiste mit vier Abschnitten in der Farbe der Stage.

**Inszenierung (Überarbeitung 3):** Der Prolog läuft als Bühnen-Szene: Kreis um den Altar, Sog nach oben, die Kamera
folgt dem Kometen hinab. Der Kurierstoß zieht eine Lichtbahn mit Kopf und Schweif (`hermes.streak`), das Blinzeln öffnet
kleine Portale und endet in einem Lichtstern, die Herma zerspringt in echte Marmorbrocken, die Speere der vierten Stufe
fallen aus einem Riss im Himmel genau im Treffer. Flügel und Chlamys schwingen nach, im Flug neigt er sich in Kurven
(höchstens 14°). Alle fünf Szenen (`hermes.prolog`, `stage2`–`stage4`, `finale`) sind überspringbar.

### Hekate, Göttin der Schwellen (Insel C)

**Start:** mit dem Schwarzen Brief in der Hand den Altar vor dem Hekataion anklicken. Sie schwebt, gleitet und hält zwei
Fackeln. Effektiv 1300 Leben, Phasen bei 66 % und 33 %.

| Angriff | Ansage | Was tun |
| --- | --- | --- |
| Fackelschwung (zweimal) | zwei Kegel 60°, Radius 7 | hinter sie treten; der zweite zielt neu |
| Fackelwurf | Kreise Radius 3 am Ziel und davor | ausweichen; das Feuer brennt 3 s am Boden. **Solange die Fackel fehlt, ist sie offen** (goldene Rune) |
| Feuerpfad | Linie 6 breit auf dem Weg, dem du am nächsten stehst | den Weg wechseln |
| Schwellentritt (ab II) | Rune am Wegende hinter dir, Linie durch dich zur Mitte | aus der Linie; sie erscheint dort und stößt durch |
| Schlüsseldrehung (ab II, Saal-Gefahr) | alles rot, ein Weg mit goldenem Rand; **in den zwei anderen Toren fällt ein Fallgitter** | auf den goldenen Weg, nie zweimal derselbe; wer hinter einem geschlossenen Tor steht, läuft um den Pfeiler herum (die Ansage ist dafür lang genug). Nach dem Fenster heben sich die Gitter |
| Dreigestalt (III) | drei Körper an den Wegenden, nur einer mit brennender Fackel | **die Echte in der Ansage treffen** (auch mit Pfeil): Die Trugbilder verwehen samt ihren Ansagen, sie taumelt lange offen. Ein Trugbild zu schlagen bringt nichts |
| Die dunkle Stunde (III, Saal-Gefahr, 10 s) | der Himmel wird nachtblau, die Fackeln erlöschen; sie tritt an das Hekataion und greift nicht an. Ihr Blick schwenkt dreimal: zwei Kegel 60° (außen Radius 18, innen 9), darunter ein kalter Lichtkegel, dann steht der Blick 1 s | **hinter Stein** (Tor, Fackelturm, Hekataion; goldene Rune, Statuszeile „Gedeckt“) oder **in die Hocke**: Wer schleicht, wird erst im inneren Kegel gesehen. Wer gewittert wird, bekommt einen roten Kreis unter die Füße (1 s, ausweichen). **Wird in der ganzen Stunde niemand gewittert, flammen die Fackeln auf und blenden sie: 3 s offen.** Ein Fackelturm, hinter dem sich jemand versteckt hat, bröckelt danach bis auf den Sockel (bis Kampfende) |

In der dunklen Stunde wird der Himmel nur dunkler (Stimmung, keine Blindheit, keine Langsamkeit); Ansagen und Gold
bleiben voll sichtbar. Sie ersetzt die frühere Mondfinsternis. Die Terrassen zwischen den Wegen (+3) schützen vor dem
Bodenfeuer des Fackelwurfs; alle anderen Angriffe erreichen sie, und dem Blick steht man dort offen. Nach dem Sieg tritt sie zur Seite, und das **Höllentor**
am Nordwest-Ende des Platzes öffnet sich Reihe für Reihe. Es führt in den [Tartaros](tartaros.md). Hekate gibt keine
Reliquie, aber die Schmiedevorlage, Netheritschrott und goldene Äpfel.

**Inszenierung (Überarbeitung 3):** Auftritt (`hekate.intro`, 12 s): Die Kamera schleicht den Südweg hinauf, die
Fackeltürme beiderseits des Südwegs, dann der Nordturm und zuletzt das Seelenfeuer auf dem Hekataion flammen auf, Hekate gleitet aus einem Wirbel an der Statue. Der Fackelschwung zieht ein
Flammenband am Fackelkopf, der Wurf eine Flammenspur, der Feuerpfad frisst sich als glühender Sprung voran, der
Runenring der Schlüsseldrehung rastet zum Treffer ein. Die dunkle Stunde ist der einzige Bildschirm-Pass des Kampfs
(abgedunkelt, kühler Lichtkegel des Blicks, Ansagen und Gold bleiben klar) und endet bei jedem Ausgang sofort. Niederlage
(`hekate.defeat`): Kamerafahrt zum Höllentor, das sich Reihe für Reihe füllt. Musik `music.hekate`, ab Phase II
`music.hekate_2`.

### Brontes, der Kyklop (Insel D)

**Start:** nach Hermes, Altar südlich vom Amboss. Effektiv 1500 Leben, Phase II bei 50 %.

| Angriff | Ansage | Was tun |
| --- | --- | --- |
| Hammerschlag | Linie 3 × 10, danach ein schmaler Riss dahinter | seitlich raus. Weichst du sauber aus, **steckt der Hammer** (siehe unten): 3 s offen |
| Doppelschlag (II) | zwei Linien nacheinander, die zweite leicht gedreht | zweimal ausweichen; jeder der beiden Schläge kann stecken bleiben |
| Rundschwung (nah) | Kegel 100°, Radius 7 | Abstand oder hinter Amboss bzw. Kokille |
| Glutwurf (fern) | Kreis Radius 3 am Ziel | ausweichen. Der Rohling bleibt glühend liegen und kühlt nach 8 s ab (wird dunkel). Wer länger als 5 s auf einer Essebühne steht, wird bevorzugt beworfen |
| Blasebalg (fern) | Kegel 35°, Radius 16 | zur Seite oder hinter eine **Kokille**: dort schiebt und verletzt er nicht |
| **Fehlschlag** | – | Stell dich so, dass ein **glühender Rohling** zwischen dir und ihm liegt: Der Hammer trifft den Rohling, Funken fliegen ihm ins Auge, er ist 4 s offen |
| Roste (II, Saal-Gefahr, alle 14 s) | drei von sechs Sektoren rot, die kühlen mit goldenem Rand | auf einen kühlen Rost oder auf eine Essebühne |
| Der Keil (35 % und 15 %) | drei wachsende Glutringe, dann ein Kreis Radius 6 am Entferntesten | springen, wenn die Welle kommt (auf den Bühnen trifft sie nicht); danach 2 s offen |

**Der Hammer steckt.** Trifft der Hammerschlag niemanden, endet er nicht am Amboss und zerschlägt keinen Rohling, und
stand sein Ziel mindestens 2 Blöcke neben der Linie (höchstens 14 von ihm entfernt), bleibt der Hammer im Boden stecken:
goldene Rune am Hammerkopf, 3 s offen. Kurz vor dem Ende zeigt ein roter Ring (Radius 2,6) um den Kopf, wo beim
Herausreißen die Splitter fliegen. Höchstens einmal je 15 s; sonst steht er nach dem Schlag nur kurz (ohne Rune).

**Der Amboss** deckt: Hammer und Riss enden an ihm, hinter ihm ist man vor dem Rundschwung sicher (goldene Rune am
Amboss während der Ansage). Aber jeder Hammerschlag, den er für jemanden abfängt, **reißt ihn** (Unterleiste „Amboss“,
drei Kerben). Beim dritten Riss bricht er: Es bleiben nur Trümmer, die nicht mehr decken, und Brontes taumelt 5 s offen.
Zu Beginn von Phase II holt er einen neuen aus der Esse. Bricht der Amboss in Phase II, bleibt er gebrochen; die drei
Schläge des Keils fallen dann dumpf auf den Stumpf.

Die drei **Kokillen** (Gussformen bei 60°, 180°, 300°) decken gegen Blasebalg und Rundschwung. Die zwei **Essebühnen**
(+2, beiderseits des Feuermauls) schützen vor Rosten und Glutringen; Hammer, Riss, Blasebalg, Glutwurf und Keil
erreichen sie.

**Inszenierung (Überarbeitung 3):** Auftritt (`brontes.intro`): ein Hammerschlag im Dunkeln, die Kamera fährt an ihm hoch,
das Auge öffnet sich. Der Hammer reißt echte Bodenblöcke heraus und hinterlässt glühende Risse und einen Brandfleck, der
abkühlt; der Blasebalg ist ein Funkenstrom aus dem Mund mit schwachem Hitzeflimmern, der Keil endet in einer Glutsäule
mit kurzem Stoßring durchs Bild. Das Auge erlischt beim Blenden und kommt in ruhigen Schritten zurück; die Esse glüht
mit seinem Leben. Musik `music.brontes`, ab 50 % `music.brontes_2`.

### Kratos und Bia, die Thronwache (Insel E)

**Start:** nach Brontes, Altar am Fuß der Treppe. Kratos kommt die Treppe herab; Bia steht als Statue auf dem linken
Sockel. Effektiv 1700 Leben, Phase II bei 50 %. **Bia hat eine eigene kleine Leiste** (unter seiner, 25 % seines Lebens).
Verwundbar ist sie nur, solange sie taumelt und golden leuchtet: 4 s nach dem Zusammenstoß und 1 s nach „KNIET!“, wenn
sie am Rand steht. Ein Treffer nimmt ihr höchstens 12 % der Leiste. Sonst prallt jeder Schlag ab.

| Angriff | Ansage | Was tun |
| --- | --- | --- |
| Schild (Phase I) | – | Treffer von vorn zählen nur 20 % (dumpfer Ton); von der Seite, von hinten und nach dem Schildstoß voll |
| Schildstoß | Kegel 60°, Radius 6 | zur Seite; danach hängt der Schild kurz |
| Hammerfall | zwei Schritte, Kreis Radius 3,5 am Ziel | aus dem Kreis; der Hammer steckt danach: offen |
| „WEICHT!“ | großes rotes Wort, Kreis Radius 7 um ihn | weglaufen (aus dem Nahkampf ohne Sprint zu schaffen) |
| „HERAN!“ | Ring um ihn, nur innerhalb Radius 7 sicher | zu ihm laufen, dann hinter ihn: Der Schildstoß folgt sofort. Die Ansage dauert so lange, dass jeder hinkommt; ist jemand zu weit weg, ruft er das nicht |
| „KNIET!“ | Kreis über den ganzen Platz, Bia läuft mit der Klinge quer hinüber | **Schleichtaste halten**, bis die Klingenreihe über dich hinweg ist (trifft auch in der Luft), oder auf den Treppenabsatz. Danach steht Bia 1 s golden am Rand |
| Bias Sturm (Saal-Gefahr, etwa alle 14 s) | Linie vom Sockel durch einen Spieler; sie endet an der ersten Standarte | seitlich raus, hinter eine Standarte (goldene Rune, Zeile „gedeckt“) oder auf den Treppenabsatz |
| **Der Zusammenstoß** | – | Stell dich so, dass Kratos zwischen dir und Bia steht: Sie rammt ihn, beide taumeln 4 s und sind offen. Auf ihn schlagen oder auf sie |

In Phase II wirft er den Schild weg, schlägt doppelt (zweiter Kreis 18 Ticks später), gibt Befehle paarweise, und Bia
stürmt dreimal hintereinander. Unter 20 % Leben kommt das **Letzte Gefecht**: Die Pausen zwischen seinen Angriffen
werden kürzer (× 0,7; das gilt für alle Duell-Bosse des Olymp).

**Bias Bruch und Zorn (Überarbeitung 4).** Ist Bias Leiste leer, zerspringt die Statue (kurze Szene `kratos.bia_break`,
überspringbar) und ihr Sockel liegt in Trümmern. Danach gibt es weder Bias Sturm noch „KNIET!“. Kratos tobt: Wo er einen
Befehl geben würde, schlägt er doppelt (auch in Phase I), und seine Pausen sind kürzer (× 0,85, im Letzten Gefecht nie
unter 60 % der Grundpause). „WEICHT!“ und „HERAN!“ ruft er nur noch gegen Ferne. Wer Bia ignoriert, kämpft wie bisher.

**Standarten und Treppenabsatz.** Vier Standarten (Bronze, rotes Tuch) decken gegen Bias Sturm. Schlägt sein Hammer
näher als 2,5 Blöcke neben einer ein, zerbricht sie bis auf den Sockel und kommt erst nach dem Kampf zurück; ist Bia
schon zerschlagen, steckt sein Hammer dann fest: 2 s offen. Der Treppenabsatz (+4) liegt über Bias Sturm und „KNIET!“;
Schildstoß, Hammerfall, Doppelschlag, „WEICHT!“ und „HERAN!“ erreichen ihn (die Ansage liegt dort zusätzlich auf dem
Absatz). Der Hammer schlägt auf dem Platz oder auf dem Absatz ein, nie auf den Stufen.

**Inszenierung (Überarbeitung 3):** Auftritt (`kratos.intro`): Die Kamera steigt neben Kratos die Treppe hinauf, er schlägt
den Schild auf, dann läuft ein glühender Riss über die Statue der Bia und ihre Augen leuchten auf. Schildstoß als roter
Schleier-Bogen, Hammerfall mit Ring, Staub und echten Marmorblöcken, Befehle als roter Bogen von seiner Hand zu Bia,
„KNIET!“ als breites Band auf Brusthöhe. In Phase II fliegt der Schild davon und zerspringt; Umhang und Busch schwingen
nach. Musik `music.kratos`, ab Phase II `music.kratos_2`. Seit Überarbeitung 4: Solange Bia verwundbar ist, laufen
goldene Risse über die Statue (`kratos.bia_open`), unter ihr liegt eine goldene Rune. Beim Bruch glüht ein Rissnetz auf,
große Brocken fliegen, der Sockel bricht (`kratos.bia_break`); Phasenwechsel und Niederlage zeigen danach Kratos allein
(`kratos.phase2_alone`, `kratos.defeat_alone`).

### Zeus (Insel F)

**Start:** nach Hermes, Brontes und Kratos, Altar vor dem Thron. Kurze Auftrittsszene (überspringbar). Drei Leisten zu je
1100 effektiv.

| Angriff | Ansage | Was tun |
| --- | --- | --- |
| Keraunos-Wurf (fern) | Linie 2,4 × 30 | seitlich raus; mit leerer Hand ist er offen |
| Aigis-Stoß (nah) | Kegel 70°, Radius 7 | zur Seite |
| Donnerschlag (nah) | Kreis Radius 8, dann zwei Ringe | raus, dann über die Ringe springen; der Blitz steckt: offen |
| Himmelsblitz (fern) | Kreis Radius 3,5 unter jedem Spieler, auf Kern, Terrasse und Bühne | raus oder **neben einen Blitzpfeiler** (siehe Erdung) |
| Donnerschritt (fern) | Kreis Radius 5 am Ziel | raus, er erscheint dort |
| Gorgonenblick (II) | Kegel 60°, Radius 26 | wegschauen, aus dem Kegel gehen oder hinter einen Pfeiler |
| Adler, Sturmwand (II, Saal-Gefahren) | Linie quer durch den Hof bzw. zwei Linien mit goldener Lücke | aus der Linie bzw. in die Lücke |
| Ketten (III) | „Brontes“ (Schlag → Ringe → Wurf), „Steropes“ (Schritt → Stoß → Wurf), „Arges“ (Blick → drei Himmelsblitze) | wie oben, am Ende offen |
| **Die Waage** (III, bei 66 % und 33 % der letzten Leiste) | goldene Schale am Rand, draußen Sturm | in die Schale, den vier Blitzen darin ausweichen: Er taumelt 5,5 s. Bleibt die Schale leer, heilt er sich |
| **Die Insel bricht** (III, zu Beginn und bei 50 % der letzten Leiste) | rote Kreise über zwei Schollen, Kern und bleibende Schollen golden | runter von der roten Scholle; wer fällt, holt die Bö |

- **Arena (Überarbeitung 4):** Ebener Kern (r 19,5), darum sechs Schollen als Terrasse (+2) mit je einem Blitzpfeiler
  (3 × 3, Sockel, Kupferkern), im Norden die Thronbühne (+3). Die Ringe des Donnerschlags laufen nur über den Kern: Auf
  der Terrasse ist man vor ihnen sicher. Alle anderen Angriffe erreichen Kern, Terrasse und Bühne. Zeus selbst bleibt im
  Kern; sein Donnerschritt landet immer dort.
- **Erdung:** Steht man am Boden höchstens 3 Blöcke neben dem Sockel eines frischen Pfeilers – auf dessen Scholle oder im
  Kern –, fährt der Himmelsblitz in den Pfeiler (kein Schaden, goldene Rune am Fuß). Kommt Zeus einem geladenen Pfeiler
  auf 4 Blöcke nahe oder endet sein Keraunos am Pfeiler, entlädt sich der Pfeiler in ihn: Er taumelt 4,5 s, der Pfeiler
  oxidiert. Zu Beginn von II und III werden zwei Pfeiler wieder frisch (nur solche, deren Scholle bleibt), nach dem Kampf alle.
- **Deckung:** Die Pfeiler decken gegen den Gorgonenblick und den Keraunos-Wurf. Wer gedeckt ist, sieht eine goldene Rune
  am Pfeiler und „gedeckt“ in der Statuszeile. Wegsehen schützt weiter vor dem Blick.
- **Erdungsgriff:** Ist ein Pfeiler geladen, leuchtet sein Griff in der Terrassenwand golden. Rechtsklick: Steht Zeus
  höchstens 14 Blöcke vom Pfeiler und sieht der Pfeilerkopf ihn, springt nach einer goldenen Linie (0,8 s) der Blitz zu
  ihm. Steht er dann noch in der Linie: Entladung wie oben (Taumeln 4,5 s). Ist er ausgewichen, verpufft der Blitz, und der
  Pfeiler ist trotzdem verbraucht. Zwischen zwei Griffen liegen mindestens 10 s. Fehlt eine Bedingung, sagt die
  Statuszeile, warum („Zeus ist zu weit“ usw.).
- **Die Insel bricht:** Zu Beginn von Phase III brechen die Schollen 0 (Osten) und 3 (Westen) samt Pfeilern, bei 50 %
  der letzten Leiste die Schollen 1 und 4; es bleiben 2 und 5 und die Thronbühne. Vorher eine Ansage von mindestens 3 s
  (länger, wenn jemand weit draußen steht): rote Kreise über der Scholle, Risse an den Fugen, Kern und bleibende Schollen
  golden. Liegt gerade die Waage an, kommt zuerst die Waage. Wer mitfällt, den holt die Bö auf die nächste Fläche, nie ein
  Sturz in die Tiefe. Nach dem Kampf steht alles wieder (Bauplan).
- **Luftregel:** Wer länger als 1,5 s mehr als 3 Blöcke über dem Boden bleibt, bekommt einen eigenen Blitz (angesagt).
- Echte Vanilla-Blitze schlagen nur noch an den großen Momenten ein (Pfeiler lädt sich, Entladung, Szenen), höchstens
  einer je Sekunde; alle anderen Blitze sind gezeichnet und hellen den Himmel nicht auf.
- **Ende:** Er senkt den Blitz, Nike setzt dem Spieler mit dem meisten Schaden einen goldenen Kranz auf, dann Beute.

Alle neuen Bosse sprechen vorerst nur als Text mit Stimm-Lauten (Namen in ihrer Farbe).

**Inszenierung (Überarbeitung 3):** Blitze kommen aus Rissen in der Wolkendecke und treffen genau im Schaden-Tick; den
Stoßring durchs Bild und den Ruck bekommt nur der Gerufene. Über dem Thronhof öffnen sich Lichtrisse – einer in
Phase I, drei in Phase II, fünf in Phase III, breiter bei sinkendem Leben. Erdung, Entladung, Donnerschlag (echte
Marmorblöcke), Aigis-Blick (Lichtkegel, kühle Vignette für den, der hinsieht), Sturmwand (Regenwand mit goldenen
Pfosten an der Lücke) und Waage (die Schale bleibt frei lesbar, die Kamera wackelt nicht) haben eigene Bilder. Auftritt,
Phasenwechsel und Niederlage sind Szenen (`zeus.intro`, `phase2`, `phase3`, `defeat`); am Ende senkt Nike den goldenen
Kranz auf den Sieger. Je Phase ein Thema (`music.zeus`, `zeus_2`, `zeus_3`), am Ende `music.victory_olymp`.

## Gegner des Olymp

Sie stehen nur an festen Posten auf den Inseln B bis F und auf Nebeninseln, nie auf Insel A und nie in einer Kampffläche.
Läuft dort ein Bosskampf, räumen sie sich. Leben und Schaden wachsen mit den Spielern in der Nähe; es gibt keinen Deckel
und keine Mace-Abwehr.

| Gegner | Leben | Angriff | Worauf es ankommt |
| --- | --- | --- | --- |
| **Kurete** (Himmelssoldat, bis zu drei) | 60 | schlägt vor jedem Angriff zweimal den Speer an den Schild, dann Speerstoß (Linie) oder Schildschlag (Kegel) | danach hebt er den Schild: von vorn 30 %, also **um ihn herum**. In einer Gruppe greift nie mehr als einer gleichzeitig an |
| **Greif** (Hund des Zeus, bis zu zwei) | 80 | sitzt auf seiner Säule, **Sturzflug** entlang einer angesagten Linie, am Boden Prankenhieb | nach dem Sturz liegt er 1,5 s am Boden (goldene Rune, × 1,5) |
| **Goldene Wächterin** (Automat des Hephaistos) | 140 | steht als Statue; erwacht, wenn man näher als 6 kommt oder sie trifft. Hammerarm (Kreis), Dampfstoß (Kegel) | **das Ventil auf dem Rücken** ist die Schwachstelle (× 1,5). Allein gelassen kehrt sie auf ihren Sockel zurück und heilt |

Stöße prüfen die Kante: Niemand wird von der Insel geworfen. Getötete Posten bleiben 5 Minuten leer und kommen erst
wieder, wenn man sich einmal weiter als 64 Blöcke entfernt hat. Beute: Goldnuggets, Kupferbarren, selten ein Heavens
Barren. Zum Testen: `/hha olymp spawn kurete|griffin|automaton`.

Seit der Überarbeitung 3: Kureten schlagen Funken am Schild, der Busch federt; der Greif zieht nach dem Sturzflug einen
kleinen Staubring; der Dampfstoß der goldenen Wächterin ist ein weicher weißer Schleier.

## Fortschritt und Testbefehle

| Befehl (OP) | Wirkung |
| --- | --- |
| `/hha progress` / `/hha progress set <hermes\|hekate\|brontes\|kratos\|zeus> <true\|false>` | Stand des Olymp-Pfads zeigen bzw. setzen |
| `/hha boss start <hermes\|hekate\|brontes\|kratos\|zeus>` | an die Insel bringen und den Kampf starten (ohne Olymp am eigenen Standort) |
| `/hha boss skip [boss]`, `/hha boss stop [boss]` | nächste Phase bzw. Abbruch ohne Sieg |
| `/hha tp olymp\|tempel\|dreiweg\|schmiede\|stufen\|thron\|tartaros` | Schnellreise zu den Stationen |
| `/hha olymp bridges` | alle Siegelbrücken sofort schließen (nur geladene Chunks) |
| `/hha olymp spawn <kurete\|griffin\|automaton>` | Testposten 8 Blöcke vor dir |
| `/hha arena list`, `/hha arena show <olymp_c\|olymp_d\|olymp_e\|olymp_f\|forum> cover\|levels\|parts\|devices` | Arenen auflisten; Deckung, Ebenen, bewegliche Teile bzw. Geräte als Partikel zeigen |
| `/hha arena part <teil>` (im Kampf), `/hha arena restore`, `/hha arena repair <id>` | ein Teil abwerfen (z. B. `floe_0`); alle Änderungen zurücksetzen; die Arena gegen den Bauplan reparieren |
| `/hha world retrofit status\|run <teil>\|all` | Nachrüstung der Inseln und Brücken in alten Welten |
| `/hha approach` | Weg, Fortschritt und nächster Wegpunkt |

`/hha progress set hermes false` öffnet eine schon geschlossene Brücke nicht wieder, damit niemand mitten auf ihr abstürzt.
