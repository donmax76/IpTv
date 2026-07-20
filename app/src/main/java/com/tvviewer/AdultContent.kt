package com.tvviewer

/**
 * Round 382: определение «взрослых» категорий (18+, XXX и т.п.).
 *
 * Каналы, чья ГРУППА (категория, group-title в M3U) относится к взрослому
 * контенту, по умолчанию СКРЫТЫ из списков и панели категорий. Показать
 * их можно переключателем в настройках — включение требует PIN
 * родительского контроля.
 *
 * Матчим по имени ГРУППЫ, а не по названию канала — поэтому подстрочный
 * поиск безопасен: категория «Adult»/«XXX»/«18+» = взрослый контент, а
 * канал «Adult Swim» лежит в группе «Entertainment»/«Cartoons» и не
 * попадает под фильтр.
 */
object AdultContent {

    private val KEYWORDS = listOf(
        "18+", "xxx", "porn", "adult", "erotic",
        "эротик", "для взрослых", "взрослое", "взрослы",
    )

    /** true, если имя группы/категории относится к взрослому контенту. */
    fun isAdultGroup(group: String?): Boolean {
        if (group.isNullOrBlank()) return false
        val g = group.lowercase()
        return KEYWORDS.any { g.contains(it) }
    }

    /** true, если канал принадлежит взрослой категории (по первому
     *  сегменту group-tag — как строятся категории в списке). */
    fun isAdult(channel: Channel): Boolean {
        val canonical = channel.group?.split(';', ',', '|')?.firstOrNull()?.trim()
        return isAdultGroup(canonical) || isAdultGroup(channel.group)
    }
}
