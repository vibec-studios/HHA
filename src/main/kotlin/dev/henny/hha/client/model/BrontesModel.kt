package dev.henny.hha.client.model

import dev.henny.hha.client.anim.Motion
import dev.henny.hha.client.anim.PoseMemory
import dev.henny.hha.client.anim.Spring
import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha.entity.boss.olymp.BrontesAction
import dev.henny.hha.logic.boss.timings.BrontesTimings
import net.minecraft.client.MinecraftClient
import net.minecraft.client.model.ModelPart
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.LivingEntity
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Brontes, der Kyklop (Brief `tmp/overhaul2/models/brontes.md`): ein Auge unter schwerem Brauenwulst, Buckel aus
 * Schultern, Lederschürze, der Schmiedehammer auf der rechten Schulter, die Zange am Gürtel links.
 *
 * Die Schlüsselposen stehen als Tabelle unten (generiert aus `tools/hha_assets/brontes_model.py --kotlin`, dieselben
 * Winkel wie `POSES` dort). Die Ruhe ist die Grundhaltung des Modells; Gang, Atmen, Blinzeln und Schürze kommen als
 * Zusatz dazu. Jede Aktion läuft über `actionTime` selbst durch Ausholen → Schlag → Nachlauf/offen → zurück, mit den
 * Zeiten aus [BrontesTimings]. Wechselt die Aktion, blendet [PoseMemory] über [FADE] Ticks aus der zuletzt gezeigten
 * Haltung über (Überarbeitung 3), Federn ([Spring]) lassen Schürze, Kopf mit Bart, Hammerkopf und die Zange am Gürtel
 * nachschwingen; der Fußaufsatz kommt aus [Motion]. Zustand je Wesen in [RIGS] (nach `entityId`).
 *
 * Requisiten schalten über die Sichtbarkeit: `tongs` (in der Hand) und `tongs_belt` (am Gürtel), `ingot` (Rohling in
 * der Zange), `wedge` (der Keil), `hammer` (in der Hand) und `hammer_dropped` (an der Wurzel: liegt bei BLINDED am
 * Boden, steht bei WEDGE_THROW kopfüber neben ihm). Die Posen legen den Hammer in der Hand genau auf die Lage des
 * abgelegten, damit der Tausch nicht springt.
 */
/**
 * Anker des zuletzt gezeichneten Bildes je Brontes in Weltkoordinaten (Auge, Mund, Hammerkopf), geschrieben von
 * [BrontesModel.eyeAnchor], gelesen von `BrontesFx` (Blasebalg aus dem Mund, Glutband am Hammerkopf).
 */
object BrontesAnchors {
    class Frame {
        var eye: Vec3d = Vec3d.ZERO
        var mouth: Vec3d = Vec3d.ZERO
        var hammer: Vec3d = Vec3d.ZERO
        /** Alter des Wesens im Bild (Alter + Zwischenbild). */
        var age = -1f
        var seen = 0L
    }

    internal val frames = HashMap<Int, Frame>()

    /** Anker, wenn sie im Bild zur Zeit [now] (Alter + Zwischenbild) entstanden sind, sonst `null`. */
    fun fresh(entityId: Int, now: Float, slack: Float = 1.5f): Frame? =
        frames[entityId]?.takeIf { it.age >= now - slack && it.age <= now + slack }
}

class BrontesModel(root: ModelPart) : GuardianModel(root) {
    private val parts: Array<ModelPart>
    private val rest: FloatArray
    private val base: FloatArray
    private val mixed: FloatArray
    private val current: FloatArray

    /** Wesen und Zeit des zuletzt posierten Bildes (das Modell ist für alle Brontes dasselbe; Anker gehören zu ihm). */
    private var lastEntityId = 0
    private var lastAge = 0f

    init {
        val list = ArrayList<ModelPart>(BONES.size)
        for (i in BONES.indices) {
            val parent = PARENTS[i]
            list.add(if (parent < 0) root.getChild(BONES[i]) else list[parent].getChild(BONES[i]))
        }
        parts = list.toTypedArray()
        rest = FloatArray(parts.size * 3)
        for (i in parts.indices) {
            rest[i * 3] = parts[i].originX
            rest[i * 3 + 1] = parts[i].originY
            rest[i * 3 + 2] = parts[i].originZ
        }
        base = FloatArray(parts.size * 6)
        mixed = FloatArray(parts.size * 6)
        current = FloatArray(parts.size * 6)
    }

    private fun bone(name: String) = parts[BONES.indexOf(name)]

    private val body = bone("body")
    private val chest = bone("chest")
    private val head = bone("head")
    private val brow = bone("brow")
    private val eye = bone("eye")
    private val armR = bone("arm_r")
    private val armL = bone("arm_l")
    private val foreL = bone("fore_l")
    private val hammer = bone("hammer")
    private val wedge = bone("wedge")
    private val tongs = bone("tongs")
    private val ingot = bone("ingot")
    private val tongsBelt = bone("tongs_belt")
    private val apron = bone("apron")
    private val legR = bone("leg_r")
    private val legL = bone("leg_l")
    private val shinR = bone("shin_r")
    private val shinL = bone("shin_l")
    private val dropped = bone("hammer_dropped")

    /** Ein Schlüsselbild der Zeitleiste: ab [at] Ticks gilt [pose] (null = Ruhe/Gang); [hard] = Schlag (beschleunigt). */
    private class Key(val at: Float, val pose: FloatArray?, val hard: Boolean = false)

    override fun setAngles(state: GuardianRenderState) {
        super.setAngles(state)
        val t = state.age
        val w = walk(state)
        val p = phase(state)
        val a = state.actionTime
        trembleClock = t
        val rig = rig(state.entityId)
        val dt = rig.dt(t)

        // --- Ruhe und Gang: schwer und breitbeinig, der Hammer ruht auf der Schulter, der linke Arm pendelt weit ------
        val breath = sin(t * 0.07f)
        chest.pitch += breath * 0.015f
        head.pitch -= breath * 0.01f
        armL.roll -= 0.03f + breath * 0.012f
        armR.roll += breath * 0.006f
        // Bei jedem Schritt sackt der Körper 2 Einheiten.
        body.originY += abs(sin(p)) * 2f * w
        body.roll += sin(p) * 0.06f * w
        chest.yaw += sin(p) * 0.06f * w
        chest.roll -= sin(p) * 0.025f * w
        legs(state, legR, legL, shinR, shinL, 0.5f)
        armL.pitch += cos(p) * 0.5f * w
        foreL.pitch -= max(0f, cos(p)) * 0.25f * w
        armR.pitch += sin(p) * 0.03f * w
        chest.xScale = 1f
        chest.yScale = 1f
        chest.zScale = 1f

        capture()

        // --- Aktion, beim Wechsel weich aus der zuletzt GEZEIGTEN Haltung (PoseMemory, PLAN 2.1 Punkt 3) ------------
        pose(state.action, a)
        store(current)
        val restart = state.action == rig.action && a + RESTART_SLACK < rig.actionTime
        restore(rig.memory.blend(state.action, current, t, FADE, restart))
        props(state.action, a)

        // --- Nachgeordnet: Federn (Schürze, Bart/Kopf, Hammerkopf, Zange am Gürtel), Blick, Mund, Blinzeln --------
        secondary(state, rig, dt, a, p, w)
        head.yaw += state.relativeHeadYaw * DEG * 0.5f
        head.pitch += state.pitch * DEG * 0.5f
        // Mund zum Text: Das Modell hat keinen eigenen Kiefer (Kiefer und Bart sitzen am Kopf), also nickt der Kopf
        // leicht mit und die Brauen heben sich.
        val mouth = CinePlayer.mouthOf(state.entityId)
        head.pitch += mouth * MOUTH_NOD
        brow.pitch -= mouth * MOUTH_BROW
        // Das Auge blinzelt alle 80–140 Ticks für 2 Ticks (je Wesen versetzt); bei BLINDED steuert props() es.
        val period = 80 + (state.entityId * 37 and 0x3f)
        if ((t.toInt() + state.entityId * 13) % period < 2) eye.visible = false

        // Leuchtmaske an den Kampfzustand gekoppelt, weich nachgeführt (siehe glowAlpha).
        val glowTarget = glowTarget(state.action, a)
        rig.glow = if (rig.glow < 0f) glowTarget else rig.glow + (glowTarget - rig.glow) * (1f - exp(-dt / GLOW_TAU))
        rig.action = state.action
        rig.actionTime = a
        lastEntityId = state.entityId
        lastAge = t
    }

    /**
     * Leuchtmaske (Soll aus PLAN 2.1 Punkt 7): steigt beim Ausholen schwerer Schläge, fällt im offenen Fenster
     * (geblendet, leere Hände nach dem Keil). Keine Pulse, nur weiche Übergänge.
     */
    override fun glowAlpha(state: GuardianRenderState): Float = RIGS[state.entityId]?.glow?.takeIf { it >= 0f } ?: GLOW_REST

    private fun glowTarget(action: Int, a: Float): Float = when (action) {
        BrontesAction.HAMMER -> GLOW_REST + (1f - GLOW_REST) * window(a, SOFT, T.HAMMER_STUCK_END.f)
        BrontesAction.DOUBLE_HAMMER -> GLOW_REST + (1f - GLOW_REST) * window(a, SOFT, T.DOUBLE_STUCK_END.f)
        BrontesAction.FORGE -> GLOW_REST + (1f - GLOW_REST) * step(a, 4f, T.FORGE_HIT_1.f)
        BrontesAction.BLINDED -> GLOW_OPEN
        // Überarbeitung 4 (P16): Hammer steckt bzw. Amboss gebrochen – beides offen.
        BrontesAction.STUCK -> GLOW_OPEN
        BrontesAction.STAGGER -> GLOW_OPEN
        BrontesAction.WEDGE_THROW -> if (a < T.WEDGE_HIT_END) 1f else GLOW_OPEN
        BrontesAction.RISE -> GLOW_DIM + (GLOW_REST - GLOW_DIM) * step(a, RISE_EYE_OPEN - 6f, RISE_EYE_OPEN + 10f)
        else -> GLOW_REST
    }

    /** Augenmitte für `BossGlow` (Anker `eye`) in Weltkoordinaten; im Feature-Renderer nach dem Modell aufrufen. */
    fun eyeAnchor(matrices: MatrixStack, cam: Vec3d): Vec3d {
        val v = Vector3f()
        matrices.push()
        for (part in EYE_CHAIN.map { bone(it) }) part.applyTransform(matrices)
        matrices.peek().positionMatrix.transformPosition(0f, 3f / 16f, -0.6f / 16f, v)
        matrices.pop()
        val eyeAt = Vec3d(v.x + cam.x, v.y + cam.y, v.z + cam.z)
        recordAnchors(matrices, cam, eyeAt)
        return eyeAt
    }

    /**
     * Merkt sich im selben Bild Mund und Hammerkopf (Weltkoordinaten) für die Effekte von `BrontesFx` (Blasebalg aus
     * dem Mund, Glutband am Hammerkopf). Läuft nur, wenn `BossGlow` den Augen-Anker holt (Auge offen); sonst nehmen
     * die Effekte ihre Rückfall-Lage.
     */
    private fun recordAnchors(matrices: MatrixStack, cam: Vec3d, eyeAt: Vec3d) {
        val v = Vector3f()
        val out = BrontesAnchors.frames.getOrPut(lastEntityId) { BrontesAnchors.Frame() }
        matrices.push()
        for (part in MOUTH_CHAIN.map { bone(it) }) part.applyTransform(matrices)
        matrices.peek().positionMatrix.transformPosition(0f, MOUTH_Y / 16f, MOUTH_Z / 16f, v)
        matrices.pop()
        out.mouth = Vec3d(v.x + cam.x, v.y + cam.y, v.z + cam.z)
        matrices.push()
        for (part in HAMMER_CHAIN.map { bone(it) }) part.applyTransform(matrices)
        matrices.peek().positionMatrix.transformPosition(0f, HAMMER_HEAD_Y / 16f, 0f, v)
        matrices.pop()
        out.hammer = Vec3d(v.x + cam.x, v.y + cam.y, v.z + cam.z)
        out.eye = eyeAt
        out.age = lastAge
        out.seen = System.currentTimeMillis()
    }

    /** Ist das Auge gerade offen (für `BossGlow`: kein Leuchten bei BLINDED und beim Blinzeln)? */
    val eyeOpen: Boolean get() = eye.visible

    // -----------------------------------------------------------------------------------------------------------------
    // Federn und Bewegungsmessung (PLAN 2.1 Punkt 3, REF T13)
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Nachschwingen: Schürze folgt ihrer Hängelage über eine Feder, Kopf/Bart, Hammerkopf und die Zange am Gürtel
     * schwingen als Rest um 0 und werden nur angestoßen (harte Schlüsselbilder, Fußaufsatz, Stopp aus dem Gang).
     */
    private fun secondary(state: GuardianRenderState, rig: Rig, dt: Float, a: Float, p: Float, w: Float) {
        // Stöße an den harten Schlüsselbildern der laufenden Aktion
        if (rig.action == state.action) {
            for (h in hitsOf(state.action)) {
                if (rig.actionTime < h && a >= h) {
                    val k = if (state.action == BrontesAction.BLINDED) BLINDED_KICK else 1f
                    rig.apron.kick(APRON_HIT * k)
                    rig.beard.kick(BEARD_HIT * k)
                    rig.hammer.kick(HAMMER_HIT_KICK)
                    rig.belt.kick(BELT_HIT * k)
                }
            }
        }
        // Landestoß beim Stampfen: jeder Fußaufsatz (Vorzeichenwechsel der Schrittphase), stärker je schneller er geht
        val entity = MinecraftClient.getInstance().world?.getEntityById(state.entityId) as? LivingEntity
        var stride = w
        if (entity != null) {
            val tickDelta = (state.age - entity.age).coerceIn(0f, 1f)
            rig.motion.update(entity, tickDelta, state.action, a.toInt())
            stride = (rig.motion.speed / STEP_FULL_SPEED).coerceIn(0f, 1f)
            // Stopp aus dem Gang: der Hammerkopf zieht nach, die Zange pendelt
            if (rig.motion.speed > MOVING_SPEED) rig.moving = true
            else if (rig.moving && rig.motion.speed < STOPPED_SPEED) {
                rig.moving = false
                rig.hammer.kick(HAMMER_STOP_KICK)
                rig.belt.kick(BELT_STOP_KICK)
            }
        }
        val s = sin(p)
        if (w > STEP_MIN_WALK && (s > 0f) != (rig.lastStep > 0f)) {
            rig.apron.kick(APRON_STEP * stride)
            rig.beard.kick(BEARD_STEP * stride)
            rig.belt.kick(BELT_STEP * stride * if (s > 0f) 1f else -1f)
        }
        rig.lastStep = s

        // Anwenden
        val lean = body.pitch + chest.pitch
        val thigh = min(legR.pitch, legL.pitch) + body.pitch
        val drape = -lean * APRON_FOLLOW + min(0f, thigh) * APRON_LEG
        apron.pitch = rig.apron.step(drape, dt).coerceIn(drape - APRON_SWING_MAX, drape + APRON_SWING_MAX)
        apron.yaw = -chest.yaw * 0.5f
        apron.roll = -chest.roll * 0.5f
        val nod = rig.beard.step(0f, dt).coerceIn(-BEARD_MAX, BEARD_MAX)
        head.pitch += nod
        brow.pitch -= nod * 0.5f
        if (hammer.visible) hammer.pitch += rig.hammer.step(0f, dt).coerceIn(-HAMMER_LAG_MAX, HAMMER_LAG_MAX)
        else rig.hammer.step(0f, dt)
        val swing = rig.belt.step(0f, dt).coerceIn(-BELT_MAX, BELT_MAX)
        tongsBelt.roll += swing
        tongsBelt.pitch += swing * 0.4f
    }

    /** Ticks der harten Schlüsselbilder je Aktion (dort stoßen die Federn an). */
    private fun hitsOf(action: Int): FloatArray = when (action) {
        BrontesAction.HAMMER -> HITS_HAMMER
        BrontesAction.DOUBLE_HAMMER -> HITS_DOUBLE
        BrontesAction.SWING -> HITS_SWING
        BrontesAction.TOSS -> HITS_TOSS
        BrontesAction.BELLOWS -> HITS_BELLOWS
        BrontesAction.BLINDED -> HITS_BLINDED
        BrontesAction.FORGE -> HITS_FORGE
        BrontesAction.WEDGE_THROW -> HITS_WEDGE
        BrontesAction.TONGS_UP -> HITS_TONGS
        BrontesAction.YIELD -> HITS_YIELD
        BrontesAction.STUCK -> HITS_STUCK
        BrontesAction.STAGGER -> HITS_STAGGER
        else -> NO_HITS
    }

    /** Zustand je Wesen (Federn, Überblendung, Bewegung); entfernte Wesen werden nach [FORGET_MS] vergessen. */
    private class Rig(size: Int) {
        val memory = PoseMemory(size)
        val apron = Spring(0.30f, 0.32f)
        val beard = Spring(0.42f, 0.38f)
        val hammer = Spring(0.36f, 0.30f)
        val belt = Spring(0.26f, 0.22f)
        val motion = Motion()
        var action = Int.MIN_VALUE
        var actionTime = 0f
        var lastStep = 0f
        var moving = false
        var glow = -1f
        private var last = Float.NaN
        var seen = 0L

        /** Zeitschritt seit dem letzten Bild (Ticks, 0..4; erstes Bild und Zeitsprünge 0). */
        fun dt(now: Float): Float {
            seen = System.currentTimeMillis()
            val d = if (last.isNaN() || now < last || now - last > 40f) 0f else (now - last).coerceAtMost(4f)
            last = now
            return d
        }
    }

    private fun rig(entityId: Int): Rig {
        RIGS[entityId]?.let { return it }
        forgetStale()
        return Rig(parts.size * 6).also { RIGS[entityId] = it }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Aktionen
    // -----------------------------------------------------------------------------------------------------------------

    private fun pose(action: Int, a: Float) {
        when (action) {
            BrontesAction.HAMMER -> {
                val hit = T.HAMMER_HIT.f
                play(a,
                    Key(0f, null), Key(SOFT, HAMMER_WINDUP), Key(hit, HAMMER_WINDUP),
                    Key(T.HAMMER_HIT_END.f, HAMMER_HIT, true), Key(T.HAMMER_HIT_END + 5f, HAMMER_STUCK),
                    Key(T.HAMMER_STUCK_END.f, HAMMER_STUCK), Key(T.HAMMER_END.f, null))
                tug(a, T.HAMMER_HIT_END + 5f, T.HAMMER_STUCK_END.f)
                tremble(window(a, SOFT, hit) * 0.5f)
            }
            BrontesAction.DOUBLE_HAMMER -> {
                val h1 = T.DOUBLE_HIT.f
                val h2 = T.DOUBLE_HIT2.f
                play(a,
                    Key(0f, null), Key(SOFT, HAMMER_WINDUP), Key(h1, HAMMER_WINDUP),
                    Key(h1 + 2f, HAMMER_HIT, true), Key(h1 + 4f, HAMMER_HIT),
                    // Reißt den Hammer sofort wieder hoch und schlägt leicht gedreht noch einmal.
                    Key(h2 - 4f, HAMMER_WINDUP), Key(h2, HAMMER_WINDUP),
                    Key(h2 + 2f, HAMMER_HIT, true), Key(h2 + 7f, HAMMER_STUCK),
                    Key(T.DOUBLE_STUCK_END.f, HAMMER_STUCK), Key(T.DOUBLE_END.f, null))
                val twist = step(a, h1 + 4f, h2 - 4f) * (1f - step(a, T.DOUBLE_STUCK_END.f, T.DOUBLE_END.f))
                body.yaw += twist * 0.12f
                chest.yaw += twist * 0.16f
                tug(a, h2 + 7f, T.DOUBLE_STUCK_END.f)
                tremble(window(a, SOFT, h1) * 0.5f)
            }
            BrontesAction.SWING -> {
                play(a,
                    Key(0f, null), Key(7f, SWING_WINDUP), Key(T.SWING_HIT.f, SWING_WINDUP),
                    Key(T.SWING_HIT_END.f, SWING_HIT, true), Key(T.SWING_HIT_END + 9f, SWING_HIT),
                    Key(T.SWING_END.f, null))
                tremble(window(a, 7f, T.SWING_HIT.f) * 0.4f)
            }
            BrontesAction.TOSS -> {
                play(a,
                    Key(0f, null), Key(6f, TOSS_REACH), Key(T.TOSS_REACH.f, TOSS_REACH),
                    Key(T.TOSS_HIT - 5f, TOSS_WINDUP), Key(T.TOSS_HIT.f, TOSS_WINDUP),
                    Key(T.TOSS_HIT_END.f, TOSS_RELEASE, true), Key(T.TOSS_HIT_END + 7f, TOSS_RELEASE),
                    Key(T.TOSS_END.f, null))
                // Er zeigt den Rohling: kleines Wiegen aus dem Handgelenk.
                foreL.pitch += sin(a * 0.55f) * 0.05f * window(a, T.TOSS_HIT - 5f, T.TOSS_HIT.f)
            }
            BrontesAction.BELLOWS -> {
                val hit = T.BELLOWS_HIT.f
                play(a,
                    Key(0f, null), Key(8f, BELLOWS_WINDUP), Key(hit, BELLOWS_WINDUP),
                    Key(hit + 3f, BELLOWS_HIT, true), Key(T.BELLOWS_HIT_END + 6f, BELLOWS_HIT),
                    Key(T.BELLOWS_END.f, null))
                // Die Brust bläht sich beim Ausholen (1,1-fach) und fällt beim Stoß zusammen.
                val swell = step(a, 2f, hit) * (1f - step(a, hit, hit + 4f))
                chest.xScale = 1f + 0.1f * swell
                chest.zScale = 1f + 0.1f * swell
                chest.yScale = 1f + 0.04f * swell
            }
            BrontesAction.BLINDED -> {
                play(a, Key(0f, null), Key(5f, BLINDED, true))
                // Er taumelt gebeugt rückwärts und schwankt; der Hammer liegt (als eigener Knochen) still.
                val reel = step(a, 3f, 28f)
                val sway = step(a, 5f, 12f)
                body.originZ += reel * 7f + sin(a * 0.24f) * 1.2f * sway
                body.roll += sin(a * 0.21f) * 0.06f * sway
                chest.yaw += sin(a * 0.17f + 1f) * 0.08f * sway
                head.roll += sin(a * 0.3f) * 0.05f * sway
                legR.pitch += sin(a * 0.24f) * 0.12f * sway
                legL.pitch -= sin(a * 0.24f) * 0.12f * sway
            }
            BrontesAction.FORGE -> {
                val h1 = T.FORGE_HIT_1.f
                val h2 = T.FORGE_HIT_2.f
                val h3 = T.FORGE_HIT_3.f
                play(a,
                    Key(0f, null), Key(10f, FORGE_WINDUP), Key(h1 - 2f, FORGE_WINDUP),
                    Key(h1, FORGE_HIT, true), Key(h1 + 4f, FORGE_HIT), Key(h2 - 7f, FORGE_WINDUP), Key(h2 - 2f, FORGE_WINDUP),
                    Key(h2, FORGE_HIT, true), Key(h2 + 4f, FORGE_HIT), Key(h3 - 7f, FORGE_WINDUP), Key(h3 - 2f, FORGE_WINDUP),
                    Key(h3, FORGE_HIT, true), Key(h3 + 6f, FORGE_HIT),
                    // Zeigen des fertigen Keils: die Zange hoch vor das Auge (Haltung wie beim Glutwurf).
                    Key(h3 + 14f, TOSS_WINDUP), Key(T.FORGE_END.f, TOSS_WINDUP))
                // Zwischen den Schlägen hebt die linke Hand den Rohling mit der Zange an.
                val lift = window(a, h1 + 4f, h2 - 4f) + window(a, h2 + 4f, h3 - 4f)
                armL.pitch -= lift * 0.3f
                foreL.pitch -= lift * 0.25f
            }
            BrontesAction.WEDGE_THROW -> {
                play(a,
                    Key(0f, null), Key(10f, WEDGE_WINDUP), Key(T.WEDGE_HIT.f, WEDGE_WINDUP),
                    Key(T.WEDGE_HIT_END.f, WEDGE_EMPTY, true), Key(T.WEDGE_END - 5f, WEDGE_EMPTY),
                    Key(T.WEDGE_END.f, null))
                tremble(window(a, 10f, T.WEDGE_HIT.f) * 0.6f)
            }
            BrontesAction.TONGS_UP -> {
                play(a,
                    Key(0f, null), Key(T.TONGS_RAISE - 2f, TONGS_WINDUP), Key(T.TONGS_HIT.f, TONGS_WINDUP),
                    Key(T.TONGS_HIT_END.f, TONGS_HIT, true), Key(T.TONGS_END - 7f, TONGS_HIT),
                    Key(T.TONGS_END.f, null))
            }
            BrontesAction.RISE -> {
                // Auftritt (Szene `brontes.intro`): kauert in der Glut, richtet sich langsam auf, das Auge öffnet erst danach.
                play(a, Key(-1f, RISE), Key(RISE_HOLD, RISE), Key(RISE_STAND, null))
            }
            BrontesAction.YIELD -> {
                play(a, Key(0f, null), Key(16f, YIELD))
                // Er nickt einmal.
                head.pitch += window(a, 18f, 32f) * 0.22f
            }
            BrontesAction.STUCK -> {
                // Überarbeitung 4 (P16): Der Hammerkopf steckt im Boden, er zerrt mit beiden Armen in Rucken (jeder Ruck
                // stößt Bart und Schürze an, siehe HITS_STUCK), holt vor dem Herausreißen Luft und reißt ihn hoch.
                val rip = T.STUCK_RIP.f
                play(a, Key(-1f, HAMMER_STUCK), Key(rip - 2f, HAMMER_STUCK), Key(rip + 2f, HAMMER_WINDUP, true), Key(T.STUCK_END.f, null))
                strain(a, STRAIN_FROM, rip - 2f)
                chest.pitch -= window(a, rip - T.SPLINTER_W, rip) * STRAIN_BRACE
            }
            BrontesAction.STAGGER -> {
                // Überarbeitung 4 (P16): Der Amboss bricht unter dem Hammer, er taumelt zurück, blickt auf den Stumpf,
                // schwankt (≤ 1 Hz) und fängt sich gegen Ende.
                val end = T.STAGGER_END.f
                play(a, Key(-1f, HAMMER_HIT), Key(8f, null))
                val reel = step(a, 2f, 24f) * (1f - step(a, end - 24f, end))
                val sway = step(a, 4f, 12f) * (1f - step(a, end - 16f, end))
                body.originZ += reel * 6f + sin(a * 0.22f) * 1.1f * sway
                body.roll += sin(a * 0.19f) * 0.07f * sway
                chest.pitch += reel * 0.12f
                head.pitch += reel * 0.15f
                head.roll += sin(a * 0.27f) * 0.06f * sway
                legR.pitch += sin(a * 0.22f) * 0.12f * sway
                legL.pitch -= sin(a * 0.22f) * 0.12f * sway
            }
        }
    }

    /**
     * Er zerrt am fest steckenden Hammer (P16): alle [STRAIN_PERIOD] Ticks ein Ruck mit beiden Armen nach hinten
     * (Brust zurück, Körper nach hinten, Kopf nickt mit), dazwischen zittern die Arme.
     */
    private fun strain(a: Float, from: Float, to: Float) {
        val k = window(a, from, to)
        if (k <= 0f) return
        val phase = ((a - from).coerceAtLeast(0f) % STRAIN_PERIOD) / STRAIN_PERIOD
        val jerk = (if (phase < STRAIN_PULL) sin(phase / STRAIN_PULL * Math.PI.toFloat()) else 0f) * k
        chest.pitch -= jerk * 0.12f
        body.originZ += jerk * 1.2f
        armR.pitch -= jerk * 0.08f
        armL.pitch -= jerk * 0.08f
        head.pitch += jerk * 0.05f
        tremble(k)
    }

    /** Sichtbarkeit der Requisiten zur Zeit [a] der laufenden Aktion. */
    private fun props(action: Int, a: Float) {
        var inHand = true
        var down = false
        var tongsInHand = false
        var hot = false
        var keil = false
        when (action) {
            BrontesAction.TOSS -> {
                tongsInHand = a >= 3f && a < T.TOSS_END - 4f
                hot = a >= T.TOSS_REACH && a < T.TOSS_HIT + 1f
            }
            BrontesAction.BLINDED -> {
                inHand = a < 5f
                down = !inHand
            }
            BrontesAction.FORGE -> {
                tongsInHand = a >= 3f
                hot = a >= 3f
            }
            BrontesAction.WEDGE_THROW -> {
                keil = a < T.WEDGE_HIT_END - 1f
                inHand = a < 10f || a >= T.WEDGE_END - 5f
                down = !inHand
            }
            BrontesAction.TONGS_UP -> tongsInHand = a >= 2f && a < T.TONGS_END - 3f
        }
        hammer.visible = inHand
        dropped.visible = down
        tongs.visible = tongsInHand
        tongsBelt.visible = !tongsInHand
        ingot.visible = hot
        wedge.visible = keil
        eye.visible = when (action) {
            BrontesAction.BLINDED -> blindedEyeLit(a)
            BrontesAction.RISE -> a >= RISE_EYE_OPEN
            else -> true
        }
        brow.visible = true
    }

    /**
     * Geblendet: Das Auge erlischt [BLIND_DARK] Ticks und kommt in drei langsamen Schritten zurück. Jede Phase dauert
     * mindestens 4 Ticks, ein ganzer Wechsel also ≥ 9 Ticks (unter 2 Hz, PLAN 2.2).
     */
    private fun blindedEyeLit(a: Float): Boolean {
        var lit = false
        for (edge in BLIND_EDGES) if (a >= edge) lit = !lit
        return lit
    }

    /** Er zerrt am steckenden Hammer: kurze Rucke nach hinten, die Arme zittern. */
    private fun tug(a: Float, from: Float, to: Float) {
        val k = window(a, from, to)
        if (k <= 0f) return
        val jerk = max(0f, sin(a * 0.7f)) * k
        chest.pitch -= jerk * 0.08f
        body.originZ += jerk * 0.8f
        tremble(k * 0.8f)
    }

    private fun tremble(k: Float) {
        if (k <= 0f) return
        val t = trembleClock
        armR.pitch += sin(t * 2.3f) * 0.015f * k
        armL.pitch += sin(t * 2.7f + 1f) * 0.015f * k
        chest.yaw += sin(t * 1.9f) * 0.008f * k
    }

    /** Uhr für das Zittern (Alter des Wesens), damit es nicht mit der Aktion stehen bleibt. */
    private var trembleClock = 0f

    // -----------------------------------------------------------------------------------------------------------------
    // Zeitleiste
    // -----------------------------------------------------------------------------------------------------------------

    /** Merkt sich die Ruhe-/Ganglage aller Knochen (Ziel von `null` in der Zeitleiste). */
    private fun capture() = store(base)

    private fun store(out: FloatArray) {
        for (i in parts.indices) {
            val part = parts[i]
            out[i * 6] = part.pitch
            out[i * 6 + 1] = part.yaw
            out[i * 6 + 2] = part.roll
            out[i * 6 + 3] = part.originX - rest[i * 3]
            out[i * 6 + 4] = part.originY - rest[i * 3 + 1]
            out[i * 6 + 5] = part.originZ - rest[i * 3 + 2]
        }
    }

    private fun restore(from: FloatArray) {
        for (i in parts.indices) {
            val part = parts[i]
            part.pitch = from[i * 6]
            part.yaw = from[i * 6 + 1]
            part.roll = from[i * 6 + 2]
            part.originX = rest[i * 3] + from[i * 6 + 3]
            part.originY = rest[i * 3 + 1] + from[i * 6 + 4]
            part.originZ = rest[i * 3 + 2] + from[i * 6 + 5]
        }
    }

    /** Zeitleiste: weich zwischen zwei Schlüsselbildern, beschleunigt in ein hartes (Schlag). */
    private fun play(time: Float, vararg keys: Key) {
        if (time <= keys[0].at) return
        var i = 0
        while (i < keys.size - 1 && time >= keys[i + 1].at) i++
        if (i == keys.size - 1) {
            blend(keys[i].pose, keys[i].pose, 1f)
            return
        }
        val from = keys[i]
        val to = keys[i + 1]
        val u = ((time - from.at) / (to.at - from.at)).coerceIn(0f, 1f)
        blend(from.pose, to.pose, if (to.hard) u * u else u * u * (3f - 2f * u))
    }

    private fun blend(from: FloatArray?, to: FloatArray?, u: Float) {
        for (i in parts.indices) {
            val o = i * 6
            val fromSet = from != null && !from[o].isNaN()
            val toSet = to != null && !to[o].isNaN()
            if (!fromSet && !toSet) continue
            for (k in 0 until 6) {
                val x = if (fromSet) from!![o + k] else base[o + k]
                val y = if (toSet) to!![o + k] else base[o + k]
                mixed[o + k] = x + (y - x) * u
            }
            val part = parts[i]
            part.pitch = mixed[o]
            part.yaw = mixed[o + 1]
            part.roll = mixed[o + 2]
            part.originX = rest[i * 3] + mixed[o + 3]
            part.originY = rest[i * 3 + 1] + mixed[o + 4]
            part.originZ = rest[i * 3 + 2] + mixed[o + 5]
        }
    }

    /** 1 zwischen [from] und [to], mit kurzen weichen Rändern. */
    private fun window(t: Float, from: Float, to: Float) = step(t, from, from + 3f) * (1f - step(t, to - 3f, to))

    private companion object {
        val T = BrontesTimings
        val Int.f get() = toFloat()
        const val SOFT = 8f
        const val FADE = 6f
        const val N = Float.NaN
        val EYE_CHAIN = arrayOf("body", "chest", "head", "brow", "eye")
        const val APRON_FOLLOW = 0.35f
        const val APRON_LEG = 0.45f

        // --- Überarbeitung 3: Darstellungs-Konstanten (keine Kampfzahlen) ---------------------------------------------
        /** So viele Ticks Rückschritt der Aktionszeit gelten als Neustart derselben Aktion (Überblendung). */
        const val RESTART_SLACK = 0.5f
        /** Auftritt: kauert bis [RISE_HOLD], steht bei [RISE_STAND], Auge offen ab [RISE_EYE_OPEN] (Aktionszeit). */
        const val RISE_HOLD = 50f
        const val RISE_STAND = 95f
        const val RISE_EYE_OPEN = 118f
        /** Geblendet: Auge aus bis 20, dann an/aus/an/aus/an (Wechselzeiten der Aktionszeit). */
        const val BLIND_DARK = 20f
        val BLIND_EDGES = floatArrayOf(BLIND_DARK, 26f, 31f, 38f, 42f)
        /** Mund: Kopf nickt, Brauen heben sich (Bogenmaß bei voller Öffnung). */
        const val MOUTH_NOD = 0.05f
        const val MOUTH_BROW = 0.07f
        /** Leuchtmaske: Ruhe, offen (Fenster), gedimmt (kauert in der Glut); weich nachgeführt mit τ in Ticks. */
        const val GLOW_REST = 0.85f
        const val GLOW_OPEN = 0.45f
        const val GLOW_DIM = 0.35f
        const val GLOW_TAU = 4f
        /** Federstöße (Bogenmaß je Tick) an harten Schlüsselbildern, beim Fußaufsatz und beim Stopp. */
        const val APRON_HIT = 0.10f
        const val BEARD_HIT = 0.06f
        const val HAMMER_HIT_KICK = -0.12f
        const val BELT_HIT = 0.08f
        const val BLINDED_KICK = 1.4f
        const val APRON_STEP = 0.035f
        const val BEARD_STEP = 0.02f
        const val BELT_STEP = 0.05f
        const val HAMMER_STOP_KICK = 0.08f
        const val BELT_STOP_KICK = 0.09f
        /** Ausschlag-Deckel der Federn (Bogenmaß). */
        const val APRON_SWING_MAX = 0.45f
        const val BEARD_MAX = 0.12f
        const val HAMMER_LAG_MAX = 0.28f
        const val BELT_MAX = 0.4f
        /** Gang: ab dieser Schrittweite zählt der Fußaufsatz; voller Stoß bei diesem Tempo (Blöcke/Tick). */
        const val STEP_MIN_WALK = 0.2f
        const val STEP_FULL_SPEED = 0.12f
        const val MOVING_SPEED = 0.07f
        const val STOPPED_SPEED = 0.025f
        val HITS_HAMMER = floatArrayOf(T.HAMMER_HIT_END.f)
        val HITS_DOUBLE = floatArrayOf(T.DOUBLE_HIT + 2f, T.DOUBLE_HIT2 + 2f)
        val HITS_SWING = floatArrayOf(T.SWING_HIT_END.f)
        val HITS_TOSS = floatArrayOf(T.TOSS_HIT_END.f)
        val HITS_BELLOWS = floatArrayOf(T.BELLOWS_HIT + 3f)
        val HITS_BLINDED = floatArrayOf(5f, 14f, 23f)
        val HITS_FORGE = floatArrayOf(T.FORGE_HIT_1.f, T.FORGE_HIT_2.f, T.FORGE_HIT_3.f)
        val HITS_WEDGE = floatArrayOf(T.WEDGE_HIT_END.f)
        val HITS_TONGS = floatArrayOf(T.TONGS_HIT_END.f)
        val HITS_YIELD = floatArrayOf(16f)
        /** P16: Zerren am steckenden Hammer – Rucke alle [STRAIN_PERIOD] Ticks ab [STRAIN_FROM], Herausreißen. */
        const val STRAIN_FROM = 2f
        const val STRAIN_PERIOD = 12f
        const val STRAIN_PULL = 0.3f
        const val STRAIN_BRACE = 0.06f
        val HITS_STUCK = FloatArray(((T.STUCK_RIP - 2f - STRAIN_FROM) / STRAIN_PERIOD).toInt() + 1) { STRAIN_FROM + STRAIN_PERIOD * (it + STRAIN_PULL * 0.5f) } +
            floatArrayOf(T.STUCK_RIP + 2f)
        val HITS_STAGGER = floatArrayOf(2f, 14f)
        val NO_HITS = FloatArray(0)

        /** Anker im Kopf (Mund vor dem Kiefer) und im Hammer (Mitte des Kopfes), Modell-Pixel. */
        val MOUTH_CHAIN = arrayOf("body", "chest", "head")
        const val MOUTH_Y = -1f
        const val MOUTH_Z = -11.5f
        val HAMMER_CHAIN = arrayOf("body", "chest", "arm_r", "fore_r", "hammer")
        const val HAMMER_HEAD_Y = -63f

        /** Vergessen nach so vielen Millisekunden ohne Bild. */
        const val FORGET_MS = 10_000L
        private val RIGS = HashMap<Int, Rig>()

        fun forgetStale() {
            val now = System.currentTimeMillis()
            RIGS.values.removeIf { now - it.seen > FORGET_MS }
            BrontesAnchors.frames.values.removeIf { now - it.seen > FORGET_MS }
        }

        // POSES BEGIN (generiert aus tools/hha_assets/brontes_model.py --kotlin)
        val BONES = arrayOf("body", "chest", "pec_r", "pec_l", "trap_r", "trap_l", "head", "brow", "eye", "arm_r", "fore_r", "hammer", "wedge", "arm_l", "fore_l", "tongs", "tongs_jaw", "ingot", "apron", "leg_r", "shin_r", "leg_l", "shin_l", "tongs_belt", "hammer_dropped")
        val PARENTS = intArrayOf(-1, 0, 1, 1, 1, 1, 1, 6, 7, 1, 9, 10, 10, 1, 13, 14, 15, 15, 1, 0, 19, 0, 21, 0, -1)
        val HAMMER_WINDUP = floatArrayOf(
            -0.1000f, 0.0000f, 0.0000f, 0.0000f, -4.6403f, 0.0000f, -0.1800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -1.9000f, 0.3450f, 0.8450f, 0.0000f, 0.0000f, 0.0000f,
            -1.4100f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 2.5274f, 0.0961f, 0.8924f, 5.0069f, -8.1973f, -0.9653f,
            N, N, N, N, N, N, -1.2800f, 1.4000f, 0.6000f, 0.0000f, 0.0000f, 0.0000f,
            -1.0600f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.0460f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2200f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.3600f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f,
            0.1800f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val HAMMER_HIT = floatArrayOf(
            0.3000f, 0.0000f, 0.0000f, 0.0000f, -4.5003f, 0.0000f, 0.7200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.6200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -1.1000f, -0.8950f, 1.0450f, 0.0000f, 0.0000f, 0.0000f,
            -1.2200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 3.1188f, 0.3754f, 1.2266f, 6.9173f, -7.2154f, -4.8326f,
            N, N, N, N, N, N, -0.5100f, 0.2700f, 0.6250f, 0.0000f, 0.0000f, 0.0000f,
            -0.7100f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.7395f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.1500f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f,
            0.7500f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f,
            0.2500f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val HAMMER_STUCK = floatArrayOf(
            0.2400f, 0.0000f, 0.0000f, 0.0000f, -3.6732f, 0.0000f, 0.5800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.4200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -1.4000f, -0.8600f, 1.2550f, 0.0000f, 0.0000f, 0.0000f,
            -0.8300f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.9130f, 0.6526f, 1.3129f, 6.6566f, -7.5791f, -6.2413f,
            N, N, N, N, N, N, -0.7600f, 0.2000f, 0.5400f, 0.0000f, 0.0000f, 0.0000f,
            -0.1750f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.5660f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.8600f, 0.0000f, 0.1600f, 0.0000f, 0.0000f, 0.0000f,
            0.6200f, 0.0000f, -0.1600f, 0.0000f, 0.0000f, 0.0000f, 0.2600f, 0.0000f, -0.1600f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.0000f, 0.1600f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val SWING_WINDUP = floatArrayOf(
            0.0800f, 0.3500f, 0.0000f, 0.0000f, -3.0263f, 0.0000f, 0.3000f, 0.5500f, 0.1000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.2000f, -0.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -1.1650f, -1.4050f, 1.7300f, 0.0000f, 0.0000f, 0.0000f,
            -1.8000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.1802f, 1.0431f, -1.2732f, 1.2185f, 1.2192f, -8.2398f,
            N, N, N, N, N, N, -0.2950f, 1.2200f, 0.6400f, 0.0000f, 0.0000f, 0.0000f,
            -0.1100f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.3805f, -0.2750f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, -0.6300f, 0.0000f, 0.3200f, 0.0000f, 0.0000f, 0.0000f,
            0.8500f, 0.0000f, -0.3200f, 0.0000f, 0.0000f, 0.0000f, -0.4300f, 0.0000f, -0.3800f, 0.0000f, 0.0000f, 0.0000f,
            0.7500f, 0.0000f, 0.3800f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val SWING_HIT = floatArrayOf(
            0.0800f, -0.4000f, 0.0000f, 0.0000f, -3.5072f, 0.0000f, 0.3000f, -0.8000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.2000f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.2950f, -1.1700f, 1.0350f, 0.0000f, 0.0000f, 0.0000f,
            -1.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4028f, 1.2434f, -1.8466f, 7.2138f, -4.1622f, -5.8630f,
            N, N, N, N, N, N, -0.5200f, 0.7900f, 0.6400f, 0.0000f, 0.0000f, 0.0000f,
            -0.0750f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.4030f, 0.4000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, -0.2800f, 0.0000f, 0.3200f, 0.0000f, 0.0000f, 0.0000f,
            0.5500f, 0.0000f, -0.3200f, 0.0000f, 0.0000f, 0.0000f, -0.6800f, 0.0000f, -0.3800f, 0.0000f, 0.0000f, 0.0000f,
            0.8500f, 0.0000f, 0.3800f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val TOSS_REACH = floatArrayOf(
            0.1000f, -0.1500f, 0.0000f, 0.0000f, -5.2217f, 0.0000f, 0.4000f, -0.5000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.2000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.6300f, -1.7250f, 1.7300f, 0.0000f, 0.0000f, 0.0000f,
            -1.6650f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 2.8164f, 0.0072f, -1.8114f, -6.1360f, -3.3440f, -2.7443f,
            N, N, N, N, N, N, 0.1800f, 1.1250f, -0.1500f, 0.0000f, 0.0000f, 0.0000f,
            -0.1050f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.8444f, 0.8534f, 0.5741f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.3100f, 0.2500f, -0.0250f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, 0.0000f, 0.1400f, 0.0000f, 0.0000f, 0.0000f,
            0.4000f, 0.0000f, -0.1400f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, -0.1400f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.0000f, 0.1400f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val TOSS_WINDUP = floatArrayOf(
            0.0000f, 0.1000f, 0.0000f, 0.0000f, -4.7573f, 0.0000f, 0.1000f, 0.3000f, -0.0600f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.1500f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -1.3650f, -0.6700f, 1.6500f, 0.0000f, 0.0000f, 0.0000f,
            -1.1050f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 2.6644f, 0.0427f, 2.7601f, 2.4975f, 0.9134f, -1.7689f,
            N, N, N, N, N, N, 0.6800f, -0.1750f, -0.6050f, 0.0000f, 0.0000f, 0.0000f,
            -2.5900f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, -0.8040f, 0.9710f, -1.1731f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.1700f, -0.1500f, 0.0300f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.1400f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.0000f, -0.1400f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, -0.1400f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, 0.1400f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val TOSS_RELEASE = floatArrayOf(
            0.1500f, -0.1500f, 0.0000f, 0.0000f, -4.5110f, 0.0000f, 0.3500f, -0.4000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.3000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.2200f, -1.6550f, 1.3800f, 0.0000f, 0.0000f, 0.0000f,
            -1.7950f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 2.7748f, 0.1253f, -1.8942f, -5.9854f, -3.1292f, -2.4902f,
            N, N, N, N, N, N, -0.5100f, 1.1150f, 0.6200f, 0.0000f, 0.0000f, 0.0000f,
            -1.2600f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 3.1194f, 0.6814f, 3.0239f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.4225f, 0.2000f, -0.0300f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, 0.1400f, 0.0000f, 0.0000f, 0.0000f,
            0.4500f, 0.0000f, -0.1400f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, -0.1400f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, 0.1400f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val BELLOWS_WINDUP = floatArrayOf(
            -0.0800f, 0.0000f, 0.0000f, 0.0000f, -3.5841f, 0.0000f, -0.1600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -1.1250f, -0.5150f, 0.9950f, 0.0000f, 0.0000f, 0.0000f,
            -0.5900f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.7272f, -0.6869f, -2.5939f, -4.0012f, -6.2617f, -10.3271f,
            N, N, N, N, N, N, -0.9950f, 0.4600f, -0.7850f, 0.0000f, 0.0000f, 0.0000f,
            -0.6400f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.0060f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1200f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.2300f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.1500f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val BELLOWS_HIT = floatArrayOf(
            0.2000f, 0.0000f, 0.0000f, 0.0000f, -4.3294f, 0.0000f, 0.6200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.7300f, -0.7100f, 0.7000f, 0.0000f, 0.0000f, 0.0000f,
            -1.9500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.5988f, 0.4510f, -2.1792f, 1.8924f, -2.2943f, -11.2784f,
            N, N, N, N, N, N, -0.5600f, 0.7600f, -0.4550f, 0.0000f, 0.0000f, 0.0000f,
            -1.9550f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.5570f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.8000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.5500f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val BLINDED = floatArrayOf(
            0.2500f, 0.1000f, 0.0600f, 0.0000f, -4.7797f, 0.0000f, 0.5500f, 0.1000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            0.1000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -1.2150f, -0.4950f, 0.5700f, 0.0000f, 0.0000f, 0.0000f,
            -1.6300f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.9746f, 0.0896f, 0.2131f, 27.7374f, -70.8054f, 56.8172f,
            N, N, N, N, N, N, -0.4750f, 0.7600f, 0.5650f, 0.0000f, 0.0000f, 0.0000f,
            -1.8550f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.5050f, -0.0500f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f,
            0.3500f, 0.0000f, -0.1800f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.5500f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val FORGE_WINDUP = floatArrayOf(
            0.0800f, 0.1500f, 0.0000f, 0.0000f, -4.7956f, 0.0000f, 0.2000f, 0.2000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            0.1000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -2.5350f, 1.0500f, -0.4650f, 0.0000f, 0.0000f, 0.0000f,
            -2.2050f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.7323f, 0.3701f, 1.5470f, 6.7060f, -6.0692f, -7.5777f,
            N, N, N, N, N, N, 0.0150f, 0.4450f, -0.1400f, 0.0000f, 0.0000f, 0.0000f,
            -1.0100f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, -2.5103f, 0.2378f, 2.7569f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.2330f, -0.1000f, -0.0400f, 0.0000f, 0.0000f, 0.0000f, -0.3800f, 0.0000f, 0.3400f, 0.0000f, 0.0000f, 0.0000f,
            0.4500f, 0.0000f, -0.3400f, 0.0000f, 0.0000f, 0.0000f, 0.0200f, 0.0000f, -0.3400f, 0.0000f, 0.0000f, 0.0000f,
            0.4500f, 0.0000f, 0.3400f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val FORGE_HIT = floatArrayOf(
            0.2000f, 0.0500f, 0.0000f, 0.0000f, -5.2341f, 0.0000f, 0.5500f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.3000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.6800f, -1.8000f, 0.7200f, 0.0000f, 0.0000f, 0.0000f,
            -1.9100f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 2.9195f, -0.3799f, 1.5757f, 7.1407f, -5.4471f, -3.5487f,
            N, N, N, N, N, N, 0.1150f, 0.6750f, -0.1450f, 0.0000f, 0.0000f, 0.0000f,
            -1.4950f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, -2.3727f, 0.0020f, 2.6496f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.4650f, -0.0250f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6500f, 0.0000f, 0.3400f, 0.0000f, 0.0000f, 0.0000f,
            0.6000f, 0.0000f, -0.3400f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.0000f, -0.3400f, 0.0000f, 0.0000f, 0.0000f,
            0.6000f, 0.0000f, 0.3400f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val WEDGE_WINDUP = floatArrayOf(
            -0.0500f, 0.3000f, 0.0000f, 0.0000f, -4.5578f, 0.0000f, -0.1200f, 0.4500f, -0.1000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            0.0000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -1.5850f, 0.1550f, 0.2150f, 0.0000f, 0.0000f, 0.0000f,
            -1.9200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 2.6098f, 0.3459f, 3.0378f, 28.8578f, -80.3157f, -12.1903f,
            -2.2858f, 0.1977f, -1.1379f, 0.0000f, 0.0000f, 0.0000f, 0.1400f, -0.0700f, -1.0100f, 0.0000f, 0.0000f, 0.0000f,
            -1.6850f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.1430f, -0.2250f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.3500f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.0000f, -1.2793f, 3.1416f, -16.0000f, -68.0000f, 6.0000f,
        )
        val WEDGE_EMPTY = floatArrayOf(
            0.2400f, -0.2000f, 0.0000f, 0.0000f, -4.0732f, 0.0000f, 0.6200f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.4500f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.5650f, -0.1500f, 0.1800f, 0.0000f, 0.0000f, 0.0000f,
            -2.1050f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.0260f, -0.7753f, -0.8642f, 4.8484f, -43.2424f, 24.2241f,
            N, N, N, N, N, N, -0.0300f, 0.2350f, 0.2250f, 0.0000f, 0.0000f, 0.0000f,
            -1.1250f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.6385f, 0.1750f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.9900f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f,
            0.6000f, 0.0000f, -0.1800f, 0.0000f, 0.0000f, 0.0000f, 0.2100f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.0000f, -1.2793f, 3.1416f, -16.0000f, -68.0000f, 6.0000f,
        )
        val TONGS_WINDUP = floatArrayOf(
            -0.0600f, -0.1000f, 0.0000f, 0.0000f, -4.7146f, 0.0000f, -0.2000f, -0.2000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.6200f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.4900f, -0.9000f, 1.1750f, 0.0000f, 0.0000f, 0.0000f,
            -1.2900f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 2.4808f, -0.1467f, 2.8409f, 2.5469f, 0.1837f, -0.7307f,
            N, N, N, N, N, N, -1.7350f, -0.7700f, -1.0850f, 0.0000f, 0.0000f, 0.0000f,
            -0.2400f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, -0.0475f, 0.0325f, -0.2683f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.0010f, 0.1000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, -0.1400f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.2600f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.2500f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val TONGS_HIT = floatArrayOf(
            0.1200f, -0.1000f, 0.0000f, 0.0000f, -4.6822f, 0.0000f, 0.4200f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.3000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.7650f, -0.7900f, 0.3900f, 0.0000f, 0.0000f, 0.0000f,
            -2.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 2.8747f, 0.7144f, -2.7702f, -3.2676f, 0.9441f, -3.5861f,
            N, N, N, N, N, N, -0.4850f, 0.8200f, -0.3250f, 0.0000f, 0.0000f, 0.0000f,
            -1.8400f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 1.9381f, 0.8764f, 1.3718f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.3915f, 0.1000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5700f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.4500f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.2300f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.2500f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val RISE = floatArrayOf(
            0.5500f, 0.0000f, 0.0000f, 0.0000f, 1.3939f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.4250f, -0.8950f, 0.6950f, 0.0000f, 0.0000f, 0.0000f,
            -1.0550f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 2.5639f, 0.8224f, 0.9586f, 6.7187f, -5.9861f, -2.3795f,
            N, N, N, N, N, N, -0.0050f, 0.6700f, -0.1700f, 0.0000f, 0.0000f, 0.0000f,
            -1.3500f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.9750f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.9000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f,
            1.6000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, -1.4500f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f,
            1.7500f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        val YIELD = floatArrayOf(
            0.1200f, 0.0000f, 0.0000f, 0.0000f, -1.4615f, 0.0000f, 0.4200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.7250f, -1.0350f, 0.6250f, 0.0000f, 0.0000f, 0.0000f,
            -1.8200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.5622f, -0.5535f, -1.9356f, 6.0820f, 15.8377f, -21.5151f,
            N, N, N, N, N, N, -0.1650f, 1.1550f, 0.2100f, 0.0000f, 0.0000f, 0.0000f,
            -2.0200f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.2565f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2700f, 0.0000f, 0.1600f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, -0.1600f, 0.0000f, 0.0000f, 0.0000f, -0.1700f, 0.0000f, -0.1600f, 0.0000f, 0.0000f, 0.0000f,
            0.1500f, 0.0000f, 0.1600f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N,
        )
        // POSES END
    }
}
