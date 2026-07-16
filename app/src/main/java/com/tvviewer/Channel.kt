package com.tvviewer

import java.io.Serializable

data class Channel(
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
    /** Имя плейлиста откуда канал. Заполняется при добавлении в
     *  избранное чтобы во вкладке Favorites было видно "1+1
     *  · Украина" — пользователю понятно из какого плейлиста этот
     *  канал. Для каналов из текущего плейлиста (не favorites) — null. */
    val sourcePlaylist: String? = null
) : Serializable
