package dev.henny.hha

import dev.henny.hha.entity.HeavensMaceEntity
import dev.henny.hha.entity.boss.DivineParcelEntity
import dev.henny.hha.entity.boss.HermaEntity
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.SerpentEntity
import dev.henny.hha.entity.boss.SerpentPartEntity
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnGroup
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys

object HhaEntities {

    private val HEAVENS_MACE_KEY: RegistryKey<EntityType<*>> =
        RegistryKey.of(RegistryKeys.ENTITY_TYPE, Hha.id("heavens_mace"))

    val HEAVENS_MACE: EntityType<HeavensMaceEntity> = Registry.register(
        Registries.ENTITY_TYPE,
        HEAVENS_MACE_KEY,
        EntityType.Builder.create({ type: EntityType<HeavensMaceEntity>, world -> HeavensMaceEntity(type, world) }, SpawnGroup.MISC)
            .dimensions(0.5f, 0.5f)
            .maxTrackingRange(64)
            .trackingTickInterval(2)
            .build(HEAVENS_MACE_KEY)
    )

    private fun <T : net.minecraft.entity.Entity> boss(
        name: String, width: Float, height: Float, group: SpawnGroup,
        factory: (EntityType<T>, net.minecraft.world.World) -> T,
    ): EntityType<T> {
        val key: RegistryKey<EntityType<*>> = RegistryKey.of(RegistryKeys.ENTITY_TYPE, Hha.id(name))
        return Registry.register(
            Registries.ENTITY_TYPE, key,
            EntityType.Builder.create({ type: EntityType<T>, world -> factory(type, world) }, group)
                .dimensions(width, height)
                .maxTrackingRange(32)
                .trackingTickInterval(1)
                .makeFireImmune()
                .build(key),
        )
    }

    val HERMES: EntityType<HermesEntity> = boss("hermes", 1.0f, 2.8f, SpawnGroup.MONSTER, ::HermesEntity)
    val SERPENT: EntityType<SerpentEntity> = boss("serpent", 1.2f, 1.2f, SpawnGroup.MONSTER, ::SerpentEntity)
    val SERPENT_PART: EntityType<SerpentPartEntity> = boss("serpent_part", 1.4f, 1.4f, SpawnGroup.MISC, ::SerpentPartEntity)
    val HERMA: EntityType<HermaEntity> = boss("herma", 1.6f, 4.0f, SpawnGroup.MONSTER, ::HermaEntity)
    val DIVINE_PARCEL: EntityType<DivineParcelEntity> = boss("divine_parcel", 1.6f, 1.4f, SpawnGroup.MISC, ::DivineParcelEntity)
    val HERMES_BOLT: EntityType<HermesBoltEntity> = boss("hermes_bolt", 0.5f, 0.5f, SpawnGroup.MISC, ::HermesBoltEntity)

    fun init() {
        FabricDefaultAttributeRegistry.register(HERMES, HermesEntity.attributes())
        FabricDefaultAttributeRegistry.register(SERPENT, SerpentEntity.attributes())
        FabricDefaultAttributeRegistry.register(HERMA, HermaEntity.attributes())
    }
}
