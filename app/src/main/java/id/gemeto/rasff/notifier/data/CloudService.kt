package id.gemeto.rasff.notifier.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import tw.ktrssreader.kotlin.model.channel.RssStandardChannel
import tw.ktrssreader.kotlin.parser.RssStandardParser

class CloudService(private val httpClient: HttpClient) {

    suspend fun getArticles(
        urlString: String = "https://webgate.ec.europa.eu/rasff-window/backend/public/consumer/rss/5010/"
    ): RssStandardChannel {
        val response = httpClient.get(urlString).bodyAsText()
        return RssStandardParser().parse(response)
    }
}