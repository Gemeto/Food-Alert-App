package id.gemeto.rasff.notifier.domain.service

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class TranslationService {

    private val _defaultTranslatorOptions = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.SPANISH)
        .build()
    private val _languageIdentifier = LanguageIdentification.getClient()
    private val _translatorDownloadConditions = DownloadConditions.Builder().build()

    fun translateTextToSpanish(text: String): String {
        val languageCode = Tasks.await(
            _languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener {}
                .addOnFailureListener {}
        )
        if(languageCode == "es" || languageCode == "und"){
            return text
        }
        var translator = Translation.getClient(_defaultTranslatorOptions)
        val translatorOptions = TranslatorOptions.Builder()
            .setSourceLanguage(languageCode)
            .setTargetLanguage(TranslateLanguage.SPANISH)
            .build()
        translator = Translation.getClient(translatorOptions)
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

    fun translateTextToEnglish(text: String): String {
        val languageCode = Tasks.await(
            _languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener {}
                .addOnFailureListener {}
        )
        if(languageCode == "en" || languageCode == "und") {
            return text
        }
        val translatorOptions = TranslatorOptions.Builder()
            .setSourceLanguage(languageCode)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        val translator = Translation.getClient(translatorOptions)
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