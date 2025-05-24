package id.gemeto.rasff.notifier.data

import android.util.Log
import com.fleeksoft.ksoup.Ksoup
import id.gemeto.rasff.notifier.ui.Article
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayList
import java.util.regex.Pattern


class CloudService(private val httpClient: HttpClient) {
    object CloudServiceConstants {
        const val NO_IMAGE_URL = "https://media.istockphoto.com/id/887464786/vector/no-cameras-allowed-sign-flat-icon-in-red-crossed-out-circle-vector.jpg?s=612x612&w=0&k=20&c=LVkPMBiZas8zxBPmhEApCv3UiYjcbYZJsO-CVQjAJeU="
    }
    var lastRSSArticleDate: Long = Long.MAX_VALUE

    fun extractFirstHttpsUrl(text: String): String {
        val urlPattern = Pattern.compile(
            "https://[^\\s]+",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = urlPattern.matcher(text)

        return if (matcher.find()) {
            matcher.group(0) // group(0) returns the entire matched string
        } else {
            ""
        }
    }

    suspend fun getRSSArticles(
        urlString: String = "https://webgate.ec.europa.eu/rasff-window/backend/public/consumer/rss/5010/"
    ): List<Article> {
        Log.d("FETCHING", "FETCHING RASFF SITE")
        val html = httpClient.get(urlString).bodyAsText()
        val doc = Ksoup.parse(html)
        val content = doc.select("item")
        val articles = ArrayList<Article>()
        Log.d("FETCHING", "FETCHING RASFF PAGE")
        coroutineScope {
            content.forEachIndexed { index, value ->
                async {
                    try {
                        val text = value.select("description").text()
                        val textDate = text.replace("Notified by .* on ".toRegex(), "")
                        var unixTime: Long = 0
                        if(textDate.isNotEmpty()) {
                            val date = LocalDate.parse(textDate, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                            unixTime = date.atStartOfDay(ZoneId.systemDefault()).toInstant().epochSecond
                        }
                        articles.add(Article(value.select("title").text(), value.select("description").text(), extractFirstHttpsUrl(value.toString()), CloudServiceConstants.NO_IMAGE_URL, unixTime))
                    }catch(_: Exception){

                    }
                }.await()
            }
        }
        lastRSSArticleDate = articles.last().unixTime
        return articles
    }

    suspend fun getHTMLArticles(
        page: Int = 1,
        itemsPerPage: Int = 10,
        urlString: String = "https://www.aesan.gob.es/AECOSAN/web/seguridad_alimentaria/subseccion/otras_alertas_alimentarias.htm"
    ): List<Article> {
        val domain = "https://www.aesan.gob.es"
        Log.d("FETCHING", "FETCHING AESAN SITE")
        val html = httpClient.get(urlString).bodyAsText()
        val doc = Ksoup.parse(html)
        val content = doc.select(".theContent p")
        val temp = content.drop(2).dropLast(1)
        val articles = ArrayList<Article>()
        Log.d("FETCHING", "FETCHING AESAN PAGE")
        content.clear()
        temp.toCollection(content)
        coroutineScope {
            content.forEachIndexed { index, value ->
                async {//Processing async because the image scrapping takes some time
                    try {
                        val iteration: Int = index/2
                        val loadedElements = (page-1) * itemsPerPage
                        if (index % 2 == 0 && iteration > loadedElements && iteration <= (page * itemsPerPage)) {
                            var link = value.selectFirst("a")!!.attr("href")
                            if (!link.contains("http")) {
                                link = domain + link
                            }
                            val articleHtml = httpClient.get(link).bodyAsText()
                            val articleDoc = Ksoup.parse(articleHtml)
                            val imageSrc = articleDoc.selectFirst("img")?.attr("src")
                            val text = articleDoc.selectFirst(".theContent p")?.text() ?: ""
                            val textDate = text.replace("Fecha y hora: ", "")
                            var unixTime: Long = 0
                            if(textDate.isNotEmpty() && !textDate.contains("[a-zA-Z]".toRegex())) {
                                val date = LocalDate.parse(textDate, DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                                unixTime = date.atStartOfDay(ZoneId.systemDefault()).toInstant().epochSecond
                            }
                            articles.add(Article(value.text(), content[index+1].text(), link,
                                if(!imageSrc.isNullOrEmpty() && link.contains("aesan.gob.es", true)) { domain + imageSrc } else CloudServiceConstants.NO_IMAGE_URL, unixTime))
                        }
                    }catch(_: Exception){

                    }
                }.await()
            }
        }
        return articles
    }
}