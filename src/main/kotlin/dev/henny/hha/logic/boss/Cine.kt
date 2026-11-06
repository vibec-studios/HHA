package dev.henny.hha.logic.boss

import dev.henny.hha.logic.Vfx
import dev.henny.hha.net.CinePayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.Entity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.Vec3d

/**
 * Befehle an die Cutscene-Wiedergabe des Clients (`client/cine/CinePlayer`). Alle Inhalte kommen vom
 * Server; der Client spielt nur ab. Die Opcodes sind das Protokoll, deshalb nur hinten anfügen.
 */
object Cine {

    const val OP_START = 0
    const val OP_CAMERA = 1
    const val OP_DIALOG = 2
    const val OP_FADE = 3
    const val OP_TITLE = 4
    const val OP_END = 5
    const val OP_ATMOSPHERE = 6
    const val OP_WINGS = 7
    const val OP_ORBIT = 8
    const val OP_MUSIC = 9
    const val OP_CLEAR_VFX = 10

    /** Sprecher für die Textbox und die Stimmlage der Dialog-Laute. */
    const val SPEAKER_NARRATOR = 0
    const val SPEAKER_HERMES = 1
    const val SPEAKER_ZEUS = 2
    const val SPEAKER_SHADE = 3

    fun start(players: Iterable<ServerPlayerEntity>) = send(players, OP_START)

    fun end(players: Iterable<ServerPlayerEntity>) = send(players, OP_END)

    /** Gerade Kamerafahrt von [from] nach [to]; der Blick wandert von [lookFrom] nach [lookTo] oder folgt [lookAt]. */
    fun camera(
        players: Iterable<ServerPlayerEntity>, from: Vec3d, to: Vec3d, lookFrom: Vec3d, lookTo: Vec3d, ticks: Int,
        fovFrom: Double = 70.0, fovTo: Double = 70.0, lookAt: Entity? = null, lookOffset: Double = 1.6,
    ) = send(
        players, OP_CAMERA,
        listOf(
            from.x, from.y, from.z, to.x, to.y, to.z,
            lookFrom.x, lookFrom.y, lookFrom.z, lookTo.x, lookTo.y, lookTo.z,
            ticks.toDouble(), fovFrom, fovTo, (lookAt?.id ?: -1).toDouble(), lookOffset,
        ),
    )

    /** Kamerakreis um [center]; Winkel in Grad, [lookAt] wird angeschaut (sonst die Mitte). */
    fun orbit(
        players: Iterable<ServerPlayerEntity>, center: Vec3d, radius: Double, height: Double, angleFrom: Double, angleTo: Double,
        ticks: Int, fovFrom: Double = 70.0, fovTo: Double = 70.0, lookAt: Entity? = null, lookOffset: Double = 1.6,
        radiusTo: Double = radius, heightTo: Double = height,
    ) = send(
        players, OP_ORBIT,
        listOf(
            center.x, center.y, center.z, radius, radiusTo, height, heightTo, angleFrom, angleTo,
            ticks.toDouble(), fovFrom, fovTo, (lookAt?.id ?: -1).toDouble(), lookOffset,
        ),
    )

    fun dialog(players: Iterable<ServerPlayerEntity>, speaker: Int, lineKey: String, ticks: Int) =
        send(players, OP_DIALOG, listOf(speaker.toDouble(), ticks.toDouble()), listOf(lineKey))

    /** Blende: Farbe (0xRRGGBB) von Deckkraft [from] nach [to]. */
    fun fade(players: Iterable<ServerPlayerEntity>, color: Int, from: Double, to: Double, ticks: Int) =
        send(players, OP_FADE, listOf(color.toDouble(), from, to, ticks.toDouble()))

    fun title(players: Iterable<ServerPlayerEntity>, titleKey: String, subtitleKey: String, ticks: Int) =
        send(players, OP_TITLE, listOf(ticks.toDouble()), listOf(titleKey, subtitleKey))

    fun atmosphere(players: Iterable<ServerPlayerEntity>, style: Vfx.Style?, center: Vec3d, ticks: Int) = send(
        players, OP_ATMOSPHERE,
        listOf((style?.ordinal ?: -1).toDouble(), center.x, center.y, center.z, ticks.toDouble()),
    )

    /** Startet ein Musikstück in Dauerschleife, `null` stoppt die Musik. */
    fun music(players: Iterable<ServerPlayerEntity>, event: SoundEvent?) =
        send(players, OP_MUSIC, emptyList(), listOf(event?.id()?.toString() ?: ""))

    /** Beendet alle laufenden 3D-Effekte (z. B. Flügel und Auren, die eigentlich länger halten). */
    fun clearVfx(players: Iterable<ServerPlayerEntity>) = send(players, OP_CLEAR_VFX)

    fun wings(players: Iterable<ServerPlayerEntity>, on: Boolean) =
        send(players, OP_WINGS, listOf(if (on) 1.0 else 0.0))

    private fun send(players: Iterable<ServerPlayerEntity>, op: Int, numbers: List<Double> = emptyList(), texts: List<String> = emptyList()) {
        val payload = CinePayload(op, numbers, texts)
        for (player in players) ServerPlayNetworking.send(player, payload)
    }
}
