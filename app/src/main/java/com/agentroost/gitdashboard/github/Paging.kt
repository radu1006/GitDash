package com.agentroost.gitdashboard.github

/**
 * One page of a paginated GitHub collection.
 *
 * [nextPage] is decoded from the `Link: <...>; rel="next"` header, with a size-based fallback for
 * servers/proxies that strip the header.
 */
data class Paged<T>(
    val items: List<T>,
    val page: Int,
    val nextPage: Int?,
    val meta: ApiMeta = ApiMeta(),
) {
    val hasMore: Boolean get() = nextPage != null
}

/** Parser for GitHub's RFC 5988 `Link` response header. */
object LinkHeader {

    private val LINK_PATTERN = Regex("""<([^>]+)>\s*;\s*rel="([^"]+)"""")
    private val PAGE_PATTERN = Regex("""[?&]page=(\d+)""")

    /**
     * Returns the page number of the `rel="next"` link, or null when there is no next page.
     * [currentPage] is only used as a fallback when the next URL carries no `page` parameter.
     */
    fun nextPage(linkHeader: String?, currentPage: Int = 1): Int? {
        if (linkHeader.isNullOrBlank()) return null

        val nextUrl = LINK_PATTERN.findAll(linkHeader)
            .firstOrNull { match ->
                match.groupValues[2]
                    .split(' ', ',')
                    .map { it.trim() }
                    .contains("next")
            }
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        return PAGE_PATTERN.find(nextUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: (currentPage + 1)
    }
}
