package com.tvviewer

/**
 * Playlists with multiple server sources for better streaming
 */
object BuiltInPlaylists {

    private const val IPTV_ORG = "https://iptv-org.github.io/iptv"
    private const val FREE_IPTV = "https://raw.githubusercontent.com/Free-IPTV/Countries/master"

    val categories: List<PlaylistCategory> = listOf(
        PlaylistCategory("general", listOf(
            Playlist("Все каналы", "$IPTV_ORG/index.m3u"),
            Playlist("Спорт", "$IPTV_ORG/categories/sports.m3u"),
            Playlist("Новости", "$IPTV_ORG/categories/news.m3u"),
            Playlist("Музыка", "$IPTV_ORG/categories/music.m3u"),
            Playlist("Кино", "$IPTV_ORG/categories/movies.m3u"),
        )),
        PlaylistCategory("countries", listOf(
            Playlist("🇷🇺 Россия (1)", "$IPTV_ORG/countries/ru.m3u"),
            Playlist("🇷🇺 Россия (2)", "$FREE_IPTV/ru.m3u"),
            Playlist("🇺🇦 Украина (1)", "$IPTV_ORG/countries/ua.m3u"),
            Playlist("🇺🇦 Украина (2)", "$FREE_IPTV/ua.m3u"),
            Playlist("🇧🇾 Беларусь", "$IPTV_ORG/countries/by.m3u"),
            Playlist("🇰🇿 Казахстан", "$IPTV_ORG/countries/kz.m3u"),
            Playlist("🇦🇿 Азербайджан (1)", "$IPTV_ORG/countries/az.m3u"),
            Playlist("🇦🇿 Азербайджан (2)", "$FREE_IPTV/az.m3u"),
            Playlist("🇬🇪 Грузия", "$IPTV_ORG/countries/ge.m3u"),
            Playlist("🇲🇩 Молдова", "$IPTV_ORG/countries/md.m3u"),
            Playlist("🇵🇱 Польша", "$IPTV_ORG/countries/pl.m3u"),
            Playlist("🇩🇪 Германия", "$IPTV_ORG/countries/de.m3u"),
            Playlist("🇬🇧 UK", "$IPTV_ORG/countries/uk.m3u"),
            Playlist("🇺🇸 США", "$IPTV_ORG/countries/us.m3u"),
            Playlist("🇹🇷 Турция", "$IPTV_ORG/countries/tr.m3u"),
        ))
    )

    fun getAllPlaylists(): List<Playlist> = categories.flatMap { it.playlists }
}

data class PlaylistCategory(val id: String, val playlists: List<Playlist>)
