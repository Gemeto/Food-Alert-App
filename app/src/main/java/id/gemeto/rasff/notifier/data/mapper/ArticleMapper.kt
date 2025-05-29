package id.gemeto.rasff.notifier.data.mapper

import id.gemeto.rasff.notifier.data.local.entity.Article as DbArticle
import id.gemeto.rasff.notifier.ui.Article as UiArticle

class ArticleMapper {

    companion object {
        fun toDbArticle(uiArticle: UiArticle, titleVector: List<Float>? = null): DbArticle {
            return DbArticle(
                id = uiArticle.link,
                title = uiArticle.title,
                content = uiArticle.description,
                imageUrl = uiArticle.imageUrl,
                unixTime = uiArticle.unixTime,
                titleVector = titleVector ?: emptyList()
            )
        }

        fun toUiArticle(dbArticle: DbArticle): UiArticle {
            return UiArticle(
                title = dbArticle.title,
                description = dbArticle.content,
                link = dbArticle.id,
                imageUrl = dbArticle.imageUrl,
                unixTime = dbArticle.unixTime,
                titleVector = dbArticle.titleVector
            )
        }
    }

}