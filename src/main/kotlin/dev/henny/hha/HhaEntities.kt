package dev.henny.hha

import dev.henny.hha.entity.HeavensMaceEntity
import dev.henny.hha.entity.ImpEntity
import dev.henny.hha.entity.miniboss.IxionEntity
import dev.henny.hha.entity.miniboss.KampeEntity
import dev.henny.hha.entity.miniboss.SisyphusEntity
import dev.henny.hha.entity.miniboss.TantalusEntity
import dev.henny.hha.entity.miniboss.WardenOfChainsEntity
import dev.henny.hha.entity.boss.DivineParcelEntity
import dev.henny.hha.entity.boss.HadesEntity
import dev.henny.hha.entity.boss.KronosEntity
import dev.henny.hha.entity.boss.CoreEntity
import dev.henny.hha.entity.boss.HermaEntity
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.SerpentEntity
import dev.henny.hha.entity.boss.olymp.BiaEntity
import dev.henny.hha.entity.boss.olymp.BrontesEntity
import dev.henny.hha.entity.boss.olymp.HekateEntity
import dev.henny.hha.entity.boss.olymp.KratosEntity
import dev.henny.hha.entity.boss.olymp.ZeusEntity
import dev.henny.hha.entity.mob.AutomatonEntity
import dev.henny.hha.entity.mob.GriffinEntity
import dev.henny.hha.entity.mob.KureteEntity
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
    val HADES: EntityType<HadesEntity> = boss("hades", 1.8f, 4.0f, SpawnGroup.MONSTER, ::HadesEntity)
    val HERMA: EntityType<HermaEntity> = boss("herma", 1.6f, 4.0f, SpawnGroup.MONSTER, ::HermaEntity)
    val DIVINE_PARCEL: EntityType<DivineParcelEntity> = boss("divine_parcel", 1.6f, 1.4f, SpawnGroup.MISC, ::DivineParcelEntity)
    val HERMES_BOLT: EntityType<HermesBoltEntity> = boss("hermes_bolt", 0.5f, 0.5f, SpawnGroup.MISC, ::HermesBoltEntity)
    val KRONOS: EntityType<dev.henny.hha.entity.boss.KronosEntity> = boss("kronos", 2.4f, 6.2f, SpawnGroup.MONSTER, ::KronosEntity)
    val CORE: EntityType<dev.henny.hha.entity.boss.CoreEntity> = boss("core", 1.6f, 2.6f, SpawnGroup.MISC, ::CoreEntity)
    // Wächter: Trefferboxen laut Plan 6.3, ohne Aufblasen über das SCALE-Attribut.
    val WARDEN_OF_CHAINS: EntityType<dev.henny.hha.entity.miniboss.WardenOfChainsEntity> = boss("warden_of_chains", 1.5f, 4.2f, SpawnGroup.MONSTER, ::WardenOfChainsEntity)
    val SISYPHUS: EntityType<dev.henny.hha.entity.miniboss.SisyphusEntity> = boss("sisyphus", 1.4f, 3.8f, SpawnGroup.MONSTER, ::SisyphusEntity)
    val IXION: EntityType<dev.henny.hha.entity.miniboss.IxionEntity> = boss("ixion", 2.2f, 3.2f, SpawnGroup.MONSTER, ::IxionEntity)
    val TANTALUS: EntityType<dev.henny.hha.entity.miniboss.TantalusEntity> = boss("tantalus", 1.2f, 3.9f, SpawnGroup.MONSTER, ::TantalusEntity)
    val KAMPE: EntityType<KampeEntity> = boss("kampe", 2.4f, 4.6f, SpawnGroup.MONSTER, ::KampeEntity)
    val IMP: EntityType<dev.henny.hha.entity.ImpEntity> = boss("imp", 0.6f, 0.95f, SpawnGroup.MONSTER, ::ImpEntity)

    // Überarbeitung 2 (PLAN 10.1/10.3): Bosse und Gegner des Olymp, Trefferboxen laut Plan.
    val HEKATE: EntityType<dev.henny.hha.entity.boss.olymp.HekateEntity> = boss("hekate", 1.2f, 3.2f, SpawnGroup.MONSTER, ::HekateEntity)
    val BRONTES: EntityType<dev.henny.hha.entity.boss.olymp.BrontesEntity> = boss("brontes", 2.6f, 5.4f, SpawnGroup.MONSTER, ::BrontesEntity)
    val KRATOS: EntityType<dev.henny.hha.entity.boss.olymp.KratosEntity> = boss("kratos", 1.8f, 4.4f, SpawnGroup.MONSTER, ::KratosEntity)
    val BIA: EntityType<dev.henny.hha.entity.boss.olymp.BiaEntity> = boss("bia", 1.4f, 4.0f, SpawnGroup.MONSTER, ::BiaEntity)
    val ZEUS: EntityType<dev.henny.hha.entity.boss.olymp.ZeusEntity> = boss("zeus", 1.8f, 5.0f, SpawnGroup.MONSTER, ::ZeusEntity)
    val KURETE: EntityType<dev.henny.hha.entity.mob.KureteEntity> = boss("kurete", 0.7f, 2.1f, SpawnGroup.MONSTER, ::KureteEntity)
    val GRIFFIN: EntityType<dev.henny.hha.entity.mob.GriffinEntity> = boss("griffin", 1.4f, 1.8f, SpawnGroup.MONSTER, ::GriffinEntity)
    val AUTOMATON: EntityType<dev.henny.hha.entity.mob.AutomatonEntity> = boss("automaton", 1.1f, 2.8f, SpawnGroup.MONSTER, ::AutomatonEntity)

    fun init() {
        FabricDefaultAttributeRegistry.register(HERMES, HermesEntity.attributes())
        FabricDefaultAttributeRegistry.register(SERPENT, SerpentEntity.attributes())
        FabricDefaultAttributeRegistry.register(HERMA, HermaEntity.attributes())
        FabricDefaultAttributeRegistry.register(HADES, HadesEntity.attributes())
        FabricDefaultAttributeRegistry.register(KRONOS, dev.henny.hha.entity.boss.KronosEntity.attributes())
        FabricDefaultAttributeRegistry.register(CORE, dev.henny.hha.entity.boss.CoreEntity.attributes())
        FabricDefaultAttributeRegistry.register(WARDEN_OF_CHAINS, dev.henny.hha.entity.miniboss.WardenOfChainsEntity.attributes())
        FabricDefaultAttributeRegistry.register(SISYPHUS, dev.henny.hha.entity.miniboss.SisyphusEntity.attributes())
        FabricDefaultAttributeRegistry.register(IXION, dev.henny.hha.entity.miniboss.IxionEntity.attributes())
        FabricDefaultAttributeRegistry.register(TANTALUS, dev.henny.hha.entity.miniboss.TantalusEntity.attributes())
        FabricDefaultAttributeRegistry.register(KAMPE, KampeEntity.attributes())
        FabricDefaultAttributeRegistry.register(IMP, dev.henny.hha.entity.ImpEntity.attributes())
        FabricDefaultAttributeRegistry.register(HEKATE, dev.henny.hha.entity.boss.olymp.HekateEntity.attributes())
        FabricDefaultAttributeRegistry.register(BRONTES, dev.henny.hha.entity.boss.olymp.BrontesEntity.attributes())
        FabricDefaultAttributeRegistry.register(KRATOS, dev.henny.hha.entity.boss.olymp.KratosEntity.attributes())
        FabricDefaultAttributeRegistry.register(BIA, dev.henny.hha.entity.boss.olymp.BiaEntity.attributes())
        FabricDefaultAttributeRegistry.register(ZEUS, dev.henny.hha.entity.boss.olymp.ZeusEntity.attributes())
        FabricDefaultAttributeRegistry.register(KURETE, dev.henny.hha.entity.mob.KureteEntity.attributes())
        FabricDefaultAttributeRegistry.register(GRIFFIN, dev.henny.hha.entity.mob.GriffinEntity.attributes())
        FabricDefaultAttributeRegistry.register(AUTOMATON, dev.henny.hha.entity.mob.AutomatonEntity.attributes())
    }
}
