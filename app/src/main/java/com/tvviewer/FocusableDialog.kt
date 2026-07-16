package com.tvviewer

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
        // Round 178: используем тему SidePanel вместо MaterialAlertDialog —
        // у последней зашит windowMinWidthMajor=65% и windowBackground с
        // insets, из-за чего наш width=50% + gravity=END всё равно
        // выглядел как центральное окно. SidePanel снимает эти лимиты.
        val builder = AlertDialog.Builder(context, R.style.Theme_TVViewer_SidePanel)
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
        // Round 177: показываем как боковую панель на пол-экрана справа,
        // а не как центральное всплывающее окно. На пульте всё по-прежнему
        // работает (ListView сохраняет фокус), но визуально — выезжающее
        // меню как в нативных Android Settings, а не модальный диалог.
        dialog.window?.let { w ->
            val screenW = context.resources.displayMetrics.widthPixels
            val params = w.attributes
            params.gravity = Gravity.END or Gravity.TOP
            params.width = (screenW * 0.5f).toInt().coerceAtLeast(320)
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0
            params.y = 0
            w.attributes = params
        }
        return dialog
    }
}
