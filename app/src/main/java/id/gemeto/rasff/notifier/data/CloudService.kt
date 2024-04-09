package id.gemeto.rasff.notifier.data

import com.fleeksoft.ksoup.Ksoup
import id.gemeto.rasff.notifier.ui.Article
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        val titles = ArrayList<String>()
        val links = ArrayList<String>()
        val images = ArrayList<String>()
        val temp = content.drop(2).dropLast(1)
        content.clear()
        temp.toCollection(content)
        var i = 0
        coroutineScope {
            content.forEachIndexed { index, value ->
                launch(Dispatchers.Main) {
                    withContext(Dispatchers.Main) {
                        //Titles
                        if (index % 2 == 0) {
                            titles.add(i, value.text())
                        } else {
                            titles[i] = titles[i] + " - " + value.text()
                            i++
                        }
                        //Links
                        if (value.selectFirst("a") != null) {
                            val link = value.selectFirst("a")!!.attr("href")
                            links.add(link)
                            //Images
                            val articleHtml = httpClient.get(
                                if (link.contains("http")) {
                                    link
                                } else {
                                    domain + link
                                }
                            ).bodyAsText()
                            val articleDoc = Ksoup.parse(articleHtml)
                            val imageSrc = articleDoc.selectFirst("img")?.attr("src")
                            if (imageSrc != null) {
                                images.add(domain + imageSrc)
                            } else {
                                images.add("")
                            }
                        }
                    }
                }
            }
        }
        val articles = ArrayList<Article>()
        titles.forEachIndexed {index, value ->
            articles.add(Article(titles[index], "", links[index], images[index]))
        }
        return articles
    }
}