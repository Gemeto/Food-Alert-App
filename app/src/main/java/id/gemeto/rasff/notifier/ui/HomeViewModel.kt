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
import kotlinx.coroutines.flow.onEach
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

    val uiState: StateFlow<UiResult<HomeUiState>> = searchText
        .onEach { _isSearching.update { true } }
        .combine(_uiState) { query, state ->
            if(state is UiResult.Success && query.isNotEmpty()) {
                UiResult.Success(
                    HomeUiState(state.data.title, state.data.link, state.data.description, state.data.articles.filter { it.title.contains(query, true) })
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
                    _isSearching.update { true }
                    _page.value++
                    val home = withContext(Dispatchers.IO) {
                        ((_uiState.value as UiResult.Success<HomeUiState>).data.articles as ArrayList).addAll(
                            cloudService.getHTMLArticles(_page.value, _itemsPerPage)
                        )
                        (_uiState.value as UiResult.Success<HomeUiState>).data
                    }
                    _uiState.update { UiResult.Success(home) }
                    _isSearching.update { false }
                }
            } catch (err: Throwable) {
                _uiState.update { UiResult.Fail(err) }
            }
        }
    }
}