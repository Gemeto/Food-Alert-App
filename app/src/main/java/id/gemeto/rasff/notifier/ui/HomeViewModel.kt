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
                    val response = cloudService.getArticles()
                    uiMapper.map(response)
                }
                _uiState.update {
                    UiResult.Success(home)
                }
            } catch (err: Throwable) {
                _uiState.update { UiResult.Fail(err) }
            }
        }
    }
}