package id.gemeto.rasff.notifier.data

import android.util.Log
import com.fleeksoft.ksoup.Ksoup
import id.gemeto.rasff.notifier.ui.Article
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import tw.ktrssreader.kotlin.model.channel.RssStandardChannel
import tw.ktrssreader.kotlin.parser.RssStandardParser
import java.util.ArrayList

class CloudService(private val httpClient: HttpClient) {
    object CloudServiceConstants {
        const val NO_IMAGE_URL = "https://media.istockphoto.com/id/887464786/vector/no-cameras-allowed-sign-flat-icon-in-red-crossed-out-circle-vector.jpg?s=612x612&w=0&k=20&c=LVkPMBiZas8zxBPmhEApCv3UiYjcbYZJsO-CVQjAJeU="
    }

    suspend fun getRSSArticles(
        urlString: String = "https://webgate.ec.europa.eu/rasff-window/backend/public/consumer/rss/5010/"
    ): RssStandardChannel {
        Log.d("FETCHING", "FETCHING RASFF")
        val response = httpClient.get(urlString).bodyAsText()
        return RssStandardParser().parse(response)
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
                            articles.add(Article(value.text(), content[index+1].text(), link,
                                if(!imageSrc.isNullOrEmpty() && link.contains("aesan.gob.es", true)) { domain + imageSrc } else CloudServiceConstants.NO_IMAGE_URL))
                        }
                    }catch(_: Exception){

                    }
                }.await()
            }
        }
        return articles
    }
}