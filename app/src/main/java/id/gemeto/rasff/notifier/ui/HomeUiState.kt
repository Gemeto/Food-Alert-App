package id.gemeto.rasff.notifier.ui

import androidx.compose.runtime.Immutable

data class HomeUiState(
    val title: String,
    val link: String,
    val description: String,
    val articles: List<Article>
)

@Immutable
data class Article(
    val title: String,
    val description: String,
    val link: String,
    val imageUrl: String
)