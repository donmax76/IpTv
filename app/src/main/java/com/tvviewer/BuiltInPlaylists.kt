package com.tvviewer

/**
 * Playlists - iptv-org (reliable). Removed Free-IPTV (2) - URLs often fail.
 */
object BuiltInPlaylists {

    private const val B = "https://iptv-org.github.io/iptv"

    val categories: List<PlaylistCategory> = listOf(
        PlaylistCategory("custom", listOf(
            Playlist("FlyVideo", "http://flyvideo.ucoz.ru/zedomS.m3u"),
        )),
        PlaylistCategory("general", listOf(
            Playlist("Все каналы", "$B/index.m3u"),
            Playlist("Спорт", "$B/categories/sports.m3u"),
            Playlist("Новости", "$B/categories/news.m3u"),
            Playlist("Музыка", "$B/categories/music.m3u"),
            Playlist("Кино", "$B/categories/movies.m3u"),
        )),
        PlaylistCategory("countries", listOf(
            Playlist("🇷🇺 Россия", "$B/countries/ru.m3u"),
            Playlist("🇺🇦 Украина", "$B/countries/ua.m3u"),
            Playlist("🇧🇾 Беларусь", "$B/countries/by.m3u"),
            Playlist("🇰🇿 Казахстан", "$B/countries/kz.m3u"),
            Playlist("🇦🇿 Азербайджан", "$B/countries/az.m3u"),
            Playlist("🇦🇿 Mədəniyyət TV", "https://raw.githubusercontent.com/donmax76/TestApp/master/TVViewer/playlists/medeniyyet.m3u"),
            Playlist("🇬🇪 Грузия", "$B/countries/ge.m3u"),
            Playlist("🇲🇩 Молдова", "$B/countries/md.m3u"),
            Playlist("🇵🇱 Польша", "$B/countries/pl.m3u"),
            Playlist("🇩🇪 Германия", "$B/countries/de.m3u"),
            Playlist("🇬🇧 UK", "$B/countries/uk.m3u"),
            Playlist("🇺🇸 США", "$B/countries/us.m3u"),
            Playlist("🇹🇷 Турция", "$B/countries/tr.m3u"),
        ))
    )

    fun getAllPlaylists(): List<Playlist> = categories.flatMap { it.playlists }
}

data class PlaylistCategory(val id: String, val playlists: List<Playlist>)
