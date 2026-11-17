package dev.henny.hha.stage

import dev.henny.hha.client.cine.stage.StageDemo
import dev.henny.hha.stage.scripts.BrontesStages
import dev.henny.hha.stage.scripts.GuardianStages
import dev.henny.hha.stage.scripts.HadesStages
import dev.henny.hha.stage.scripts.HekateStages
import dev.henny.hha.stage.scripts.HermesStages
import dev.henny.hha.stage.scripts.KampeStages
import dev.henny.hha.stage.scripts.KratosStages
import dev.henny.hha.stage.scripts.RpgStages
import dev.henny.hha.stage.scripts.ZeusStages
import java.io.File
import java.util.Locale

/**
 * Schreibt alle Bühnen-Skripte als JSON (reine Daten, ohne Spielstart) für `tools/hha_assets/stage_preview.py`.
 * Aufruf: `python tools/hha_assets/gradle_locked.py dumpStages` → `tmp/overhaul3/previews/stage/stages.json`.
 *
 * Läuft ohne Minecraft-Bootstrap: Jede Skript-Datei wird einzeln registriert; wirft eine (etwa weil sie beim Laden
 * eine Registry braucht), fehlt nur sie im Export und steht unter `"failed"`.
 */
object StageDump {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "tmp/overhaul3/previews/stage/stages.json")
        val failed = LinkedHashMap<String, String>()
        val files = listOf<Pair<String, () -> Unit>>(
            "StageDemo" to { StageDemo.register() },
            "HermesStages" to { HermesStages.register() },
            "HekateStages" to { HekateStages.register() },
            "BrontesStages" to { BrontesStages.register() },
            "KratosStages" to { KratosStages.register() },
            "ZeusStages" to { ZeusStages.register() },
            "HadesStages" to { HadesStages.register() },
            "GuardianStages" to { GuardianStages.register() },
            "KampeStages" to { KampeStages.register() },
            "RpgStages" to { RpgStages.register() },
        )
        for ((name, register) in files) {
            try {
                register()
            } catch (e: Throwable) {
                failed[name] = "${e::class.java.simpleName}: ${e.message}"
            }
        }
        val scripts = StageScripts.ids().mapNotNull { StageScripts.get(it) }
        out.parentFile?.mkdirs()
        out.writeText(json(scripts, failed), Charsets.UTF_8)
        println("Bühne: ${scripts.size} Skripte nach ${out.path}" + if (failed.isEmpty()) "" else ", ohne ${failed.keys}")
        for ((name, why) in failed) println("  $name: $why")
    }

    /** JSON aller [scripts]; [failed] = Skript-Dateien, die sich nicht laden ließen. */
    fun json(scripts: List<StageScript>, failed: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder()
        sb.append("{\n\"scripts\": [\n")
        scripts.forEachIndexed { i, s ->
            if (i > 0) sb.append(",\n")
            script(sb, s)
        }
        sb.append("\n],\n\"failed\": {")
        failed.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(", ")
            sb.append(str(k)).append(": ").append(str(v))
        }
        sb.append("}\n}\n")
        return sb.toString()
    }

    private fun script(sb: StringBuilder, s: StageScript) {
        sb.append("{\"id\": ").append(str(s.id)).append(", \"length\": ").append(s.length)
            .append(", \"skippable\": ").append(s.skippable).append(",\n \"shots\": [")
        s.shots.forEachIndexed { i, shot ->
            if (i > 0) sb.append(",")
            sb.append("\n  {\"from\": ").append(shot.from).append(", \"to\": ").append(shot.to)
                .append(", \"cam\": ").append(keys(shot.cam)).append(", \"look\": ").append(keys(shot.look))
                .append(", \"fov\": [").append(num(shot.fov.first)).append(", ").append(num(shot.fov.second)).append("]")
                .append(", \"ease\": ").append(str(shot.ease.name)).append(", \"arc\": ").append(shot.arc)
                .append(", \"follow\": ").append(num(shot.follow)).append(", \"handheld\": ").append(num(shot.handheld))
                .append(", \"cut\": ").append(shot.cut).append("}")
        }
        sb.append("],\n \"cues\": [")
        s.cues.forEachIndexed { i, cue ->
            if (i > 0) sb.append(",")
            sb.append("\n  ").append(cue(cue))
        }
        sb.append("]}")
    }

    private fun cue(c: Cue): String {
        val f = LinkedHashMap<String, String>()
        f["type"] = str(c::class.simpleName ?: "?")
        f["at"] = c.at.toString()
        f["end"] = c.end.toString()
        when (c) {
            is Caption -> { f["speaker"] = c.speaker.toString(); f["key"] = str(c.key); f["ticks"] = c.ticks.toString(); f["nameKey"] = str(c.nameKey) }
            is Card -> { f["title"] = str(c.titleKey); f["sub"] = str(c.subKey); f["ticks"] = c.ticks.toString() }
            is Chapter -> { f["title"] = str(c.titleKey); f["kicker"] = str(c.kickerKey); f["ticks"] = c.ticks.toString(); f["color"] = c.color.toString() }
            is Fade -> { f["color"] = c.color.toString(); f["from"] = num(c.from); f["to"] = num(c.to); f["ticks"] = c.ticks.toString() }
            is Flash -> { f["color"] = c.color.toString(); f["strength"] = num(c.strength); f["ticks"] = c.ticks.toString() }
            is Kick -> { f["pitch"] = num(c.pitch); f["yaw"] = num(c.yaw); f["roll"] = num(c.roll); f["ticks"] = c.ticks.toString() }
            is Shake -> f["strength"] = num(c.strength)
            is Sound -> { f["id"] = str(c.id); f["volume"] = num(c.volume) }
            is Music -> f["id"] = str(c.id)
            is FxCue -> { f["id"] = str(c.id); f["style"] = str(c.style.name); f["pos"] = key(c.pos); f["life"] = c.life.toString() }
            is ScreenCue -> { f["kind"] = str(c.kind.name); f["pos"] = key(c.pos); f["ticks"] = c.ticks.toString() }
            is PoseCue -> { f["actor"] = c.actor.toString(); f["pose"] = str(c.pose.name); f["ticks"] = c.ticks.toString() }
            is Costume -> { f["on"] = c.on.toString(); f["dissolve"] = c.dissolve.toString() }
            is PuppetSpawn -> { f["name"] = str(c.name); f["entityType"] = str(c.entityType); f["pos"] = key(c.pos); f["yaw"] = num(c.yaw) }
            is PuppetMove -> { f["name"] = str(c.name); f["path"] = keys(c.path); f["lift"] = num(c.lift.toFloat()) }
            is PuppetAct -> { f["name"] = str(c.name); f["action"] = c.action.toString() }
            is PuppetRemove -> f["name"] = str(c.name)
            is Mark -> f["name"] = str(c.name)
        }
        return f.entries.joinToString(", ", "{", "}") { (k, v) -> "${str(k)}: $v" }
    }

    private fun keys(list: List<Key>): String = list.joinToString(", ", "[", "]") { key(it) }

    private fun key(k: Key): String = when (k) {
        is Key.At -> "{\"k\": \"At\", \"f\": ${num(k.f)}, \"r\": ${num(k.r)}, \"u\": ${num(k.u)}}"
        is Key.World -> "{\"k\": \"World\", \"x\": ${num(k.x)}, \"y\": ${num(k.y)}, \"z\": ${num(k.z)}}"
        is Key.Actor -> "{\"k\": \"Actor\", \"i\": ${k.index}, \"up\": ${num(k.up)}}"
        is Key.ActorRel -> "{\"k\": \"ActorRel\", \"i\": ${k.index}, \"f\": ${num(k.f)}, \"r\": ${num(k.r)}, \"u\": ${num(k.u)}}"
        is Key.Puppet -> "{\"k\": \"Puppet\", \"name\": ${str(k.name)}, \"up\": ${num(k.up)}}"
        is Key.PuppetRel -> "{\"k\": \"PuppetRel\", \"name\": ${str(k.name)}, \"f\": ${num(k.f)}, \"r\": ${num(k.r)}, \"u\": ${num(k.u)}}"
        is Key.Group -> "{\"k\": \"Group\", \"up\": ${num(k.up)}}"
        Key.Eye -> "{\"k\": \"Eye\"}"
        Key.EyeLook -> "{\"k\": \"EyeLook\"}"
        Key.Last -> "{\"k\": \"Last\"}"
    }

    private fun num(v: Double): String = if (v.isFinite()) String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.') else "0"
    private fun num(v: Float): String = num(v.toDouble())

    private fun str(s: String): String = buildString {
        append('"')
        for (ch in s) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> if (ch < ' ') append(String.format(Locale.ROOT, "\\u%04x", ch.code)) else append(ch)
        }
        append('"')
    }
}
