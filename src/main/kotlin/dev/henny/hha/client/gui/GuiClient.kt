package dev.henny.hha.client.gui

import dev.henny.hha.net.GuiActionPayload
import dev.henny.hha.net.GuiRequestPayload
import dev.henny.hha.net.GuiStatePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

/** Clientseitiger Stand des Kontrollzentrums: letzter Server-Stand, Meldungen und das Öffnen. */
object GuiClient {

    /** Letzter Stand vom Server, `null` solange keiner da ist oder der Server HHA nicht kennt. */
    var state: GuiStatePayload? = null
        private set

    /** Meldungen für die Toasts im Menü (Text, Erfolg, Zeitpunkt in ms). */
    class Toast(val text: Text, val ok: Boolean, val time: Long)
    val toasts = ArrayList<Toast>()

    /** Taste zum Öffnen und Schließen (Standard K), gesetzt in HhaClient. */
    var menuKey: net.minecraft.client.option.KeyBinding? = null

    /** Wird auf `true` gesetzt, wenn das Menü beim nächsten Tick aufgehen soll (z. B. nach einem Chat-Befehl). */
    var openNextTick = false

    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(GuiStatePayload.ID) { payload, _ ->
            state = payload
            if (payload.message.isNotEmpty()) {
                val arg: Any = if (payload.message == "hha.gui.msg.toggled_on" || payload.message == "hha.gui.msg.toggled_off" ||
                    payload.message == "hha.gui.msg.value_set") ConfigMeta.name(payload.messageArg) else payload.messageArg
                push(Text.translatable(payload.message, arg), payload.ok)
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            state = null
            toasts.clear()
        }
    }

    fun serverAvailable(): Boolean = ClientPlayNetworking.canSend(GuiRequestPayload.ID)

    fun request() {
        if (serverAvailable()) ClientPlayNetworking.send(GuiRequestPayload)
    }

    fun send(action: String, key: String = "", value: String = "") {
        if (serverAvailable()) ClientPlayNetworking.send(GuiActionPayload(action, key, value))
    }

    fun push(text: Text, ok: Boolean) {
        toasts.add(Toast(text, ok, System.currentTimeMillis()))
        while (toasts.size > 4) toasts.removeAt(0)
    }

    fun open() {
        val client = MinecraftClient.getInstance()
        if (client.player == null) return
        client.setScreen(HhaMenuScreen())
    }

    fun tick(client: MinecraftClient) {
        if (openNextTick && client.currentScreen == null) {
            openNextTick = false
            open()
        }
    }
}
