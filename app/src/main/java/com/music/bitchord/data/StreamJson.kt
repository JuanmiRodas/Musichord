package com.music.bitchord.data

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BitChord Audio: the big JSON stores (Song DNA, its people…) read and written one entry at a
 * time. Read whole as text, parsed whole and only then turned into entries, a store of
 * thousands of songs held three copies of itself at once; saved by building one string of all
 * of it, every few seconds while it grew. With the app's memory near its limit, one of those
 * peaks was enough to crash it.
 *
 * The file shape doesn't change: `{ <top fields>, "<field>": { "<key>": {…}, … } }`.
 */
object StreamJson {

    /**
     * Reads [file]'s object at [field] entry by entry, handing each to [onEntry]. Other top-level
     * fields go to [onTop] (numbers, strings and booleans as such). Returns false if unreadable.
     */
    fun readEntries(
        file: File,
        field: String,
        onEntry: (String, JSONObject) -> Unit,
        onTop: (String, Any?) -> Unit = { _, _ -> },
    ): Boolean = runCatching {
        file.bufferedReader(Charsets.UTF_8, 64 * 1024).use { input ->
            val r = JsonReader(input)
            r.isLenient = true
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                if (name == field && r.peek() == JsonToken.BEGIN_OBJECT) {
                    r.beginObject()
                    while (r.hasNext()) {
                        val key = r.nextName()
                        if (r.peek() == JsonToken.BEGIN_OBJECT) {
                            val entry = readObject(r)
                            runCatching { onEntry(key, entry) }
                        } else r.skipValue()
                    }
                    r.endObject()
                } else {
                    onTop(name, readValue(r))
                }
            }
            r.endObject()
        }
        true
    }.getOrDefault(false)

    /**
     * Writes `{ top…, "field": { entries… } }` to [file] through a temporary file, each entry
     * turned into text on its own.
     */
    fun writeEntries(file: File, top: JSONObject, field: String, entries: Sequence<Pair<String, JSONObject>>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter(Charsets.UTF_8, 64 * 1024).use { out ->
            out.write("{")
            var first = true
            top.keys().forEach { k ->
                if (!first) out.write(",")
                first = false
                out.write(JSONObject.quote(k))
                out.write(":")
                out.write(JSONObject.wrap(top.get(k)).let { v -> if (v is String) JSONObject.quote(v) else v.toString() })
            }
            if (!first) out.write(",")
            out.write(JSONObject.quote(field))
            out.write(":{")
            var firstEntry = true
            for ((k, v) in entries) {
                if (!firstEntry) out.write(",")
                firstEntry = false
                out.write(JSONObject.quote(k))
                out.write(":")
                out.write(v.toString())
            }
            out.write("}}")
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun readObject(r: JsonReader): JSONObject {
        val o = JSONObject()
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            o.put(name, readValue(r) ?: JSONObject.NULL)
        }
        r.endObject()
        return o
    }

    private fun readArray(r: JsonReader): JSONArray {
        val a = JSONArray()
        r.beginArray()
        while (r.hasNext()) a.put(readValue(r) ?: JSONObject.NULL)
        r.endArray()
        return a
    }

    private fun readValue(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_OBJECT -> readObject(r)
        JsonToken.BEGIN_ARRAY -> readArray(r)
        JsonToken.STRING -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.NULL -> { r.nextNull(); null }
        JsonToken.NUMBER -> {
            // Kept as JSONObject would: whole numbers as Long, the rest as Double.
            val s = r.nextString()
            s.toLongOrNull() ?: s.toDouble()
        }
        else -> { r.skipValue(); null }
    }

    /** Used heap in MB, for the log. */
    fun usedMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    }
}
