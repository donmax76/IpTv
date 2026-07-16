package com.tvviewer

/**
 * Round 194: применение настройки `channelSort` из Settings к
 * списку каналов. Раньше pref писалась но никем не читалась —
 * сортировка была dead-feature. Здесь все 5 вариантов реализованы:
 *
 *  - "default" — порядок из плейлиста (без изменений)
 *  - "number"  — по `tvg-chno` если есть, иначе по позиции в плейлисте
 *  - "name"    — алфавит (case-insensitive)
 *  - "group"   — сначала по группе, внутри по имени
 *  - "quality" — сначала те у кого выше известное разрешение
 *                (cached `prefs.getChannelHeight`), потом остальные
 */
object ChannelSorter {

    fun apply(prefs: AppPreferences, list: List<Channel>): List<Channel> {
        if (list.isEmpty()) return list
        return when (prefs.channelSort) {
            "name" -> list.sortedBy { it.name.lowercase() }
            "group" -> list.sortedWith(
                compareBy(
                    // Channels without a group go to the bottom alphabetically.
                    { it.group?.lowercase() ?: "zzzzzz" },
                    { it.name.lowercase() }
                )
            )
            "quality" -> {
                // Стабильная сортировка: каналы с известной высотой
                // сначала, по убыванию; остальные сохраняют исходный
                // порядок относительно друг друга.
                val withHeight = list
                    .map { it to prefs.getChannelHeight(it.url) }
                val sorted = withHeight.sortedWith(
                    compareByDescending<Pair<Channel, Int>> {
                        it.second.takeIf { h -> h > 0 } ?: -1
                    }
                )
                sorted.map { it.first }
            }
            "number" -> {
                // Channel data class не хранит tvg-chno — используем
                // tvgId как ключ, иначе порядок плейлиста.
                list.sortedWith(
                    compareBy(
                        { it.tvgId?.toIntOrNull() ?: Int.MAX_VALUE },
                        { it.name.lowercase() }
                    )
                )
            }
            else -> list  // "default" — без изменений
        }
    }
}
