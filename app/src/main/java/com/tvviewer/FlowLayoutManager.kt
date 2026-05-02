package com.tvviewer

import androidx.recyclerview.widget.RecyclerView

/** Простейший flow-layout для чипов: укладывает item'ы слева направо,
 *  переносит на новую строку когда не хватает ширины. Используется для
 *  показа всех категорий сразу без горизонтальной прокрутки на TV-боксе.
 *  Не поддерживает скролл (не нужно — overlay-панель ограничена 320dp,
 *  чипов мало; всё помещается в 2-3 строки). */
class FlowLayoutManager : RecyclerView.LayoutManager() {

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.WRAP_CONTENT,
            RecyclerView.LayoutParams.WRAP_CONTENT
        )

    override fun isAutoMeasureEnabled(): Boolean = true
    override fun canScrollVertically(): Boolean = false
    override fun canScrollHorizontally(): Boolean = false

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (itemCount == 0) {
            detachAndScrapAttachedViews(recycler)
            return
        }
        detachAndScrapAttachedViews(recycler)
        val widthAvail = width - paddingStart - paddingEnd
        var x = paddingStart
        var y = paddingTop
        var rowHeight = 0
        for (i in 0 until itemCount) {
            val view = recycler.getViewForPosition(i)
            addView(view)
            measureChildWithMargins(view, 0, 0)
            val w = getDecoratedMeasuredWidth(view)
            val h = getDecoratedMeasuredHeight(view)
            if (x + w > paddingStart + widthAvail && x > paddingStart) {
                x = paddingStart
                y += rowHeight
                rowHeight = 0
            }
            layoutDecorated(view, x, y, x + w, y + h)
            x += w
            if (h > rowHeight) rowHeight = h
        }
    }
}
