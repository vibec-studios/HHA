package dev.henny.hha.logic.boss

/**
 * GENERIERT von tools/hha_assets/voice_lines.py: wie viele Ticks jede gesprochene Dialogzeile dauert.
 * So bleibt eine Szene stehen, bis der Satz zu Ende gesprochen ist. Ohne Einträge gelten die Zeiten aus dem Drehbuch.
 */
object HhaVoiceLengths {

    private val LENGTHS: Map<String, Int> = mapOf(
        "hha.hermes.line.descent.dead_know" to 116,
        "hha.hermes.line.descent.enough" to 38,
        "hha.hermes.line.descent.every_soul" to 131,
        "hha.hermes.line.descent.serpents" to 125,
        "hha.hermes.line.descent.way_short" to 76,
        "hha.hermes.line.feather.falls" to 121,
        "hha.hermes.line.feather.moment" to 42,
        "hha.hermes.line.feather.other_job" to 79,
        "hha.hermes.line.feather.thief" to 131,
        "hha.hermes.line.feather.touched" to 131,
        "hha.hermes.line.finale.another_day" to 74,
        "hha.hermes.line.finale.catch" to 130,
        "hha.hermes.line.finale.caught" to 86,
        "hha.hermes.line.finale.delivered" to 133,
        "hha.hermes.line.finale.hades_seal" to 85,
        "hha.hermes.line.finale.second_letter" to 123,
        "hha.hermes.line.finale.sign" to 45,
        "hha.hermes.line.finale.stinger" to 78,
        "hha.hermes.line.guide.back" to 86,
        "hha.hermes.line.guide.end" to 85,
        "hha.hermes.line.guide.follow" to 87,
        "hha.hermes.line.guide.slow" to 119,
        "hha.hermes.line.prologue.altar" to 141,
        "hha.hermes.line.prologue.apollo" to 169,
        "hha.hermes.line.prologue.catch" to 62,
        "hha.hermes.line.prologue.condition" to 104,
        "hha.hermes.line.prologue.delivery" to 86,
        "hha.hermes.line.prologue.pull" to 89,
        "hha.hermes.line.prologue.seal" to 98,
        "hha.hermes.line.prologue.sender" to 161,
        "hha.hermes.line.shade.ash" to 163,
        "hha.hermes.line.shade.breath" to 134,
        "hha.hermes.line.shade.coin" to 133,
        "hha.hermes.line.shade.contract" to 134,
        "hha.hermes.line.shade.contract_end" to 78,
        "hha.hermes.line.shade.gate" to 150,
        "hha.hermes.line.shade.open" to 119,
        "hha.hermes.line.shade.snakes" to 98,
        "hha.hermes.line.shade.stay" to 61,
        "hha.hermes.line.shade.voice" to 129,
        "hha.hermes.line.shade.warning" to 87,
        "hha.hermes.line.shade.who" to 85,
        "hha.hermes.line.shade.wings" to 91,
        "hha.hermes.line.zeus.breath" to 100,
        "hha.hermes.line.zeus.end_it" to 73,
        "hha.hermes.line.zeus.feather" to 140,
        "hha.hermes.line.zeus.fun" to 93,
        "hha.hermes.line.zeus.name" to 41,
        "hha.hermes.line.zeus.rule" to 130,
        "hha.hermes.line.zeus.yes_father" to 36,
    )

    fun ticks(key: String, base: Int): Int = maxOf(base, LENGTHS[key] ?: 0)
}
