package eu.kanade.tachiyomi.extension.pt.sussyscan

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

@Serializable
sealed class ApiResult<out T> {
    @Serializable
    data class Paginated<T>(
        @SerialName("pagina") val currentPage: Int,
        @SerialName("totalPaginas") val totalPages: Int,
        @SerialName("resultado") val results: T
    ) : ApiResult<T>() {
        val hasNextPage get() = currentPage < totalPages
    }

    @Serializable
    data class Wrapper<T>(
        @SerialName("resultado") val results: T
    ) : ApiResult<T>()
}

@Serializable
data class HomeWrapper(
    @SerialName("dataTop") val popular: ApiResult.Paginated<List<MangaDto>>?,
    @SerialName("atualizacoesInicial") val latestUpdates: ApiResult.Paginated<List<MangaDto>>
)

@Serializable
data class MangaDto(
    @SerialName("obr_id") val id: Int,
    @SerialName("obr_nome") val title: String,
    @SerialName("obr_slug") val slug: String?,
    @SerialName("obr_descricao") val rawDescription: String?,
    @SerialName("obr_imagem") val thumbnailPath: String?,
    @SerialName("status") val status: MangaStatus,
    @SerialName("scan_id") val scanId: Int,
    @SerialName("tags") val genres: List<GenreDto>
) {
    fun toSManga(baseUrl: String): SManga {
        val description = rawDescription?.let { 
            Jsoup.parseBodyFragment(it).text() 
        }
        
        return SManga.create().apply {
            url = "/obra/$id/${slug ?: ""}"
            title = this@MangaDto.title
            this.description = description
            thumbnail_url = buildThumbnailUrl()
            status = status.toSMangaStatus()
            genre = genres.joinToString(", ") { it.name }
            initialized = true
        }
    }

    private fun buildThumbnailUrl(): String? {
        return when {
            thumbnailPath.isNullOrBlank() -> null
            thumbnailPath.startsWith("http") -> thumbnailPath
            else -> "$CDN_URL/scans/$scanId/obras/$id/$thumbnailPath"
        }
    }

    @Serializable
    data class GenreDto(
        @SerialName("tag_nome") val name: String
    )

    @Serializable
    data class MangaStatus(
        @SerialName("stt_nome") val name: String?
    ) {
        fun toSMangaStatus(): Int = when (name?.lowercase()) {
            "em andamento" -> SManga.ONGOING
            "completo" -> SManga.COMPLETED
            "hiato" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
data class ChapterListWrapper(
    @SerialName("capitulos") val chapters: List<ChapterDto>
)

@Serializable
data class ChapterDto(
    @SerialName("cap_id") val id: Int,
    @SerialName("cap_nome") val name: String,
    @SerialName("cap_numero") val number: Float?,
    @SerialName("cap_lancado_em") val releaseDate: String
)

@Serializable
data class ChapterPagesWrapper(
    @SerialName("cap_paginas") val pages: List<PageDto>,
    @SerialName("obra") val mangaRef: MangaReference,
    @SerialName("cap_numero") val chapterNumber: Int
) {
    @Serializable
    data class MangaReference(
        @SerialName("obr_id") val id: Int,
        @SerialName("scan_id") val scanId: Int
    )
}

@Serializable
data class PageDto(
    val src: String,
    @SerialName("numero") val position: Int? = null
) {
    val isWordPressContent get() = position == null
}