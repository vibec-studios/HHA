package dev.henny.hha.world

import com.mojang.datafixers.util.Pair
import dev.henny.hha.Hha
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.tag.TagKey
import net.minecraft.structure.StructureContext
import net.minecraft.structure.StructurePieceType
import net.minecraft.world.biome.Biome
import net.minecraft.world.biome.BiomeKeys
import net.minecraft.world.biome.source.util.MultiNoiseUtil
import net.minecraft.world.gen.structure.Structure
import net.minecraft.world.gen.structure.StructureType
import java.util.function.Consumer

/** Biom-Einträge für die Oberwelt; wird von VanillaBiomeParametersMixin benutzt und muss ohne Mod-Start laufen. */
object HhaBiomes {

    @JvmField val OLYMPUS: RegistryKey<Biome> = RegistryKey.of(RegistryKeys.BIOME, Hha.id("olympus"))
    @JvmField val HADES: RegistryKey<Biome> = RegistryKey.of(RegistryKeys.BIOME, Hha.id("hades"))

    /**
     * Die warmen, steinigen Gipfel der Oberwelt werden zum Olymp: Berge formt in Minecraft das Gelände-Rauschen, nicht
     * das Biom, und genau diese Einträge sitzen auf den höchsten warmen Graten. Das Hades-Biom kommt als eigener
     * Höhlen-Eintrag dazu, in der Tiefe zwischen Tiefenhöhle und Oberfläche, nur in trockenen und nicht zu kalten Gegenden.
     */
    @JvmStatic
    fun wrap(original: Consumer<Pair<MultiNoiseUtil.NoiseHypercube, RegistryKey<Biome>>>): Consumer<Pair<MultiNoiseUtil.NoiseHypercube, RegistryKey<Biome>>> {
        original.accept(
            Pair.of(
                MultiNoiseUtil.createNoiseHypercube(
                    MultiNoiseUtil.ParameterRange.of(-0.45f, 1f),
                    MultiNoiseUtil.ParameterRange.of(-1f, 0.1f),
                    MultiNoiseUtil.ParameterRange.of(-0.19f, 0.8f),
                    MultiNoiseUtil.ParameterRange.of(-1f, 1f),
                    MultiNoiseUtil.ParameterRange.of(0.55f, 1.05f),
                    MultiNoiseUtil.ParameterRange.of(-1f, 1f),
                    0f,
                ),
                HADES,
            ),
        )
        return Consumer { pair ->
            if (pair.second == BiomeKeys.STONY_PEAKS) original.accept(Pair.of(pair.first, OLYMPUS)) else original.accept(pair)
        }
    }
}

/** Registriert Strukturen und Weltgenerierungs-Features; die Daten selbst liegen unter data/hha/worldgen. */
object HhaWorldgen {

    val HADES_GATE_TAG: TagKey<Structure> = TagKey.of(RegistryKeys.STRUCTURE, Hha.id("hades_gate"))

    val HADES_PALACE_TAG: TagKey<Structure> = TagKey.of(RegistryKeys.STRUCTURE, Hha.id("hades_palace"))

    lateinit var TARTARUS_PIECE: StructurePieceType; private set
    private val tartarusTypes = HashMap<TartarusKind, StructureType<TartarusStructure>>()

    fun tartarusType(kind: TartarusKind): StructureType<TartarusStructure> = tartarusTypes.getValue(kind)

    lateinit var TEMPLE_PIECE: StructurePieceType; private set
    lateinit var HALL_PIECE: StructurePieceType; private set
    lateinit var TEMPLE_TYPE: StructureType<OlympusTempleStructure>; private set
    lateinit var HALL_TYPE: StructureType<HadesHallStructure>; private set

    fun init() {
        TEMPLE_PIECE = Registry.register(Registries.STRUCTURE_PIECE, Hha.id("olympus_temple"), StructurePieceType { _: StructureContext, nbt: NbtCompound -> OlympusTemplePiece(nbt) })
        HALL_PIECE = Registry.register(Registries.STRUCTURE_PIECE, Hha.id("hades_hall"), StructurePieceType { _: StructureContext, nbt: NbtCompound -> HadesHallPiece(nbt) })
        TEMPLE_TYPE = Registry.register(Registries.STRUCTURE_TYPE, Hha.id("olympus_temple"), StructureType { OlympusTempleStructure.CODEC })
        HALL_TYPE = Registry.register(Registries.STRUCTURE_TYPE, Hha.id("hades_hall"), StructureType { HadesHallStructure.CODEC })
        TARTARUS_PIECE = Registry.register(Registries.STRUCTURE_PIECE, Hha.id("tartarus"), StructurePieceType { _: StructureContext, nbt: NbtCompound -> TartarusPiece(nbt) })
        for (kind in TartarusKind.entries) {
            val codec = TartarusStructure.codec(kind)
            tartarusTypes[kind] = Registry.register(Registries.STRUCTURE_TYPE, Hha.id(kind.id), StructureType { codec })
        }
        Registry.register(Registries.FEATURE, Hha.id("olympus_surface"), OlympusSurfaceFeature())
        Registry.register(Registries.FEATURE, Hha.id("ruined_column"), RuinedColumnFeature())
        Registry.register(Registries.FEATURE, Hha.id("hades_surface"), HadesSurfaceFeature())
        Registry.register(Registries.FEATURE, Hha.id("tartaros_ruin"), TartarosRuinFeature())
        Registry.register(Registries.FEATURE, Hha.id("tartaros_carcass"), TartarosCarcassFeature())
        Registry.register(Registries.FEATURE, Hha.id("tartaros_dead_tree"), TartarosDeadTreeFeature())
        Registry.register(Registries.FEATURE, Hha.id("cold_shade"), ColdShadeFeature())
        Registry.register(Registries.FEATURE, Hha.id("phlegethon_vent"), PhlegethonVentFeature())
        Registry.register(Registries.FEATURE, Hha.id("kokytos_shard"), KokytosShardFeature())
        Registry.register(Registries.FEATURE, Hha.id("erebos_stele"), ErebosSteleFeature())
        Registry.register(Registries.FEATURE, Hha.id("marsh_stump"), MarshStumpFeature())
    }
}
