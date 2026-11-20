package dev.henny.hha.gametest

import dev.henny.hha.world.HhaBiomes
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Identifier
import net.minecraft.world.biome.source.MultiNoiseBiomeSourceParameterLists
import net.minecraft.world.gen.feature.util.PlacedFeatureIndexer

/**
 * Prüft die Weltgenerierungs-Daten, ohne ein Fenster zu öffnen oder einen Chunk zu erzeugen: Der Serverprozess liest alle
 * Datenpakete wie beim normalen Start (fehlerhaftes JSON bricht dort ab) und wird beendet, bevor die Welt geladen wird.
 * Aufruf: gradlew runVerifyWorldgen
 */
class WorldgenVerify : ModInitializer {

    override fun onInitialize() {
        if (System.getProperty("hha.verifyWorldgen") == null) return
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            var code = 0
            try {
                check(server)
                println("HHA-VERIFY OK")
            } catch (error: Throwable) {
                println("HHA-VERIFY FEHLER: $error")
                error.printStackTrace(System.out)
                code = 1
            }
            System.out.flush()
            Runtime.getRuntime().halt(code)
        }
    }

    /** Misst, was `/locate structure` kostet: dieselbe Kandidatensuche und dieselben Aufrufe wie im Spiel, nur ohne Welt. */
    private fun benchLocate(server: MinecraftServer) {
        val registries = server.registryManager
        val options = registries.getOrThrow(RegistryKeys.DIMENSION).getOrThrow(net.minecraft.world.dimension.DimensionOptions.OVERWORLD).value()
        val generator = options.chunkGenerator() as net.minecraft.world.gen.chunk.NoiseChunkGenerator
        val noiseParameters = registries.getOrThrow(RegistryKeys.NOISE_PARAMETERS)
        val structures = registries.getOrThrow(RegistryKeys.STRUCTURE)
        val world = net.minecraft.world.HeightLimitView.create(-64, 384)
        for ((name, spacing, separation, salt) in listOf(listOf<Any>("olympus_temple", 24, 8, 94201337), listOf<Any>("hades_hall", 32, 12, 66601337))) {
            val entry = structures.getEntry(Identifier.of("hha", name as String)).orElseThrow()
            val structure = entry.value()
            for (seed in listOf(1L, 2024L, 987654321L)) {
                val config = net.minecraft.world.gen.noise.NoiseConfig.create(generator.settings.value(), noiseParameters, seed)
                val placement = net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement(spacing as Int, separation as Int, net.minecraft.world.gen.chunk.placement.SpreadType.LINEAR, salt as Int)
                if (name == "hades_hall") {
                    // Wie viel der Tiefe gehört dem Hades-Biom?
                    var hits = 0
                    val samples = 400
                    for (i in 0 until samples) {
                        val biome = generator.biomeSource.getBiome((i % 20) * 256, -32 shr 2, (i / 20) * 256, config.multiNoiseSampler)
                        if (biome.matchesKey(HhaBiomes.HADES)) hits++
                    }
                    println("HHA-VERIFY Hades-Anteil auf y=-32, seed=$seed: ${hits * 100 / samples} %")
                }
                val started = System.nanoTime()
                var checked = 0
                var found: net.minecraft.util.math.ChunkPos? = null
                var ring = 0
                search@ while (ring <= 100 && (System.nanoTime() - started) < 40_000_000_000L) {
                    for (dx in -ring..ring) {
                        for (dz in -ring..ring) {
                            if (kotlin.math.abs(dx) != ring && kotlin.math.abs(dz) != ring) continue
                            val start = placement.getStartChunk(seed, dx * spacing, dz * spacing)
                            checked++
                            val context = net.minecraft.world.gen.structure.Structure.Context(
                                registries, generator, generator.biomeSource, config, server.structureTemplateManager,
                                seed, start, world, structure.validBiomes::contains,
                            )
                            if (structure.getValidStructurePosition(context).isPresent) {
                                found = start
                                break@search
                            }
                        }
                    }
                    ring++
                }
                val millis = (System.nanoTime() - started) / 1_000_000
                val where = found?.let { "gefunden bei ${it.startX} ${it.startZ}" } ?: "nichts gefunden"
                println("HHA-VERIFY locate $name seed=$seed: $checked Kandidaten, $millis ms, ${if (checked > 0) millis / checked else 0} ms je Kandidat, $where")
            }
        }
    }

    /**
     * Zeichnet alle Baupläne in den Speicher. Das fängt Fehler ab und liefert Blocklisten für die Vorschau. Der Boden ist
     * leicht gewellt (wie das Ödland, ±3 Blöcke), damit Fundamente und Böschungen im Bild zu sehen sind; wo der Plan den
     * Boden nicht berührt, steht er als Pseudoblock `terrain` in der Liste.
     */
    private class MemoryCanvas(
        override val minX: Int, override val maxX: Int, override val minZ: Int, override val maxZ: Int, private val level: Int, private val wavy: Boolean = true,
        private val shape: ((Int, Int) -> Int)? = null,
    ) : dev.henny.hha.world.BlockCanvas() {
        val blocks = HashMap<Long, net.minecraft.block.BlockState>()
        val touched = HashSet<Long>()

        private fun key(x: Int, y: Int, z: Int) = net.minecraft.util.math.BlockPos.asLong(x, y, z)

        override fun put(x: Int, y: Int, z: Int, state: net.minecraft.block.BlockState) {
            val k = key(x, y, z)
            touched.add(k)
            if (state.isAir) blocks.remove(k) else blocks[k] = state
        }

        override fun ground(x: Int, z: Int): Int =
            if (shape != null) shape.invoke(x, z) else if (!wavy) level else level + Math.round(1.6 * kotlin.math.sin(x / 19.0) + 1.4 * kotlin.math.cos(z / 27.0) + 0.6 * kotlin.math.sin((x + z) / 9.0)).toInt()

        /** Im Ödland ist der Boden meist Schwarzstein; so sehen Böschungen im Bild wie Gelände aus. */
        override fun surfaceState(x: Int, z: Int): net.minecraft.block.BlockState = net.minecraft.block.Blocks.BLACKSTONE.defaultState

        override fun chest(x: Int, y: Int, z: Int) = put(x, y, z, net.minecraft.block.Blocks.CHEST.defaultState)
        override fun spawner(x: Int, y: Int, z: Int, type: net.minecraft.entity.EntityType<*>) = put(x, y, z, net.minecraft.block.Blocks.SPAWNER.defaultState)

        /** Sicht für die Prüfungen der Überarbeitung 4: Gelände gilt unter `ground − 1` als fest, wo der Plan nichts gesetzt hat. */
        fun grid(name: String): Grid = Grid(name, blocks) { x, y, z -> y <= ground(x, z) - 1 && key(x, y, z) !in touched }

        fun write(name: String, terrain: Boolean = true) {
            val out = java.io.File("plan_$name.txt").bufferedWriter()
            for ((k, state) in blocks) {
                val pos = net.minecraft.util.math.BlockPos.fromLong(k)
                out.write("${pos.x} ${pos.y} ${pos.z} ${net.minecraft.registry.Registries.BLOCK.getId(state.block).path}")
                out.newLine()
            }
            if (terrain) {
                for (x in minX..maxX) {
                    for (z in minZ..maxZ) {
                        val y = ground(x, z) - 1
                        if (key(x, y, z) in touched) continue
                        out.write("$x $y $z terrain")
                        out.newLine()
                    }
                }
            }
            out.close()
            println("HHA-VERIFY Plan $name: ${blocks.size} Blöcke")
        }
    }

    private fun dumpPlans() {
        val level = 72
        val plans = ArrayList<Triple<String, dev.henny.hha.world.TartarusKind, net.minecraft.util.math.BlockPos>>()
        plans.add(Triple("palace", dev.henny.hha.world.TartarusKind.PALACE, net.minecraft.util.math.BlockPos(0, level, 0)))
        plans.add(Triple("fortress", dev.henny.hha.world.TartarusKind.FORTRESS, net.minecraft.util.math.BlockPos(0, level, 0)))
        plans.add(Triple("prison", dev.henny.hha.world.TartarusKind.PRISON, net.minecraft.util.math.BlockPos(0, level, 0)))
        for (i in 0..2) {
            // Die Variante hängt von der Position ab (wie in TartarusPlans.draw); passend ausgewählt, damit jede einmal drankommt.
            val z = (0..64).map { it * 16 }.first { Math.floorMod(it / 16 * 17, 3) == i }
            plans.add(Triple("site$i", dev.henny.hha.world.TartarusKind.PUNISHMENT, net.minecraft.util.math.BlockPos(0, level, z)))
        }
        for ((name, kind, center) in plans) {
            val r = kind.reach
            val canvas = MemoryCanvas(center.x - r, center.x + r, center.z - r, center.z + r, level)
            dev.henny.hha.world.TartarusPlans.draw(canvas, kind, center)
            canvas.write(name)
            grids[name] = canvas.grid(name)
            tartarosCenters[name] = Triple(kind, if (kind == dev.henny.hha.world.TartarusKind.PUNISHMENT) dev.henny.hha.world.TartarusPlans.siteVariant(center) else -1, center)
        }
        // Olymp-Tempel: auf einem Gipfel, die Treppe läuft nach Süden den Hang hinab.
        val temple = dev.henny.hha.world.OlympusTemplePiece
        val templeCanvas = MemoryCanvas(-temple.REACH, temple.REACH, -temple.REACH, temple.REACH + temple.STAIRS, 160) { x, z ->
            // Ein Gipfel: oben flach, nach außen fällt der Hang ab.
            166 - (maxOf(0.0, kotlin.math.hypot(x.toDouble(), z.toDouble()) - 18.0) * 0.9).toInt()
        }
        dev.henny.hha.world.OlympusTemplePlan.draw(templeCanvas, net.minecraft.util.math.BlockPos(0, 166, 0))
        templeCanvas.write("temple")
        grids["temple"] = templeCanvas.grid("temple")
        // Hades-Halle tief unten, der Schacht führt zur Oberfläche bei y = 70.
        val hall = dev.henny.hha.world.HadesHallPiece
        val floor = dev.henny.hha.logic.world.HadesGate.FLOOR_Y
        val hallCanvas = MemoryCanvas(-hall.REACH, hall.REACH, -hall.REACH, hall.SHAFT_Z + hall.SHAFT_R + hall.SHRINE_EXTRA, 70, wavy = false)
        dev.henny.hha.world.HadesHallPlan.draw(hallCanvas, net.minecraft.util.math.BlockPos(0, floor, 0), 70)
        hallCanvas.write("hall", terrain = false)
        grids["hall"] = hallCanvas.grid("hall")
        // Zwei Ruinen: eine mit Altar, eine mit Statue (die Wahl hängt am Zufall).
        var found = 0
        var seed = 1L
        val seen = HashSet<Boolean>()
        while (found < 2 && seed < 200) {
            val ruinCanvas = MemoryCanvas(-24, 24, -24, 24, level)
            dev.henny.hha.world.RuinPlan.draw(ruinCanvas, 0, level - 1, 0, net.minecraft.util.math.random.Random.create(seed), if (found == 0) dev.henny.hha.world.RuinDrift.ASH else dev.henny.hha.world.RuinDrift.MOSS)
            val altar = ruinCanvas.blocks.values.any { it.isOf(net.minecraft.block.Blocks.CHEST) }
            if (seen.add(altar)) {
                ruinCanvas.write("ruin$found")
                grids["ruin$found"] = ruinCanvas.grid("ruin$found")
                found++
            }
            seed++
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Olymp (Überarbeitung 2, PLAN 8.4)
    // ---------------------------------------------------------------------------------------------

    /** Leinwand im Speicher für die Olymp-Pläne: weiß, was schon steht (für weiche Blöcke wie Wolken und Gras). */
    private class SkyMemory(
        override val minX: Int, override val maxX: Int, override val minZ: Int, override val maxZ: Int, private val floor: Int,
    ) : dev.henny.hha.world.olymp.SkyCanvas() {
        val blocks = HashMap<Long, net.minecraft.block.BlockState>()

        private fun key(x: Int, y: Int, z: Int) = net.minecraft.util.math.BlockPos.asLong(x, y, z)

        override fun put(x: Int, y: Int, z: Int, state: net.minecraft.block.BlockState) {
            if (!inside(x, z)) return
            val k = key(x, y, z)
            if (state.isAir) blocks.remove(k) else blocks[k] = state
        }

        override fun isFree(x: Int, y: Int, z: Int): Boolean = inside(x, z) && key(x, y, z) !in blocks
        override fun ground(x: Int, z: Int): Int = floor
        override fun chest(x: Int, y: Int, z: Int) = put(x, y, z, net.minecraft.block.Blocks.CHEST.defaultState)
        override fun spawner(x: Int, y: Int, z: Int, type: net.minecraft.entity.EntityType<*>) {}

        fun state(x: Int, y: Int, z: Int): net.minecraft.block.BlockState = blocks[key(x, y, z)] ?: net.minecraft.block.Blocks.AIR.defaultState

        fun grid(name: String): Grid = Grid(name, blocks, null)

        fun write(name: String) {
            val out = java.io.File("plan_$name.txt").bufferedWriter()
            for ((k, state) in blocks) {
                val pos = net.minecraft.util.math.BlockPos.fromLong(k)
                out.write("${pos.x} ${pos.y} ${pos.z} ${net.minecraft.registry.Registries.BLOCK.getId(state.block).path}")
                out.newLine()
            }
            out.close()
            println("HHA-VERIFY Plan $name: ${blocks.size} Blöcke")
        }
    }

    private val olympusAllowed = setOf("olympus_portal", "hades_portal", "boss_altar", "stele", "offering_pedestal", "olympus_altar", "green_fire")

    /**
     * Erlaubte Blöcke (Überarbeitung 4, PLAN 7.11; vorher `checkVanilla`): `minecraft:*`, die Funktionsblöcke und die
     * Deko- und Geräte-Blöcke aus `HhaDecoBlocks.DECO_IDS`.
     */
    private fun checkBlocks(name: String, blocks: Map<Long, net.minecraft.block.BlockState>) {
        val foreign = blocks.values.map { net.minecraft.registry.Registries.BLOCK.getId(it.block) }
            .filter { it.namespace != "minecraft" && it.path !in olympusAllowed && it.path !in dev.henny.hha.HhaDecoBlocks.DECO_IDS }.toSet()
        require(foreign.isEmpty()) { "Plan $name enthält fremde Blöcke: $foreign" }
    }

    private fun checkBlocks(name: String, canvas: SkyMemory) = checkBlocks(name, canvas.blocks)

    /**
     * Wolkentor (P03/P13): Selbsttest der Rahmenerkennung (leere Liste = bestanden) und Ankunft auf Insel A: Unter
     * `ARRIVAL_SPOT` liegt ein fester Block, darüber zwei Blöcke frei, und das Ankunftstor ist im Bauplan gefüllt.
     */
    private fun checkPortals() {
        val failures = dev.henny.hha.logic.world.PortalFramesCheck.run()
        require(failures.isEmpty()) { "Rahmenprüfung des Wolkentors: $failures" }
        val layout = dev.henny.hha.world.olymp.OlympusLayout
        val canvas = drawIsle(layout.A)
        val spot = net.minecraft.util.math.BlockPos.ofFloored(layout.ARRIVAL_SPOT)
        require(canvas.state(spot.x, spot.y - 1, spot.z).let { !it.isAir && it.blocksMovement() }) {
            "Kein fester Boden unter dem Ankunftspunkt $spot"
        }
        require(canvas.state(spot.x, spot.y, spot.z).isAir && canvas.state(spot.x, spot.y + 1, spot.z).isAir) { "Ankunftspunkt $spot ist verbaut" }
        val gate = layout.ARRIVAL_GATE
        require(canvas.state(gate.x, gate.y, gate.z).isOf(dev.henny.hha.HhaBlocks.OLYMPUS_PORTAL)) { "Ankunftstor $gate ist im Bauplan nicht gefüllt" }
        println("HHA-VERIFY Wolkentor: Rahmenprüfung bestanden, Ankunft $spot (Blick ${layout.ARRIVAL_YAW}°) frei auf festem Boden")
    }

    private fun drawIsle(isle: dev.henny.hha.world.olymp.OlympusLayout.Isle): SkyMemory {
        val box = dev.henny.hha.world.olymp.OlympusPlans.box(isle)
        val canvas = SkyMemory(box.minX, box.maxX, box.minZ, box.maxZ, isle.floorY - 1)
        dev.henny.hha.world.olymp.OlympusPlans.draw(canvas, isle)
        return canvas
    }

    /** Dimension, Biome, feste Platzierung und Reichweite der Pfad-Teile. */
    private fun checkOlympus(server: MinecraftServer) {
        val registries = server.registryManager
        val key = net.minecraft.registry.RegistryKey.of(RegistryKeys.DIMENSION, Identifier.of("hha", "olympus"))
        val options = registries.getOrThrow(RegistryKeys.DIMENSION).getOrThrow(key).value()
        val generator = options.chunkGenerator() as net.minecraft.world.gen.chunk.NoiseChunkGenerator
        val olympusBiomes = generator.biomeSource.biomes.mapNotNull { it.key.orElse(null) }.toSet()
        require(HhaBiomes.ELYSIAN_MEADOW in olympusBiomes && HhaBiomes.MARBLE_HEIGHTS in olympusBiomes) { "Biome des Olymp fehlen in der Biomquelle: $olympusBiomes" }
        net.minecraft.world.gen.feature.util.PlacedFeatureIndexer.collectIndexedFeatures(generator.biomeSource.biomes.toList(), { it.value().generationSettings.features }, true)
        val config = net.minecraft.world.gen.noise.NoiseConfig.create(generator.settings.value(), registries.getOrThrow(RegistryKeys.NOISE_PARAMETERS), 7L)
        val view = net.minecraft.world.HeightLimitView.create(0, 384)
        val ground = generator.getHeightOnGround(0, 0, net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG, view, config)
        require(ground <= 1) { "Olymp ist nicht leer: Boden bei $ground" }
        // Biome nach Höhe: Wiesen unten, Marmorhöhen oben.
        val low = generator.biomeSource.getBiome(0, 120 shr 2, 0, config.multiNoiseSampler)
        val high = generator.biomeSource.getBiome(0, 232 shr 2, 0, config.multiNoiseSampler)
        require(low.matchesKey(HhaBiomes.ELYSIAN_MEADOW) && high.matchesKey(HhaBiomes.MARBLE_HEIGHTS)) { "Biomverteilung im Olymp falsch: $low / $high" }
        println("HHA-VERIFY Olymp: Leere bis y $ground, Biome ${olympusBiomes.map { it.value.path }}, Feature-Reihenfolge in Ordnung")
        // Die feste Platzierung muss genau die Start-Chunks der Pfad-Teile nennen.
        val sets = registries.getOrThrow(RegistryKeys.STRUCTURE_SET)
        val path = sets.get(Identifier.of("hha", "olympus_path")) ?: error("Struktur-Set olympus_path fehlt")
        val placement = path.placement() as? dev.henny.hha.world.olymp.FixedStructurePlacement ?: error("olympus_path hat keine feste Platzierung")
        val want = dev.henny.hha.world.olymp.OlympusStructures.startChunks().map { "${it.x},${it.z}" }.toSet()
        val have = placement.chunks.map { "${it.x},${it.z}" }.toSet()
        require(want == have) { "Start-Chunks in structure_set/olympus_path.json passen nicht: Datei $have, Plan $want" }
        require(want.size == dev.henny.hha.world.olymp.OlympusStructures.ELEMENTS.size) { "Zwei Pfad-Teile teilen einen Start-Chunk" }
        for (element in dev.henny.hha.world.olymp.OlympusStructures.ELEMENTS) {
            require(dev.henny.hha.world.olymp.OlympusStructures.fitsReferenceRange(element)) { "Pfad-Teil ${element.id} reicht weiter als 8 Chunks um seinen Start" }
        }
        val islets = sets.get(Identifier.of("hha", "sky_islets")) ?: error("Struktur-Set sky_islets fehlt")
        val spread = islets.placement() as net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement
        require(spread.spacing == 9 && spread.separation == 4) { "Nebeninseln: random_spread 9/4 erwartet" }
        // Brücken: Siegellücken wie im Plan.
        for (bridge in dev.henny.hha.world.olymp.OlympusBridges.ALL) {
            require((bridge.sealedBy != null) == (bridge.gap != null)) { "Brücke ${bridge.id}: Lücke fehlt oder ist zu viel" }
            println("HHA-VERIFY Brücke ${bridge.id}: ${bridge.length} Zellen, ${bridge.y1 - bridge.y0} Stufen, Pfeiler ${bridge.piers}, Lücke ${bridge.gap}, Siegel ${bridge.sealedBy}")
        }
        // Lage von Toren, Engstelle, Rahmen und Ereignissen je Brücke (PZ1, Wunsch P06 2).
        dev.henny.hha.world.olymp.OlympusBridges.describe().forEach { println("HHA-VERIFY Weg $it") }
        println("HHA-VERIFY Olymp-Pfad: ${want.size} Starts ${want.sorted()}, alle in Reichweite")
    }

    /** Schreibt die Baupläne des Olymp und prüft die Kampfflächen; dazu die Draufsicht des ganzen Pfads. */
    private fun dumpOlympus() {
        val layout = dev.henny.hha.world.olymp.OlympusLayout
        val top = HashMap<Long, Pair<Int, String>>()
        fun fold(canvas: SkyMemory) {
            for ((k, state) in canvas.blocks) {
                val pos = net.minecraft.util.math.BlockPos.fromLong(k)
                val column = net.minecraft.util.math.ChunkPos.toLong(pos.x, pos.z)
                val current = top[column]
                if (current == null || current.first < pos.y) top[column] = pos.y to net.minecraft.registry.Registries.BLOCK.getId(state.block).path
            }
        }
        for (isle in layout.ISLES) {
            val canvas = drawIsle(isle)
            checkBlocks("isle_${isle.letter}", canvas)
            canvas.write("olymp_${isle.letter}")
            grids["olymp_${isle.letter}"] = canvas.grid("olymp_${isle.letter}")
            fold(canvas)
        }
        for (bridge in dev.henny.hha.world.olymp.OlympusBridges.ALL) {
            val box = bridge.box
            val canvas = SkyMemory(box.minX, box.maxX, box.minZ, box.maxZ, bridge.y0)
            dev.henny.hha.world.olymp.OlympusBridges.draw(canvas, bridge, dev.henny.hha.world.olymp.OlympusBridges.Part.OUTSIDE)
            checkBlocks("bridge_${bridge.id}", canvas)
            fold(canvas)
            // Überarbeitung 4 (PLAN 7.11): alle fünf Brücken ganz (versiegelt) und jede Siegelbrücke offen.
            val whole = SkyMemory(box.minX, box.maxX, box.minZ, box.maxZ, bridge.y0)
            dev.henny.hha.world.olymp.OlympusBridges.draw(whole, bridge, dev.henny.hha.world.olymp.OlympusBridges.Part.ALL)
            whole.write("olymp_bridge_${bridge.id}")
            grids["olymp_bridge_${bridge.id}"] = whole.grid("olymp_bridge_${bridge.id}")
            if (bridge.sealedBy != null) {
                // Die Siegelbrücke nach dem Nachsetzen: so sieht sie aus, wenn ihr Boss besiegt ist.
                val open = SkyMemory(box.minX, box.maxX, box.minZ, box.maxZ, bridge.y0)
                dev.henny.hha.world.olymp.OlympusBridges.draw(open, bridge, dev.henny.hha.world.olymp.OlympusBridges.Part.ALL, sealed = false)
                val marker = bridge.marker()!!
                require(open.state(marker.x, marker.y, marker.z).isOf(net.minecraft.block.Blocks.CHISELED_QUARTZ_BLOCK)) { "Markierblock der Siegelbrücke ${bridge.id} fehlt" }
                open.write("olymp_bridge_${bridge.id}_open")
                grids["olymp_bridge_${bridge.id}_open"] = open.grid("olymp_bridge_${bridge.id}_open")
            }
        }
        for ((i, kind) in dev.henny.hha.world.olymp.SkyIslets.Kind.entries.withIndex()) {
            val center = net.minecraft.util.math.BlockPos(0, 150, 0)
            val box = dev.henny.hha.world.olymp.SkyIslets.box(kind, center)
            val canvas = SkyMemory(box.minX, box.maxX, box.minZ, box.maxZ, center.y)
            dev.henny.hha.world.olymp.SkyIslets.draw(canvas, kind, center, 4242 + i * 17)
            checkBlocks("islet_$kind", canvas)
            canvas.write("olymp_islet_${kind.name.lowercase()}")
            grids["olymp_islet_${kind.name.lowercase()}"] = canvas.grid("olymp_islet_${kind.name.lowercase()}")
        }
        val out = java.io.File("olymp_top.txt").bufferedWriter()
        for ((column, entry) in top) {
            val x = net.minecraft.util.math.ChunkPos.getPackedX(column)
            val z = net.minecraft.util.math.ChunkPos.getPackedZ(column)
            out.write("$x $z ${entry.first} ${entry.second}")
            out.newLine()
        }
        out.close()
        println("HHA-VERIFY Draufsicht Olymp: ${top.size} Spalten")
        val posts = dev.henny.hha.world.olymp.OlympusPlans.posts()
        for (post in posts) {
            require(post.isle != 'A') { "Posten auf Insel A: $post" }
            val isle = layout.ISLES.first { it.letter == post.isle }
            if (isle.arenaRadius > 0) require(kotlin.math.hypot((post.pos.x - isle.center.x).toDouble(), (post.pos.z - isle.center.z).toDouble()) > isle.arenaRadius + 2) { "Posten in der Kampffläche: $post" }
        }
        println("HHA-VERIFY Posten: ${posts.size} (${posts.groupingBy { it.kind }.eachCount()})")
    }

    /** Schreibt die Geländehöhen des Tartaros als Bild, um die Form zu prüfen, ohne Chunks zu erzeugen. */
    private fun mapTartaros(server: MinecraftServer) {
        val registries = server.registryManager
        val key = net.minecraft.registry.RegistryKey.of(RegistryKeys.DIMENSION, Identifier.of("hha", "tartaros"))
        val generator = registries.getOrThrow(RegistryKeys.DIMENSION).getOrThrow(key).value().chunkGenerator() as net.minecraft.world.gen.chunk.NoiseChunkGenerator
        val config = net.minecraft.world.gen.noise.NoiseConfig.create(generator.settings.value(), registries.getOrThrow(RegistryKeys.NOISE_PARAMETERS), 12345L)
        val world = net.minecraft.world.HeightLimitView.create(0, 256)
        val size = 200
        val out = StringBuilder()
        val biomes = StringBuilder()
        val grid = Array(size) { arrayOfNulls<String>(size) }
        var low = Int.MAX_VALUE
        var high = Int.MIN_VALUE
        for (z in 0 until size) {
            for (x in 0 until size) {
                val h = generator.getHeightOnGround(x * 8, z * 8, net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG, world, config)
                val biome = generator.biomeSource.getBiome((x * 8) shr 2, 80 shr 2, (z * 8) shr 2, config.multiNoiseSampler)
                val name = biome.key.map { it.value.path }.orElse("?")
                grid[z][x] = name
                biomes.append(name)
                if (x == size - 1) biomes.appendLine() else biomes.append(" ")
                low = minOf(low, h)
                high = maxOf(high, h)
                out.append(h)
                if (x == size - 1) out.appendLine() else out.append(" ")
            }
        }
        java.io.File("tartaros_heights.txt").writeText(out.toString())
        java.io.File("tartaros_biomes.txt").writeText(biomes.toString())
        // Die Rauschwerte der Oberflächenregeln, Block für Block: preview_terrain.py setzt daraus mit denselben Schwellen
        // wie tartaros_world.py die Oberfläche zusammen, ohne dass ein Chunk erzeugt wird.
        try {
            fun sampler(name: String) = config.getOrCreateSampler(net.minecraft.registry.RegistryKey.of(RegistryKeys.NOISE_PARAMETERS, Identifier.of("hha", name)))
            val patch = sampler("tartaros_patch")
            val speckle = sampler("tartaros_speckle")
            val crack = sampler("tartaros_crack")
            val span = 384
            // Ein Ausschnitt, in dem möglichst viele Biome aneinanderstoßen: aus der Biomkarte (ein Feld = 8 Blöcke) gewählt.
            val cells = span / 8
            var best = -1.0
            var ox = 0
            var oz = 0
            for (cz in 0..size - cells step 6) {
                for (cx in 0..size - cells step 6) {
                    val count = HashMap<String, Int>()
                    for (z in cz until cz + cells step 2) for (x in cx until cx + cells step 2) count.merge(grid[z][x] ?: "?", 1, Int::plus)
                    val total = count.values.sum().toDouble()
                    val entropy = count.values.sumOf { val p = it / total; -p * kotlin.math.ln(p) }
                    if (entropy > best) {
                        best = entropy
                        ox = cx * 8
                        oz = cz * 8
                    }
                }
            }
            val surface = StringBuilder()
            for (z in 0 until span) {
                for (x in 0 until span) {
                    val wx = ox + x
                    val wz = oz + z
                    val biome = generator.biomeSource.getBiome(wx shr 2, 72 shr 2, wz shr 2, config.multiNoiseSampler).key.map { it.value.path }.orElse("?")
                    surface.append(biome).append(':')
                        .append(String.format(java.util.Locale.ROOT, "%.3f", patch.sample(wx.toDouble(), 0.0, wz.toDouble()))).append(':')
                        .append(String.format(java.util.Locale.ROOT, "%.3f", speckle.sample(wx.toDouble(), 0.0, wz.toDouble()))).append(':')
                        .append(String.format(java.util.Locale.ROOT, "%.3f", crack.sample(wx.toDouble(), 0.0, wz.toDouble())))
                    if (x == span - 1) surface.appendLine() else surface.append(' ')
                }
            }
            java.io.File("tartaros_surface.txt").writeText(surface.toString())
            println("HHA-VERIFY Oberflächenprobe ${span}x$span ab $ox $oz geschrieben")
        } catch (error: Throwable) {
            println("HHA-VERIFY Oberflächenprobe übersprungen: $error")
        }
        // Dieselbe Prüfung wie beim Erzeugen des Chunk-Generators: Widersprüche in der Feature-Reihenfolge wären ein Absturz.
        val tartarusBiomes = generator.biomeSource.biomes.toList()
        net.minecraft.world.gen.feature.util.PlacedFeatureIndexer.collectIndexedFeatures(tartarusBiomes, { it.value().generationSettings.features }, true)
        println("HHA-VERIFY Tartaros: ${tartarusBiomes.size} Biome, Feature-Reihenfolge in Ordnung")
        println("HHA-VERIFY Tartaros-Höhen: $low bis $high")
    }

    // ---------------------------------------------------------------------------------------------
    // Überarbeitung 4 (PLAN 3.8, 7.11): Verträge, Schwebe-Zähler, Bauplan-Selbsttest, Teile abziehen
    // ---------------------------------------------------------------------------------------------

    /** Eine Blocktabelle zum Prüfen; [terrain] sagt, wo Gelände fest ist (nur Tartaros-Pläne). */
    class Grid(val name: String, val blocks: Map<Long, net.minecraft.block.BlockState>, val terrain: ((Int, Int, Int) -> Boolean)?) {
        fun state(x: Int, y: Int, z: Int): net.minecraft.block.BlockState =
            blocks[net.minecraft.util.math.BlockPos.asLong(x, y, z)] ?: net.minecraft.block.Blocks.AIR.defaultState

        /** Fester Körper (Kollisionsform) oder Gelände. */
        fun solid(x: Int, y: Int, z: Int): Boolean {
            val s = blocks[net.minecraft.util.math.BlockPos.asLong(x, y, z)]
            if (s != null) return !s.getCollisionShape(net.minecraft.world.EmptyBlockView.INSTANCE, net.minecraft.util.math.BlockPos.ORIGIN).isEmpty
            return terrain?.invoke(x, y, z) == true
        }

        fun without(name: String, remove: (Int, Int, Int) -> Boolean): Grid {
            val copy = HashMap<Long, net.minecraft.block.BlockState>(blocks.size)
            for ((k, v) in blocks) {
                if (!remove(net.minecraft.util.math.BlockPos.unpackLongX(k), net.minecraft.util.math.BlockPos.unpackLongY(k), net.minecraft.util.math.BlockPos.unpackLongZ(k))) copy[k] = v
            }
            return Grid(name, copy, terrain)
        }

        fun write(file: String, box: net.minecraft.util.math.BlockBox? = null) {
            val out = java.io.File(file).bufferedWriter()
            var n = 0
            for ((k, state) in blocks) {
                if (state.isAir) continue
                val pos = net.minecraft.util.math.BlockPos.fromLong(k)
                if (box != null && !box.contains(pos)) continue
                out.write("${pos.x} ${pos.y} ${pos.z} ${net.minecraft.registry.Registries.BLOCK.getId(state.block).path}")
                out.newLine()
                n++
            }
            out.close()
            println("HHA-VERIFY Plan ${file.removePrefix("plan_").removeSuffix(".txt")}: $n Blöcke")
        }
    }

    private val grids = LinkedHashMap<String, Grid>()
    private val tartarosCenters = LinkedHashMap<String, Triple<dev.henny.hha.world.TartarusKind, Int, net.minecraft.util.math.BlockPos>>()

    /**
     * Warnungen der Vertragsprüfung sind Fehler (seit P24 dauerhaft streng); nur `-Dhha.verify.strict=false` meldet sie
     * bloß (zum Nachsehen während eines Umbaus).
     */
    private val strict = System.getProperty("hha.verify.strict")?.toBoolean() != false

    /** `-Dhha.verify.only=<id>`: Vertragsprüfung, Arena-Dumps und Selbsttest nur für diesen Bauplan. */
    private val only: String? = System.getProperty("hha.verify.only")?.takeIf { it.isNotBlank() && it != "true" }

    private val warnings = LinkedHashMap<String, LinkedHashSet<String>>()

    private fun warn(arena: String, message: String) {
        warnings.getOrPut(arena) { LinkedHashSet() }.add(message)
    }

    /** Mitte des Forums im Prüflauf (über Insel B, wie `HermesArena.chooseHeight` es ungefähr wählt). */
    private val forumTest = net.minecraft.util.math.BlockPos(0, 208, -130)

    /** Zählt nicht als schwebend und nicht als fester Nachbar: Licht, Ketten, Pflanzen, Wolken, Teppiche, Feuer. */
    private fun exempt(state: net.minecraft.block.BlockState): Boolean {
        if (state.isAir || !state.fluidState.isEmpty) return true
        if (state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) || state.isIn(net.minecraft.registry.tag.BlockTags.FLOWERS) ||
            state.isIn(net.minecraft.registry.tag.BlockTags.SAPLINGS) || state.isIn(net.minecraft.registry.tag.BlockTags.REPLACEABLE_BY_TREES) ||
            state.isIn(net.minecraft.registry.tag.BlockTags.WOOL_CARPETS) || state.isIn(net.minecraft.registry.tag.BlockTags.CAVE_VINES) ||
            state.isIn(net.minecraft.registry.tag.BlockTags.CANDLES) || state.isIn(net.minecraft.registry.tag.BlockTags.FIRE)
        ) return true
        val path = net.minecraft.registry.Registries.BLOCK.getId(state.block).path
        return "lantern" in path || "chain" in path || "torch" in path || "vine" in path || path in SOFT_BLOCKS
    }

    private val SOFT_BLOCKS = setOf(
        "storm_cloud", "snow_block", "white_stained_glass", "white_concrete", "gray_concrete", "light_gray_concrete",
        "moss_carpet", "green_fire", "lightning_rod", "waxed_lightning_rod", "end_rod",
    )

    private val NEIGHBOURS = listOf(intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 1, 0), intArrayOf(0, -1, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1))

    /** Schwebe-Zähler: Blöcke ohne festen Nachbarn in den sechs Richtungen (PLAN 7.11). */
    private fun floating(g: Grid): Set<Long> {
        val out = HashSet<Long>()
        for ((k, state) in g.blocks) {
            if (exempt(state)) continue
            val x = net.minecraft.util.math.BlockPos.unpackLongX(k)
            val y = net.minecraft.util.math.BlockPos.unpackLongY(k)
            val z = net.minecraft.util.math.BlockPos.unpackLongZ(k)
            val held = NEIGHBOURS.any { d ->
                val n = g.blocks[net.minecraft.util.math.BlockPos.asLong(x + d[0], y + d[1], z + d[2])]
                if (n != null) !exempt(n) else g.terrain?.invoke(x + d[0], y + d[1], z + d[2]) == true
            }
            if (!held) out.add(k)
        }
        return out
    }

    private fun fmt(k: Long) = "${net.minecraft.util.math.BlockPos.unpackLongX(k)} ${net.minecraft.util.math.BlockPos.unpackLongY(k)} ${net.minecraft.util.math.BlockPos.unpackLongZ(k)}"

    /** Prüft alle Pläne mit dem Schwebe-Zähler gegen die Ausnahmeliste und schreibt `float_report.txt`. */
    private fun checkFloating() {
        val report = java.io.File("float_report.txt").bufferedWriter()
        val problems = ArrayList<String>()
        var total = 0
        for ((name, g) in grids) {
            val now = floating(g)
            total += now.size
            val allowed = FloatBaseline.allowed(name)
            val fresh = now.filter { fmt(it) !in allowed }
            for (k in now.sortedBy { fmt(it) }) report.write("$name ${fmt(k)} ${net.minecraft.registry.Registries.BLOCK.getId(g.blocks.getValue(k).block).path}\n")
            if (fresh.isNotEmpty()) problems.add("$name: ${fresh.size} neu schwebend, z. B. ${fresh.take(12).map { fmt(it) }}")
            if (now.isNotEmpty()) println("HHA-VERIFY Schwebe-Zähler $name: ${now.size} (Ausnahmeliste ${allowed.size}, neu ${fresh.size})")
        }
        report.close()
        println("HHA-VERIFY Schwebe-Zähler: $total schwebende Blöcke in ${grids.size} Plänen (Liste in float_report.txt)")
        require(problems.isEmpty()) { "Schwebende Blöcke außerhalb der Ausnahmeliste: $problems" }
    }

    /** Liegt (x, z) unter/über dem Kasten [b] (nur waagerecht)? */
    private fun inColumns(b: net.minecraft.util.math.BlockBox, x: Int, z: Int) = x in b.minX..b.maxX && z in b.minZ..b.maxZ

    /** Strahl in kleinen Schritten über die Tabelle: blockiert, sobald er durch einen festen Block läuft. */
    private fun rayBlocked(g: Grid, from: net.minecraft.util.math.Vec3d, to: net.minecraft.util.math.Vec3d): Boolean {
        val d = to.subtract(from)
        val steps = (d.length() / 0.05).toInt().coerceAtLeast(1)
        var last = Long.MIN_VALUE
        for (i in 0..steps) {
            val p = from.add(d.multiply(i.toDouble() / steps))
            val x = kotlin.math.floor(p.x).toInt()
            val y = kotlin.math.floor(p.y).toInt()
            val z = kotlin.math.floor(p.z).toInt()
            val k = net.minecraft.util.math.BlockPos.asLong(x, y, z)
            if (k == last) continue
            last = k
            if (g.solid(x, y, z)) return true
        }
        return false
    }

    /**
     * Prüft eine Arena gegen ihren Vertrag (PLAN 3.8): Kern eben und frei, Deckung fest und mit Sichtschutz, Ebenen mit
     * Boden und Zugang, Geräte an ihrem Platz, Teile. [coreHard]: Kernfehler sind immer Fehler (Olymp wie bisher).
     */
    private fun checkSpec(spec: dev.henny.hha.world.arena.ArenaSpec, g: Grid, clear: Int, coreHard: Boolean, altar: net.minecraft.util.math.BlockPos? = null) {
        val id = spec.id
        // 1. Kern
        val skip = HashSet<Long>()
        // Deckung samt einem Rand von 1 (Sockelstufen, Fasen gehören zur Deckung).
        for (c in spec.cover) for (x in c.box.minX - 1..c.box.maxX + 1) for (z in c.box.minZ - 1..c.box.maxZ + 1) skip.add(net.minecraft.util.math.ChunkPos.toLong(x, z))
        for (d in spec.devices) skip.add(net.minecraft.util.math.ChunkPos.toLong(d.pos.x, d.pos.z))
        for (l in spec.levels) for (a in l.access) skip.add(net.minecraft.util.math.ChunkPos.toLong(a.x, a.z))
        if (altar != null) skip.add(net.minecraft.util.math.ChunkPos.toLong(altar.x, altar.z))
        // Tor-Stürze: Über einem Teil mit fills (Fallgitter) darf ab part.maxY + 1 gebaut sein, in der Teilzone und eine
        // Zelle nach außen und innen (Sturz 2 tief). (PZ1, Wunsch P07 1.)
        val lintelFrom = HashMap<Long, Int>()
        for (p in spec.parts) if (p.fills) for ((x, z) in p.zone.cells()) for (dx in -1..1) for (dz in -1..1) {
            val k = net.minecraft.util.math.ChunkPos.toLong(x + dx, z + dz)
            lintelFrom[k] = minOf(lintelFrom[k] ?: Int.MAX_VALUE, p.maxY + 1)
        }
        val coreProblems = ArrayList<String>()
        var cells = 0
        for ((x, z) in spec.core.cells()) {
            if (net.minecraft.util.math.ChunkPos.toLong(x, z) in skip) continue
            if (spec.levels.any { it.zone.containsCell(x, z) }) continue
            cells++
            val floor = g.state(x, spec.coreY - 1, z)
            val shape = floor.getCollisionShape(net.minecraft.world.EmptyBlockView.INSTANCE, net.minecraft.util.math.BlockPos.ORIGIN)
            if (!net.minecraft.block.Block.isShapeFullCube(shape) && g.terrain?.invoke(x, spec.coreY - 1, z) != true) {
                coreProblems.add("Boden $x ${spec.coreY - 1} $z: ${net.minecraft.registry.Registries.BLOCK.getId(floor.block).path}")
            }
            val lintel = lintelFrom[net.minecraft.util.math.ChunkPos.toLong(x, z)] ?: Int.MAX_VALUE
            for (h in 0 until clear) {
                if (spec.coreY + h >= lintel) continue
                val state = g.state(x, spec.coreY + h, z)
                if (!state.isAir && !exempt(state)) coreProblems.add("frei? $x ${spec.coreY + h} $z: ${net.minecraft.registry.Registries.BLOCK.getId(state.block).path}")
            }
        }
        if (coreProblems.isNotEmpty()) {
            val message = "Kern nicht eben/frei (${coreProblems.size}): ${coreProblems.take(12)}"
            if (coreHard) throw IllegalStateException("Kampffläche $id: $message") else warn(id, message)
        }
        // 2. Deckung und Sichtstrahl
        val eye = spec.coreY + 1.62
        val source = net.minecraft.util.math.Vec3d(spec.center.x + 0.5, eye, spec.center.z + 0.5)
        var sheltered = 0
        for (c in spec.cover) {
            val b = c.box
            var solid = 0
            var all = 0
            for (x in b.minX..b.maxX) for (y in b.minY..b.maxY) for (z in b.minZ..b.maxZ) {
                all++
                if (g.solid(x, y, z)) solid++
            }
            val top = (b.minX..b.maxX).any { x -> (b.minZ..b.maxZ).any { z -> g.solid(x, b.maxY, z) } }
            // Fester Körper: mindestens ein Drittel des Kastens fest (Statuen, Bäume) und oben noch ein Block.
            if (solid * 3 < all || !top) warn(id, "Deckung ${c.id} unvollständig: $solid/$all fest, oben ${if (top) "ja" else "nein"} (Kasten ${b.minX} ${b.minY} ${b.minZ} … ${b.maxX} ${b.maxY} ${b.maxZ})")
            if (inColumns(b, spec.center.x, spec.center.z)) continue
            val cx = (b.minX + b.maxX + 1) / 2.0
            val cz = (b.minZ + b.maxZ + 1) / 2.0
            val dir = net.minecraft.util.math.Vec3d(cx - source.x, 0.0, cz - source.z).normalize()
            val hw = (b.maxX - b.minX + 1) / 2.0
            val hd = (b.maxZ - b.minZ + 1) / 2.0
            val edge = minOf(if (kotlin.math.abs(dir.x) > 1e-6) hw / kotlin.math.abs(dir.x) else Double.MAX_VALUE, if (kotlin.math.abs(dir.z) > 1e-6) hd / kotlin.math.abs(dir.z) else Double.MAX_VALUE)
            val ty = eye.coerceIn(b.minY + 0.5, b.maxY + 0.5)
            val target = net.minecraft.util.math.Vec3d(cx + dir.x * (edge + 1.5), ty, cz + dir.z * (edge + 1.5))
            if (rayBlocked(g, source, target)) sheltered++ else warn(id, "Sichtstrahl hinter ${c.id} nicht blockiert")
        }
        // 3. Ebenen
        for (l in spec.levels) {
            var support = 0
            var count = 0
            for ((x, z) in l.zone.cells()) {
                count++
                if (g.solid(x, l.floorY - 1, z)) support++
            }
            if (count == 0 || support * 10 < count * 9) warn(id, "Ebene ${l.id}: Boden auf y ${l.floorY - 1} nur $support/$count")
            val access = l.access.any { val b = g.state(it.x, it.y, it.z).block; b is net.minecraft.block.StairsBlock || b is net.minecraft.block.SlabBlock }
            if (!access) warn(id, "Ebene ${l.id}: kein Treppen-/Stufenblock an den ${l.access.size} Zugängen (z. B. ${l.access.take(2)})")
        }
        // 4. Geräte
        for (d in spec.devices) {
            val state = g.state(d.pos.x, d.pos.y, d.pos.z)
            if (state.block !== d.block()) warn(id, "Gerät ${d.id} fehlt an ${d.pos.x} ${d.pos.y} ${d.pos.z} (dort ${net.minecraft.registry.Registries.BLOCK.getId(state.block).path})")
        }
        // 5. Teile
        for (p in spec.parts) {
            var n = 0
            for ((x, z) in p.zone.cells()) for (y in p.minY..p.maxY) if (!g.state(x, y, z).isAir) n++
            if (p.fills && n > 0) warn(id, "Teil ${p.id} soll im Plan leer sein, hat $n Blöcke")
            if (!p.fills && n == 0) warn(id, "Teil ${p.id} enthält keinen Block")
        }
        // 6. Blockschutz (P24, fester Punkt 6): Deckung, Geräte, Zugänge, Ebenen (Boden bis Kopf) und Teile liegen im
        // geschützten Kasten `spec.box`; sonst könnten Spieler dort im Kampf abbauen oder bauen.
        val outside = LinkedHashSet<String>()
        fun inBox(x: Int, y: Int, z: Int) = spec.box.contains(net.minecraft.util.math.BlockPos(x, y, z))
        for (c in spec.cover) if (!inBox(c.box.minX, c.box.minY, c.box.minZ) || !inBox(c.box.maxX, c.box.maxY, c.box.maxZ)) outside.add("Deckung ${c.id}")
        for (d in spec.devices) if (!spec.box.contains(d.pos)) outside.add("Gerät ${d.id}")
        for (l in spec.levels) {
            if (l.access.any { !spec.box.contains(it) }) outside.add("Zugang ${l.id}")
            if (l.zone.cells().any { (x, z) -> !inBox(x, l.floorY - 1, z) || !inBox(x, l.floorY + 1, z) }) outside.add("Ebene ${l.id}")
        }
        for (p in spec.parts) if (p.zone.cells().any { (x, z) -> !inBox(x, p.minY, z) || !inBox(x, p.maxY, z) }) outside.add("Teil ${p.id}")
        if (outside.isNotEmpty()) warn(id, "Blockschutz: außerhalb von spec.box: $outside")
        println("HHA-VERIFY Arena $id: Kern $cells Felder${if (coreProblems.isEmpty()) " eben und frei" else " (${coreProblems.size} Mängel)"}, Deckung ${spec.cover.size} (Sichtschutz $sheltered), Ebenen ${spec.levels.size}, Teile ${spec.parts.size}, Geräte ${spec.devices.size}, Warnungen ${warnings[id]?.size ?: 0}")
    }

    /** Teile abziehen: Nach dem Abbruch darf nichts zusätzlich schweben (PLAN 7.11). */
    private fun checkLost(spec: dev.henny.hha.world.arena.ArenaSpec, g: Grid, parts: List<dev.henny.hha.world.arena.Part>, suffix: String) {
        val removable = parts.filter { !it.fills }
        if (removable.isEmpty()) return
        val lost = g.without("${spec.id}_$suffix") { x, y, z -> removable.any { p -> y in p.minY..p.maxY && p.zone.containsCell(x, z) } }
        lost.write("plan_arena_${spec.id}_$suffix.txt", spec.box)
        val before = floating(g)
        val fresh = floating(lost).filter { it !in before && spec.box.contains(net.minecraft.util.math.BlockPos.fromLong(it)) }
        if (fresh.isNotEmpty()) warn(spec.id, "nach Abzug ($suffix) schweben ${fresh.size} Blöcke, z. B. ${fresh.take(12).map(::fmt)}")
        println("HHA-VERIFY Teile abgezogen ${spec.id} ($suffix): ${removable.size} Teile, ${fresh.size} Blöcke schweben danach")
    }

    /**
     * Bauplan-Selbsttest (PLAN 3.8/7.11): die echte Logik aus `ArenaBlueprints.selfTest` (Design, Edit-Register,
     * Reparatur über `BlueprintLogic`), dieselbe wie im Spiel. (PZ1, Wunsch P02 1.)
     */
    private fun selfTest(id: String, origin: net.minecraft.util.math.BlockPos) {
        val r = dev.henny.hha.logic.boss.arena.ArenaBlueprints.selfTest(id, origin)
        println("HHA-VERIFY Bauplan $id: ${r.cells} Zellen aufgenommen, Selbsttest (${r.changed} Zellen, ${r.viaEdits} über Edits, ${r.repaired} repariert): ${r.message}")
        require(r.ok) { "Bauplan $id: ${r.message}" }
    }

    /** Vertragszahlen als Datei für `API.md` (Deckung, Ebenen, Teile, Geräte, Wegpunkte). */
    private fun writeContract(specs: List<dev.henny.hha.world.arena.ArenaSpec>) {
        val out = java.io.File("contract.txt").bufferedWriter()
        fun box(b: net.minecraft.util.math.BlockBox) = "${b.minX} ${b.minY} ${b.minZ} … ${b.maxX} ${b.maxY} ${b.maxZ}"
        for (s in specs) {
            out.write("== ${s.id}: Mitte ${s.center.x} ${s.center.y} ${s.center.z}, Radius ${s.radius}, Kern y ${s.coreY}, Kasten ${box(s.box)}\n")
            for (c in s.cover) out.write("  Deckung ${c.id}: ${box(c.box)} schützt ${c.protects.sorted()} ${if (c.breakable) "verbrauchbar" else "fest"}\n")
            for (l in s.levels) out.write("  Ebene ${l.id}: y ${l.floorY}, ${l.zone.cells().count()} Felder, Zugang ${l.access.take(3).joinToString { "${it.x} ${it.y} ${it.z}" }}${if (l.access.size > 3) " … (${l.access.size})" else ""}\n")
            for (p in s.parts) out.write("  Teil ${p.id}: y ${p.minY} … ${p.maxY}, ${p.zone.cells().count()} Spalten${if (p.fills) ", füllt" else ""}\n")
            for (d in s.devices) out.write("  Gerät ${d.id}: ${d.pos.x} ${d.pos.y} ${d.pos.z} ${net.minecraft.registry.Registries.BLOCK.getId(d.block()).path} ${d.facing.asString()}\n")
        }
        val approaches = dev.henny.hha.world.olymp.OlympusArenas.APPROACHES + dev.henny.hha.world.TartarusArenas.palaceApproach(net.minecraft.util.math.BlockPos(0, 72, 0))
        for (a in approaches) {
            out.write("== Weg ${a.id} (${a.bossKey}), halbe Breite ${a.halfWidth}\n")
            for (w in a.points) out.write("  ${w.id}: ${"%.1f".format(java.util.Locale.ROOT, w.pos.x)} ${"%.1f".format(java.util.Locale.ROOT, w.pos.y)} ${"%.1f".format(java.util.Locale.ROOT, w.pos.z)} (r ${w.reach})\n")
        }
        out.close()
    }


    private fun overhaul4() {
        // Forum: alle vier Stages.
        for (stage in 1..4) {
            val box = dev.henny.hha.world.olymp.ForumPlan.box(forumTest)
            val canvas = SkyMemory(box.minX, box.maxX, box.minZ, box.maxZ, forumTest.y)
            dev.henny.hha.world.olymp.ForumPlan.draw(canvas, forumTest, stage, emptySet())
            checkBlocks("forum_$stage", canvas)
            canvas.write("forum_$stage")
            grids["forum_$stage"] = canvas.grid("forum_$stage")
        }
        // Schnitt durch den Palast-Anmarsch: dz +40 … +120, |dx| ≤ 20.
        grids["palace"]?.let { palace ->
            palace.without("palace_approach") { x, _, z -> kotlin.math.abs(x) > 20 || z !in 40..120 }.write("plan_palace_approach.txt")
        }
        // Verträge: Olymp-Arenen auf den Inselplänen, Forum je Stage, Tartaros-Kerne auf den vollen Bauplänen.
        val layout = dev.henny.hha.world.olymp.OlympusLayout
        val checks = ArrayList<Triple<dev.henny.hha.world.arena.ArenaSpec, Grid, net.minecraft.util.math.BlockPos?>>()
        for ((letter, spec) in listOf('C' to dev.henny.hha.world.olymp.OlympusArenas.C, 'D' to dev.henny.hha.world.olymp.OlympusArenas.D, 'E' to dev.henny.hha.world.olymp.OlympusArenas.E, 'F' to dev.henny.hha.world.olymp.OlympusArenas.F)) {
            checks.add(Triple(spec, grids.getValue("olymp_$letter"), layout.ISLES.first { it.letter == letter }.altar))
        }
        val forum = dev.henny.hha.world.olymp.OlympusArenas.FORUM(forumTest)
        val tartaros = tartarosCenters.map { (name, t) -> name to dev.henny.hha.world.TartarusArenas.spec(t.first, t.second, t.third) }
        val all = checks.map { it.first } + forum + tartaros.map { it.second }
        writeContract(all)
        val scope: (String) -> Boolean = { only == null || it == only }
        for ((spec, g, altar) in checks) if (scope(spec.id)) {
            checkSpec(spec, g, 24, coreHard = true, altar = altar)
            g.write("plan_arena_${spec.id}.txt", spec.box)
            checkLost(spec, g, spec.parts, "lost")
            if (spec.id == "olymp_f") checkLost(spec, g, spec.parts.filter { it.id == "floe_0" || it.id == "floe_3" }, "lost03")
        }
        if (scope(forum.id)) {
            for (stage in 1..4) checkSpec(forum, grids.getValue("forum_$stage"), 24, coreHard = false)
            grids.getValue("forum_1").write("plan_arena_forum.txt", forum.box)
            checkLost(forum, grids.getValue("forum_1"), forum.parts, "lost")
        }
        for ((name, spec) in tartaros) if (scope(spec.id)) {
            val g = grids.getValue(name)
            checkSpec(spec, g, 4, coreHard = false)
            g.write("plan_arena_${spec.id}.txt", spec.box)
            checkLost(spec, g, spec.parts, "lost")
        }
        // Bauplan-Selbsttest
        for (id in dev.henny.hha.world.arena.ArenaPlans.IDS) {
            if (!scope(id)) continue
            val origin = when {
                id == "forum" -> forumTest
                id.startsWith("olymp_") -> net.minecraft.util.math.BlockPos.ORIGIN
                else -> tartaros.first { it.second.id == id }.let { tartarosCenters.getValue(it.first).third }
            }
            selfTest(id, origin)
        }
        checkFloating()
        // Zusammenfassung: Warnungen je Arena; mit -Dhha.verify.strict=true sind sie Fehler.
        val count = warnings.values.sumOf { it.size }
        for ((arena, list) in warnings) {
            println("HHA-VERIFY Warnungen $arena (${list.size}):")
            for (line in list) println("HHA-VERIFY   - $line")
        }
        println("HHA-VERIFY Vertragsprüfung: $count Warnungen in ${warnings.size} Arenen${if (only != null) " (nur $only)" else ""}${if (strict) ", streng" else ", Modus melden"}")
        if (strict) require(count == 0) { "Vertragsprüfung streng: $count Warnungen (${warnings.keys})" }
    }

    private fun check(server: MinecraftServer) {
        // Mixins erzwingen: So fällt ein falsches Ziel hier auf und nicht erst bei der ersten Explosion.
        Class.forName("net.minecraft.world.explosion.ExplosionImpl")
        val registries = server.registryManager
        val biomes = registries.getOrThrow(RegistryKeys.BIOME)
        for (name in listOf("olympus", "hades", "tartaros", "asphodel", "phlegethon", "kokytos", "erebos", "styx_marsh", "elysian_meadow", "marble_heights")) {
            require(biomes.containsId(Identifier.of("hha", name))) { "Biom fehlt: $name" }
        }
        val structures = registries.getOrThrow(RegistryKeys.STRUCTURE)
        // Altlasten (Typen und JSONs bleiben für alte Welten) und die neuen Strukturen des Olymp.
        for (name in listOf("olympus_temple", "hades_hall", "olympus_path", "sky_islet")) {
            require(structures.containsId(Identifier.of("hha", name))) { "Struktur fehlt: $name" }
        }
        for (name in listOf("tartaros", "olympus")) {
            require(registries.getOrThrow(RegistryKeys.DIMENSION).containsId(Identifier.of("hha", name))) { "Dimension fehlt: $name" }
        }

        // Überarbeitung 2: Olymp und Hades entstehen in der Oberwelt nicht mehr.
        val lists = registries.getOrThrow(RegistryKeys.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
        val entries = lists.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD).value().entries.entries.map { it.second }
        val keys = entries.mapNotNull { it.key.orElse(null) }.toSet()
        require(HhaBiomes.OLYMPUS !in keys) { "Olymp steht noch in der Oberwelt" }
        require(HhaBiomes.HADES !in keys) { "Hades steht noch in der Oberwelt" }
        val structureSets = registries.getOrThrow(RegistryKeys.STRUCTURE_SET)
        for (name in listOf("olympus_temples", "hades_halls")) require(!structureSets.containsId(Identifier.of("hha", name))) { "Struktur-Set der Oberwelt noch da: $name" }
        println("HHA-VERIFY Oberwelt: ${keys.size} Biome, kein Olymp, kein Hades, keine Tempel und Hallen")
        checkOlympus(server)
        checkPortals()
        // Selbsttest des Regelwerks (P03): leere Liste = bestanden. (PZ1, Wunsch P03 1.)
        val rules = dev.henny.hha.logic.boss.ArenaRulesCheck.run()
        require(rules.isEmpty()) { "Selbsttest Regelwerk (P03): $rules" }
        println("HHA-VERIFY Regelwerk (P03): Selbsttest bestanden (${dev.henny.hha.logic.boss.ArenaRulesCheck.CASES} Fälle)")

        // Dieselbe Prüfung wie beim Erzeugen des Chunk-Generators: Widersprüche in der Feature-Reihenfolge wären ein Absturz.
        PlacedFeatureIndexer.collectIndexedFeatures(entries.distinct(), { it.value().generationSettings.features }, true)
        println("HHA-VERIFY Feature-Reihenfolge der Oberwelt in Ordnung")

        // Tags müssen auf echte Einträge zeigen.
        val tagged = structures.iterateEntries(dev.henny.hha.world.HhaWorldgen.HADES_GATE_TAG).toList()
        require(tagged.isNotEmpty()) { "Struktur-Tag hades_gate ist leer" }
        val fire = net.minecraft.registry.Registries.BLOCK.getEntry(dev.henny.hha.HhaBlocks.GREEN_FIRE)
        require(fire.isIn(net.minecraft.registry.tag.BlockTags.FIRE)) { "Grünes Feuer fehlt im Fire-Tag" }
        println("HHA-VERIFY Tags in Ordnung")
        if (System.getProperty("hha.benchLocate") != null) benchLocate(server)
        if (System.getProperty("hha.mapTartaros") != null) mapTartaros(server)
        dumpPlans()
        dumpOlympus()
        overhaul4()
    }
}
