package com.mv.wallpapers.plugin

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/** Minimum-resolution label, shared by the settings page. */
object OptionsLabels {
    fun minWidth(w: Int) = WallpaperLibrary.MIN_WIDTH_OPTIONS.firstOrNull { it.first == w }?.second ?: "Any size"
}

/**
 * The Settings tab: settings list on the left, the highlighted setting's choices (or what
 * it does) on the right. Left/right move between the two lists. [onChanged] tells the home
 * screen to refresh its counts.
 */
class SettingsPage(private val activity: Activity, root: View, private val onChanged: () -> Unit) {

    /** [icon]: an action with its own icon instead of a checkbox. */
    private class Choice(
        val label: String, val isCurrent: () -> Boolean, val icon: Int = 0,
        /** Grey line under the label (actions in Library settings). */
        val sub: (() -> String)? = null,
        /** Stay in the list after OK (actions that update their own line). */
        val stay: Boolean = false,
        val apply: () -> Unit
    )

    /** A setting with a list of [choices], or a one-press [action] (no submenu). */
    private class Setting(
        val icon: Int,
        val label: String,
        val value: () -> String,
        val choices: () -> List<Choice> = { emptyList() },
        val okHint: String = "Press OK to run.",
        /** Extra block under the description on the right (library stats). */
        val info: (() -> CharSequence)? = null,
        val action: (() -> Unit)? = null
    )


    /** Library info: bold heading, bold photo counts, then the repo size. */
    private fun libraryInfo(): CharSequence {
        if (repoSize == null) Thread { fetchRepoSize(); activity.runOnUiThread { if (repoSize != null) refreshLists() } }.start()
        val lib = WallpaperLibrary.load(activity.applicationContext, allowNetwork = false) ?: return "Library not loaded yet."
        val all = lib.items.size
        val uhd = lib.items.count { WallpaperLibrary.is4k(it) }
        val sb = android.text.SpannableStringBuilder()
        fun bold(s: String) {
            val st = sb.length; sb.append(s)
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sb.length, 0)
        }
        bold("GITHUB LIBRARY INFO"); sb.append("\n")
        bold("$all"); sb.append(" photos total\n")
        bold("$uhd"); sb.append(" at 2160p+ (4K)\n")
        bold("${all - uhd}"); sb.append(" at 1080p+ (<2160p)\n\n")
        sb.append("GitHub repo size: ${repoSize ?: "checking…"} (5 GB soft limit)")
        return sb
    }

    private fun refreshLibrary() {
        Toast.makeText(activity, "Refreshing…", Toast.LENGTH_SHORT).show()
        Thread {
            val lib = WallpaperLibrary.load(activity.applicationContext, allowNetwork = true, fresh = true)
            fetchRepoSize()
            activity.runOnUiThread {
                Toast.makeText(activity, "Library refreshed: ${lib?.items?.size ?: 0} wallpapers", Toast.LENGTH_SHORT).show()
                refreshLists()
                onChanged()
            }
        }.start()
    }

    /** Size of the GitHub repo (photos + history), from the public GitHub API. */
    private var repoSize: String? = null

    private fun fetchRepoSize() {
        try {
            val c = java.net.URL("https://api.github.com/repos/markoman013/mv-wallpapers").openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 8000; c.readTimeout = 8000
            if (c.responseCode == 200) {
                val kb = org.json.JSONObject(c.inputStream.bufferedReader().readText()).optLong("size", -1)
                if (kb >= 0) repoSize = String.format(java.util.Locale.US, "%.2f GB", kb / 1_048_576.0)
            }
            c.disconnect()
        } catch (_: Exception) {}
    }

    private val settings = listOf(
        Setting(
            R.drawable.ic_library,
            "Library settings",
            { "Refresh, reshuffle, updates and GitHub token" },
            info = { libraryInfo() },
            choices = {
                listOf(
                    Choice("Refresh library", { false }, R.drawable.ic_refresh, sub = {
                        WallpaperLibrary.load(activity.applicationContext, allowNetwork = false)
                            ?.let { "${it.items.size} photos total" } ?: "Reload the list from GitHub"
                    }, stay = true) { refreshLibrary() },
                    Choice("Reshuffle", { false }, R.drawable.ic_shuffle, sub = {
                        val lib = WallpaperLibrary.load(activity.applicationContext, allowNetwork = false)
                        (if (lib != null) "${WallpaperLibrary.selectedCount(lib)} selected  ·  " else "") +
                            "${WallpaperLibrary.notShownYet()} yet to show this round"
                    }, stay = true) {
                        WallpaperLibrary.reset(WallpaperLibrary.load(activity.applicationContext, allowNetwork = false))
                        Toast.makeText(activity, "Reshuffled. The new round starts with the next wallpaper change.", Toast.LENGTH_SHORT).show()
                    },
                    Choice("Check for updates", { false }, R.drawable.ic_download, sub = { updateStatus }, stay = true) { checkOrInstall() },
                    Choice("GitHub token", { false }, R.drawable.ic_key, sub = {
                        if (GitHubApi.token != null) "Added · OK to replace, or save it empty to remove"
                        else "Not added · needed to delete or move photos from the TV"
                    }, stay = true) { askForToken() },
                )
            }
        ),
        Setting(
            R.drawable.ic_schedule,
            "Wallpapers per hour",
            { "${WallpaperLibrary.batchSize} per hour" },
            {
                WallpaperLibrary.BATCH_OPTIONS.map { n ->
                    Choice("$n per hour", { WallpaperLibrary.batchSize == n }) { WallpaperLibrary.batchSize = n }
                }
            }
        ),
        Setting(
            R.drawable.ic_palette,
            "App theme",
            { ColorTheme.current.label },
            {
                // Always A–Z by name.
                ColorTheme.entries.sortedBy { it.label }.map { t ->
                    Choice(t.label, { ColorTheme.current == t }) {
                        if (ColorTheme.current != t) {
                            ColorTheme.current = t
                            SettingsActivity.reopenSettings = true
                            // Start over like a fresh launch (new task, nothing restored).
                            activity.startActivity(android.content.Intent(activity, SettingsActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                            // Projectivy opened this screen in its own task, so close it too (Back would show the old theme).
                            activity.finish()
                            activity.overridePendingTransition(0, 0)
                        }
                    }
                }
            }
        ),
        Setting(
            R.drawable.ic_blur,
            "Blur on menus",
            { if (BlurBar.enabled) "On · turn off if scrolling stutters" else "Off" },
            okHint = "Note: needs Android 12 or newer (older shows solid menus)\n\nPress OK to turn on/off.",
            action = {
                BlurBar.enabled = !BlurBar.enabled
                (activity as? SettingsActivity)?.setUpBlur()
                refreshLists()
            }
        ),
    )

    private var pendingUpdate: UpdateChecker.Update? = null
    private var updateStatus = "Current version: v${BuildConfig.VERSION_NAME}"

    /** First press checks; if there's a newer version, the next press downloads and installs it. */
    private fun checkOrInstall() {
        val u = pendingUpdate
        if (u == null) {
            updateStatus = "Checking…"
            refreshLists()
            Thread {
                val newer = UpdateChecker.check()
                activity.runOnUiThread {
                    pendingUpdate = newer
                    updateStatus = if (newer == null) "Current version: v${BuildConfig.VERSION_NAME} · up to date"
                    else "v${newer.version} available · press again to install (current v${BuildConfig.VERSION_NAME})"
                    refreshLists()
                }
            }.start()
            return
        }
        Thread {
            try {
                val file = UpdateChecker.download(activity, u) { pct ->
                    activity.runOnUiThread { updateStatus = "Downloading v${u.version}… $pct%"; refreshLists() }
                }
                activity.runOnUiThread {
                    updateStatus = "v${u.version} downloaded · press again if the installer didn't open"
                    refreshLists()
                    UpdateChecker.install(activity, file)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    updateStatus = "Download failed · press to try again"
                    refreshLists()
                }
            }
        }.start()
    }

    private val list: ListView = root.findViewById(R.id.settingsList)
    private val choices: ListView = root.findViewById(R.id.settingsChoices)
    private val detailTitle: TextView = root.findViewById(R.id.detailTitle)
    private val detailText: TextView = root.findViewById(R.id.detailText)
    private val detailFooter: TextView = root.findViewById(R.id.detailFooter)
    private val adapter = SettingsAdapter()
    private val choiceAdapter = ChoiceAdapter()
    private var current: Setting? = null

    init {
        list.adapter = adapter
        // The info block (Library settings) scrolls with the choices, right under them.
        (detailFooter.parent as android.view.ViewGroup).removeView(detailFooter)
        choices.addFooterView(detailFooter, null, false)
        choices.adapter = choiceAdapter
        choices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == choiceAdapter.count - 1) MenuDialog.revealFooter(choices, detailFooter)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // Highlighting a setting shows its choices on the right.
        list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                showDetail(settings.getOrNull(position))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // OK: run a one-press setting, or move into its choices.
        list.setOnItemClickListener { _, _, position, _ ->
            val s = settings.getOrNull(position) ?: return@setOnItemClickListener
            val action = s.action
            if (action != null) {
                action()
                onChanged()
                refreshLists()
            } else {
                enterChoices()
            }
        }
        choices.setOnItemClickListener { _, _, position, _ ->
            val c = current?.choices()?.getOrNull(position) ?: return@setOnItemClickListener
            c.apply()
            onChanged()
            refreshLists()
            if (!c.stay) list.requestFocus()
        }
        // Right from the list goes into the choices; left from the choices comes back.
        list.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                enterChoices(); true
            } else wrap(list, keyCode, event)
        }
        choices.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                list.requestFocus(); true
            } else wrap(choices, keyCode, event)
        }
        showDetail(settings.first())
    }

    /** Up on the first row goes to the last one and down on the last goes to the first (like the side menus). */
    private fun wrap(l: ListView, keyCode: Int, event: android.view.KeyEvent): Boolean {
        val n = l.adapter.count - l.footerViewsCount
        if (event.action != android.view.KeyEvent.ACTION_DOWN || n < 2) return false
        val pos = l.selectedItemPosition
        return when {
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && pos == 0 -> {
                MenuDialog.selectLast(l, n - 1, if (l === choices) detailFooter else null); true
            }
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && pos == n - 1 -> { l.setSelection(0); true }
            else -> false
        }
    }

    fun focus() {
        list.requestFocus()
    }

    /** Back: leave the choices first. Returns false when there's nothing to go back from. */
    fun handleBack(): Boolean {
        if (choices.hasFocus()) {
            list.requestFocus()
            return true
        }
        return false
    }

    private fun enterChoices() {
        val s = current ?: return
        if (s.action != null || s.choices().isEmpty()) return
        choices.requestFocus()
        choices.setSelection(s.choices().indexOfFirst { it.isCurrent() }.coerceAtLeast(0))
    }

    private fun showDetail(s: Setting?) {
        current = s
        detailTitle.text = s?.label ?: ""
        // Description and "Press OK…" on top; a menu's info block (Library settings) sits under its buttons.
        val info = s?.info?.invoke()
        detailText.text = when {
            s == null -> ""
            s.action != null -> android.text.SpannableStringBuilder(s.value()).append("\n\n").append(s.okHint)
            else -> s.value()
        }
        detailFooter.text = info ?: ""
        detailFooter.visibility = if (info != null) View.VISIBLE else View.GONE
        choiceAdapter.notifyDataSetChanged()
    }

    private fun refreshLists() {
        adapter.notifyDataSetChanged()
        showDetail(current)
    }

    private fun askForToken() {
        val input = android.widget.EditText(activity).apply {
            hint = "github_pat_…"
            setSingleLine(true)
            setTextColor(0xFFE6E0E9.toInt())
            setHintTextColor(0xFF938F99.toInt())
        }
        Popup.show(
            activity, "GitHub token",
            "Fine-grained token for markoman013/mv-wallpapers with Contents: read and write. Tip: type or paste it from your phone with the Google TV app keyboard.",
            icon = R.drawable.ic_key, confirm = "Save", input = input
        ) {
            GitHubApi.token = input.text.toString()
            refreshLists()
            Toast.makeText(activity, if (GitHubApi.token != null) "Token saved" else "Token removed", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class SettingsAdapter : BaseAdapter() {
        override fun getCount() = settings.size
        override fun getItem(position: Int): Any = settings[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: activity.layoutInflater.inflate(R.layout.item_option, parent, false)
            val s = settings[position]
            view.findViewById<ImageView>(R.id.icon).apply {
                setImageResource(s.icon)
                visibility = View.VISIBLE
            }
            view.findViewById<TextView>(R.id.label).text = s.label
            view.findViewById<TextView>(R.id.value).apply {
                text = s.value()
                visibility = View.VISIBLE
            }
            return view
        }
    }

    private inner class ChoiceAdapter : BaseAdapter() {
        private fun items() = current?.takeIf { it.action == null }?.choices() ?: emptyList()
        override fun getCount() = items().size
        override fun getItem(position: Int): Any = items()[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: activity.layoutInflater.inflate(R.layout.item_option, parent, false)
            val c = items()[position]
            view.findViewById<ImageView>(R.id.icon).apply {
                setImageResource(if (c.icon != 0) c.icon else if (c.isCurrent()) R.drawable.radio_on else R.drawable.radio_off)
                visibility = View.VISIBLE
            }
            view.findViewById<TextView>(R.id.label).text = c.label
            view.findViewById<TextView>(R.id.value).apply {
                val sub = c.sub?.invoke()
                text = sub ?: ""
                visibility = if (sub != null) View.VISIBLE else View.GONE
            }
            return view
        }
    }
}
