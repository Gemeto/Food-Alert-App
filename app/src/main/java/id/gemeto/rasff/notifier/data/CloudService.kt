package id.gemeto.rasff.notifier.data

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

    suspend fun getRSSArticles(
        urlString: String = "https://webgate.ec.europa.eu/rasff-window/backend/public/consumer/rss/5010/"
    ): RssStandardChannel {
        val response = httpClient.get(urlString).bodyAsText()
        return RssStandardParser().parse(response)
    }

    suspend fun getHTMLArticles(
        urlString: String = "https://www.aesan.gob.es/AECOSAN/web/seguridad_alimentaria/subseccion/otras_alertas_alimentarias.htm"
    ): List<Article> {
        val domain = "https://www.aesan.gob.es"
        val html = httpClient.get(urlString).bodyAsText()
        val doc = Ksoup.parse(html)
        val content = doc.select(".theContent p")
        val temp = content.drop(2).dropLast(1)
        val articles = ArrayList<Article>()
        content.clear()
        temp.toCollection(content)
        coroutineScope {
            content.forEachIndexed { index, value ->
                async {//Processing async because the image scrapping takes some time
                    try {
                        if (index % 2 == 0) {
                            var link = value.selectFirst("a")!!.attr("href")
                            if (!link.contains("http")) {
                                link = domain + link
                            }
                            val articleHtml = httpClient.get(
                                link
                            ).bodyAsText()
                            val articleDoc = Ksoup.parse(articleHtml)
                            val imageSrc = articleDoc.selectFirst("img")?.attr("src")
                            articles.add(Article(value.text(), content[index+1].text(), value.selectFirst("a")!!.attr("href"), if(imageSrc != null) { domain + imageSrc } else ""))
                        }
                    }catch(_: Exception){

                    }
                }.await()
            }
        }
        return articles
    }
}