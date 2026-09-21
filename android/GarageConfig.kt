package com.example.garagedooropener

// =============================================================================
// Configuration
//
// Everything device-specific lives in a JSON file in the app's private storage,
// not in this source. That means:
//
//   - the source can be published without stripping anything out
//   - credentials can be rotated by pasting a new config, with no rebuild
//   - handing someone the APK does not hand them your secrets
//
// It does NOT make the credentials strongly protected on the phone. App-private
// storage is not readable by other apps, which is reasonable, but anyone with
// root access or the unlocked device can get at it. Treat it roughly as well
// protected as a saved password in a browser.
//
// Any of the three connection methods is enough on its own. Provide only the
// bluetooth block and the app is a single button; provide only localUrl and it
// never tries the internet. Methods without complete credentials are hidden
// rather than shown failing.
// =============================================================================

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Web credentials. Local and internet are the same server reached two ways, so
 *  they share a login, certificate pin and entity IDs - only the address and the
 *  subnet list differ. */
data class WebConfig(
    val username: String,
    val password: String,
    val certPinSha256: String,
    val localUrl: String?,
    val localSubnets: List<String>,
    val internetUrl: String?,
    val doorStatusId: String,
    val pressButtonId: String,
) {
    val hasLocal: Boolean get() = !localUrl.isNullOrBlank()
    val hasInternet: Boolean get() = !internetUrl.isNullOrBlank()
}

data class BluetoothConfig(
    val deviceName: String,
    val serviceUuid: UUID,
    val triggerUuid: UUID,
    val secret: String,
)

data class GarageConfig(
    val web: WebConfig?,
    val bluetooth: BluetoothConfig?,
) {
    val hasWeb: Boolean get() = web != null && (web.hasLocal || web.hasInternet)
    val hasLocal: Boolean get() = web?.hasLocal == true
    val hasInternet: Boolean get() = web?.hasInternet == true
    val hasBluetooth: Boolean get() = bluetooth != null

    /** True if at least one connection method is fully specified. */
    val isUsable: Boolean get() = hasWeb || hasBluetooth

    /** Short description of what this config can do, for the settings screen. */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (hasLocal) parts += "Local"
        if (hasInternet) parts += "Internet"
        if (hasBluetooth) parts += "Bluetooth"
        return if (parts.isEmpty()) "Nothing configured" else parts.joinToString(", ")
    }
}

/** Thrown with a message meant to be shown to the user. */
class ConfigError(message: String) : Exception(message)

object ConfigStore {

    private const val FILE_NAME = "garage-config.json"

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun exists(context: Context) = file(context).exists()

    /** Returns null if no config has been saved yet. Throws ConfigError if what
     *  is saved can't be parsed - better to say so than to start up half
     *  configured. */
    fun load(context: Context): GarageConfig? {
        val f = file(context)
        if (!f.exists()) return null
        return parse(f.readText())
    }

    fun rawText(context: Context): String {
        val f = file(context)
        return if (f.exists()) f.readText() else ""
    }

    fun save(context: Context, json: String): GarageConfig {
        val config = parse(json)          // validate before writing anything
        file(context).writeText(json)
        return config
    }

    /**
     * Parses and validates. Every failure gets a message naming the specific
     * field, because "invalid config" with no detail is useless when you are
     * standing in a driveway.
     */
    fun parse(json: String): GarageConfig {
        if (json.isBlank()) throw ConfigError("Config is empty.")

        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw ConfigError("Not valid JSON: ${e.message}")
        }

        val web = if (root.has("web")) parseWeb(root.getJSONObject("web")) else null
        val bluetooth = if (root.has("bluetooth")) parseBluetooth(root.getJSONObject("bluetooth")) else null

        val config = GarageConfig(web, bluetooth)
        if (!config.isUsable) {
            throw ConfigError(
                "No usable connection method. Provide a \"web\" block with at " +
                    "least localUrl or internetUrl, or a \"bluetooth\" block, or both."
            )
        }
        return config
    }

    private fun parseWeb(o: JSONObject): WebConfig {
        val localUrl = o.optString("localUrl").takeIf { it.isNotBlank() }
        val internetUrl = o.optString("internetUrl").takeIf { it.isNotBlank() }

        if (localUrl == null && internetUrl == null) {
            throw ConfigError("\"web\" needs at least one of localUrl or internetUrl.")
        }
        for (url in listOfNotNull(localUrl, internetUrl)) {
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                throw ConfigError("URL must start with https:// (or http://): $url")
            }
        }

        val username = o.optString("username")
        val password = o.optString("password")
        if (username.isBlank()) throw ConfigError("\"web\" is missing username.")
        if (password.isBlank()) throw ConfigError("\"web\" is missing password.")

        val pin = o.optString("certPinSha256")
        if (pin.isBlank()) {
            throw ConfigError(
                "\"web\" is missing certPinSha256. The device uses a self-signed " +
                    "certificate, so the app pins its public key. make-cert.sh prints this value."
            )
        }

        val subnets = mutableListOf<String>()
        o.optJSONArray("localSubnets")?.let { arr ->
            for (i in 0 until arr.length()) subnets += arr.getString(i)
        }
        if (localUrl != null && subnets.isEmpty()) {
            throw ConfigError(
                "localUrl is set but localSubnets is empty. The app uses this to tell " +
                    "whether the phone is on a network from which localUrl is reachable, " +
                    "e.g. [\"192.168.1.\"]."
            )
        }

        return WebConfig(
            username = username,
            password = password,
            certPinSha256 = pin,
            localUrl = localUrl,
            localSubnets = subnets,
            internetUrl = internetUrl,
            doorStatusId = o.optString("doorStatusId").ifBlank { "door_status" },
            pressButtonId = o.optString("pressButtonId").ifBlank { "press_button" },
        )
    }

    private fun parseBluetooth(o: JSONObject): BluetoothConfig {
        val name = o.optString("deviceName")
        if (name.isBlank()) {
            throw ConfigError("\"bluetooth\" is missing deviceName (the name: under esphome: in the YAML).")
        }
        val secret = o.optString("secret")
        if (secret.isBlank()) {
            throw ConfigError("\"bluetooth\" is missing secret (must match ble_secret in secrets.yaml).")
        }

        val service = parseUuid(o.optString("serviceUuid"), "serviceUuid")
        val trigger = parseUuid(o.optString("triggerUuid"), "triggerUuid")

        return BluetoothConfig(name, service, trigger, secret)
    }

    private fun parseUuid(value: String, field: String): UUID {
        if (value.isBlank()) throw ConfigError("\"bluetooth\" is missing $field.")
        return try {
            UUID.fromString(value)
        } catch (e: Exception) {
            throw ConfigError("$field is not a valid UUID: $value")
        }
    }

    /** Shown on the setup screen so there is something to copy and edit. */
    val EXAMPLE = """
{
  "web": {
    "username": "admin",
    "password": "your-web-password",
    "certPinSha256": "value printed by make-cert.sh",
    "localUrl": "https://192.168.1.50",
    "localSubnets": ["192.168.1."],
    "internetUrl": "https://you.duckdns.org:12345",
    "doorStatusId": "door_status",
    "pressButtonId": "press_button"
  },
  "bluetooth": {
    "deviceName": "garagedoor",
    "serviceUuid": "40623379-b09a-4536-a3f8-7643c85f39cf",
    "triggerUuid": "a55d5ca3-ad03-4b27-ac70-2dfc8918c74b",
    "secret": "must match ble_secret in secrets.yaml"
  }
}
""".trimIndent()
}
