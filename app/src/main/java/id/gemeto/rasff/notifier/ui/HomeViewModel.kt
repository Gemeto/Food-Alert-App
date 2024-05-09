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
    private val cloudService = CloudService(ktorClient)
    private val uiMapper = HomeUiMapper()
    private val _searchText = MutableStateFlow("")
    val searchText = _searchText.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()
    private val _uiState = MutableStateFlow<UiResult<HomeUiState>>(UiResult.Loading)
    private val _page = MutableStateFlow(1)
    private val _itemsPerPage = 10
    var articlesLoading = false
    var articlesLoaded = false

    val uiState: StateFlow<UiResult<HomeUiState>> = searchText
        //.onEach { _isSearching.update { /*true*/ false } } //TO DO change to true
        .combine(_uiState) { query, state ->
            if(!_isSearching.value && state is UiResult.Success && query.isNotEmpty()) {
                /*while(state.data.articles.none{ it.title.contains(query, true) }) {
                    loadMoreArticles()
                }*/
                UiResult.Success(
                    HomeUiState(state.data.title, state.data.link, state.data.description, state.data.articles.filter { it.title.contains(query, true) })
                )
            }else{
                state
            }
        }
        //.onEach { _isSearching.update { false } }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            _uiState.value
        )

    fun onSearchTextChange(text: String) {
        _searchText.value = text
    }

    init {
        loadArticles()
    }

    private fun loadArticles() {
        viewModelScope.launch {
            try {
                val home = withContext(Dispatchers.IO) {
                    uiMapper.map(
                        cloudService.getRSSArticles(_page.value, _itemsPerPage),
                        cloudService.getHTMLArticles(_page.value, _itemsPerPage)
                    )
                }
                _uiState.update {
                    UiResult.Success(home)
                }
            } catch (err: Throwable) {
                _uiState.update { UiResult.Fail(err) }
            }
        }
    }

    fun loadMoreArticles() {
        viewModelScope.launch {
            try {
                if(!_isSearching.value) {
                    articlesLoading = true
                    _isSearching.update { true }
                    _page.value++
                    val stateData = (_uiState.value as UiResult.Success<HomeUiState>).data
                    val home = withContext(Dispatchers.IO) {
                        (stateData.articles as ArrayList).addAll(
                            cloudService.getHTMLArticles(_page.value, _itemsPerPage)
                        )
                        while(searchText.value.isNotEmpty() && stateData.articles.count { it.title.contains(searchText.value, true) } < 4){
                            _page.value++
                            val newArticles = cloudService.getHTMLArticles(_page.value, _itemsPerPage)
                            if(newArticles.isEmpty()){
                                break;
                            }
                            (stateData.articles).addAll(
                                newArticles
                            )
                        }
                        (_uiState.value as UiResult.Success<HomeUiState>).data
                    }
                    if(searchText.value.isNotEmpty()){
                        _uiState.update {
                            UiResult.Success(
                                HomeUiState(home.title, home.link, home.description, home.articles.filter { it.title.contains(searchText.value, true) })
                            )
                        }
                    } else {
                        _uiState.update { UiResult.Success(home) }
                    }
                    _isSearching.update { false }
                    articlesLoaded = true
                    articlesLoading = false
                }
            } catch (err: Throwable) {
                _uiState.update { UiResult.Fail(err) }
            }
        }
    }
}