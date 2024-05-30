package id.gemeto.rasff.notifier.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import id.gemeto.rasff.notifier.data.CloudService
import id.gemeto.rasff.notifier.data.ktorClient
import id.gemeto.rasff.notifier.ui.util.UiResult
import kotlinx.coroutines.Dispatchers
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

class HomeViewModel : ViewModel() {

    //Dependecies
    private val _cloudService = CloudService(ktorClient)
    private val _uiMapper = HomeUiMapper()
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    //Variables
    private val _uiState = MutableStateFlow<UiResult<HomeUiState>>(UiResult.Loading)
    private val _uiStateUnfiltered = MutableStateFlow<UiResult<HomeUiState>>(UiResult.Loading)
    private val _searchText = MutableStateFlow("")
    val searchText = _searchText.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()
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
    private fun canLoad(): Boolean = !_allArticlesLoaded && !_isSearching.value
            && !_isLoadingMore.value && _uiStateUnfiltered.value is UiResult.Success
    private fun canLoadMore(articles: List<Article>, currentItems: Int): Boolean  = !_isSearching.value
            && (articles.count { it.title.contains(searchText.value, true) } < (currentItems + HomeViewConstants.ITEMS_PER_PAGE)
            || _cloudService.lastRSSArticleDate < articles.last().unixTime)
    private fun canLoadMoreSearching(articles: List<Article>): Boolean  = !_allArticlesLoaded
            && totalSearchedArticles(articles) < HomeViewConstants.ITEMS_PER_PAGE //change articles count
    private fun searchQuerys(): List<String> = searchText.value.trim().split(" ", "\n").map { it.lowercase().removeSuffix("s") }
    private fun totalSearchedArticles(articles: List<Article>): Int = articles.count { searchQuerys().any{ query ->
        it.title.lowercase().contains(query)}
    }
    private fun searchFilter(article: Article): Boolean = searchQuerys().any{ query -> article.title.lowercase().contains(query)}

    val uiState: StateFlow<UiResult<HomeUiState>> = searchText
        .onEach { _isSearching.update { true } }
        .combine(_uiState) { query, state ->
            if(state is UiResult.Success && query.isNotEmpty()) {
                val articles = (_uiStateUnfiltered.value as UiResult.Success<HomeUiState>).data.articles.toMutableList()
                while(canLoadMoreSearching(articles)){
                    _page.value++
                    val newArticles = _cloudService.getHTMLArticles(
                        _page.value,
                        HomeViewConstants.ITEMS_PER_PAGE
                    )
                    if(newArticles.isEmpty()){
                        _allArticlesLoaded = true
                        break
                    }
                    (articles).addAll(newArticles)
                }
                val result = UiResult.Success(HomeUiState(articles))
                _uiStateUnfiltered.value = result
                _uiState.update { result }
                UiResult.Success(
                    HomeUiState(articles.filter {article -> searchFilter(article) })
                )
            }else{
                state
            }
        }
        .onEach { _isSearching.update { false } }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            _uiState.value
        )
    init {
        viewModelScope.launch {
            try {
                val home = withContext(Dispatchers.IO) {
                    _uiMapper.map(
                        _cloudService.getRSSArticles()
                        .plus(_cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE))
                    )
                }
                _uiStateUnfiltered.value = UiResult.Success(home)
                _uiState.update { UiResult.Success(home) }
            }catch (err: Throwable) {
                _uiState.update { UiResult.Fail(err) }
            }
        }
    }

    fun loadMoreArticles() {
        viewModelScope.launch {
            try {
                if(canLoad()) {
                    _isLoadingMore.update { true }
                    _page.value++
                    val state = (_uiStateUnfiltered.value as UiResult.Success).data
                    val home = withContext(Dispatchers.IO) {
                        (state.articles as ArrayList).addAll(_cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE))
                        val currentArticles = state.articles.count { searchFilter(it) }
                        while(canLoadMore(state.articles, currentArticles)){
                            _page.update { _page.value + 1 }
                            val newArticles = _cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE)
                            if(newArticles.isEmpty()){
                                _allArticlesLoaded = true
                                break
                            }
                            (state.articles).addAll(newArticles)
                        }
                        state
                    }
                    _uiStateUnfiltered.value = UiResult.Success(home)
                    if(searchText.value.isNotEmpty()){
                        _uiState.update {
                            UiResult.Success(
                                HomeUiState(home.articles.filter { searchFilter(it) })
                            )
                        }
                    } else {
                        _uiState.update { UiResult.Success(home) }
                    }
                    _isLoadingMore.update { false }
                }
            } catch (err: Throwable) {
                _uiState.update { UiResult.Fail(err) }
            }
        }
    }

    fun onSearchTextChange(text: String) {
        _searchText.value = text
    }

    suspend fun imageOCR(image: InputImage) {
        withContext(Dispatchers.IO) {
            val result = Tasks.await(
                recognizer.process(image)
                    .addOnSuccessListener {}
                    .addOnFailureListener {}
            )
            _searchText.update { result.text.replace("[0-9]".toRegex(), "") }
        }
    }
}