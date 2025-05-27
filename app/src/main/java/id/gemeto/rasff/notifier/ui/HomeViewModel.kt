package id.gemeto.rasff.notifier.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import id.gemeto.rasff.notifier.data.AppDatabase
import id.gemeto.rasff.notifier.data.ArticleDAO
import id.gemeto.rasff.notifier.data.CloudService
import id.gemeto.rasff.notifier.data.LastNotified
import id.gemeto.rasff.notifier.data.TitleVectorizerService
import id.gemeto.rasff.notifier.data.ktorClient
import id.gemeto.rasff.notifier.ui.util.UiResult
import id.gemeto.rasff.notifier.utils.VectorUtils
import kotlinx.coroutines.Dispatchers
// Alias for clarity
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
    private val _titleVectorizerService = TitleVectorizerService(context = application) // Instantiate the service
    private val _uiMapper = HomeUiMapper() // This might need adjustment

    //Mappers
    private fun toDbArticle(uiArticle: UiArticle, titleVector: List<Float>? = null): DbArticle {
        return DbArticle(
            id = uiArticle.link, // Assuming link is a unique identifier
            title = uiArticle.title,
            content = uiArticle.description,
            titleVector = titleVector ?: emptyList()
        )
    }

    private fun toUiArticle(dbArticle: DbArticle): UiArticle {
        return UiArticle(
            title = dbArticle.title,
            description = dbArticle.content,
            link = dbArticle.id,
            imageUrl = "", // Placeholder
            unixTime = 0L // Placeholder
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

    private suspend fun canLoadMoreSearching(articlesInUi: List<UiArticle>): Boolean  = !_allArticlesLoaded &&
            totalSearchedArticles(articlesInUi) < HomeViewConstants.ITEMS_PER_PAGE

    private fun searchQuerys(): List<String> = searchText.value.trim().split(" ", "\n").map { it.lowercase().removeSuffix("s") }

    //This function now also have in account the similarity findings
    private suspend fun totalSearchedArticles(articles: List<UiArticle>): Int = articles.count { uiArticle ->
        searchQuerys().any{ query -> uiArticle.title.lowercase().contains(query) }
    } + articles.count { uiArticle ->
        VectorUtils.cosineSimilarity(_titleVectorizerService.getVector(searchText.value), _titleVectorizerService.getVector(uiArticle.title)) > SIMILARITY_THRESHOLD
    }
    private suspend fun searchFilter(article: UiArticle): Boolean =
        searchQuerys().any{ query -> article.title.lowercase().contains(query) }

    val uiState: StateFlow<UiResult<HomeUiState>> = searchText
        .onEach { _isSearching.update { true } }
        .combine(_uiStateUnfiltered) { query, unfilteredState ->
            if (query.isNotEmpty()) {
                _isSearching.update { true }
                val queryVector = _titleVectorizerService.getVector(query) // Suspend call
                val allDbArticles = _articleDao.getAll() // Suspend call

                val articlesWithSimilarity = allDbArticles.map { dbArticle ->
                    val similarity = VectorUtils.cosineSimilarity(queryVector, dbArticle.titleVector)
                    ArticleWithSimilarity(dbArticle, similarity)
                }

                // Primary filter: similarity search
                var filteredArticles = articlesWithSimilarity
                    .filter { it.similarity > SIMILARITY_THRESHOLD && it.similarity.isFinite() }
                    .sortedByDescending { it.similarity }
                    .map { it.dbArticle }

                var usedSimilaritySearch = true
                if (filteredArticles.isEmpty()) {
                    // Fallback: keyword search on all articles from _uiStateUnfiltered
                    // (which should be up-to-date thanks to other parts of the ViewModel)
                    usedSimilaritySearch = false
                    if (unfilteredState is UiResult.Success) {
                        // Perform keyword search on the UI articles held in unfilteredState
                        // This re-uses the existing searchFilter logic
                        val keywordFilteredUiArticles = unfilteredState.data.articles.filter { uiArt -> searchFilter(uiArt) }
                        // We need to map these back to DbArticle if the rest of the flow expects DbArticles,
                        // or adjust the mapping. For simplicity, let's assume we need their IDs to fetch full DbArticles.
                        // However, searchFilter operates on UiArticle.title.
                        // The original fallback logic was: UiResult.Success(HomeUiState(currentArticles.filter { article -> searchFilter(article) }))
                        // where currentArticles were UiArticle.
                        // Let's adapt to return UiArticles directly from keyword search.
                        _isSearching.update { false } // search is complete
                        // If fallback is used, we return UiArticles directly
                        return@combine UiResult.Success(HomeUiState(keywordFilteredUiArticles))
                    } else {
                        // If unfilteredState is not Success, pass it through
                        _isSearching.update { false }
                        return@combine unfilteredState
                    }
                }
                
                // If primary filter (similarity search) yielded results, map DbArticles to UiArticles
                val finalUiArticles = filteredArticles.map { toUiArticle(it) }

                // Logic for fetching more from cloud if search results are too few (canLoadMoreSearching)
                // This part needs careful integration if `finalUiArticles` is used as the base.
                // The original `canLoadMoreSearching` worked with `UiArticle` list.
                // For now, we'll keep the cloud fetching logic as it was, operating on `unfilteredState.data.articles`
                // and then the entire search process will re-filter.
                // This might lead to fetching more than strictly necessary if the search query is very specific
                // and the initial DB results are sparse.

                if (unfilteredState is UiResult.Success) {
                    val currentUiArticlesForCloudCheck = unfilteredState.data.articles.toMutableList()
                    var newArticlesFetchedWhileSearching = false
                     // Pass `finalUiArticles` to canLoadMoreSearching if it's adapted,
                     // or use a version of searchFilter for dbArticles if needed.
                     // For now, let's assume canLoadMoreSearching still uses the general list.
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
                        newArticlesFetchedWhileSearching = true
                        // After fetching, the entire search process will run again in the next emission
                        // due to _uiStateUnfiltered potentially changing or if we force re-evaluation.
                        // For simplicity, we'll let the next emission of _uiStateUnfiltered (if it changes) handle re-searching.
                        // Or, we can re-run the search logic here.
                        // Given the current structure, if new articles are added, _uiStateUnfiltered should be updated elsewhere,
                        // triggering this combine block again.
                        // Let's assume for now that if new articles are fetched, we should update unfilteredState and let it re-trigger.
                        // This part is tricky because we are already inside the combine.
                        // A simpler approach is to update _uiStateUnfiltered from where new articles are added (loadMore/init)
                        // and let this combine block purely react to existing data.
                        // The original logic of modifying `currentArticles` (which were UI articles) and then re-filtering is complex here.

                        // To ensure new articles are searchable, we should re-fetch all and re-calculate similarity
                        // This is inefficient but ensures correctness with the current structure.
                        // A better way would be to update _uiStateUnfiltered and let the flow re-trigger.
                        // For this iteration, if new articles are fetched, we will update _uiStateUnfiltered
                        // which will then cause this whole combine block to run again.
                        if (newArticlesFetchedWhileSearching) {
                             val allLatestDbArticles = _articleDao.getAll()
                             val allLatestUiArticles = allLatestDbArticles.map { toUiArticle(it) }
                             val articles = withContext(Dispatchers.Default) { // Or Dispatchers.IO if it involves I/O
                                 _uiMapper.map(allLatestUiArticles)
                            }
                            _uiStateUnfiltered.value = UiResult.Success(articles)
                            // The current processing will be superseded by the new emission.
                            // We can return current results and let it refresh.
                        }
                        break // Exit while loop for now, let re-emission handle full re-search
                    }
                }


                _isSearching.update { false }
                UiResult.Success(HomeUiState(finalUiArticles))

            } else { // query is empty
                _isSearching.update { false }
                unfilteredState
            }
        }
        .onEach { _isSearching.update { false } } // Ensure isSearching is reset
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

                if (uiArticles.isEmpty()) {
                    // Fetch from cloud, save to DB, then update UI
                    val rssArticles = withContext(Dispatchers.IO) { _cloudService.getRSSArticles() }
                    val htmlArticles = withContext(Dispatchers.IO) { _cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE) }
                    val cloudArticles = rssArticles.plus(htmlArticles)

                    // Vectorize and map articles before inserting
                    val dbArticlesToInsert = withContext(Dispatchers.IO) { // Ensure IO context for vectorizer
                        cloudArticles.map { uiArticle ->
                            val titleVector = _titleVectorizerService.getVector(uiArticle.title)
                            toDbArticle(uiArticle, titleVector)
                        }
                    }
                    _articleDao.insertAll(dbArticlesToInsert) // No need for extra withContext if DAO is suspending

                    // Re-fetch from DB to ensure consistency and get all data
                    articlesFromDb = withContext(Dispatchers.IO) { _articleDao.getAll() }
                    uiArticles = articlesFromDb.map { toUiArticle(it) }
                }
                val homeUiState = withContext(Dispatchers.Default) { // Or Dispatchers.IO if it involves I/O
                    _uiMapper.map(uiArticles)
                }
                _uiStateUnfiltered.value = UiResult.Success(homeUiState)
                // _uiState will be updated by the combine flow based on _uiStateUnfiltered and searchText
            } catch (err: Throwable) {
                val errorResult = UiResult.Fail(err)
                _uiStateUnfiltered.value = errorResult
                // _uiState will also be updated by combine
            }
        }
    }

    fun loadMoreArticles() {
        viewModelScope.launch {
            try {
                if (canLoad()) {
                    _isLoadingMore.update { true }
                    // val currentUnfilteredState = _uiStateUnfiltered.value // Not used directly like this anymore
                    
                    _page.value++ // Increment page for fetching next set from cloud
                    val newCloudArticles = withContext(Dispatchers.IO) {
                        _cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE)
                    }

                    if (newCloudArticles.isNotEmpty()) {
                        // Vectorize and map new articles before inserting
                        val newDbArticles = withContext(Dispatchers.IO) { // Ensure IO context for vectorizer
                            newCloudArticles.map { uiArticle ->
                                val titleVector = _titleVectorizerService.getVector(uiArticle.title)
                                toDbArticle(uiArticle, titleVector)
                            }
                        }
                        _articleDao.insertAll(newDbArticles) // No need for extra withContext if DAO is suspending

                        // After inserting, get all articles from DB to update the unfiltered state
                        val allDbArticles = withContext(Dispatchers.IO) { _articleDao.getAll() }
                        val allUiArticles = allDbArticles.map { toUiArticle(it) }
                        val homeUiState = _uiMapper.map(allUiArticles)
                        _uiStateUnfiltered.value = UiResult.Success(homeUiState)
                        // _uiState will be updated by the combine flow
                    } else {
                        _allArticlesLoaded = true // No more articles from cloud
                    }
                    _isLoadingMore.update { false }
                }
            } catch (err: Throwable) {
                _uiState.update { UiResult.Fail(err) } // Keep this for loadMore specific errors
                _isLoadingMore.update { false }
            }
        }
    }

    fun onSearchTextChange(text: String) {
        _searchText.value = text
    }

    // imageOCR function remains unchanged for now
    suspend fun imageOCR() {
        withContext(Dispatchers.IO) {
            val result = null // Placeholder for actual OCR result
            // _searchText.update { result.text.replace("[0-9]".toRegex(), "") }
        }
    }
}