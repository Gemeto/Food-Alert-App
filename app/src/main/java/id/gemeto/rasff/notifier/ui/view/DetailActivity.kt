package id.gemeto.rasff.notifier.ui.view

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.gemeto.rasff.notifier.ui.theme.MyApplicationTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage

class DetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title: String? = intent.getStringExtra("title")
        val description: String? = intent.getStringExtra("description")
        val imageUrl: String? = intent.getStringExtra("imageUrl")
        val link: String? = intent.getStringExtra("link")
        setContent {
            MyApplicationTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DetailScreen(title = title, description = description, imageUrl = imageUrl, link = link)
                }
            }
        }
    }
}

@Composable
fun DetailScreen(title: String?, description: String?, imageUrl: String?, link: String?) {
    val handler = LocalUriHandler.current
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .wrapContentHeight()
                    .padding(18.dp)
                    .wrapContentSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    title ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    description ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Button(
                    content = {
                        Text("Abrir en el navegador")
                    },
                    onClick = {
                        if(!link.isNullOrEmpty()) {
                            handler.openUri(link)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                AsyncImage(
                    model = imageUrl,
                    contentDescription = imageUrl,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DetailPreview() {
    MyApplicationTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            DetailScreen(title = "title", description = "description", imageUrl = "https://i.stack.imgur.com/NTaY0.png", "")
        }
    }
}
