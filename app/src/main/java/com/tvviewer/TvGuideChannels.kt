package com.tvviewer

/**
 * Holder for channels when opening TvGuide - avoids TransactionTooLargeException
 * (Intent has ~1MB limit, channel list can be 4MB+)
 */
object TvGuideChannels {
    var channels: List<Channel> = emptyList()
    var currentUrl: String? = null
}
