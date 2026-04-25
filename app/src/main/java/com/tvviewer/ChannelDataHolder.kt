package com.tvviewer

/**
 * Singleton to pass data between fragments without Intent size limits.
 */
object ChannelDataHolder {
    var pendingPlaylistName: String? = null
    var pendingPlaylistUrl: String? = null
    var allChannels: List<Channel> = emptyList()
    var epgData: Map<String, List<EpgRepository.Programme>> = emptyMap()
    var currentChannelIndex: Int = 0
    /** Set by PlayerActivity when the user wants the side menu shown
     *  upon returning to MainActivity. Consumed once by MainActivity.onResume. */
    var openDrawerOnReturn: Boolean = false
}
