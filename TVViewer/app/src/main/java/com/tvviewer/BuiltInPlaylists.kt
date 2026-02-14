package com.tvviewer

/**
 * Built-in playlists from publicly available free IPTV sources.
 * Sources: iptv-org, free-to-air streams
 */
object BuiltInPlaylists {

    val playlists: List<Playlist> = listOf(
        Playlist(
            name = "IPTV-ORG (Общие)",
            url = "https://iptv-org.github.io/iptv/index.m3u",
            channels = emptyList()
        ),
        Playlist(
            name = "IPTV-ORG (Россия)",
            url = "https://iptv-org.github.io/iptv/countries/ru.m3u",
            channels = emptyList()
        ),
        Playlist(
            name = "IPTV-ORG (Украина)",
            url = "https://iptv-org.github.io/iptv/countries/ua.m3u",
            channels = emptyList()
        ),
        Playlist(
            name = "IPTV-ORG (Беларусь)",
            url = "https://iptv-org.github.io/iptv/countries/by.m3u",
            channels = emptyList()
        ),
        Playlist(
            name = "IPTV-ORG (Спорт)",
            url = "https://iptv-org.github.io/iptv/categories/sports.m3u",
            channels = emptyList()
        ),
        Playlist(
            name = "IPTV-ORG (Новости)",
            url = "https://iptv-org.github.io/iptv/categories/news.m3u",
            channels = emptyList()
        ),
        Playlist(
            name = "IPTV-ORG (Музыка)",
            url = "https://iptv-org.github.io/iptv/categories/music.m3u",
            channels = emptyList()
        ),
        Playlist(
            name = "Free-IPTV (Русские каналы)",
            url = "https://raw.githubusercontent.com/Free-IPTV/Countries/master/ru.m3u",
            channels = emptyList()
        )
    )
}
