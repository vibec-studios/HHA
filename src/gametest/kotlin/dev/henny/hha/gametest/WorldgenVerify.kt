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

    /** Schreibt die Geländehöhen des Tartaros als Bild, um die Form zu prüfen, ohne Chunks zu erzeugen. */
    private fun mapTartaros(server: MinecraftServer) {
        val registries = server.registryManager
        val key = net.minecraft.registry.RegistryKey.of(RegistryKeys.DIMENSION, Identifier.of("hha", "tartaros"))
        val generator = registries.getOrThrow(RegistryKeys.DIMENSION).getOrThrow(key).value().chunkGenerator() as net.minecraft.world.gen.chunk.NoiseChunkGenerator
        val config = net.minecraft.world.gen.noise.NoiseConfig.create(generator.settings.value(), registries.getOrThrow(RegistryKeys.NOISE_PARAMETERS), 12345L)
        val world = net.minecraft.world.HeightLimitView.create(0, 256)
        val size = 150
        val out = StringBuilder()
        var low = Int.MAX_VALUE
        var high = Int.MIN_VALUE
        for (z in 0 until size) {
            for (x in 0 until size) {
                val h = generator.getHeightOnGround(x * 2, z * 2, net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG, world, config)
                low = minOf(low, h)
                high = maxOf(high, h)
                out.append(h)
                if (x == size - 1) out.appendLine() else out.append(" ")
            }
        }
        java.io.File("tartaros_heights.txt").writeText(out.toString())
        println("HHA-VERIFY Tartaros-Höhen: $low bis $high")
    }

    private fun check(server: MinecraftServer) {
        // Mixins erzwingen: So fällt ein falsches Ziel hier auf und nicht erst bei der ersten Explosion.
        Class.forName("net.minecraft.world.explosion.ExplosionImpl")
        val registries = server.registryManager
        val biomes = registries.getOrThrow(RegistryKeys.BIOME)
        for (name in listOf("olympus", "hades", "tartaros")) {
            require(biomes.containsId(Identifier.of("hha", name))) { "Biom fehlt: $name" }
        }
        val structures = registries.getOrThrow(RegistryKeys.STRUCTURE)
        for (name in listOf("olympus_temple", "hades_hall")) {
            require(structures.containsId(Identifier.of("hha", name))) { "Struktur fehlt: $name" }
        }
        require(registries.getOrThrow(RegistryKeys.DIMENSION).containsId(Identifier.of("hha", "tartaros"))) { "Dimension fehlt" }

        // Sitzen die neuen Biome wirklich in der Oberwelt-Verteilung?
        val lists = registries.getOrThrow(RegistryKeys.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
        val entries = lists.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD).value().entries.entries.map { it.second }
        val keys = entries.mapNotNull { it.key.orElse(null) }.toSet()
        require(HhaBiomes.OLYMPUS in keys) { "Olymp nicht in der Oberwelt" }
        require(HhaBiomes.HADES in keys) { "Hades nicht in der Oberwelt" }
        println("HHA-VERIFY Oberwelt: ${keys.size} Biome, ${entries.count { it.matchesKey(HhaBiomes.OLYMPUS) }} Olymp-Einträge")

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
    }
}
