package id.gemeto.rasff.notifier.ui.view

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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CardColors
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.datatransport.BuildConfig
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initWorkers()
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

    private fun initWorkers() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val notifierWorkRequest = PeriodicWorkRequestBuilder<NotifierWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniquePeriodicWork(
            "NotifierWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            notifierWorkRequest,
        )
    }
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val context = LocalContext.current
    val homeState = viewModel.uiState.collectAsStateWithLifecycle()
    if(Build.VERSION.SDK_INT > 32){
        RuntimePermissionsDialog(
            Manifest.permission.POST_NOTIFICATIONS,
            onPermissionDenied = {
            },
            onPermissionGranted = {
                Toast.makeText(context, "Notifications Permission Denied", Toast.LENGTH_SHORT).show()
            },
        )
    }
    when (val state = homeState.value) {
        is UiResult.Fail -> {
            Text(text = "FATAL ERROR")
        }
        is UiResult.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CustomLoadingIndicator()
            }
        }
        is UiResult.Success -> {
            val searchText by viewModel.searchText.collectAsState()
            Scaffold(
                bottomBar = {
                    SearchBar(
                        query = searchText,
                        onQueryChange = viewModel::onSearchTextChange,
                        onSearch = {
                            viewModel.onSearchTextChange(searchText)
                        },
                        active = false,
                        onActiveChange = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        placeholder = {
                            Text("Buscar...")
                        }
                    ) {}
                },
                content = { paddingValues ->
                    Articles(
                        data = state.data,
                        viewModel = viewModel,
                        onLoadMore = {
                            viewModel.loadMoreArticles()
                        },
                    )
                }
            )
        }
    }
}

@Composable
fun Articles(data: HomeUiState, viewModel: HomeViewModel, onLoadMore: () -> Unit) {
    val context = LocalContext.current
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val listState = rememberLazyListState()
    val file = context.createImageFile()
    val uri = FileProvider.getUriForFile(
        context,
        BuildConfig.APPLICATION_ID + ".provider",
        file
    )
    var capturedImageUri by remember {
        mutableStateOf<Uri>(Uri.EMPTY)
    }
    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) {
            capturedImageUri = uri
        }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (it) {
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 75.dp),
        contentPadding = PaddingValues(vertical = 30.dp, horizontal = 16.dp),
        state = listState
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = HomeViewModel.HomeViewConstants.TITLE,
                    style = MaterialTheme.typography.headlineMedium, // Un estilo más prominente
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary, // Usar color primario para destacar
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Divider(
                    modifier = Modifier
                        .fillMaxWidth(0.6f) // El divisor no ocupa todo el ancho
                        .padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                )
                Text(
                    text = HomeViewModel.HomeViewConstants.DESCRIPTION,
                    style = MaterialTheme.typography.bodyMedium, // Un poco más pequeño para contraste
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, // Un color un poco más suave
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    content = { Text("Toma una foto de tu lista de la compra!") },
                    modifier = Modifier.fillMaxHeight(),
                    onClick = {
                        val permissionCheckResult =
                            ContextCompat.checkSelfPermission(context, "android.permission.CAMERA")
                        if (permissionCheckResult == PackageManager.PERMISSION_GRANTED) {
                            cameraLauncher.launch(uri)
                        } else {
                            permissionLauncher.launch("android.permission.CAMERA")
                        }
                    }
                )
                LaunchedEffect(capturedImageUri) {
                    snapshotFlow{ capturedImageUri.toString() }
                        .distinctUntilChanged()
                        .collect {
                            if (capturedImageUri.path?.isNotEmpty() == true) {
                                val intent = Intent(context, OCRActivity::class.java)
                                intent.putExtra("title", "Gemma 3n Chat")
                                intent.putExtra("imageUri", capturedImageUri.toString())
                                //Obtenemos todos los titulos de las alertas como contexto
                                intent.putExtra("context",
                                    data.articles.joinToString("\n") { it.title })
                                context.startActivity(intent)
                                capturedImageUri = Uri.EMPTY
                            }
                        }
                }

            }
        }
        if(!isSearching) {
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
    }
    if(isLoadingMore || isSearching){
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CustomLoadingIndicator()
        }
    }
    LazyListLoaderHandler(listState = listState, buffer = 1) {
        onLoadMore()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview(showBackground = true)
@Composable
fun CustomLoadingIndicator() {
    ContainedLoadingIndicator(
        modifier = Modifier.size(200.dp),
        containerColor = Color.Black,
        indicatorColor = Color.White
    )
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
        colors = CardColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.secondary,
            disabledContentColor = MaterialTheme.colorScheme.primary
        ),
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
fun LazyListLoaderHandler(
    listState: LazyListState,
    buffer: Int = 1,
    onLoadMore: () -> Unit
) {
    val loadMore = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItemsNumber = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0)
            Log.d("CHECKING", "$lastVisibleItemIndex : $totalItemsNumber")
            lastVisibleItemIndex >= (totalItemsNumber - buffer)
        }
    }
    LaunchedEffect(loadMore) {
        snapshotFlow{ loadMore.value }
            .distinctUntilChanged()
            .collect {
                if(loadMore.value){
                    Log.d("LOAD MORE", "LOAD MORE")
                    onLoadMore()
                }
            }
    }
}

fun Context.createImageFile(): File {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date())
    val imageFileName = "JPEG_" + timeStamp + "_"
    return File.createTempFile(
        imageFileName, /* prefix */
        ".jpg", /* suffix */
        externalCacheDir /* directory */
    )
}

