package id.gemeto.rasff.notifier.ui

import android.text.Html
import tw.ktrssreader.kotlin.model.channel.RssStandardChannel
import tw.ktrssreader.kotlin.model.item.RssStandardItem

class HomeUiMapper {

    fun map(responseRSS: RssStandardChannel, responseHTML: List<Article>): HomeUiState {
        return HomeUiState(
            title = responseRSS.title.orEmpty(),
            link = responseRSS.link.orEmpty(),
            description = responseRSS.description.orEmpty(),
            articles = mapArticle(responseRSS.items.orEmpty()).plus(responseHTML)
        )
    }

    private fun mapArticle(response: List<RssStandardItem>): List<Article> {
        return response.map { article ->
            Article(
                title = article.title.orEmpty(),
                description = if (article.description != null) {
                    Html.fromHtml(article.description, Html.FROM_HTML_MODE_COMPACT).toString()
                } else "",
                link = article.link.orEmpty(),
                imageUrl = article.enclosure?.url.orEmpty(),
            )
        }
    }
}
