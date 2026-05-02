package com.tvviewer

import android.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * After AlertDialog.show(), force a bright focus background on the embedded
 * ListView. The default theme attribute override doesn't always make it
 * through (Material's MaterialAlertDialog wraps things differently), so we
 * apply the selector programmatically as a belt-and-braces measure.
 */
fun AlertDialog.installFocusListBackground(): AlertDialog {
    listView?.let { lv ->
        val selector = ContextCompat.getDrawable(context, R.drawable.bg_dialog_list_item)
        selector?.let {
            lv.selector = it
            // Selector рисуется ПОВЕРХ строк — иначе тёмная подложка
            // плейлистного диалога перекрывает фиолетовую заливку и
            // юзер не видит куда переместился фокус через DPAD.
            lv.isDrawSelectorOnTop = false
        }
        lv.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        lv.isFocusable = true
        lv.isFocusableInTouchMode = true
        // post() — список ещё не выкатан в момент show(), фокус
        // запрашиваем после layout-фазы.
        lv.post {
            lv.requestFocus()
            if (lv.checkedItemPosition < 0 && lv.count > 0) {
                lv.setSelection(0)
            }
        }
    }
    return this
}
