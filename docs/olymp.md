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
| C Dreiweg der Hekate | 0 141 −246 | Ebener Platz mit drei Wegen, Hekataion und Altar; im Nordwesten das vermauerte runde **Höllentor** (−36 135 −301) | `/hha tp dreiweg` |
| D Schmiede der Kyklopen | 126 161 −371 | Amboss, sechs Roste über Glut, die Esse mit Rauchsäule: Brontes | `/hha tp schmiede` |
| E Stufen der Macht | 150 193 −528 | Platz vor einer Freitreppe, zwei Sockel: Kratos und Bia | `/hha tp stufen` |
| F Thron des Zeus | 32 233 −655 | Hof mit sechs Kupferpfeilern und Blitzableitern, der Thron im Norden, eine Gewitterwolke darüber: Zeus | `/hha tp thron` |

- **Siegelbrücken:** In der Mitte der Brücken B→C und C→D (nach Hermes), D→E (nach Brontes) und E→F (nach Kratos) fehlt
  ein Stück. Ist der Boss davor besiegt und jemand näher als 96 Blöcke, schließt sich die Lücke mit hellem Klang und
  goldenen Federn, einmal je Welt. An den Sperren steht, wen man vorher besiegen muss.
- **Goldene Fernsäule:** Nach der Ankunft und nach jedem Sieg zeigt eine goldene Säule am Horizont zum nächsten Ziel.
- **Stelen** an jeder Insel erzählen, wer dort wartet und worauf es ankommt (Stelen 6–13 auf dem Pfad, 14 und 15 auf
  Nebeninseln).
- **Nebeninseln** zwischen y 96 und 190: Wiesen mit einem Baum, Ruinen mit drei Säulen und einer Truhe (Olymp-Beute:
  Gold, Kupfer, Federn, Bücher, selten ein goldener Apfel), Wolkenbänke und Kureten-Posten.
- Im Olymp regnet es nie, es spawnen keine Monster, Betten funktionieren. Wer fliegend abkürzt, spart nur Laufweg: Die
  Altäre prüfen den Fortschritt.

Gebaut ist alles aus Vanilla-Blöcken. Eigene Blöcke gibt es nur dort, wo sie eine Funktion haben: Wolkentor,
Höllentor, Altar der Prüfung (`boss_altar`), Stelen.

## Die Kämpfe

Jeder Kampf ist ein lesbares Duell: Jeder Angriff wird am Boden angesagt (Kreis, Ring, Linie oder Kegel), der Rand steht
sofort, die Fläche füllt sich bis zum Treffer und blitzt in den letzten Ticks weiß auf. **Gold heißt immer sicher, offen
oder lohnendes Ziel**, Gefahr trägt die Farbe des Bosses. Eine **goldene Rune** unter dem Boss heißt: offen, jeder
Treffer zählt 1,8-fach. Höchstens eine Saal-Gefahr läuft zur selben Zeit, und kein Boss ruft Gegner mit eigener KI herbei.

Gestartet wird am **Altar der Prüfung** der Insel (Rechtsklick), für Tests mit `/hha boss start <boss>`. Die Kampffläche
ist geschützt: Blöcke bleiben heil, Fallschaden gibt es nicht, wer über den Rand fällt, wird von einer Bö zurückgeworfen
(4 Schaden). Wer k. o. geht, wacht mit 6 Leben vor der Arena auf und behält sein Inventar. Gehen alle, verschwindet der
Boss, und der Altar ist wieder frei.

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
**Himmelsforum**; nach dem Kampf ist es wieder weg. Der versiegelte Botenbrief ruft Hermes nicht mehr, er zeigt nur noch
den Weg zum Wolkentor. Alte Opfersteine in der Oberwelt antworten mit einem Hinweis.

Die Kernidee: **Fang den Boten.** Triffst du ihn, während er im Kurierstoß an dir vorbeifliegt, zählt der Treffer
anderthalbfach und er stolpert.

| Stage | Leiste | Angriffe (feste Reihenfolge) | Worauf es ankommt |
| --- | --- | --- | --- |
| I – Der Bote | 1000 → 700 | Kurierstoß × 3 → Federfächer × 2 → Sturmschlag mit Bodenwelle | Seitlich aus der Linie oder ihn im Vorbeiflug abfangen; zwischen die Federn; raus aus dem Kreis, dann über die Welle springen, solange der Stab steckt |
| II – Der Dieb | 700 → 400 | Spiegel-Stoß (drei Körper) → Diebesgriff → Wegkreuzungen → Spiegel-Stoß → Herma-Sprung | Nur der Echte hat den goldenen Kreis. Nach dem Diebesgriff hält er deine Effekte als Kugel: triff ihn in 12 s, dann bekommst du sie zurück. Zerschlag den glühenden Herma-Stein, bevor er landet |
| III – Der Seelenführer | 400 → 150 | Die Zwillinge → Seelensäulen → Lanzenreihe → Styx-Flut → Kurierstoß × 2 | Zwischen den Bahnen der Schlangen bleiben; Säulen: in Bewegung bleiben; Lanzen: quer laufen; Flut: in den goldenen Bereich |
| IV – Der Götterbote | 150 → 0 | Sturzflug × 3, Speere des Olymp × 2, Letztes Wort | Verwundbar nur in der Erschöpfung danach (goldene Rune). Über die Strahlen des Letzten Worts springen |

In der Luft treffen Seelensäulen, Speere, Sturzflug und Konter weiter; über Bodenwellen und die Strahlen des Letzten
Worts springt man. Wer k. o. geht, landet am Eingang der Insel B. **Finale:** Paket öffnen (Beute, siehe Reliquien),
dann wirft Hermes dem Öffner den **Schwarzen Brief** zu und geht. Den Brief braucht man für Hekate.

### Hekate, Göttin der Schwellen (Insel C)

**Start:** mit dem Schwarzen Brief in der Hand den Altar vor dem Hekataion anklicken. Sie schwebt, gleitet und hält zwei
Fackeln. Effektiv 1300 Leben, Phasen bei 66 % und 33 %.

| Angriff | Ansage | Was tun |
| --- | --- | --- |
| Fackelschwung (zweimal) | zwei Kegel 60°, Radius 7 | hinter sie treten; der zweite zielt neu |
| Fackelwurf | Kreise Radius 3 am Ziel und davor | ausweichen; das Feuer brennt 3 s am Boden. **Solange die Fackel fehlt, ist sie offen** (goldene Rune) |
| Feuerpfad | Linie 6 breit auf dem Weg, dem du am nächsten stehst | den Weg wechseln |
| Schwellentritt (ab II) | Rune am Wegende hinter dir, Linie durch dich zur Mitte | aus der Linie; sie erscheint dort und stößt durch |
| Schlüsseldrehung (ab II, Saal-Gefahr) | alles rot, ein Weg mit goldenem Rand | auf den goldenen Weg, nie zweimal derselbe; die Ansage ist so lang, dass jeder hinkommt |
| Dreigestalt (III) | drei Körper an den Wegenden, nur einer mit brennender Fackel | **die Echte in der Ansage treffen** (auch mit Pfeil): Die Trugbilder verwehen samt ihren Ansagen, sie taumelt lange offen. Ein Trugbild zu schlagen bringt nichts |
| Mondfinsternis (III, Saal-Gefahr) | der Himmel wird nachtblau, zwei goldene Kreise | in einen goldenen Kreis |

Der Himmel wird dabei nur dunkler (Stimmung, keine Blindheit). Nach dem Sieg tritt sie zur Seite, und das **Höllentor**
am Nordwest-Ende des Platzes öffnet sich Reihe für Reihe. Es führt in den [Tartaros](tartaros.md). Hekate gibt keine
Reliquie, aber die Schmiedevorlage, Netheritschrott und goldene Äpfel.

### Brontes, der Kyklop (Insel D)

**Start:** nach Hermes, Altar südlich vom Amboss. Effektiv 1500 Leben, Phase II bei 50 %.

| Angriff | Ansage | Was tun |
| --- | --- | --- |
| Hammerschlag | Linie 3 × 10, danach ein schmaler Riss dahinter | seitlich raus; der Hammer steckt danach: offen |
| Doppelschlag (II) | zwei Linien nacheinander, die zweite leicht gedreht | zweimal ausweichen |
| Rundschwung (nah) | Kegel 100°, Radius 7 | Abstand oder hinter den Amboss |
| Glutwurf (fern) | Kreis Radius 3 am Ziel | ausweichen. Der Rohling bleibt glühend liegen und kühlt nach 8 s ab (wird dunkel) |
| Blasebalg (fern) | Kegel 35°, Radius 16 | zur Seite, er schiebt etwa 6 Blöcke weg |
| **Fehlschlag** | – | Stell dich so, dass ein **glühender Rohling** zwischen dir und ihm liegt: Der Hammer trifft den Rohling, Funken fliegen ihm ins Auge, er ist 4 s offen |
| Roste (II, Saal-Gefahr, alle 14 s) | drei von sechs Sektoren rot, die kühlen mit goldenem Rand | auf einen kühlen Rost |
| Der Keil (35 % und 15 %) | drei wachsende Glutringe, dann ein Kreis Radius 6 am Entferntesten | springen, wenn die Welle kommt; danach 2 s offen |

Der **Amboss** deckt: Hammer und Riss enden an ihm, wer dahinter steht, ist vor dem Rundschwung sicher.

### Kratos und Bia, die Thronwache (Insel E)

**Start:** nach Brontes, Altar am Fuß der Treppe. Kratos kommt die Treppe herab; Bia steht als Statue auf dem linken
Sockel. Effektiv 1700 Leben, Phase II bei 50 %. **Bia nimmt nie Schaden und hat keine Leiste.**

| Angriff | Ansage | Was tun |
| --- | --- | --- |
| Schild (Phase I) | – | Treffer von vorn zählen nur 20 % (dumpfer Ton); von der Seite, von hinten und nach dem Schildstoß voll |
| Schildstoß | Kegel 60°, Radius 6 | zur Seite; danach hängt der Schild kurz |
| Hammerfall | zwei Schritte, Kreis Radius 3,5 am Ziel | aus dem Kreis; der Hammer steckt danach: offen |
| „WEICHT!“ | großes rotes Wort, Kreis Radius 7 um ihn | weglaufen (aus dem Nahkampf ohne Sprint zu schaffen) |
| „HERAN!“ | Ring um ihn, nur innerhalb Radius 7 sicher | zu ihm laufen, dann hinter ihn: Der Schildstoß folgt sofort. Die Ansage dauert so lange, dass jeder hinkommt; ist jemand zu weit weg, ruft er das nicht |
| „KNIET!“ | Kreis über den ganzen Platz, Bia läuft mit der Klinge quer hinüber | **Schleichtaste halten**, bis die Klingenreihe über dich hinweg ist (trifft auch in der Luft) |
| Bias Sturm (Saal-Gefahr, etwa alle 14 s) | Linie vom Sockel durch einen Spieler | seitlich raus |
| **Der Zusammenstoß** | – | Stell dich so, dass Kratos zwischen dir und Bia steht: Sie rammt ihn, beide taumeln, 4 s offen |

In Phase II wirft er den Schild weg, schlägt doppelt (zweiter Kreis 18 Ticks später), gibt Befehle paarweise, und Bia
stürmt dreimal hintereinander.

### Zeus (Insel F)

**Start:** nach Hermes, Brontes und Kratos, Altar vor dem Thron. Kurze Auftrittsszene (überspringbar). Drei Leisten zu je
1100 effektiv.

| Angriff | Ansage | Was tun |
| --- | --- | --- |
| Keraunos-Wurf (fern) | Linie 2,4 × 30 | seitlich raus; mit leerer Hand ist er offen |
| Aigis-Stoß (nah) | Kegel 70°, Radius 7 | zur Seite |
| Donnerschlag (nah) | Kreis Radius 8, dann zwei Ringe | raus, dann über die Ringe springen; der Blitz steckt: offen |
| Himmelsblitz (fern) | Kreis Radius 3,5 unter jedem Spieler | raus oder **neben einen Kupferpfeiler** (siehe Erdung) |
| Donnerschritt (fern) | Kreis Radius 5 am Ziel | raus, er erscheint dort |
| Gorgonenblick (II) | Kegel 60°, Radius 26 | wegschauen, aus dem Kegel gehen oder hinter einen Pfeiler |
| Adler, Sturmwand (II, Saal-Gefahren) | Linie quer durch den Hof bzw. zwei Linien mit goldener Lücke | aus der Linie bzw. in die Lücke |
| Ketten (III) | „Brontes“ (Schlag → Ringe → Wurf), „Steropes“ (Schritt → Stoß → Wurf), „Arges“ (Blick → drei Himmelsblitze) | wie oben, am Ende offen |
| **Die Waage** (III, bei 66 % und 33 % der letzten Leiste) | goldene Schale am Rand, draußen Sturm | in die Schale, den vier Blitzen darin ausweichen: Er taumelt 5,5 s. Bleibt die Schale leer, heilt er sich |

- **Erdung:** Steht man am Boden höchstens 3 Blöcke neben einem frischen Pfeiler, fährt der Himmelsblitz in den Pfeiler
  (kein Schaden, goldene Rune am Fuß). Kommt Zeus einem geladenen Pfeiler nahe oder wirft er den Keraunos hindurch,
  entlädt sich der Pfeiler in ihn: Er taumelt 4,5 s, der Pfeiler oxidiert. Zu Beginn von II und III werden zwei Pfeiler
  wieder frisch, nach dem Kampf alle.
- **Luftregel:** Wer länger als 1,5 s mehr als 3 Blöcke über dem Boden bleibt, bekommt einen eigenen Blitz (angesagt).
- Echte Vanilla-Blitze schlagen nur noch an den großen Momenten ein (Pfeiler lädt sich, Entladung, Szenen), höchstens
  einer je Sekunde; alle anderen Blitze sind gezeichnet und hellen den Himmel nicht auf.
- **Ende:** Er senkt den Blitz, Nike setzt dem Spieler mit dem meisten Schaden einen goldenen Kranz auf, dann Beute.

Alle neuen Bosse sprechen vorerst nur als Text mit Stimm-Lauten (Namen in ihrer Farbe).

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

## Fortschritt und Testbefehle

| Befehl (OP) | Wirkung |
| --- | --- |
| `/hha progress` / `/hha progress set <hermes\|hekate\|brontes\|kratos\|zeus> <true\|false>` | Stand des Olymp-Pfads zeigen bzw. setzen |
| `/hha boss start <hermes\|hekate\|brontes\|kratos\|zeus>` | an die Insel bringen und den Kampf starten (ohne Olymp am eigenen Standort) |
| `/hha boss skip [boss]`, `/hha boss stop [boss]` | nächste Phase bzw. Abbruch ohne Sieg |
| `/hha tp olymp\|tempel\|dreiweg\|schmiede\|stufen\|thron\|tartaros` | Schnellreise zu den Stationen |
| `/hha olymp bridges` | alle Siegelbrücken sofort schließen (nur geladene Chunks) |
| `/hha olymp spawn <kurete\|griffin\|automaton>` | Testposten 8 Blöcke vor dir |

`/hha progress set hermes false` öffnet eine schon geschlossene Brücke nicht wieder, damit niemand mitten auf ihr abstürzt.
