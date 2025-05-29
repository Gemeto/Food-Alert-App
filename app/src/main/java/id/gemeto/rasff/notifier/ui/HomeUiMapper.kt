package id.gemeto.rasff.notifier.ui

import id.gemeto.rasff.notifier.domain.service.TranslationService

class HomeUiMapper {

    private val _translationService = TranslationService()

    fun map(articles: List<Article>): HomeUiState {
        val orderedArticles = articles.sortedByDescending { item -> item.unixTime }
        return HomeUiState(
            articles = orderedArticles.map { item ->
                Article(
                    _translationService.translateTextToSpanish(item.title),
                    _translationService.translateTextToSpanish(item.description),
                    item.link,
                    item.imageUrl,
                    item.unixTime,
                    item.titleVector
                )
            }
        )
    }
}
