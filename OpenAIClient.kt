package com.youcandoit.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class OpenAIClient(private val context: Context) {
    private val prefs = context.getSharedPreferences("ycdi_ai", Context.MODE_PRIVATE)
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) { prefs.edit().putString("api_key", value.trim()).apply() }

    private fun request(method: String, path: String, body: ByteArray? = null, contentType: String = "application/json"): String {
        require(apiKey.isNotBlank()) { "أضيفي مفتاح OpenAI أولاً من الإعدادات." }
        val c = URL("https://api.openai.com/v1$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $apiKey")
        c.setRequestProperty("Content-Type", contentType)
        c.connectTimeout = 30000
        c.readTimeout = 120000
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (c.responseCode !in 200..299) throw IOException("OpenAI ${c.responseCode}: $text")
        return text
    }

    fun transcribe(file: File, language: String = "ar"): String {
        val boundary = "----YCDI-${UUID.randomUUID()}"
        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", "gpt-4o-transcribe")
        field("language", language)
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray())
        file.inputStream().use { it.copyTo(out) }
        out.write("\r\n--$boundary--\r\n".toByteArray())
        return JSONObject(request("POST", "/audio/transcriptions", out.toByteArray(), "multipart/form-data; boundary=$boundary")).optString("text")
    }

    fun uploadAndAnalyze(uri: Uri, name: String, prompt: String): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("تعذر قراءة الملف")
        val boundary = "----YCDI-${UUID.randomUUID()}"
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val out = ByteArrayOutputStream()
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nuser_data\r\n".toByteArray())
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${name.replace("\"", "_")}\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
        out.write(bytes); out.write("\r\n--$boundary--\r\n".toByteArray())
        val uploaded = JSONObject(request("POST", "/files", out.toByteArray(), "multipart/form-data; boundary=$boundary"))
        val fileId = uploaded.getString("id")
        val input = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().put(JSONObject().apply { put("type", "input_text"); put("text", prompt) }).put(JSONObject().apply { put("type", "input_file"); put("file_id", fileId) }))
        })
        val body = JSONObject().apply { put("model", "gpt-5.6-luna"); put("input", input); put("store", false) }.toString().toByteArray()
        val response = JSONObject(request("POST", "/responses", body))
        return extractOutputText(response)
    }

    fun analyzeText(text: String, prompt: String): String {
        val body = JSONObject().apply {
            put("model", "gpt-5.6-luna"); put("store", false)
            put("input", JSONArray().put(JSONObject().apply {
                put("role", "user"); put("content", "$prompt\n\nالمحتوى:\n$text")
            }))
        }.toString().toByteArray()
        return extractOutputText(JSONObject(request("POST", "/responses", body)))
    }

    private fun extractOutputText(o: JSONObject): String {
        o.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = o.optJSONArray("output") ?: return o.toString()
        val sb = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) sb.append(content.optJSONObject(j)?.optString("text", "") ?: "")
        }
        return sb.toString().ifBlank { o.toString() }
    }
}
