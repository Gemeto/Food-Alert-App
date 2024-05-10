package id.gemeto.rasff.notifier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.compose.AsyncImage
import id.gemeto.rasff.notifier.ui.Article
import id.gemeto.rasff.notifier.ui.HomeUiState
import id.gemeto.rasff.notifier.ui.HomeViewModel
import id.gemeto.rasff.notifier.ui.RuntimePermissionsDialog
import id.gemeto.rasff.notifier.ui.theme.MyApplicationTheme
import id.gemeto.rasff.notifier.ui.theme.Typography
import id.gemeto.rasff.notifier.ui.util.UiResult
import id.gemeto.rasff.notifier.workers.NotifierWorker
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        val notifierWorkRequest =
            PeriodicWorkRequestBuilder<NotifierWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(24, TimeUnit.SECONDS)
                .setConstraints(constraints)
                .build()
        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniquePeriodicWork(
            "NotifierWorker",
            ExistingPeriodicWorkPolicy.UPDATE,
            notifierWorkRequest,
        )
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(viewModel = homeViewModel)
                }
            }
        }
    }
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    RuntimePermissionsDialog(
        Manifest.permission.POST_NOTIFICATIONS,
        onPermissionDenied = {},
        onPermissionGranted = {},
    )

    val homeState = viewModel.uiState.collectAsStateWithLifecycle()
    when (val state = homeState.value) {
        is UiResult.Fail -> {
            Text(text = "Fatal error ocurred...")
        }

        UiResult.Loading -> {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        is UiResult.Success -> {
            val searchText by viewModel.searchText.collectAsState()
            Scaffold(
                bottomBar = {
                    BottomAppBar(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextField(
                            value = searchText,
                            onValueChange = viewModel::onSearchTextChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(text = "Buscar...") }
                        )
                    }
                },
                content = {
                    Articles(data = state.data, viewModel = viewModel, onLoadMore = {
                        viewModel.loadMoreArticles()
                    })
                }
            )
        }
    }
}

@Composable
fun Articles(data: HomeUiState, viewModel: HomeViewModel, onLoadMore: () -> Unit) {
    val context = LocalContext.current
    val isSearching by viewModel.isSearching.collectAsState()
    val listState = rememberLazyListState()

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(bottom = 75.dp),
        contentPadding = PaddingValues(16.dp),
        state = listState
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(18.dp)
            ) {
                Text(
                    HomeViewModel.HomeViewConstants.TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(18.dp, 18.dp)
                )
                Text(
                    HomeViewModel.HomeViewConstants.DESCRIPTION,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        items(data.articles) { item ->
            ArticleItem(item) { article ->
                val intent = Intent(context, DetailActivity::class.java)
                intent.putExtra("title", article.title)
                intent.putExtra("description", article.description)
                intent.putExtra("imageUrl", article.imageUrl)
                intent.putExtra("link", article.link)
                context.startActivity(intent)
            }
        }
    }
    if(isSearching){
        Box(modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
    AtricleLazyLoaderHandler(listState = listState, buffer = 1) {
        onLoadMore()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleItem(item: Article, onItemClicked: (Article) -> Unit) {
    Card(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth()
            .wrapContentHeight(),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        ),
        onClick = {
            onItemClicked.invoke(item)
        }
    ) {
        Column {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Text(text = item.title, style = Typography.titleMedium, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp))
            Text(text = item.description, style = Typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
fun AtricleLazyLoaderHandler(
    listState: LazyListState,
    buffer: Int = 1,
    onLoadMore: () -> Unit
) {
    val loadMore = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItemsNumber = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0)
            lastVisibleItemIndex >= (totalItemsNumber - buffer)
        }
    }

    LaunchedEffect(loadMore) {
        snapshotFlow{ loadMore.value }
            .distinctUntilChanged()
            .collect {
                if(loadMore.value){
                    onLoadMore()
                }
            }
    }
}

@Preview(showBackground = true)
@Composable
fun HomePreview() {
    MyApplicationTheme {
        Articles(
            viewModel = HomeViewModel(),
            data = HomeUiState(
                title = "Dave Leeds on Kotlin - typealias.com",
                link = "https://typealias.com/",
                description = "Recent content about Kotlin programming on typealias.com",
                articles = List(5) {
                    Article(
                        "Data Classes and Destructuring",
                        "At the end of the last chapter, we saw how all objects in Kotlin inherit three functions from an open class called Any. Those functions are equals(), hashCode(), and toString(). In this chapter, we're going to learn about data classes ...",
                        "https://typealias.com/start/kotlin-data-classes-and-destructuring/",
                        "https://typealias.com/img/social/social-data-classes.png",
                    )
                }
            ),
            onLoadMore = {}
        )
    }
}