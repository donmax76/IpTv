package com.tvviewer

data class Channel(
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val group: String? = null
)
