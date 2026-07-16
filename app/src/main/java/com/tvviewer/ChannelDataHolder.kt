package com.tvviewer

/**
 * Singleton to pass data between fragments without Intent size limits.
 *
 * @Volatile на полях которые читаются из main thread И пишутся из
 * фоновых корутин (allChannels — Home/Main coroutine; epgData —
 * applicationScope в TVViewerApp + Settings refresh). Без volatile
 * читатель в main thread может видеть stale значение из-за CPU cache.
 * Lists/Maps сами immutable (kotlin.collections), так что безопасно
 * атомарно подменять reference.
 */
object ChannelDataHolder {
    var pendingPlaylistName: String? = null
    var pendingPlaylistUrl: String? = null
    /** URL последнего успешно загруженного в allChannels плейлиста.
     *  HomeFragment / MainActivity проверяют его перед fetch — если
     *  совпадает с тем что хочет юзер, повторно не качаем (экономит
     *  3-10 сек на медленном TV-боксе). */
    @Volatile var loadedPlaylistUrl: String? = null
    @Volatile var allChannels: List<Channel> = emptyList()
    @Volatile var epgData: Map<String, List<EpgRepository.Programme>> = emptyMap()
    @Volatile var currentChannelIndex: Int = 0
    /** Set by PlayerActivity when the user wants the side menu shown
     *  upon returning to MainActivity. Consumed once by MainActivity.onResume. */
    var openDrawerOnReturn: Boolean = false
    /** When PlayerActivity finishes via its own drawer item, this stores
     *  which tab MainActivity should switch to on resume:
     *  0=Playlists 1=Channels 2=TV Guide 3=Favorites 4=Recent 5=Settings.
     *  Negative or unset means "stay where you are". */
    var returnToTabIndex: Int = -1
}
