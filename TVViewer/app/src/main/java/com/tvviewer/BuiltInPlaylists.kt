package com.tvviewer

/**
 * Built-in playlists from iptv-org (https://github.com/iptv-org/iptv)
 */
object BuiltInPlaylists {

    private const val BASE = "https://iptv-org.github.io/iptv"

    val categories: List<PlaylistCategory> = listOf(
        PlaylistCategory("general", listOf(
            Playlist("IPTV-ORG (Все каналы)", "$BASE/index.m3u"),
            Playlist("IPTV-ORG (Спорт)", "$BASE/categories/sports.m3u"),
            Playlist("IPTV-ORG (Новости)", "$BASE/categories/news.m3u"),
            Playlist("IPTV-ORG (Музыка)", "$BASE/categories/music.m3u"),
            Playlist("IPTV-ORG (Кино)", "$BASE/categories/movies.m3u"),
            Playlist("IPTV-ORG (Документальные)", "$BASE/categories/documentary.m3u"),
            Playlist("IPTV-ORG (Детские)", "$BASE/categories/kids.m3u"),
            Playlist("IPTV-ORG (Образование)", "$BASE/categories/education.m3u"),
        )),
        PlaylistCategory("countries", listOf(
            Playlist("🇷🇺 Россия", "$BASE/countries/ru.m3u"),
            Playlist("🇺🇦 Украина", "$BASE/countries/ua.m3u"),
            Playlist("🇧🇾 Беларусь", "$BASE/countries/by.m3u"),
            Playlist("🇰🇿 Казахстан", "$BASE/countries/kz.m3u"),
            Playlist("🇺🇿 Узбекистан", "$BASE/countries/uz.m3u"),
            Playlist("🇬🇪 Грузия", "$BASE/countries/ge.m3u"),
            Playlist("🇦🇲 Армения", "$BASE/countries/am.m3u"),
            Playlist("🇦🇿 Азербайджан", "$BASE/countries/az.m3u"),
            Playlist("🇲🇩 Молдова", "$BASE/countries/md.m3u"),
            Playlist("🇱🇹 Литва", "$BASE/countries/lt.m3u"),
            Playlist("🇱🇻 Латвия", "$BASE/countries/lv.m3u"),
            Playlist("🇪🇪 Эстония", "$BASE/countries/ee.m3u"),
            Playlist("🇵🇱 Польша", "$BASE/countries/pl.m3u"),
            Playlist("🇩🇪 Германия", "$BASE/countries/de.m3u"),
            Playlist("🇫🇷 Франция", "$BASE/countries/fr.m3u"),
            Playlist("🇬🇧 Великобритания", "$BASE/countries/uk.m3u"),
            Playlist("🇺🇸 США", "$BASE/countries/us.m3u"),
            Playlist("🇹🇷 Турция", "$BASE/countries/tr.m3u"),
            Playlist("🇮🇹 Италия", "$BASE/countries/it.m3u"),
            Playlist("🇪🇸 Испания", "$BASE/countries/es.m3u"),
        ))
    )

    fun getAllPlaylists(): List<Playlist> = categories.flatMap { it.playlists }
}

data class PlaylistCategory(val id: String, val playlists: List<Playlist>)
