package eu.kanade.tachiyomi.extension.pt.sussyscan

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.WebViewActivity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class SussyToons : HttpSource(), ConfigurableSource {

    override val name = "Sussy Toons"
    override val lang = "pt-BR"
    override val supportsLatest = true
    override val id = 6963507464339951166
    override val versionId = 2

    private val json: Json by injectLazy()
    private val isCi = System.getenv("CI") == "true"
    private val preferences: SharedPreferences = getPreferences()

    // Configurações de URL
    private var apiUrl: String
        get() = preferences.getString(API_BASE_URL_PREF, defaultApiUrl)!!
        set(value) = preferences.edit().putString(API_BASE_URL_PREF, value).apply()

    private var restoreDefaultEnable: Boolean
        get() = preferences.getBoolean(DEFAULT_PREF, false)
        set(value) = preferences.edit().putBoolean(DEFAULT_PREF, value).apply()

    override val baseUrl: String get() = when {
        isCi -> defaultBaseUrl
        else -> preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!
    }

    private val defaultBaseUrl = "https://www.sussytoons.wtf"
    private val defaultApiUrl = "https://api.sussytoons.wtf"

    // Gerenciamento de Cookies e Cliente HTTP
    private val cookieJar = PersistentCookieJar()
    override val client = network.cloudflareClient.newBuilder()
        .cookieJar(cookieJar)
        .addInterceptor(CloudflareInterceptor())
        .addInterceptor(RetryInterceptor())
        .build()

    init {
        if (restoreDefaultEnable) resetDefaultSettings()
    }

    private fun resetDefaultSettings() {
        restoreDefaultEnable = false
        with(preferences.edit()) {
            putString(DEFAULT_BASE_URL_PREF, null)
            putString(API_DEFAULT_BASE_URL_PREF, null)
            apply()
        }
    }

    //region [Interceptores Cloudflare]
    private inner class CloudflareInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)

            if (isCloudflareChallenge(response)) {
                response.close()
                resolveCloudflareChallenge(request.url.toString())
                return chain.proceed(request.newBuilder().build())
            }
            return response
        }

        private fun isCloudflareChallenge(response: Response): Boolean {
            return response.code == 503 &&
                    response.headers["Server"]?.contains("cloudflare") == true &&
                    response.peekBody(5000).string().contains("challenge-form")
        }

        private fun resolveCloudflareChallenge(url: String) {
            var challengeResolved = false
            WebViewActivity.open(context, url, name,
                onResult = { success ->
                    challengeResolved = success
                    if (!success) throw IOException("Falha na verificação Cloudflare")
                }
            )
            if (!challengeResolved) throw IOException("CAPTCHA não resolvido")
        }
    }

    private inner class RetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var response = chain.proceed(chain.request())
            if (!response.isSuccessful) {
                response.close()
                Thread.sleep(2000)
                response = chain.proceed(chain.request())
            }
            return response
        }
    }

    private inner class PersistentCookieJar : CookieJar {
        private val prefs = getPreferences()
        private val cookieKey = "cloudflare_cookies_${hashCode()}"

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return prefs.getStringSet(cookieKey, emptySet())?.mapNotNull {
                Cookie.parse(url, it)
            } ?: emptyList()
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            prefs.edit().putStringSet(cookieKey,
                cookies.map { it.toString() }.toSet()
            ).apply()
        }

        fun clear() = prefs.edit().remove(cookieKey).apply()
    }
    //endregion

    //region [Parsing Básico]
    override fun popularMangaRequest(page: Int) = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val json = response.parseScriptToJson()
            ?: return MangasPage(emptyList(), false)
        val mangas = json.parseAs<WrapperDto>().popular?.toSMangaList()
            ?: emptyList()
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/atualizacoes".toHttpUrl().newBuilder()
            .addQueryParameter("pagina", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val json = response.parseScriptToJson()
            ?: return MangasPage(emptyList(), false)
        val dto = json.parseAs<WrapperDto>()
        val mangas = dto.latest.toSMangaList()
        return MangasPage(mangas, dto.latest.hasNextPage())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/obras".toHttpUrl().newBuilder()
            .addQueryParameter("obr_nome", query)
            .addQueryParameter("limite", "8")
            .addQueryParameter("pagina", page.toString())
            .addQueryParameter("todos_generos", "true")
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<ResultDto<List<MangaDto>>>()
        return MangasPage(dto.toSMangaList(), dto.hasNextPage())
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val json = response.parseScriptToJson()
            ?: throw IOException("Detalhes do mangá não encontrados")
        return json.parseAs<ResultDto<MangaDto>>().results.toSManga()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = response.parseScriptToJson() ?: return emptyList()
        return json.parseAs<ResultDto<WrapperChapterDto>>().results.chapters.map {
            SChapter.create().apply {
                name = it.name
                it.chapterNumber?.let {
                    chapter_number = it
                }
                setUrlWithoutDomain("$baseUrl/capitulo/${it.id}")
                date_upload = dateFormat.tryParse(it.updateAt)
            }
        }.sortedByDescending(SChapter::chapter_number)
    }
    //endregion

    //region [Parsing de Páginas]
    private val pageUrlSelector = "img.chakra-image"

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        
        if (hasPlaceholderContent(document)) {
            throw IOException("Conteúdo bloqueado. Toque para verificar")
        }

        return extractRealPages(document).ifEmpty {
            parseJsonPages(document)
        }
    }

    private fun hasPlaceholderContent(doc: Document): Boolean {
        return doc.selectFirst("img[src*='cdn-cgi'], img[alt~=Cloudflare]") != null ||
                doc.html().contains("cloudflare")
    }

    private fun extractRealPages(doc: Document): List<Page> {
        return doc.select("$pageUrlSelector:not([src*='cdn-cgi'])").mapIndexed { index, element ->
            Page(index, document.location(), element.absUrl("src"))
        }
    }

    private fun parseJsonPages(document: Document): List<Page> {
        val dto = extractScriptData(document)
            .let(::extractJsonContent)
            .let(::parseJsonToChapterPageDto)

        return dto.pages.mapIndexed { index, image ->
            val imageUrl = when {
                image.isWordPressContent() -> {
                    CDN_URL.toHttpUrl().newBuilder()
                        .addPathSegments("wp-content/uploads/WP-manga/data")
                        .addPathSegments(image.src.toPathSegment())
                        .build()
                }
                else -> {
                    "$CDN_URL/scans/${dto.manga.scanId}/obras/${dto.manga.id}/capitulos/${dto.chapterNumber}/${image.src}"
                        .toHttpUrl()
                }
            }
            Page(index, imageUrl = imageUrl.toString())
        }
    }

    private fun extractScriptData(document: Document): String {
        return document.select("script").map(Element::data)
            .firstOrNull(pageRegex::containsMatchIn)
            ?: throw Exception("Failed to load pages: Script data not found")
    }

    private fun extractJsonContent(scriptData: String): String {
        return pageRegex.find(scriptData)
            ?.groups?.get(1)?.value
            ?.let { json.decodeFromString<String>("\"$it\"") }
            ?: throw Exception("Failed to extract JSON from script")
    }

    private fun parseJsonToChapterPageDto(jsonContent: String): ChapterPageDto {
        return try {
            jsonContent.parseAs<ResultDto<ChapterPageDto>>().results
        } catch (e: Exception) {
            throw Exception("Failed to load pages: ${e.message}")
        }
    }

    override fun imageUrlParse(response: Response): String = ""
    //endregion

    //region [Configurações]
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        listOf(
            createUrlPreference(BASE_URL_PREF, "URL Base", defaultBaseUrl),
            createUrlPreference(API_BASE_URL_PREF, "URL da API", defaultApiUrl),
            createResetPreference(),
            createCookiePreference()
        ).forEach(screen::addPreference)
    }

    private fun createUrlPreference(key: String, title: String, default: String): EditTextPreference {
        return EditTextPreference(screen.context).apply {
            this.key = key
            this.title = title
            summary = "Clique para editar\nPadrão: $default"
            dialogTitle = title
            dialogMessage = "URL padrão:\n$default"
            setDefaultValue(default)
        }
    }

    private fun createResetPreference(): SwitchPreferenceCompat {
        return SwitchPreferenceCompat(screen.context).apply {
            key = DEFAULT_PREF
            title = "Redefinir configurações"
            summary = "Restaura valores padrão no próximo início"
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(context, "Reinicie o app para aplicar", Toast.LENGTH_LONG).show()
                true
            }
        }
    }

    private fun createCookiePreference(): SwitchPreferenceCompat {
        return SwitchPreferenceCompat(screen.context).apply {
            key = "clear_cookies"
            title = "Limpar cookies"
            summary = "Remove todos os dados de navegação"
            setOnPreferenceClickListener {
                cookieJar.clear()
                Toast.makeText(context, "Cookies limpos!", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }
    //endregion

    //region [Utilitários]
    private fun Response.parseScriptToJson(): String? {
        val quickJs = QuickJs.create()
        val document = asJsoup()
        val script = document.select("script")
            .map(Element::data)
            .filter(String::isNotEmpty)
            .joinToString("\n")

        val content = quickJs.evaluate(
            """
                globalThis.self = globalThis;
                $script
                self.__next_f.map(it => it[it.length - 1]).join('')
            """.trimIndent(),
        ) as String

        return PAGE_JSON_REGEX.find(content)?.groups?.get(0)?.value
    }

    private fun HttpUrl.Builder.dropPathSegment(count: Int): HttpUrl.Builder {
        repeat(count) {
            removePathSegment(0)
        }
        return this
    }

    private fun String.toPathSegment() = this.trim().split("/")
        .filter(String::isNotEmpty)
        .joinToString("/")
    //endregion

    companion object {
        const val CDN_URL = "https://cdn.sussytoons.site"

        val pageRegex = """capituloInicial.{3}(.*?)(\}\]\})""".toRegex()
        val POPULAR_JSON_REGEX = """\{\"dataFeatured.+totalPaginas":\d+\}{2}""".toRegex()
        val LATEST_JSON_REGEX = """\{\"atualizacoesInicial.+\}\}""".toRegex()
        val DETAILS_CHAPTER_REGEX = """\{\"resultado.+"\}{3}""".toRegex()
        val PAGE_JSON_REGEX = """$POPULAR_JSON_REGEX|$LATEST_JSON_REGEX|$DETAILS_CHAPTER_REGEX""".toRegex()

        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val API_BASE_URL_PREF = "overrideApiUrl"
        private const val DEFAULT_PREF = "defaultPref"
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val API_DEFAULT_BASE_URL_PREF = "defaultApiUrl"

        @SuppressLint("SimpleDateFormat")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    }
}

//region [Classes de DTO]
@Serializable
data class WrapperDto(
    val popular: List<MangaDto>? = null,
    val latest: AtualizacoesWrapper
)

@Serializable
data class AtualizacoesWrapper(
    val obras: List<MangaDto>,
    val tem_proxima: Boolean
)

@Serializable
data class MangaDto(
    val obr_id: String,
    val obr_slug: String,
    val obr_nome: String,
    val obr_sinopse: String? = null,
    val obr_status: String,
    val obr_autor: String? = null,
    val obr_generos: List<String>,
    val scanId: String? = null
)

@Serializable
data class WrapperChapterDto(val chapters: List<ChapterDto>)

@Serializable
data class ChapterDto(
    val cap_id: String,
    val cap_nome: String,
    @SerialName("cap_numero") val chapterNumber: Float?,
    @SerialName("cap_data_atualizacao") val updateAt: String
)

@Serializable
data class ChapterPageDto(
    val manga: MangaScanInfo,
    val chapterNumber: String,
    val pages: List<PageData>
)

@Serializable
data class MangaScanInfo(val id: String, val scanId: String)

@Serializable
data class PageData(val src: String) {
    fun isWordPressContent() = src.contains("wp-content", ignoreCase = true)
}
//endregion