package id.gemeto.rasff.notifier.ui

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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel : ViewModel() {

    /* todo: install dependency injection lib */
    private val _uiState = MutableStateFlow<UiResult<HomeUiState>>(UiResult.Loading)
    private val stateCheckpoint = MutableStateFlow<UiResult<HomeUiState>>(UiResult.Loading)
    private val _cloudService = CloudService(ktorClient)
    private val _uiMapper = HomeUiMapper()
    private val _searchText = MutableStateFlow("")
    val searchText = _searchText.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()
    private val _page = MutableStateFlow(0)
    private val _itemsPerPage = 4
    private var _allArticlesLoaded = false

    object ViewConstants {
        const val TITLE = "Alertas alimentarias en España"
        const val DESCRIPTION = "Hilo de alertas alimentarias en España notificadas por la aplicación RASFF y la web de la AESAN"
    }

    val uiState: StateFlow<UiResult<HomeUiState>> = searchText
        .combine(_uiState) { query, state ->
            if(!_isSearching.value && state is UiResult.Success && query.isNotEmpty()) {
                _isSearching.update { true }
                val articles = (stateCheckpoint.value as UiResult.Success<HomeUiState>).data.articles.toMutableList()
                while(!_allArticlesLoaded && articles.count { it.title.contains(searchText.value, true) } < _itemsPerPage){
                    _page.value++
                    val newArticles = _cloudService.getHTMLArticles(_page.value, _itemsPerPage)
                    if(newArticles.isEmpty()){
                        _allArticlesLoaded = true
                        break;
                    }
                    (articles).addAll(newArticles)
                }
                val result = UiResult.Success(HomeUiState(ViewConstants.TITLE, state.data.link, ViewConstants.DESCRIPTION, articles))
                stateCheckpoint.value = result
                _uiState.update { result }
                _isSearching.update { false }
                UiResult.Success(
                    HomeUiState(ViewConstants.TITLE, state.data.link, ViewConstants.DESCRIPTION, articles.filter { it.title.contains(query, true) })
                )
            }else{
                state
            }
        }
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
                if(!_allArticlesLoaded && !_isSearching.value) {
                    _isSearching.update { true }
                    _page.value++
                    val home:HomeUiState
                    if(stateCheckpoint.value !is UiResult.Success){
                        home = withContext(Dispatchers.IO) {
                            _uiMapper.map(
                                _cloudService.getRSSArticles(_page.value, _itemsPerPage),
                                _cloudService.getHTMLArticles(_page.value, _itemsPerPage)
                            )
                        }
                        stateCheckpoint.value = UiResult.Success(home)
                    }else{
                        val state = (stateCheckpoint.value as UiResult.Success<HomeUiState>).data
                        val currentArticles = state.articles.count { it.title.contains(searchText.value, true) }
                        home = withContext(Dispatchers.IO) {
                            (state.articles as ArrayList).addAll(
                                _cloudService.getHTMLArticles(_page.value, _itemsPerPage)
                            )
                            while(state.articles.count { it.title.contains(searchText.value, true) } <= currentArticles){
                                _page.value++
                                val newArticles = _cloudService.getHTMLArticles(_page.value, _itemsPerPage)
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
                                HomeUiState(ViewConstants.TITLE, home.link, ViewConstants.DESCRIPTION, home.articles.filter { it.title.contains(searchText.value, true) })
                            )
                        }
                    } else {
                        _uiState.update { UiResult.Success(home) }
                    }
                    _isSearching.update { false }
                }
            } catch (err: Throwable) {
                _uiState.update { UiResult.Fail(err) }
            }
        }
    }
}