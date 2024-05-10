package id.gemeto.rasff.notifier.ui

import android.text.Html
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import id.gemeto.rasff.notifier.data.CloudService
import tw.ktrssreader.kotlin.model.channel.RssStandardChannel
import tw.ktrssreader.kotlin.model.item.RssStandardItem

class HomeUiMapper {

    private val translatorOptions = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.SPANISH)
        .build()
    private val englishSpanishTranslator = Translation.getClient(translatorOptions)
    private val translatorConditions = DownloadConditions.Builder().build()

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
                title = translateText(article.title.orEmpty()),
                description = if (article.description != null) {
                    translateText(Html.fromHtml(article.description, Html.FROM_HTML_MODE_COMPACT).toString())
                } else "",
                link = article.link.orEmpty(),
                imageUrl = CloudService.CloudServiceConstants.NO_IMAGE_URL,
            )
        }
    }

    private fun translateText(text: String): String {
        var result = ""
        Tasks.await(
            englishSpanishTranslator.downloadModelIfNeeded(translatorConditions)
            .addOnSuccessListener {

            }.addOnFailureListener { exception ->

            }
        )
        result = Tasks.await(
            englishSpanishTranslator.translate(text)
                .addOnSuccessListener { translated ->
                    translated
                }.addOnFailureListener { exception ->

                }
        )
        return result
    }
}
