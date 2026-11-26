package com.mv.wallpapers.plugin

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** In-app pop-up (blurred like the panels): title, message, optional text field, two buttons. */
object Popup {
    fun show(
        activity: Activity,
        title: String,
        message: String,
        icon: Int = 0,
        confirm: String,
        cancel: String = "Cancel",
        danger: Boolean = false,
        input: EditText? = null,
        onConfirm: () -> Unit
    ) {
        val panel = activity.layoutInflater.inflate(R.layout.popup, null) as BlurBar
        panel.cornerRadiusDp = 28f
        panel.findViewById<TextView>(R.id.popupTitle).text = title
        panel.findViewById<TextView>(R.id.popupMessage).text = message
        panel.findViewById<ImageView>(R.id.popupIcon).apply {
            if (icon != 0) setImageResource(icon) else visibility = View.GONE
        }
        if (input != null) panel.findViewById<FrameLayout>(R.id.popupInput).apply {
            visibility = View.VISIBLE
            addView(input)
        }
        val yes = panel.findViewById<Button>(R.id.popupConfirm)
        val no = panel.findViewById<Button>(R.id.popupCancel)
        yes.text = confirm
        no.text = cancel
        if (danger) yes.setTextColor(0xFFF2B8B5.toInt())
        // Left/right between the buttons (and down from the text field) are set directly,
        // so moving never depends on Android guessing the nearest button.
        val keys = View.OnKeyListener { v, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@OnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (v === no) { yes.requestFocus(); true } else true
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (v === yes) { no.requestFocus(); true } else true
                // Up/down: stay on the buttons.
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> false
            }
        }
        yes.setOnKeyListener(keys)
        no.setOnKeyListener(keys)
        input?.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                yes.requestFocus(); true
            } else false
        }
        val overlay = Overlay.center(activity, panel)
        yes.setOnClickListener { overlay.close { onConfirm() } }
        no.setOnClickListener { overlay.close() }
        // Safe default: a text field gets focus first; a delete starts on Cancel.
        panel.post { (input ?: if (danger) no else yes).requestFocus() }
    }
}
