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

        parseWhatsApp(t)?.let { (name, message) ->
            return ParsedCommand(CommandType.WHATSAPP_MESSAGE, mapOf("name" to name, "message" to message))
        }

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

            has("search", "google", "look up") ->
                cmd(CommandType.WEB_SEARCH, afterWord(t, "search", "google", "up")
                    .removePrefix("for ").trim())

            // "open the phone" means the Phone app, not a call
            !Regex("^(open|launch|start|run)\\b").containsMatchIn(t) && has("call", "phone", "dial", "ring") ->
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
        // Politeness words go everywhere; greetings / wake-word leftovers only at the start,
        // so a message like "send hello to Qasim" keeps its "hello".
        val politeness = setOf("please", "pls", "plz", "kindly")
        val leading = setOf("hey", "hello", "hi", "ok", "okay", "mojo", "moto", "mujo", "mojjo")
        val words = s.trim().split(" ").filter { it.isNotBlank() && it !in politeness }
        return words.dropWhile { it in leading }.joinToString(" ")
    }

    /**
     * "open whatsapp and search qasim contact send hello message to him",
     * "send hello to qasim on whatsapp", "whatsapp qasim saying hello" → (qasim, hello)
     */
    private fun parseWhatsApp(t: String): Pair<String, String>? {
        if (!Regex("\\bwhats ?app\\b").containsMatchIn(t)) return null
        if (!Regex("\\b(send|message|text|msg|write|saying)\\b").containsMatchIn(t)) return null
        val s = t.replace(Regex("\\b(on|in|via|using|through|from) (the )?whats ?app\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val pronouns = setOf("him", "her", "them", "it", "me", "my")
        val stop = setOf("saying", "that", "says", "and", "message", "text", "on", "whatsapp", "whats", "app", "contact")

        fun cleanName(raw: String): String =
            raw.split(" ").takeWhile { it.isNotBlank() && it !in stop }.take(2).joinToString(" ")

        val name = Regex("\\bto ([a-z]+(?: [a-z]+)?)").findAll(s)
            .map { cleanName(it.groupValues[1]) }
            .firstOrNull { it.isNotBlank() && it.split(" ")[0] !in pronouns }
            ?: Regex("\\bsearch (?:for )?([a-z]+(?: [a-z]+)?)").find(s)?.let { cleanName(it.groupValues[1]) }
            ?: Regex("\\b([a-z]+) contact\\b").find(s)?.groupValues?.get(1)
            ?: Regex("\\b(?:message|text|whatsapp) ([a-z]+)").find(s)?.let { cleanName(it.groupValues[1]) }
        if (name.isNullOrBlank() || name in pronouns) return null

        val message = (Regex("\\b(?:saying|that says|says|that)\\s+(.+)$").find(s)?.groupValues?.get(1)
            ?: Regex("\\bsend (?:a |the )?(?:message |text )?(.+?)(?: message| text)?(?: to [a-z ]+)?$")
                .find(s)?.groupValues?.get(1))
            ?.trim()
        if (message.isNullOrBlank() || message in setOf("a message", "message", "text", "a text")) return null
        return name to message
    }

    /** Everything after the first of [words] that appears in [t]. */
    private fun afterWord(t: String, vararg words: String): String {
        val words2 = t.split(" ")
        val i = words2.indexOfFirst { it in words }
        return if (i < 0) "" else words2.drop(i + 1).joinToString(" ")
    }
}
