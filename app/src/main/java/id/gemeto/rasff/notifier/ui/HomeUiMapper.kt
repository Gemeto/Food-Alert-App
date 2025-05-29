package id.gemeto.rasff.notifier.ui

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class HomeUiMapper {

    private val _defaultTranslatorOptions = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.SPANISH)
        .build()
    private val _languageIdentifier = LanguageIdentification.getClient()
    private val _translatorDownloadConditions = DownloadConditions.Builder().build()

    fun map(articles: List<Article>): HomeUiState {
        val orderedArticles = articles.sortedByDescending { item -> item.unixTime }
        return HomeUiState(
            articles = orderedArticles.map { item ->
                Article(translateTextToSpanish(item.title), translateTextToSpanish(item.description), item.link, item.imageUrl, item.unixTime, item.titleVector)
            }
        )
    }

    private fun translateTextToSpanish(text: String): String {
        val languageCode = Tasks.await(
            _languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener {}
            .addOnFailureListener {}
        )
        var translator = Translation.getClient(_defaultTranslatorOptions)
        if (languageCode != "und" && languageCode != "en") {
            val translatorOptions = TranslatorOptions.Builder()
                .setSourceLanguage(languageCode)
                .setTargetLanguage(TranslateLanguage.SPANISH)
                .build()
            translator = Translation.getClient(translatorOptions)
        }
        Tasks.await(
            translator.downloadModelIfNeeded(_translatorDownloadConditions)
                .addOnSuccessListener {}
                .addOnFailureListener {}
        )
        val translation = Tasks.await(
            translator.translate(text)
                .addOnSuccessListener {}
                .addOnFailureListener {}
        )
        translator.close()
        return translation
    }
}
