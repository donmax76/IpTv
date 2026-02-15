package com.tvviewer

import java.io.Serializable

data class Channel(
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val group: String? = null
) : Serializable
