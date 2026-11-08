package com.mojo.app.engine.nlu
import android.content.Context
import com.mojo.app.commands.CommandType
import com.mojo.app.commands.ParsedCommand

/**
 * Turns a spoken sentence into a command. Matches whole words/phrases only,
 * so "dim" doesn't fire inside "dimension". Specific phrases are checked
 * before broad ones ("turn on wifi" before "turn on").
 */
class MojoIntentEngine(private val context: Context) {
    fun init() {}

    fun parse(text: String): ParsedCommand {
        val t = text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .let { stripFillers(it) }

        fun has(vararg phrases: String) = phrases.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }
        fun onOff(vararg things: String): Boolean? = when {
            things.none { has(it) } -> null
            has("off", "disable", "deactivate", "stop") -> false
            has("on", "enable", "activate", "start") -> true
            else -> null
        }
        fun cmd(type: CommandType, param: String? = null) =
            ParsedCommand(type, if (param == null) emptyMap() else mapOf("param" to param))

        onOff("flashlight", "torch", "flash light")?.let {
            return cmd(if (it) CommandType.FLASHLIGHT_ON else CommandType.FLASHLIGHT_OFF)
        }
        onOff("wifi", "wi fi")?.let { return cmd(if (it) CommandType.WIFI_ON else CommandType.WIFI_OFF) }
        onOff("bluetooth")?.let { return cmd(if (it) CommandType.BLUETOOTH_ON else CommandType.BLUETOOTH_OFF) }

        return when {
            has("volume up", "louder", "increase volume", "turn it up", "raise volume") -> cmd(CommandType.VOLUME_UP)
            has("volume down", "quieter", "decrease volume", "lower volume", "lower the volume", "turn it down") -> cmd(CommandType.VOLUME_DOWN)
            has("mute", "silent", "silence") -> cmd(CommandType.VOLUME_MUTE)
            has("brightness up", "brighter", "increase brightness") -> cmd(CommandType.BRIGHTNESS_UP)
            has("brightness down", "dimmer", "dim the screen", "decrease brightness", "lower brightness") -> cmd(CommandType.BRIGHTNESS_DOWN)
            has("screenshot", "screen shot", "capture screen") -> cmd(CommandType.TAKE_SCREENSHOT)
            has("lock screen", "lock the phone", "lock phone", "lock my phone") -> cmd(CommandType.LOCK_SCREEN)
            has("recent apps", "recents", "app switcher") -> cmd(CommandType.RECENT_APPS)
            has("scroll down") -> cmd(CommandType.SCROLL_DOWN)
            has("scroll up") -> cmd(CommandType.SCROLL_UP)
            has("go home", "home screen") || t == "home" -> cmd(CommandType.GO_HOME)
            has("go back") || t == "back" -> cmd(CommandType.GO_BACK)

            has("call", "phone", "dial", "ring") ->
                cmd(CommandType.CALL_CONTACT, afterWord(t, "call", "phone", "dial", "ring").removePrefix("to ").trim())
            has("open", "launch", "start", "run") ->
                cmd(CommandType.OPEN_APP, afterWord(t, "open", "launch", "start", "run")
                    // "open whatsapp and send a message" → the app is just "whatsapp"
                    .split(Regex("\\b(and|then|to|so)\\b"))[0]
                    .split(" ").filter { it.isNotBlank() && it !in setOf("the", "my", "app", "application") }
                    .joinToString(" ").trim())

            has("help", "what can you do") -> cmd(CommandType.MOJO_HELP)
            has("status", "are you there", "are you working") -> cmd(CommandType.MOJO_STATUS)
            has("stop", "go to sleep", "sleep") -> cmd(CommandType.MOJO_STOP)
            else -> ParsedCommand(CommandType.UNKNOWN, mapOf("original" to t))
        }
    }

    /**
     * Drops politeness and wake-word leftovers that the recogniser often includes:
     * "hello mojo open the teams please" → "open the teams".
     */
    private fun stripFillers(t: String): String {
        var s = " $t "
        for (phrase in listOf("can you", "could you", "would you", "will you", "for me", "right now")) {
            s = s.replace(" $phrase ", " ")
        }
        val fillers = setOf("please", "pls", "plz", "kindly", "hey", "hello", "hi", "ok", "okay",
            "mojo", "moto", "mujo", "mojjo")
        return s.trim().split(" ").filter { it.isNotBlank() && it !in fillers }.joinToString(" ")
    }

    /** Everything after the first of [words] that appears in [t]. */
    private fun afterWord(t: String, vararg words: String): String {
        val words2 = t.split(" ")
        val i = words2.indexOfFirst { it in words }
        return if (i < 0) "" else words2.drop(i + 1).joinToString(" ")
    }
}
