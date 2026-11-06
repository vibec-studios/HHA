package dev.henny.hha.client.particle

import net.minecraft.client.particle.AnimatedParticle
import net.minecraft.client.particle.BillboardParticle
import net.minecraft.client.particle.SpriteProvider
import net.minecraft.client.world.ClientWorld
import kotlin.math.cos
import kotlin.math.sin

/**
 * Funken-Partikel (Holy Spark, Ember Spark, Hellfire): spielt seine Frames
 * über die Lebenszeit ab, schwebt sanft und leuchtet auf Wunsch vollhell.
 */
class SparkleParticle(
    world: ClientWorld,
    x: Double, y: Double, z: Double,
    velocityX: Double, velocityY: Double, velocityZ: Double,
    spriteProvider: SpriteProvider,
    baseMaxAge: Int,
    scaleMultiplier: Float,
    upwardsAcceleration: Float,
    private val fullBright: Boolean,
) : AnimatedParticle(world, x, y, z, spriteProvider, upwardsAcceleration) {

    init {
        this.velocityX = velocityX + (random.nextDouble() - 0.5) * 0.02
        this.velocityY = velocityY + random.nextDouble() * 0.01
        this.velocityZ = velocityZ + (random.nextDouble() - 0.5) * 0.02
        setMaxAge(baseMaxAge + random.nextInt(baseMaxAge / 2 + 1))
        scale(scaleMultiplier)
        gravityStrength = 0.0f
        velocityMultiplier = 0.94f
        collidesWithWorld = false
        updateSprite(spriteProvider)
    }

    override fun getBrightness(tint: Float): Int =
        if (fullBright) FULL_BRIGHT else super.getBrightness(tint)

    companion object {
        const val FULL_BRIGHT = 0xF000F0
    }
}

/**
 * Feder-Partikel: fällt langsam, pendelt seitlich und dreht sich dabei
 * leicht — wie eine echte Feder.
 */
class FeatherParticle(
    world: ClientWorld,
    x: Double, y: Double, z: Double,
    spriteProvider: SpriteProvider,
) : BillboardParticle(world, x, y, z, spriteProvider.getFirst()) {

    private val swayPhase = random.nextDouble() * Math.PI * 2.0
    private val swaySpeed = 0.14 + random.nextDouble() * 0.08

    init {
        setSprite(spriteProvider.getSprite(random))
        setMaxAge(50 + random.nextInt(30))
        scale(1.5f)
        velocityX = 0.0
        velocityY = -0.02
        velocityZ = 0.0
        collidesWithWorld = true
    }

    override fun getRenderType(): RenderType = RenderType.PARTICLE_ATLAS_TRANSLUCENT

    override fun tick() {
        lastX = x
        lastY = y
        lastZ = z
        if (age++ >= maxAge) {
            markDead()
            return
        }
        val t = age * swaySpeed + swayPhase
        velocityX = sin(t) * 0.02
        velocityZ = cos(t * 0.7) * 0.015
        velocityY = (velocityY - 0.001).coerceAtLeast(-0.045)
        move(velocityX, velocityY, velocityZ)
        if (onGround) {
            velocityX = 0.0
            velocityZ = 0.0
        }
        lastZRotation = zRotation
        zRotation = (sin(t) * 0.45).toFloat()
        if (age > maxAge - 8) {
            setAlpha((maxAge - age) / 8.0f)
        }
    }
}

/**
 * Kettenglied: steht still an seiner Position, zufällige Glied-Orientierung
 * beim Spawn (kein Frame-Flackern), kurzes Leben mit Ausblenden — die Kette
 * wird durch dichtes Nachspawnen entlang der Linie gebildet.
 */
class ChainLinkParticle(
    world: ClientWorld,
    x: Double, y: Double, z: Double,
    spriteProvider: SpriteProvider,
) : BillboardParticle(world, x, y, z, spriteProvider.getFirst()) {

    init {
        setSprite(spriteProvider.getSprite(random))
        setMaxAge(10)
        scale(0.8f)
        velocityX = 0.0
        velocityY = 0.0
        velocityZ = 0.0
        collidesWithWorld = false
    }

    override fun getRenderType(): RenderType = RenderType.PARTICLE_ATLAS_TRANSLUCENT

    override fun tick() {
        lastX = x
        lastY = y
        lastZ = z
        if (age++ >= maxAge) {
            markDead()
            return
        }
        if (age > maxAge - 4) {
            setAlpha((maxAge - age) / 4.0f)
        }
    }
}

/**
 * Burst-Partikel (Divine Flash, Infernal Burst): steht still, spielt seine
 * Frames einmal groß und vollhell ab — für Einschläge und Trigger-Momente.
 */
class BurstParticle(
    world: ClientWorld,
    x: Double, y: Double, z: Double,
    spriteProvider: SpriteProvider,
) : AnimatedParticle(world, x, y, z, spriteProvider, 0.0f) {

    init {
        setMaxAge(10)
        scale(4.0f)
        velocityX = 0.0
        velocityY = 0.0
        velocityZ = 0.0
        gravityStrength = 0.0f
        velocityMultiplier = 1.0f
        collidesWithWorld = false
        updateSprite(spriteProvider)
    }

    override fun getBrightness(tint: Float): Int = SparkleParticle.FULL_BRIGHT
}
