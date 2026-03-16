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
}
