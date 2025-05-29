package id.gemeto.rasff.notifier.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.components.utils.CosineSimilarity
import id.gemeto.rasff.notifier.data.local.AppDatabase
import id.gemeto.rasff.notifier.data.local.dao.ArticleDAO
import id.gemeto.rasff.notifier.data.remote.CloudService
import id.gemeto.rasff.notifier.domain.service.TitleVectorizerService
import id.gemeto.rasff.notifier.data.remote.ktorClient
import id.gemeto.rasff.notifier.ui.Article
import id.gemeto.rasff.notifier.ui.util.UiResult
import kotlinx.coroutines.Dispatchers
import id.gemeto.rasff.notifier.data.local.entity.Article as DbArticle
import id.gemeto.rasff.notifier.ui.Article as UiArticle
import id.gemeto.rasff.notifier.data.mapper.ArticleMapper.Companion.toDbArticle
import id.gemeto.rasff.notifier.data.mapper.ArticleMapper.Companion.toUiArticle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Optional
import kotlin.collections.distinctBy
import kotlin.collections.plus

// TODO: Replace AndroidViewModel with ViewModel and use Hilt for dependency injection
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    //Dependencies
    private val _db = Room.databaseBuilder(
        context = application,
        AppDatabase::class.java, "database-alert-notifications"
    ).build()
    private val _articleDao: ArticleDAO = _db.articleDao()
    private val _cloudService = CloudService(ktorClient)
    private val _titleVectorizerService = TitleVectorizerService.getInstance(
                embeddingModelPath = "/data/local/tmp/gecko.tflite",
                sentencePieceModelPath = "/data/local/tmp/sentencepiece.model",
                useGpu = true)
    private val _uiMapper = HomeUiMapper()

    //Variables
    private val _uiState = MutableStateFlow<UiResult<HomeUiState>>(UiResult.Loading)
    private val _uiStateUnfiltered = MutableStateFlow<UiResult<HomeUiState>>(UiResult.Loading) // Holds all articles from DB/Cloud
    private val _searchText = MutableStateFlow("")
    val searchText = _searchText.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()
    private val SIMILARITY_THRESHOLD = 0.8f
    // Wrapper for articles with their similarity scores
    private data class ArticleWithSimilarity(val dbArticle: DbArticle, val similarity: Float)

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()
    private val _page = MutableStateFlow(0)
    private var _allRemoteArticlesLoaded = false

    //Objects and functions
    object HomeViewConstants {
        const val TITLE = "Alertas alimentarias en España"
        const val DESCRIPTION = "Hilo de alertas alimentarias en España notificadas por la aplicación RASFF y la web de la AESAN"
        const val ITEMS_PER_PAGE = 3 //TO DO MAKE DYNAMIC BASED ON VIEWPORT
    }

    private fun searchQuerys(): List<String> = searchText.value.trim().split(" ", "\n").map { it.lowercase().removeSuffix("s") }

    private suspend fun searchFilter(article: UiArticle): Boolean =
        searchQuerys().any{ query -> article.title.lowercase().contains(query) }
        //|| CosineSimilarity.compute(
        //    Embedding.create(_titleVectorizerService.getVector(searchText.value).toFloatArray(), ByteArray(0), 0, Optional.empty()),
        //    Embedding.create(article.titleVector.toFloatArray(), ByteArray(0), 0, Optional.empty()),
        //).toFloat() > SIMILARITY_THRESHOLD //TODO: Fix this because when getVector is called, it resets the articles list and pops on screen

    private fun shouldLoadMore(): Boolean =
        !_allRemoteArticlesLoaded && !_isSearching.value &&
        !_isLoadingMore.value && _uiStateUnfiltered.value is UiResult.Success

    private suspend fun hasMoreToLoad(articles: List<Article>, currentItems: Int): Boolean  =
        !_allRemoteArticlesLoaded && !_isSearching.value
        && (articles.count { searchFilter(it) } < (currentItems + HomeViewConstants.ITEMS_PER_PAGE)
        || _cloudService.lastRSSArticleDate < articles.last().unixTime) // This is to ensure list will always have articles from any src older than the last one

    val uiState: StateFlow<UiResult<HomeUiState>> = searchText
        .onEach { _isSearching.update { true } }
        .combine(_uiStateUnfiltered) { query, unfilteredState ->
            if (query.isNotEmpty()) {
                _isSearching.update { true }
                // List<Article> empty to add the final UI articles
                var finalUiArticles = List<Article>(0){ Article("", "", "", "", 0, floatArrayOf(0.0f).toList()) }
                if (unfilteredState is UiResult.Success) {
                    // Perform keyword search on the UI articles held in unfilteredState
                    val keywordFilteredUiArticles = unfilteredState.data.articles.filter { uiArt -> searchFilter(uiArt) }
                    finalUiArticles = keywordFilteredUiArticles
                    finalUiArticles = finalUiArticles.distinctBy { it.link }
                } else {
                    _isSearching.update { false }
                    return@combine unfilteredState
                }
                _isSearching.update { false }
                UiResult.Success(HomeUiState(finalUiArticles.sortedByDescending { it.unixTime }))
            } else { // query is empty
                _isSearching.update { false }
                unfilteredState
            }
        }
        .onEach { _isSearching.update { false } }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            _uiState.value // Initial value for the combined flow
        )

    init {
        viewModelScope.launch {
            _uiState.update { UiResult.Loading }
            _uiStateUnfiltered.update { UiResult.Loading }
            try {
                var articlesFromDb = withContext(Dispatchers.IO) { _articleDao.getAll() }
                var uiArticles = articlesFromDb.map { toUiArticle(it) }

                val rssArticles = withContext(Dispatchers.IO) { _cloudService.getRSSArticles() }
                val htmlArticles = withContext(Dispatchers.IO) { _cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE) }
                val cloudArticles = rssArticles.plus(htmlArticles)

                val maxArticleUnixTime = cloudArticles.maxBy { it.unixTime }.unixTime
                if(uiArticles.isEmpty() || maxArticleUnixTime != uiArticles.maxBy { it.unixTime }.unixTime) {
                    val dbArticlesToInsert = withContext(Dispatchers.IO) {
                        cloudArticles.map { uiArticle ->
                            val titleVector = _titleVectorizerService.getVector(uiArticle.title)
                            toDbArticle(uiArticle, titleVector)
                        }
                    }
                    _articleDao.insertAll(dbArticlesToInsert)
                    articlesFromDb = withContext(Dispatchers.IO) { _articleDao.getAll() }
                    uiArticles = articlesFromDb.map { toUiArticle(it) }
                }

                val currentArticles = uiArticles.count { searchFilter(it) }
                while(hasMoreToLoad(uiArticles, currentArticles)){
                    _page.update { _page.value + 1 }
                    val newArticles = _cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE)
                    if(newArticles.isEmpty()){
                        _allRemoteArticlesLoaded = true
                        break
                    }
                    if(uiArticles.size != uiArticles.plus(newArticles).distinctBy { it.link }.size){
                        val newDbArticles = withContext(Dispatchers.IO) {
                            newArticles.map { uiArticle ->
                                val titleVector = _titleVectorizerService.getVector(uiArticle.title)
                                toDbArticle(uiArticle, titleVector)
                            }
                        }
                        _articleDao.insertAll(newDbArticles)
                        articlesFromDb = withContext(Dispatchers.IO) { _articleDao.getAll() }
                        uiArticles = articlesFromDb.map { toUiArticle(it) }
                    }
                }
                val homeUiState = withContext(Dispatchers.Default) {
                    _uiMapper.map(uiArticles)
                }
                _uiStateUnfiltered.value = UiResult.Success(homeUiState)
            } catch (err: Throwable) {
                val errorResult = UiResult.Fail(err)
                _uiStateUnfiltered.value = errorResult
            }
        }
    }

    fun loadMoreArticles() {
        viewModelScope.launch {
            try {
                if (shouldLoadMore()) {
                    _isLoadingMore.update { true }
                    _page.value++
                    val newCloudArticles = withContext(Dispatchers.IO) {
                        _cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE)
                    }
                    if (newCloudArticles.isNotEmpty()) {
                        val newDbArticles = withContext(Dispatchers.IO) {
                            newCloudArticles.map { uiArticle ->
                                val titleVector = _titleVectorizerService.getVector(uiArticle.title)
                                toDbArticle(uiArticle, titleVector)
                            }
                        }
                        _articleDao.insertAll(newDbArticles)
                        var allDbArticles = withContext(Dispatchers.IO) { _articleDao.getAll() }
                        var allUiArticles = allDbArticles.map { toUiArticle(it) }.sortedByDescending { it.unixTime }
                        val currentArticles = allUiArticles.count { searchFilter(it) }
                        while(hasMoreToLoad(allUiArticles, currentArticles)){
                            _page.update { _page.value + 1 }
                            val newArticles = _cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE)
                            if(newArticles.isEmpty()){
                                _allRemoteArticlesLoaded = true
                                break
                            }
                            if(allUiArticles.size != allUiArticles.plus(newArticles).distinctBy { it.link }.size){
                                val newDbArticles = withContext(Dispatchers.IO) {
                                    newArticles.map { uiArticle ->
                                        val titleVector = _titleVectorizerService.getVector(uiArticle.title)
                                        toDbArticle(uiArticle, titleVector)
                                    }
                                }
                                _articleDao.insertAll(newDbArticles)
                                allDbArticles = withContext(Dispatchers.IO) { _articleDao.getAll() }
                                allUiArticles = allDbArticles.map { toUiArticle(it) }.sortedByDescending { it.unixTime }
                            }
                        }
                        val homeUiState = withContext(Dispatchers.IO) { _uiMapper.map(allUiArticles) }
                        _uiStateUnfiltered.value = UiResult.Success(homeUiState)
                    } else {
                        _allRemoteArticlesLoaded = true
                    }
                    _isLoadingMore.update { false }
                }
            } catch (err: Throwable) {
                _uiState.update { UiResult.Fail(err) }
                _isLoadingMore.update { false }
            }
        }
    }

    fun onSearchTextChange(text: String) {
        _searchText.value = text
    }
}