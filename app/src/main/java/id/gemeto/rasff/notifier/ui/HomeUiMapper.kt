package id.gemeto.rasff.notifier.ui

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class HomeUiMapper {

    private val translatorOptions = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.SPANISH)
        .build()
    private val englishSpanishTranslator = Translation.getClient(translatorOptions)
    private val translatorConditions = DownloadConditions.Builder().build()

    fun map(articles: List<Article>): HomeUiState {
        return HomeUiState(
            articles = articles.map { item ->
                Article(translateText(item.title), translateText(item.description), item.link, item.imageUrl)
            }
        )
    }

    private fun translateText(text: String): String {
        Tasks.await(
            englishSpanishTranslator.downloadModelIfNeeded(translatorConditions)
                .addOnSuccessListener {}
                .addOnFailureListener {}
        )
        return Tasks.await(
            englishSpanishTranslator.translate(text)
                .addOnSuccessListener {}
                .addOnFailureListener {}
        )
    }
}
