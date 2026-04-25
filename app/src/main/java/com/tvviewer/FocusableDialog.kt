package com.tvviewer

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

/**
 * Single source of truth for "list of choices" dialogs.
 *
 * Built-in setItems / setSingleChoiceItems on Android delegate to layouts
 * we don't fully control, and the focus state isn't reliably visible on
 * a TV remote. This helper builds a dialog with our own row layout
 * (bg_dialog_list_item — bright purple + white border on focus/select),
 * forces the highlighted row to be drawn, and auto-focuses the current
 * choice so the user instantly sees what's selected.
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
                tv.alpha = if (position == currentIndex) 1f else 0.92f
                return tv
            }
        }

        val dialog = builder
            .setSingleChoiceItems(adapter, currentIndex) { d, which ->
                onSelected(which)
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.listView?.let { lv ->
                lv.choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
                lv.setSelector(androidx.core.content.ContextCompat
                    .getDrawable(context, R.drawable.bg_dialog_list_item)!!)
                lv.requestFocus()
                if (currentIndex in items.indices) {
                    lv.setSelection(currentIndex)
                    lv.setItemChecked(currentIndex, true)
                } else if (items.isNotEmpty()) {
                    lv.setSelection(0)
                }
            }
        }
        dialog.show()
        return dialog
    }
}
