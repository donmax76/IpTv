package com.tvviewer

/**
 * Built-in playlists. Source: iptv-org (most reliable open-source IPTV
 * index, regularly updated). Раньше тут был index.m3u — единый список
 * на 9000+ каналов, файл 8 MB, парсился ~30 сек на TV-боксе. Юзер
 * жаловался на медлительность. Теперь:
 *  - "Все каналы" удалён — слишком большой
 *  - добавлены подборки по ЯЗЫКУ (меньше чем по стране, грузятся
 *    в 5-10 раз быстрее)
 *  - категории (Спорт/Новости/Кино/Музыка) разбиты по 200-500 КБ
 *  - страны как раньше
 */
object BuiltInPlaylists {

    private const val IPTV = "https://iptv-org.github.io/iptv"

    val categories: List<PlaylistCategory> = listOf(
        PlaylistCategory("by_language", listOf(
            Playlist("🌐 Русскоязычные", "$IPTV/languages/rus.m3u"),
            Playlist("🌐 Українські",    "$IPTV/languages/ukr.m3u"),
            Playlist("🌐 Azərbaycanca",  "$IPTV/languages/aze.m3u"),
            Playlist("🌐 Türkçe",        "$IPTV/languages/tur.m3u"),
            Playlist("🌐 English",       "$IPTV/languages/eng.m3u"),
            Playlist("🌐 Deutsch",       "$IPTV/languages/deu.m3u"),
            Playlist("🌐 Español",       "$IPTV/languages/spa.m3u"),
        )),
        PlaylistCategory("by_category", listOf(
            Playlist("⚽ Спорт",      "$IPTV/categories/sports.m3u"),
            Playlist("📰 Новости",    "$IPTV/categories/news.m3u"),
            Playlist("🎵 Музыка",     "$IPTV/categories/music.m3u"),
            Playlist("🎬 Кино",       "$IPTV/categories/movies.m3u"),
            Playlist("📺 Развлечения", "$IPTV/categories/entertainment.m3u"),
            Playlist("🧒 Детям",      "$IPTV/categories/kids.m3u"),
            Playlist("📚 Документальные", "$IPTV/categories/documentary.m3u"),
            Playlist("🍳 Кулинария",  "$IPTV/categories/cooking.m3u"),
            // Round 384: 18+ внутри «По категории». В выпадашке показывается
            // только когда включён показ взрослого (Настройки → 18+/XXX).
            // Round 385: iptv-org удалил свой adult-список (файл пустой) —
            // заменён на URL, который дал пользователь.
            Playlist("🔞 18+ / XXX",  "http://www.iptv.cc.ua/qwe/adult.m3u"),
        )),
        PlaylistCategory("by_country", listOf(
            Playlist("🇷🇺 Россия",     "$IPTV/countries/ru.m3u"),
            Playlist("🇺🇦 Украина",    "$IPTV/countries/ua.m3u"),
            Playlist("🇧🇾 Беларусь",   "$IPTV/countries/by.m3u"),
            Playlist("🇰🇿 Казахстан",  "$IPTV/countries/kz.m3u"),
            Playlist("🇦🇿 Азербайджан", "$IPTV/countries/az.m3u"),
            Playlist("🇬🇪 Грузия",     "$IPTV/countries/ge.m3u"),
            Playlist("🇲🇩 Молдова",    "$IPTV/countries/md.m3u"),
            Playlist("🇦🇲 Армения",    "$IPTV/countries/am.m3u"),
            Playlist("🇺🇿 Узбекистан", "$IPTV/countries/uz.m3u"),
            Playlist("🇰🇬 Кыргызстан", "$IPTV/countries/kg.m3u"),
            Playlist("🇹🇯 Таджикистан", "$IPTV/countries/tj.m3u"),
            Playlist("🇵🇱 Польша",     "$IPTV/countries/pl.m3u"),
            Playlist("🇩🇪 Германия",   "$IPTV/countries/de.m3u"),
            Playlist("🇬🇧 UK",         "$IPTV/countries/uk.m3u"),
            Playlist("🇺🇸 США",        "$IPTV/countries/us.m3u"),
            Playlist("🇨🇦 Канада",     "$IPTV/countries/ca.m3u"),
            Playlist("🇹🇷 Турция",     "$IPTV/countries/tr.m3u"),
            Playlist("🇮🇷 Иран",       "$IPTV/countries/ir.m3u"),
            Playlist("🇮🇱 Израиль",    "$IPTV/countries/il.m3u"),
        )),
        PlaylistCategory("by_region", listOf(
            // Региональные подборки — меньше чем по странам, быстрее.
            Playlist("🌍 СНГ",          "$IPTV/regions/cis.m3u"),
            Playlist("🌍 Европа",       "$IPTV/regions/eur.m3u"),
            Playlist("🌍 Азия",         "$IPTV/regions/asia.m3u"),
            Playlist("🌍 Северная Америка", "$IPTV/regions/noram.m3u"),
        )),
    )

    fun getAllPlaylists(): List<Playlist> = categories.flatMap { it.playlists }
}

data class PlaylistCategory(val id: String, val playlists: List<Playlist>)
