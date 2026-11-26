package com.mv.wallpapers.plugin

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Deletes a wallpaper from the GitHub repo. Needs a token with Contents: read and write. */
object GitHubApi {
    private const val REPO = "markoman013/mv-wallpapers"
    const val KEY_TOKEN = "github_token"

    var token: String?
        get() = PreferencesManager.preferences.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = PreferencesManager.preferences.edit().putString(KEY_TOKEN, value?.trim()).apply()

    /** deleteFile result when the file isn't on GitHub (any more) under that path. */
    const val ALREADY_GONE = "Already deleted on GitHub"

    /** Returns null on success, or a short error message. Call off the main thread. */
    fun deleteFile(path: String): String? {
        val t = token ?: return "Add a GitHub token in Settings first"
        val url = "https://api.github.com/repos/$REPO/contents/" +
            path.split('/').joinToString("/") { Uri.encode(it) }
        return try {
            // 1. Current version (sha) of the file.
            val get = open("$url?ref=main", "GET", t)
            val sha = try {
                when (get.responseCode) {
                    200 -> JSONObject(get.inputStream.bufferedReader().use { it.readText() }).getString("sha")
                    404 -> return ALREADY_GONE
                    401 -> return "GitHub token is invalid or expired"
                    else -> return "GitHub error ${get.responseCode}"
                }
            } finally {
                get.disconnect()
            }
            // 2. Delete it (a normal commit, so it can be restored from the repo history).
            val del = open(url, "DELETE", t)
            try {
                del.doOutput = true
                del.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject()
                    .put("message", "Delete ${path.substringAfterLast('/')} (from TV)")
                    .put("sha", sha)
                    .put("branch", "main")
                del.outputStream.use { it.write(body.toString().toByteArray()) }
                when (del.responseCode) {
                    200 -> null
                    401 -> "GitHub token is invalid or expired"
                    403, 404 -> "This token can't change the repo (needs Contents: read and write)"
                    409 -> "GitHub was busy, try again"
                    else -> "GitHub error ${del.responseCode}"
                }
            } finally {
                del.disconnect()
            }
        } catch (e: Exception) {
            "No connection to GitHub"
        }
    }

    /**
     * Moves a wallpaper: uploads it under [newPath] (a normal commit), then deletes the old
     * file. Returns null on success, or a short error message. Call off the main thread.
     */
    /**
     * Moves/renames a photo in ONE commit (no separate "delete old file" commit), using the
     * Git data API: the new tree points the new path at the same file and drops the old path,
     * so nothing is re-uploaded.
     */
    @Suppress("UNUSED_PARAMETER")
    fun moveFile(oldPath: String, newPath: String, rawUrl: String, message: String? = null): String? {
        val msg = message ?: "Move ${oldPath.substringAfterLast('/')} to ${newPath.substringBeforeLast('/').substringAfterLast('/')} (from TV)"
        return commitChanges(mapOf(oldPath to newPath), msg).let { if (it == ALREADY_GONE) "Couldn't find the photo on GitHub" else it }
    }

    /**
     * Moves/renames ([old] to new path) or deletes (new path null) photos, all in ONE commit.
     * Photos no longer on GitHub are skipped; [ALREADY_GONE] when none of them are there.
     * Returns null on success, or a short error message. Call off the main thread.
     */
    fun commitChanges(changes: Map<String, String?>, message: String): String? {
        val t = token ?: return "Add a GitHub token in Settings first"
        return try {
            repeat(2) { attempt ->
                val head = json("$API/git/ref/heads/main", "GET", t, null) ?: return "GitHub token is invalid or expired"
                val parent = head.getJSONObject("object").getString("sha")
                val baseTree = (json("$API/git/commits/$parent", "GET", t, null) ?: return "GitHub error")
                    .getJSONObject("tree").getString("sha")
                // Every file's current version, in one request.
                val listing = json("$API/git/trees/$baseTree?recursive=1", "GET", t, null) ?: return "GitHub error"
                val shas = HashMap<String, String>()
                val files = listing.getJSONArray("tree")
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    if (f.optString("type") == "blob") shas[f.getString("path")] = f.getString("sha")
                }
                val entries = org.json.JSONArray()
                for ((old, new) in changes) {
                    val blob = shas[old] ?: continue
                    if (new != null) {
                        if (new in shas) return "A photo named ${new.substringAfterLast('/')} is already in that collection"
                        entries.put(JSONObject().put("path", new).put("mode", "100644").put("type", "blob").put("sha", blob))
                    }
                    entries.put(JSONObject().put("path", old).put("mode", "100644").put("type", "blob").put("sha", JSONObject.NULL))
                }
                if (entries.length() == 0) return ALREADY_GONE
                val tree = json("$API/git/trees", "POST", t, JSONObject().put("base_tree", baseTree).put("tree", entries))
                    ?: return "This token can't change the repo (needs Contents: read and write)"
                val commit = json("$API/git/commits", "POST", t, JSONObject()
                    .put("message", message).put("tree", tree.getString("sha"))
                    .put("parents", org.json.JSONArray().put(parent))) ?: return "GitHub error"
                val update = open("$API/git/refs/heads/main", "PATCH", t)
                try {
                    update.doOutput = true
                    update.setRequestProperty("Content-Type", "application/json")
                    update.outputStream.use { it.write(JSONObject().put("sha", commit.getString("sha")).toString().toByteArray()) }
                    when (update.responseCode) {
                        200 -> return null
                        422 -> if (attempt == 0) return@repeat  // main moved on meanwhile: try again
                        401 -> return "GitHub token is invalid or expired"
                        403, 404 -> return "This token can't change the repo (needs Contents: read and write)"
                    }
                    return "GitHub error ${update.responseCode}"
                } finally {
                    update.disconnect()
                }
            }
            "GitHub is busy, try again"
        } catch (e: Exception) {
            "No connection to GitHub"
        }
    }

    private const val API = "https://api.github.com/repos/$REPO"

    /** JSON request; null when GitHub doesn't answer 200/201. */
    private fun json(url: String, method: String, token: String, body: JSONObject?): JSONObject? {
        val c = open(url, method, token)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (c.responseCode !in 200..201) return null
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun contentsUrl(path: String) =
        "https://api.github.com/repos/$REPO/contents/" + path.split('/').joinToString("/") { Uri.encode(it) }

    private fun open(url: String, method: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            useCaches = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
}
