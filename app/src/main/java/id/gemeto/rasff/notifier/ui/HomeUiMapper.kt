package id.gemeto.rasff.notifier.ui

class HomeUiMapper {

    fun map(articles: List<Article>): HomeUiState {
        val orderedArticles = articles.sortedByDescending { item -> item.unixTime }
        return HomeUiState(
            articles = orderedArticles.map { item ->
                Article(
                    item.title,
                    item.description,
                    item.link,
                    item.imageUrl,
                    item.unixTime,
                    item.titleVector
                )
            }
        )
    }
}
