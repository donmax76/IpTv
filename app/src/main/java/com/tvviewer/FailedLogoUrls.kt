package com.tvviewer

/**
 * Android Round 353: сессионный негативный кэш дохлых logo-URL.
 *
 * Coil не кэширует ошибки — каждый rebind строки с мёртвой ссылкой
 * запускал свежий DNS + TCP connect (до 8с таймаута), и на плейлистах,
 * где большинство лого дохлые, каждый проход по списку заново молотил
 * сеть (OkHttp сериализует 5 соединений на хост — дохлые лого одного
 * хоста ещё и голодали живые). Windows-порт для этого же завёл
 * circuit breaker (Round 269).
 *
 * URL попадает сюда из Coil onError (реальная ошибка; отмена при
 * recycle идёт в onCancel и сюда не попадает) и до конца сессии
 * больше не запрашивается — адаптеры сразу показывают letter-tile.
 */
object FailedLogoUrls {
    private const val MAX_ENTRIES = 2000
    private val failed: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet())

    fun isFailed(url: String?): Boolean =
        !url.isNullOrBlank() && url in failed

    fun markFailed(url: String?) {
        if (!url.isNullOrBlank() && failed.size < MAX_ENTRIES) {
            failed.add(url)
        }
    }
}
