package com.mojo.app.commands
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mojo.app.accessibility.MojoAccessibilityService

class CommandExecutor(private val context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    data class CommandResult(val success: Boolean, val feedback: String)

    fun execute(command: ParsedCommand, originalText: String): CommandResult = try {
        when (command.type) {
            CommandType.CALL_CONTACT   -> makeCall(command.param)
            CommandType.OPEN_APP       -> openApp(command.param)
            CommandType.GO_HOME        -> accessibility(MojoAccessibilityService.ACTION_HOME)
            CommandType.GO_BACK        -> accessibility(MojoAccessibilityService.ACTION_BACK)
            CommandType.RECENT_APPS    -> accessibility(MojoAccessibilityService.ACTION_RECENTS)
            CommandType.SCROLL_UP      -> accessibility(MojoAccessibilityService.ACTION_SCROLL_UP)
            CommandType.SCROLL_DOWN    -> accessibility(MojoAccessibilityService.ACTION_SCROLL_DOWN)
            CommandType.TAKE_SCREENSHOT-> accessibility(MojoAccessibilityService.ACTION_SCREENSHOT)
            CommandType.LOCK_SCREEN    -> accessibility(MojoAccessibilityService.ACTION_LOCK)
            CommandType.VOLUME_UP      -> vol(AudioManager.ADJUST_RAISE)
            CommandType.VOLUME_DOWN    -> vol(AudioManager.ADJUST_LOWER)
            CommandType.VOLUME_MUTE    -> vol(AudioManager.ADJUST_MUTE)
            CommandType.FLASHLIGHT_ON  -> torch(true)
            CommandType.FLASHLIGHT_OFF -> torch(false)
            CommandType.BRIGHTNESS_UP  -> brightness(true)
            CommandType.BRIGHTNESS_DOWN-> brightness(false)
            CommandType.WIFI_ON        -> wifiSettings()
            CommandType.WIFI_OFF       -> wifiSettings()
            CommandType.BLUETOOTH_ON   -> btSettings()
            CommandType.BLUETOOTH_OFF  -> btSettings()
            CommandType.MOJO_STOP   -> CommandResult(true, "Okay. Say my wake word when you need me.")
            CommandType.MOJO_HELP   -> CommandResult(true, "You can say: call someone from your contacts, open an app, volume up or down, brightness up or down, flashlight on or off, wifi, bluetooth, take a screenshot, lock the phone, scroll, go home, or go back.")
            CommandType.MOJO_STATUS -> CommandResult(true, "I'm here and listening for my wake word.")
            CommandType.UNKNOWN     -> CommandResult(false, "I did not understand that. Try saying help.")
            else                    -> CommandResult(false, "Command not yet implemented.")
        }
    } catch (e: Exception) {
        CommandResult(false, "Something went wrong. Please try again.")
    }

    /**
     * Android 10+ blocks starting screens from the background. A bound
     * AccessibilityService lifts that block, so app/call/settings commands
     * need it switched on.
     */
    private fun canOpenScreens(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || MojoAccessibilityService.instance != null

    private val needAccessibility =
        CommandResult(false, "Turn on MOJO in Accessibility settings so I can open things for you.")

    private fun makeCall(spoken: String): CommandResult {
        if (spoken.isBlank()) return CommandResult(false, "Who should I call?")
        val digits = spoken.filter { it.isDigit() || it == '+' }
        val (label, number) = if (digits.length >= 3 && spoken.none { it.isLetter() }) {
            spoken to digits
        } else {
            findContact(spoken) ?: return CommandResult(false, "I couldn't find $spoken in your contacts.")
        }
        if (!canOpenScreens()) return needAccessibility
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        context.startActivity(Intent(action, Uri.parse("tel:${Uri.encode(number)}")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        return CommandResult(true, if (canCall) "Calling $label" else "Opening the dialer for $label")
    }

    /** Best contact match for a spoken name: exact name first, then starts-with, then contains. */
    private fun findContact(spoken: String): Pair<String, String>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) return null
        val name = spoken.trim().lowercase()
        val found = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                found += n to num
            }
        }
        return found.firstOrNull { it.first.lowercase() == name }
            ?: found.firstOrNull { it.first.lowercase().startsWith(name) }
            ?: found.firstOrNull()
    }

    private fun openApp(name: String): CommandResult {
        if (name.isBlank()) return CommandResult(false, "Which app should I open?")
        val pm = context.packageManager
        val wanted = name.lowercase().replace(" ", "")
        // Launcher apps only; visible to us via the <queries> entry in the manifest
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val match = apps.firstOrNull { it.first.lowercase().replace(" ", "") == wanted }
            ?: apps.firstOrNull { it.first.lowercase().replace(" ", "").startsWith(wanted) }
            ?: apps.firstOrNull { it.first.lowercase().replace(" ", "").contains(wanted) }
            ?: return CommandResult(false, "I couldn't find an app called $name.")
        if (!canOpenScreens()) return needAccessibility
        val intent = pm.getLaunchIntentForPackage(match.second)
            ?: return CommandResult(false, "I can't open ${match.first}.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return CommandResult(true, "Opening ${match.first}")
    }

    private fun vol(dir: Int): CommandResult {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return CommandResult(true, when (dir) {
            AudioManager.ADJUST_RAISE -> "Volume up"
            AudioManager.ADJUST_LOWER -> "Volume down"
            else -> "Muted"
        })
    }

    private fun torch(on: Boolean): CommandResult = try {
        camera.setTorchMode(camera.cameraIdList[0], on)
        CommandResult(true, if (on) "Flashlight on" else "Flashlight off")
    } catch (e: Exception) { CommandResult(false, "Could not control flashlight") }

    private fun brightness(up: Boolean): CommandResult = try {
        if (Settings.System.canWrite(context)) {
            val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS,
                if (up) minOf(255, cur + 40) else maxOf(10, cur - 40))
            CommandResult(true, if (up) "Brightness increased" else "Brightness decreased")
        } else if (!canOpenScreens()) {
            needAccessibility
        } else {
            context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            CommandResult(false, "Allow MOJO to change system settings, then ask again.")
        }
    } catch (e: Exception) { CommandResult(false, "Could not change brightness") }

    private fun wifiSettings(): CommandResult {
        if (!canOpenScreens()) return needAccessibility
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        return CommandResult(true, "Opening WiFi settings")
    }

    private fun btSettings(): CommandResult {
        if (!canOpenScreens()) return needAccessibility
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        return CommandResult(true, "Opening Bluetooth settings")
    }

    private fun accessibility(action: String): CommandResult {
        val svc = MojoAccessibilityService.instance
        return if (svc != null) {
            svc.performNamedAction(action)
            CommandResult(true, "Done")
        } else {
            CommandResult(false, "Turn on MOJO in Accessibility settings for this command.")
        }
    }
}