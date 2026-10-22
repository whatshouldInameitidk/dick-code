package top.niunaijun.blackboxa.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import top.niunaijun.blackboxa.app.App
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Delivers the crash report to the crash reporter bot.
 *
 * A bot can only write into a chat that wrote to it first, so the owner opens the bot
 * once and presses start, the chat id is then read from the pending updates of the bot
 * and kept in the preferences of the app.
 */
object TelegramReporter {

    const val BOT_USERNAME = "InvXCrashReporterBot"
    const val BOT_ID = "8565342119"
    const val BOT_URL = "https://t.me/$BOT_USERNAME"

    /**
     * Token of the bot, it is shipped with the app so the report can be sent without
     * any setup. Anyone with the apk can use the bot with it.
     */
    const val DEFAULT_TOKEN = "8565342119:AAHLpnn3wTcdBeNu4b-Ek_vadTUJ83A_UOM"
    const val DEFAULT_CHAT_ID = "1910273438"

    private const val PREFS = "crash_report"
    private const val KEY_TOKEN = "bot_token"
    private const val KEY_CHAT = "bot_chat_id"
    private const val API = "https://api.telegram.org"

    class SendResult(val success: Boolean, val message: String)

    private val prefs: SharedPreferences
        get() = App.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun token(): String {
        return prefs.getString(KEY_TOKEN, "")?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKEN
    }

    fun chatId(): String {
        return prefs.getString(KEY_CHAT, "")?.takeIf { it.isNotBlank() } ?: DEFAULT_CHAT_ID
    }

    private fun saveChatId(id: String) {
        prefs.edit().putString(KEY_CHAT, id).apply()
    }

    /**
     * Sends the report to the owner of the bot.
     */
    fun sendToOwner(token: String, file: File, caption: String): SendResult {
        if (token.isBlank()) return SendResult(false, "no bot token")

        var chatId = chatId()
        if (chatId.isBlank()) {
            chatId = resolveChatId(token) ?: DEFAULT_CHAT_ID
            saveChatId(chatId)
        }

        var result = sendDocument(token, chatId, file, caption)
        if (!result.success && result.message.contains("chat not found", true)) {
            // the chat id is stale, look for the owner again
            saveChatId("")
            val fresh = resolveChatId(token)
            if (fresh != null) {
                saveChatId(fresh)
                result = sendDocument(token, fresh, file, caption)
            }
        }
        return result
    }

    /**
     * Reads the chat of the last person that wrote to the bot. offset -1 returns the
     * last update without confirming it, the updates stay pending for the next call.
     */
    fun resolveChatId(token: String): String? {
        val body = get("$API/bot$token/getUpdates?offset=-1&limit=100&timeout=0")
        if (body.isBlank()) return null
        return try {
            val results = JSONObject(body).optJSONArray("result") ?: return null
            var chatId: String? = null
            for (i in 0 until results.length()) {
                val update = results.getJSONObject(i)
                val message = update.optJSONObject("message")
                    ?: update.optJSONObject("edited_message")
                    ?: continue
                val chat = message.optJSONObject("chat") ?: continue
                val id = chat.optLong("id")
                if (id != 0L) chatId = id.toString()
            }
            chatId
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Sends the report as a file, a crash log is way over the 4096 characters of a
     * message and the file keeps the formatting.
     */
    fun sendDocument(
        token: String,
        chatId: String,
        file: File,
        caption: String
    ): SendResult {
        if (token.isBlank()) return SendResult(false, "no bot token")
        val boundary = "----BlackBoxCrash${System.currentTimeMillis()}"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$API/bot$token/sendDocument").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setChunkedStreamingMode(8192)
            }
            conn.outputStream.use { out ->
                writeField(out, boundary, "chat_id", chatId)
                writeField(out, boundary, "caption", caption)
                writeFile(out, boundary, "document", file)
                out.write("--$boundary--\r\n".toByteArray())
                out.flush()
            }
            readResponse(conn)
        } catch (t: Throwable) {
            SendResult(false, t.toString())
        } finally {
            conn?.disconnect()
        }
    }

    private fun get(url: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (t: Throwable) {
            ""
        } finally {
            conn?.disconnect()
        }
    }

    private fun writeField(out: OutputStream, boundary: String, name: String, value: String) {
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
        out.write(value.toByteArray())
        out.write("\r\n".toByteArray())
    }

    private fun writeFile(out: OutputStream, boundary: String, name: String, file: File) {
        out.write("--$boundary\r\n".toByteArray())
        out.write(
            "Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\n".toByteArray()
        )
        out.write("Content-Type: text/plain; charset=utf-8\r\n\r\n".toByteArray())
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read > 0) {
                out.write(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        out.write("\r\n".toByteArray())
    }

    private fun readResponse(conn: HttpURLConnection): SendResult {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code in 200..299 && body.contains("\"ok\":true")) {
            return SendResult(true, "sent to @$BOT_USERNAME")
        }
        return SendResult(false, "http $code: ${extractError(body)}")
    }

    private fun extractError(body: String): String {
        val match = Regex("\"description\"\\s*:\\s*\"(.*?)\"").find(body)
        return match?.groupValues?.get(1) ?: body.take(300).ifBlank { "unknown error" }
    }
}
