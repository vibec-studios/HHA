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
        }
        // Olymp-Tempel: auf einem Gipfel, die Treppe läuft nach Süden den Hang hinab.
        val temple = dev.henny.hha.world.OlympusTemplePiece
        val templeCanvas = MemoryCanvas(-temple.REACH, temple.REACH, -temple.REACH, temple.REACH + temple.STAIRS, 160) { x, z ->
            // Ein Gipfel: oben flach, nach außen fällt der Hang ab.
            166 - (maxOf(0.0, kotlin.math.hypot(x.toDouble(), z.toDouble()) - 18.0) * 0.9).toInt()
        }
        dev.henny.hha.world.OlympusTemplePlan.draw(templeCanvas, net.minecraft.util.math.BlockPos(0, 166, 0))
        templeCanvas.write("temple")
        // Hades-Halle tief unten, der Schacht führt zur Oberfläche bei y = 70.
        val hall = dev.henny.hha.world.HadesHallPiece
        val floor = dev.henny.hha.logic.world.HadesGate.FLOOR_Y
        val hallCanvas = MemoryCanvas(-hall.REACH, hall.REACH, -hall.REACH, hall.SHAFT_Z + hall.SHAFT_R + hall.SHRINE_EXTRA, 70, wavy = false)
        dev.henny.hha.world.HadesHallPlan.draw(hallCanvas, net.minecraft.util.math.BlockPos(0, floor, 0), 70)
        hallCanvas.write("hall", terrain = false)
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

    /** Nur Vanilla-Blöcke außer den Funktionsblöcken (PLAN 8.3). */
    private fun checkVanilla(name: String, canvas: SkyMemory) {
        val foreign = canvas.blocks.values.map { net.minecraft.registry.Registries.BLOCK.getId(it.block) }
            .filter { it.namespace != "minecraft" && it.path !in olympusAllowed }.toSet()
        require(foreign.isEmpty()) { "Olymp-Plan $name enthält fremde Blöcke: $foreign" }
    }

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

    /**
     * Jede Kampffläche ist in ihrem Radius eben und frei: Boden ein voller Block, darüber bis 23 hoch nur Luft, außer
     * Hekataion, Amboss, Sockeln, Pfeilern und dem Altar (PLAN 12.2, Abnahme P04).
     */
    private fun checkArena(isle: dev.henny.hha.world.olymp.OlympusLayout.Isle, canvas: SkyMemory) {
        if (isle.arenaRadius <= 0) return
        val layout = dev.henny.hha.world.olymp.OlympusLayout
        val cx = isle.center.x
        val cz = isle.center.z
        val fy = isle.floorY
        fun allowed(x: Int, h: Int, z: Int): Boolean {
            val altar = isle.altar
            if (altar != null && x == altar.x && z == altar.z && fy + h == altar.y) return true
            return when (isle.letter) {
                'C' -> kotlin.math.abs(x - cx) <= layout.HEKATAION_HALF && kotlin.math.abs(z - cz) <= layout.HEKATAION_HALF && h < layout.HEKATAION_HEIGHT
                'D' -> kotlin.math.abs(x - layout.ANVIL.x) <= layout.ANVIL_HALF && kotlin.math.abs(z - layout.ANVIL.z) <= layout.ANVIL_HALF && h < layout.ANVIL_HEIGHT
                'E' -> listOf(layout.PLINTH_LEFT, layout.PLINTH_RIGHT).any { kotlin.math.abs(x - it.x) <= layout.PLINTH_HALF && kotlin.math.abs(z - it.z) <= layout.PLINTH_HALF && h < layout.PLINTH_HEIGHT }
                'F' -> layout.PILLARS.any { it.x == x && it.z == z && h <= layout.PILLAR_HEIGHT }
                else -> false
            }
        }
        val problems = ArrayList<String>()
        var cells = 0
        for (x in cx - isle.arenaRadius..cx + isle.arenaRadius) for (z in cz - isle.arenaRadius..cz + isle.arenaRadius) {
            if (kotlin.math.hypot((x - cx).toDouble(), (z - cz).toDouble()) > isle.arenaRadius) continue
            cells++
            val floor = canvas.state(x, fy - 1, z)
            val shape = floor.getCollisionShape(net.minecraft.world.EmptyBlockView.INSTANCE, net.minecraft.util.math.BlockPos.ORIGIN)
            if (!net.minecraft.block.Block.isShapeFullCube(shape)) problems.add("Boden $x ${fy - 1} $z: ${net.minecraft.registry.Registries.BLOCK.getId(floor.block).path}")
            for (h in 0 until 24) {
                val state = canvas.state(x, fy + h, z)
                if (!state.isAir && !allowed(x, h, z)) problems.add("frei? $x ${fy + h} $z: ${net.minecraft.registry.Registries.BLOCK.getId(state.block).path}")
            }
        }
        require(problems.isEmpty()) { "Kampffläche ${isle.letter} nicht eben/frei (${problems.size}): ${problems.take(12)}" }
        println("HHA-VERIFY Kampffläche ${isle.letter}: Radius ${isle.arenaRadius}, $cells Felder eben und frei")
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
            checkVanilla("isle_${isle.letter}", canvas)
            checkArena(isle, canvas)
            canvas.write("olymp_${isle.letter}")
            fold(canvas)
        }
        for (bridge in dev.henny.hha.world.olymp.OlympusBridges.ALL) {
            val box = bridge.box
            val canvas = SkyMemory(box.minX, box.maxX, box.minZ, box.maxZ, bridge.y0)
            dev.henny.hha.world.olymp.OlympusBridges.draw(canvas, bridge, dev.henny.hha.world.olymp.OlympusBridges.Part.OUTSIDE)
            checkVanilla("bridge_${bridge.id}", canvas)
            fold(canvas)
            if (bridge.id == "ab" || bridge.id == "cd") {
                val whole = SkyMemory(box.minX, box.maxX, box.minZ, box.maxZ, bridge.y0)
                dev.henny.hha.world.olymp.OlympusBridges.draw(whole, bridge, dev.henny.hha.world.olymp.OlympusBridges.Part.ALL)
                whole.write("olymp_bridge_${bridge.id}")
            }
            if (bridge.id == "cd") {
                // Die Siegelbrücke nach dem Nachsetzen: so sieht sie aus, wenn Hermes besiegt ist.
                val open = SkyMemory(box.minX, box.maxX, box.minZ, box.maxZ, bridge.y0)
                dev.henny.hha.world.olymp.OlympusBridges.draw(open, bridge, dev.henny.hha.world.olymp.OlympusBridges.Part.ALL, sealed = false)
                val marker = bridge.marker()!!
                require(open.state(marker.x, marker.y, marker.z).isOf(net.minecraft.block.Blocks.CHISELED_QUARTZ_BLOCK)) { "Markierblock der Siegelbrücke fehlt" }
                open.write("olymp_bridge_cd_open")
            }
        }
        for ((i, kind) in dev.henny.hha.world.olymp.SkyIslets.Kind.entries.withIndex()) {
            val center = net.minecraft.util.math.BlockPos(0, 150, 0)
            val box = dev.henny.hha.world.olymp.SkyIslets.box(kind, center)
            val canvas = SkyMemory(box.minX, box.maxX, box.minZ, box.maxZ, center.y)
            dev.henny.hha.world.olymp.SkyIslets.draw(canvas, kind, center, 4242 + i * 17)
            checkVanilla("islet_$kind", canvas)
            canvas.write("olymp_islet_${kind.name.lowercase()}")
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
    }
}
