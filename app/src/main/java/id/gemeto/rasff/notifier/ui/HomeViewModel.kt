package id.gemeto.rasff.notifier.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import id.gemeto.rasff.notifier.data.AppDatabase
import id.gemeto.rasff.notifier.data.ArticleDAO
import id.gemeto.rasff.notifier.data.CloudService
import id.gemeto.rasff.notifier.data.TitleVectorizerService
import id.gemeto.rasff.notifier.data.ktorClient
import id.gemeto.rasff.notifier.ui.Article
import id.gemeto.rasff.notifier.ui.util.UiResult
import id.gemeto.rasff.notifier.utils.VectorUtils
import kotlinx.coroutines.Dispatchers
import id.gemeto.rasff.notifier.data.Article as DbArticle
import id.gemeto.rasff.notifier.ui.Article as UiArticle
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

    //Mappers
    private fun toDbArticle(uiArticle: UiArticle, titleVector: List<Float>? = null): DbArticle {
        return DbArticle(
            id = uiArticle.link,
            title = uiArticle.title,
            content = uiArticle.description,
            imageUrl = uiArticle.imageUrl,
            unixTime = uiArticle.unixTime,
            titleVector = titleVector ?: emptyList()
        )
    }

    private fun toUiArticle(dbArticle: DbArticle): UiArticle {
        return UiArticle(
            title = dbArticle.title,
            description = dbArticle.content,
            link = dbArticle.id,
            imageUrl = dbArticle.imageUrl,
            unixTime = dbArticle.unixTime,
            titleVector = dbArticle.titleVector
        )
    }

    //Variables
    private val _uiState = MutableStateFlow<UiResult<HomeUiState>>(UiResult.Loading)
    private val _uiStateUnfiltered = MutableStateFlow<UiResult<HomeUiState>>(UiResult.Loading) // Holds all articles from DB/Cloud
    private val _searchText = MutableStateFlow("")
    val searchText = _searchText.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    // Threshold for similarity; can be adjusted
    private val SIMILARITY_THRESHOLD = 0.7f

    // Wrapper for articles with their similarity scores
    private data class ArticleWithSimilarity(val dbArticle: DbArticle, val similarity: Float)

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()
    private val _page = MutableStateFlow(0)
    private var _allArticlesLoaded = false

    //Objects and functions
    object HomeViewConstants {
        const val TITLE = "Alertas alimentarias en España"
        const val DESCRIPTION = "Hilo de alertas alimentarias en España notificadas por la aplicación RASFF y la web de la AESAN"
        const val ITEMS_PER_PAGE = 3 //TO DO MAKE DYNAMIC BASED ON VIEWPORT
    }
    private fun canLoad(): Boolean = !_allArticlesLoaded && !_isSearching.value &&
            !_isLoadingMore.value && _uiStateUnfiltered.value is UiResult.Success

    private suspend fun canLoadMore(articles: List<Article>, currentItems: Int): Boolean  = !_isSearching.value
            && (articles.count { it.title.contains(searchText.value, true) }
            + articles.count { uiArticle ->
            VectorUtils.cosineSimilarity(_titleVectorizerService.getVector(searchText.value), uiArticle.titleVector) > SIMILARITY_THRESHOLD }
            < (currentItems + HomeViewConstants.ITEMS_PER_PAGE)
            || _cloudService.lastRSSArticleDate < articles.last().unixTime)

    private suspend fun canLoadMoreSearching(articlesInUi: List<UiArticle>): Boolean  = !_allArticlesLoaded &&
            totalSearchedArticles(articlesInUi) < HomeViewConstants.ITEMS_PER_PAGE

    private fun searchQuerys(): List<String> = searchText.value.trim().split(" ", "\n").map { it.lowercase().removeSuffix("s") }

    //This function now also have in account the similarity findings
    private suspend fun totalSearchedArticles(articles: List<UiArticle>): Int = articles.count { uiArticle ->
        searchQuerys().any{ query -> uiArticle.title.lowercase().contains(query) }
    } + articles.count { uiArticle ->
        VectorUtils.cosineSimilarity(_titleVectorizerService.getVector(searchText.value), uiArticle.titleVector) > SIMILARITY_THRESHOLD
    }
    private suspend fun searchFilter(article: UiArticle): Boolean =
        searchQuerys().any{ query -> article.title.lowercase().contains(query) }

    val uiState: StateFlow<UiResult<HomeUiState>> = searchText
        .onEach { _isSearching.update { true } }
        .combine(_uiStateUnfiltered) { query, unfilteredState ->
            if (query.isNotEmpty()) {
                _isSearching.update { true }
                val queryVector = _titleVectorizerService.getVector(query)
                val allDbArticles = _articleDao.getAll()

                val articlesWithSimilarity = allDbArticles.map { dbArticle ->
                    val similarity = VectorUtils.cosineSimilarity(queryVector, dbArticle.titleVector)
                    ArticleWithSimilarity(dbArticle, similarity)
                }

                // Primary filter: similarity search
                var filteredArticles = articlesWithSimilarity
                    .filter { it.similarity > SIMILARITY_THRESHOLD && it.similarity.isFinite() }
                    .sortedByDescending { it.similarity }
                    .take(5)
                    .map { DbArticle( it.dbArticle.id,
                        it.dbArticle.title + " " + it.similarity, it.dbArticle.content,
                        it.dbArticle.imageUrl, it.dbArticle.unixTime, it.dbArticle.titleVector) }
                var finalUiArticles = filteredArticles.map { toUiArticle(it) }
                if (unfilteredState is UiResult.Success) {
                    // Perform keyword search on the UI articles held in unfilteredState
                    val keywordFilteredUiArticles = unfilteredState.data.articles.filter { uiArt -> searchFilter(uiArt) }
                    finalUiArticles = finalUiArticles.plus(keywordFilteredUiArticles)
                    finalUiArticles = finalUiArticles.distinctBy { it.link }
                } else {
                    _isSearching.update { false }
                    return@combine unfilteredState
                }

                while (canLoadMoreSearching(finalUiArticles)) { // Pass the currently filtered list for count check
                    _page.value++
                    val newCloudArticles = _cloudService.getHTMLArticles(
                        _page.value,
                        HomeViewConstants.ITEMS_PER_PAGE
                    )
                    if (newCloudArticles.isEmpty()) {
                        _allArticlesLoaded = true
                        break
                    }
                    val newDbArticlesFromCloud = withContext(Dispatchers.IO) {
                        newCloudArticles.map { uiArticle ->
                            val titleVector = _titleVectorizerService.getVector(uiArticle.title)
                            toDbArticle(uiArticle, titleVector)
                        }
                    }
                    _articleDao.insertAll(newDbArticlesFromCloud)
                     val allLatestDbArticles = _articleDao.getAll()
                     val allLatestUiArticles = allLatestDbArticles.map { toUiArticle(it) }
                     val articles = withContext(Dispatchers.Default) {
                         _uiMapper.map(allLatestUiArticles)
                    }
                    _uiStateUnfiltered.value = UiResult.Success(articles)

                    //finalUiArticles = finalUiArticles.plus(allLatestUiArticles)
                    //    .sortedByDescending { it.unixTime }
                    //    .distinctBy { it.link }
                    break
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
                if (canLoad()) {
                    _isLoadingMore.update { true }
                    _page.value++ // Increment page for fetching next set from cloud
                    val newCloudArticles = withContext(Dispatchers.IO) {
                        _cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE)
                    }

                    if (newCloudArticles.isNotEmpty()) {
                        // Vectorize and map new articles before inserting
                        val newDbArticles = withContext(Dispatchers.IO) {
                            newCloudArticles.map { uiArticle ->
                                val titleVector = _titleVectorizerService.getVector(uiArticle.title)
                                toDbArticle(uiArticle, titleVector)
                            }
                        }
                        _articleDao.insertAll(newDbArticles)
                        var allDbArticles = withContext(Dispatchers.IO) { _articleDao.getAll() }
                        var allUiArticles = allDbArticles.map { toUiArticle(it) }
                        val currentArticles = allUiArticles.count { searchFilter(it) } + allUiArticles.count { uiArticle ->
                            VectorUtils.cosineSimilarity(_titleVectorizerService.getVector(searchText.value), uiArticle.titleVector) > SIMILARITY_THRESHOLD }
                        while(canLoadMore(allUiArticles, currentArticles)){
                            _page.update { _page.value + 1 }
                            val newArticles = _cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE)
                            if(newArticles.isEmpty()){
                                _allArticlesLoaded = true
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
                        _allArticlesLoaded = true // No more articles from cloud
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