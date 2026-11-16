# Der Tartaros

Ein Ödland aus Schwarzstein unter blutrotem Himmel, eine eigene Dimension (`hha:tartaros`). Fünf Wächter halten die
**Kette**, die den Palast des Hades verschließt: Jeder besiegte Wächter sprengt ein Glied, erst bei fünf von fünf öffnet
das Siegel. Hermes jagt dich hier nicht mehr, er weist nur noch den Weg. Skalierung, Deckel, Mace-Abwehr und Reliquien
stehen in [Bosse allgemein](bosse-allgemein.md).

## Der Weg hinein: das Höllentor

Das Höllentor steht am **Dreiweg der Hekate** im [Olymp](olymp.md) (Insel C, −36 135 −301). Es ist vermauert, bis Hekate
besiegt ist; dann füllt sich die runde Öffnung Reihe für Reihe mit grünem Licht (auch später noch, sobald man näher als 64
Blöcke kommt). Im Tor sieht man einen roten Himmel, die Palastkuppel mit Ketten, drehende Felssplitter und steigende Glut.

- **Hinweg:** 2 Sekunden im Tor (Kreativ sofort), das Bild blendet in dunkles Grün über, kein Wackeln. Ankunft auf einer
  Plattform mit Goldrand und vier grünen Feuerschalen; daneben steht das Rücktor.
- **Rückweg:** durch das Rücktor, zurück auf die Terrasse vor dem Tor am Dreiweg.
- **Alte Welten:** Offene Tore alter Oberwelt-Hallen führen weiter in den Tartaros und zurück in die Halle. In neuen Welten
  entstehen in der Oberwelt keine Hades-Hallen mehr.
- Testen: `/hha tp tartaros` (8 Blöcke vor der Lage des Dreiweg-Tors, notfalls auf einer kleinen Schwarzstein-Fläche).

## Die Landschaft

Eine fast flache Ebene, auf der es nichts gibt außer Wind, Asche und den Bauwerken: keine Berge, keine Schluchten, keine
Flüsse, keine Lava. Ein eigener Bildfilter färbt die Unterwelt (kalte grüne Schatten, warme Lichter, Aschedunst in der
Ferne, kaum Flimmern), abschaltbar mit `/hhac toggle tartaros_shader false`.

| Biom | Was man sieht | Was dort lebt |
| --- | --- | --- |
| Aschenöde | Schwarzstein mit grauen Ascheverwehungen und Kies unter rotem Dunst, tote Bäume, selten das Gerippe eines Riesen | Teufelchen, Skelette, Witherskelette, Piglin-Brutes, Ghasts |
| Asphodeloswiesen | Bleiche Geisterwiese aus Tuff, Blassmoos und Kalk, weiße Blüten, tote Blasseichen | Zombies, Skelette, Phantome |
| Phlegethon | Verbrannter Basalt mit schmalen Glutadern und niedrigen Basaltschloten | Teufelchen, Blazes, Magmawürfel, Ghasts |
| Kokytos | Tiefenschiefer mit Eisschollen, Blaueis-Rissen und Reif, Gestalten im Eis | Eiswanderer, Witherskelette |
| Erebos | Fast schwarz: Obsidian und Sculk unter dichtem Nebel, niedrige Obsidian-Stelen | Teufelchen, Endermen, Witherskelette, Phantome |
| Sümpfe des Styx | Ausgetrockneter Sumpf: Lehm mit Trockenrissen, Seelenerde, Wurzelstümpfe | Sumpfskelette, Ertrunkene, Schleime |

Die Bewohner steigen sichtbar aus dem Boden und werden mit mehr Spielern in der Nähe zäher und härter. Rund um Wächter und
Palast ist Ruhe.

| Bauwerk | Was dort ist | Finden |
| --- | --- | --- |
| Bronzefeste | Niedrige, breite Bronzemauer, gestufter Bergfried mit Beute, eckige Türme | `/locate structure hha:bronze_fortress` |
| Titanenkerker | Vier geneigte Kettenpylone über einer Grube mit einem angeketteten Titanen | `/locate structure hha:titan_prison` |
| Strafstätten | Sisyphos (Hügel mit glühendem Fels), Ixion (Feuerrad) oder Tantalos (Becken, Obstbaum, Fels an Ketten) | `/locate structure hha:punishment_site` |
| Palast des Hades | Rippenkuppel, Ecktürme, Propylon mit Obelisken, der Styx als Graben, Kuppelsaal mit Thron | `/locate structure hha:hades_palace` |

In neuen Welten sind benachbarte Strafstätten (Ost-West und Nord-Süd) immer verschiedene Verdammte; alte Welten behalten
ihre Verteilung.

## Der Wegweiser

Kurz nach der Ankunft und nach jedem gesprengten Glied steht **Hermes als Geist** 8 Blöcke vor dir, zeigt in eine Richtung
und nennt das nächste Ziel mit Entfernung (ein Geist je Gruppe, 6 Sekunden). Er greift nicht an, nimmt nichts, Treffer
gehen durch ihn hindurch. Dazu erscheint für 2 Minuten eine **goldene Fernsäule** über dem nächsten ungesprengten Glied.
Sind alle fünf gesprengt, zeigt er zum Palast, und eine grüne Säule steht dauerhaft über ihm (außer im Kampf und aus der
Nähe).

## Die Kette

Fünf Wächter, fünf Glieder. Die Reihenfolge ist frei, aber **jeder Sieg macht die übrigen stärker** (Kettenbonus,
`k` = schon gesprengte Glieder beim Erscheinen):

| Gesprengt | Leben | Schaden | Pausen | Sonst |
| --- | --- | --- | --- | --- |
| je Glied | × (1 + 0,12 · k) | × (1 + 0,06 · k) | – | – |
| ab 2 | | | zusätzlich × 0,85 | – |
| ab 4 | | | | nach jedem dritten Angriff sofort ein zweiter, anderer Angriff |

Der Titel beim Erscheinen nennt das Glied („Erstes Glied der Kette“ … „Letztes Glied der Kette“). Nach dem Sieg: „Ein Glied
der Kette ist gesprengt (x von 5)“, das Bauwerk bleibt frei. Das **Palastsiegel** startet den Kampf nur bei 5/5; sonst
nennt eine Zeile die Wächter, deren Glied noch hält. `/hha toggle tartaros_chain false` schaltet die Kette ab (Siegel geht
immer), `/hha progress set warden|kampe|sisyphus|ixion|tantalus true` setzt einzelne Glieder.

## Die Wächter

Kommt man einem Bauwerk nahe, erscheint sein Wächter mit Titel und eigener Leiste. Jeder kämpft nach einem Drehbuch: Er
kommt heran, wählt den Angriff nach der Entfernung (nie zweimal denselben direkt hintereinander), jeder Angriff ist am
Boden angesagt, danach steht er kurz. Eine goldene Rune heißt offen (× 1,5). Zu weit vom Bauwerk weggelockt, kehrt er
um und heilt sich. Alle treffen jetzt deutlich härter (Grundschaden × 1,4) und machen kürzere Pausen (× 0,8).

| Bauwerk | Wächter | Leben | Angriffe | Der Trick |
| --- | --- | --- | --- | --- |
| Bronzefeste | Der Kerkermeister: vierarmiger Bronze-Riese | 800 | Richtbeil (unter 40 % doppelt), Kettenwurf, Rundschlag | Lass die Kette an einer Mauer oder Säule hängen bleiben. Die **Laterne** auf dem Rücken ist die Schwachstelle; drei Treffer dort lassen ihn taumeln |
| Titanenkerker | Kampe: Schlangenfrau mit Skorpionschwanz | 920 | Schwanzfeger nach hinten (springen), Giftfächer, Skorpionstich, ab halbem Leben Sturzstöße vom Titanen | Nach dem Stich **steckt der Stachel**: zuschlagen. Der Titan gibt Deckung |
| Stätte des Sisyphos | Sisyphos mit glühendem Fels im Nacken | 750 | Felswurf (Kreis zeigt die Landung), rollender Fels, Stampfer | **Ohne Fels ist er offen.** Ab 40 % rollen Felsen vom Gipfel: Stell ihn in ihre Bahn |
| Stätte des Ixion | Ixion im brennenden Rad | 660 | Feuerrad (Linie, hinterlässt Feuer), Speichenfeuer, Funkenwurf | Nach dem Feuerrad **kippt das Rad**: Jeder Treffer lockert eine Fessel, nach drei fällt er herunter (doppelter Schaden) |
| Stätte des Tantalos | Tantalos mit Dreizack und Kelch | 700 | Dreifacher Dreizackstoß, Geysire, Flutwelle (springen) | Bei zwei Dritteln und einem Drittel **kniet er und trinkt**: 30 Leistenpunkte Schaden in der Zeit schlagen ihm den Kelch aus der Hand |

**In der Luft:** Tantalos' Geysire, Sisyphos' Felswurf und Kampes Sturzstoß treffen auch oben; Schwanzfeger, Flutwelle
und Bodenwellen lassen sich überspringen. Wer mehrfach mit der Mace von oben kommt, bekommt den **Konter** (Beil, Stachel,
Fels, Rad oder Dreizack nach oben, Abwurf), siehe [Abwehr](bosse-allgemein.md#abwehr-gegen-mace-sprünge).

Beute gibt es nur über die Reliquien-Tabelle: Kerkermeister Hells Mace, Kampe Hell Chestplate, Sisyphos Hell Boots,
Ixion Hells Sword, Tantalos Hell Leggings (je einmal je Welt), dazu jedes Mal Hell-Barren, Diamanten, goldene Äpfel und 30
Level.

## Hades und Kronos

Rechtsklick auf das Siegel im Portal des Palasts startet den Kampf (bei 5/5 Gliedern). Alle Spieler im Umkreis von 70
Blöcken kommen mit. Der Thronsaal hat 46 Blöcke Radius. **Oben steht eine geteilte Lebensleiste**: drei Abschnitte für
Hades, später zwei für Kronos.

Beide sind deutlich zäher als früher: Hades hat effektiv **1600** Leben je Phase (vorher 1150), Kronos **4160** je Phase
(vorher 2300). Beide treffen um 20 % härter (`hades_damage` regelt den Schaden beider), Kronos behält seinen Faktor 1,5.

| Phase | Was passiert |
| --- | --- |
| Hades I – Der Gastgeber | Grundangriffe (unten). Zweimal kommt sein **Blick**: hinter eine der acht Säulen, sie stürzt danach ein. Am Ende fällt er, das Tor öffnet sich, eine Truhe steht bereit: ein Köder |
| Hades II – Die Pforten der Hölle | Der Saal greift mit an, eine Gefahr nach der anderen: **Zug der Toten** (Wand mit goldener Lücke), **Höllenfeuer** (drei Wellen Kreise), **Spalten**. Dazu **Seelenketten** (hinter einer Säule reißen sie) und der **Schattenschritt** (raus aus der Rune) |
| Hades III – Das Bündnis | Hermes kämpft als grün getönter Geist mit, nur gemeinsam mit Hades als angesagter **Zangenangriff**; ein Treffer vertreibt ihn für 12 s. Beim **Urteil** sind nur drei goldene Kreise sicher. Bei zwei Dritteln und einem Drittel **stürzt der Boden ein** |
| Kronos I – Der Titan erwacht | Kronos bricht durch die Kuppel und schlägt in **vier festen Ketten**. **Uhrzeiger** fegen über den Boden (springen), beim **Stillstand** hält die Zeit an (Langsamkeit I, eine Rune folgt dir), danach schlagen Kreise ein. Bei der **Rückspulung** stehen goldene Sanduhren (allein zwei, ab zwei Spielern drei): in 12 s zerschlagen, sonst dreht er die Zeit zurück |
| Kronos II – Das Ende der Zeit | Er wächst und wird schneller, versucht zu **verschlingen** (nach außen laufen), der Boden **altert** oder **Zeitsand** fällt. Hades steht auf deiner Seite und hält ihn regelmäßig mit Ketten fest; dann ist er offen |

Hades' Grundangriffe: **Hiebfolge** (drei Kegel), **Ausfallschritt** (vor eine Säule stellen und ausweichen: Er rennt
hinein und taumelt), **Bodenschlag** (springen; danach steckt der Bident), **Bident-Wurf** (mit leerer Hand offen),
**Haltung** (genug Schaden außerhalb der Fenster bricht sie).

Kronos' Ketten (nie zweimal dieselbe hintereinander): **Ernte** (Sichel rechts, links, Spalthieb; danach steckt die Harpe),
**Äon** (Äonenschlag, laufende Ringe, Sichelhieb), **Wurf** (die Harpe fliegt hin und zurück; mit leerer Hand offen),
**Zeitschritt** (Rune bei dir, er steht plötzlich dort).

**In der Luft:** Blick, Höllenfeuer, Urteil, Bident-Einschlag, Seelenkette, Stillstand-Kreise, Zeitsand und Verschlingen
treffen auch oben; Bodenschlag, Äonenschlag, Ringe und Uhrzeiger lassen sich überspringen. Der Konter: Hades reißt den
Bident senkrecht hoch, Kronos schlägt mit der Harpe nach oben.

Stürze kosten im Kampf keinen Fallschaden, alle Blockänderungen werden am Ende zurückgenommen, auch die Kuppel. Wer k. o.
geht, landet vor dem Portal und behält sein Inventar. **Beute:** Hell Helmet als Reliquie (einmal je Welt), dazu jedes Mal
Hell-Barren, ein Netherstern, zwei verzauberte goldene Äpfel und 80 Level.

Zeilen ohne Tonaufnahme laufen als Text mit Stimm-Lauten (Kronos tief, Hades ruhig).
