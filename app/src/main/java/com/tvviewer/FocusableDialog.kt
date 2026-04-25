package com.tvviewer

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView

/**
 * Single source of truth for "list of choices" dialogs.
 *
 * Uses our own row layout (bg_dialog_list_item) so the D-pad focus
 * highlight is unmistakable, and uses setAdapter() — NOT
 * setSingleChoiceItems() — so a tap (or D-pad OK) on a row commits
 * immediately. The previous setSingleChoiceItems version sometimes
 * routed OK to the dialog's Cancel button on TV remotes and the user's
 * choice was discarded.
 */
object FocusableDialog {

    fun show(
        context: Context,
        title: CharSequence?,
        items: Array<out CharSequence>,
        currentIndex: Int = -1,
        onSelected: (Int) -> Unit,
    ): AlertDialog {
        val builder = AlertDialog.Builder(context, R.style.Theme_TVViewer_Dialog)
        if (title != null) builder.setTitle(title)

        val adapter = object : ArrayAdapter<CharSequence>(context, 0, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView)
                    ?: LayoutInflater.from(context)
                        .inflate(R.layout.dialog_list_row, parent, false) as TextView
                tv.text = items[position]
                tv.setTypeface(null,
                    if (position == currentIndex) Typeface.BOLD else Typeface.NORMAL)
                // Tiny check-mark prefix on the previously-chosen row so the
                // user sees what they were on without needing a radio button.
                if (position == currentIndex && !items[position].startsWith("✓ ")) {
                    tv.text = "✓  ${items[position]}"
                }
                return tv
            }
        }

        val dialog = builder
            .setAdapter(adapter) { d, which ->
                onSelected(which)
                d.dismiss()
            }
            .create()

        dialog.setOnShowListener {
            dialog.listView?.let { lv ->
                lv.choiceMode = ListView.CHOICE_MODE_NONE
                lv.setSelector(androidx.core.content.ContextCompat
                    .getDrawable(context, R.drawable.bg_dialog_list_item)!!)
                lv.requestFocus()
                if (currentIndex in items.indices) {
                    lv.setSelection(currentIndex)
                } else if (items.isNotEmpty()) {
                    lv.setSelection(0)
                }
                // Belt-and-braces: if for any reason focus drifts away from
                // the list, intercept OK / DPAD_CENTER on the dialog and
                // commit the selected list row manually.
                dialog.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                         keyCode == KeyEvent.KEYCODE_ENTER)) {
                        val pos = lv.selectedItemPosition.takeIf { it >= 0 }
                            ?: currentIndex.takeIf { it >= 0 } ?: 0
                        if (pos in items.indices) {
                            onSelected(pos)
                            dialog.dismiss()
                            return@setOnKeyListener true
                        }
                    }
                    false
                }
            }
        }
        dialog.show()
        return dialog
    }
}
