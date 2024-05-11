package id.gemeto.rasff.notifier.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    /* todo: install dependency injection lib */
    object HomeViewConstants {
        const val TITLE = "Alertas alimentarias en España"
        const val DESCRIPTION = "Hilo de alertas alimentarias en España notificadas por la aplicación RASFF y la web de la AESAN"
        const val ITEMS_PER_PAGE = 2
    }
    private val _uiState = MutableStateFlow<UiResult<HomeUiState>>(UiResult.Loading)
    private val _cloudService = CloudService(ktorClient)
    private val _uiMapper = HomeUiMapper()
    private val _searchText = MutableStateFlow("")
    val searchText = _searchText.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()
    private val _page = MutableStateFlow(0)
    private var _allArticlesLoaded = false
    val uiState: StateFlow<UiResult<HomeUiState>> = searchText
        .onEach { _isSearching.update { true } }
        .combine(_uiState) { query, state ->
            if(state is UiResult.Success && query.isNotEmpty()) {
                val articles = (_uiState.value as UiResult.Success<HomeUiState>).data.articles.toMutableList()
                while(!_allArticlesLoaded && articles.count { it.title.contains(searchText.value, true) } < HomeViewConstants.ITEMS_PER_PAGE){
                    _page.value++
                    val newArticles = _cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE)
                    if(newArticles.isEmpty()){
                        _allArticlesLoaded = true
                        break;
                    }
                    (articles).addAll(newArticles)
                }
                val result = UiResult.Success(HomeUiState(HomeViewConstants.TITLE, state.data.link, HomeViewConstants.DESCRIPTION, articles))
                _uiState.update { result }
                UiResult.Success(
                    HomeUiState(HomeViewConstants.TITLE, state.data.link, HomeViewConstants.DESCRIPTION, articles.filter { it.title.contains(query, true) })
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

    fun onSearchTextChange(text: String) {
        _searchText.value = text
    }

    init {
        loadMoreArticles()
    }

    fun loadMoreArticles() {
        viewModelScope.launch {
            try {
                if(!_allArticlesLoaded && !_isSearching.value && !_isLoadingMore.value) {
                    _isLoadingMore.update { true }
                    _page.value++
                    val home:HomeUiState
                    if(_uiState.value !is UiResult.Success){
                        _page.value = 0
                        home = withContext(Dispatchers.IO) {
                            _uiMapper.map(
                                _cloudService.getRSSArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE),
                                _cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE)
                            )
                        }
                    }else{
                        val state = (_uiState.value as UiResult.Success<HomeUiState>).data
                        home = withContext(Dispatchers.IO) {
                            (state.articles as ArrayList).addAll(
                                _cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE)
                            )
                            val currentArticles = state.articles.count { it.title.contains(searchText.value, true) }
                            while(!_isSearching.value && state.articles.count { it.title.contains(searchText.value, true) } < (currentArticles+HomeViewConstants.ITEMS_PER_PAGE)){
                                Log.d("LOADING MORE", "${searchText.value} -> ${state.articles.count { it.title.contains(searchText.value, true) }} : ${currentArticles}")
                                _page.value++
                                val newArticles = _cloudService.getHTMLArticles(_page.value, HomeViewConstants.ITEMS_PER_PAGE)
                                if(newArticles.isEmpty()){
                                    _allArticlesLoaded = true
                                    break;
                                }
                                (state.articles).addAll(newArticles)
                            }
                            state
                        }
                    }
                    if(searchText.value.isNotEmpty()){
                        _uiState.update {
                            UiResult.Success(
                                HomeUiState(HomeViewConstants.TITLE, home.link, HomeViewConstants.DESCRIPTION, home.articles.filter { it.title.contains(searchText.value, true) })
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
}