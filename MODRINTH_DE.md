# H&H Armors

Fügt zwei rivalisierende Rüstungssets hinzu: **Heaven** und **Hell**. Beides sind vollwertige Endgame-Sets mit eigenen Fähigkeiten, jeweils einem Schwert und einem Streitkolben. Ursprünglich für ein Zwei-Fraktionen-SMP gebaut, funktioniert aber auch im normalen Survival.

Das Heaven-Set ist weißes Netherite mit Goldverzierung. Das Hell-Set ist dunkles Netherite mit blauem Seelenfeuer (die Item-Texturen sind animiert). Hergestellt wird beides per Schmiedetisch-Upgrade aus Diamantausrüstung, ähnlich wie Netherite-Upgrades (seit der zweiten Überarbeitung standardmäßig aus: Die Teile sind Reliquien der Bosse, siehe unten).

## Fähigkeiten

Jedes Rüstungsteil hat ein eigenes Passiv, das volle Set schaltet weitere frei. Eine Cooldown-Leiste über der Hotbar zeigt, was bereit ist.

**Heaven:**
- Halo: du und Spieler in der Nähe regenerieren langsam
- Doppelsprung
- Tiefe Stürze lösen eine Schockwelle aus, statt dir zu schaden
- Unter 5 Herzen hinterlässt du eine Lichtspur, die Gegner darin verlangsamt
- Ein Strahl-Angriff, der Gegner schädigt, Teammitglieder aber heilt und entgiftet
- In der Luft schleichen zum Gleiten
- Combo treffen, und jeder weitere Schlag ist ein Auto-Crit, bis dich ein anderer Spieler trifft

**Hell:**
- Auf Lava laufen, plus Stärke solange du brennst
- Unter 3 Herzen: Extra-Tempo und ein Feuerausbruch
- Harte Landungen reißen den Boden zu temporärer Lava auf
- Fire Camp: beschwört drei Piglin-Brute-Leibwächter. Ein Soul Campfire spawnt mit Soul Soil, Fackeln und Laternen drumherum, und das ganze Lager verschwindet wieder, sobald die Brutes tot sind
- Schwert-Fähigkeit: 20 Sekunden lang 50 % schnellere Angriffe, jeder Treffer crittet, und Crits geben dir Goldapfel-Absorption

**Streitkolben** (beide Fraktionen bekommen einen):
- Der Heaven's Mace lässt sich werfen. Er fliegt bis zu 50 Blöcke, trifft alles auf dem Weg mit Blitzen, kommt zurück und trifft auf dem Rückweg nochmal
- Der Hell's Mace zieht Gegner aus bis zu 16 Blöcken an einer Kette zu dir. Rechtsklick auf einen Block macht ihn stattdessen zum Enterhaken. Man kann sich sogar an die eigenen Wind Charges oder Enderperlen im Flug hängen

Volle Sets geben außerdem +10 Herzen und Fallschaden-Immunität, dazu gibt es einen Ultra-Modus mit eigenem Cooldown.

## Olymp und Tartaros

Der **Olymp** ist eine eigene Dimension: schwebende Inseln über einem Wolkenmeer. Einen Rahmen aus Glowstone bauen (innen 2 × 3 bis 9 × 9), einen Wassereimer hineingießen und durch das Wolkentor gehen. Wer herunterfällt, stürzt mit Sanftem Fall in den Himmel der Oberwelt.

Ein fester Pfad aus sechs Inseln führt nach oben, jede mit ihrem Kampf:
- **Hermes**, der Bote: das Rätsel im Tempel lösen, dann ihn in vier Stages fangen
- **Hekate**, Göttin der Schwellen: ein Dreiweg, ein Schlüssel, der den sicheren Weg bestimmt, und drei Gestalten, von denen nur eine das Feuer trägt. Sie bewacht das Tor zum Tartaros
- **Brontes** der Kyklop: Lass seinen Hammer auf einen glühenden Rohling schlagen, dann blenden ihn die Funken; Deckung hinter dem Amboss, Schutz auf den kühlen Rosten
- **Kratos und Bia**, die Thronwache: Befolge seine Befehle (weichen, herankommen, knien) und lenke Bias Sturm in ihn hinein
- **Zeus**: Locke seine Blitze in die Kupferpfeiler, schau weg, wenn die Gorgo auf seinem Schild blickt, und stell dich in die goldene Schale seiner Waage

**Tartaros**: Fünf Wächter halten die Kette am Palast des Hades. Jeder besiegte sprengt ein Glied und macht die übrigen stärker; bei fünf von fünf öffnet das Siegel für Hades und Kronos. Ein Geist des Hermes zeigt den Weg.

Jeder Angriff wird am Boden angesagt, Gold heißt immer sicher oder offen, kein Boss ruft Helfer herbei, und nichts nimmt dir die Kontrolle. Die Bosse wachsen mit der Zahl der Spieler und kontern wiederholte Mace-Sprünge von oben. Jedes Teil der Heaven- und Hell-Sets fällt **einmal je Welt** bei seinem Boss; ein zweiter Sieg gibt Barren. Kureten, Greife und goldene Wächterinnen bewachen die Inseln.

## Konfiguration

Praktisch alles lässt sich abschalten oder anpassen: `/hha toggle` und `/hha set` ändern Fähigkeiten, Schadenswerte und Cooldowns live, ohne Neustart.

Auch die Rezepte sind editierbar. Beim ersten Start legt die Mod Vorlagen in `config/hha/datapack/` ab. Eine `.json.example` in `.json` umbenennen, bearbeiten, `/hha recipes reload` ausführen, und deine Version ersetzt das eingebaute Rezept. Neue Dateien ergeben komplett neue Rezepte.

Sonstiges:
- `/trust <spieler>` sorgt dafür, dass deine Fähigkeiten, Spuren und Brutes deine Freunde nie verletzen
- `/hha kit hell|heaven` verteilt ein voll verzaubertes PvP-Kit (braucht aktivierten `kit_mode` oder den Kreativmodus)
- Bannermuster für beide Fraktionen

## Voraussetzungen

Fabric API und Fabric Language Kotlin. Client und Server.

## Hinweise

- Piglin Brutes bekommen eine seelenblaue Umfärbung ihres Gewands. Das gilt für alle Brutes, auch in Bastionen, da sich Entity-Texturen nicht pro Mob tauschen lassen
- Temporäre Blöcke (Lava, Soul Camp) räumen sich selbst auf. Bei einem harten Server-Absturz können sie liegen bleiben
- Bugs und Ideen: [GitHub](https://github.com/Henny263462/HHA/issues)
