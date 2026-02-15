package com.tvviewer

data class Playlist(
    val name: String,
    val url: String?,
    val channels: List<Channel> = emptyList()
)
