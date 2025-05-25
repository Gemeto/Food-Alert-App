package id.gemeto.rasff.notifier.ui.view

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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

@OptIn(ExperimentalMaterial3Api::class) // Necesario para Card y otros componentes de M3
@Composable
fun DetailScreen(title: String?, description: String?, imageUrl: String?, link: String?) {
    val handler = LocalUriHandler.current

    // Estos colores se tomarían de tu MaterialTheme, configurado para blanco y negro.
    // Ejemplo:
    // val backgroundColor = MaterialTheme.colorScheme.background // Podría ser Color.White o Color.Black
    // val onBackgroundColor = MaterialTheme.colorScheme.onBackground // El opuesto para contraste
    // val surfaceContainerLowestColor = MaterialTheme.colorScheme.surfaceContainerLowest // Un gris muy sutil
    // val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant // Un color de texto más apagado
    // val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant // Para divisores
    // val primaryColor = MaterialTheme.colorScheme.primary // Para el botón (negro sobre blanco o viceversa)
    // val onPrimaryColor = MaterialTheme.colorScheme.onPrimary // Para el texto del botón

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background), // Fondo principal
        contentPadding = PaddingValues(vertical = 100.dp) // El padding se gestionará internamente
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp), // Padding vertical para todo el bloque de contenido
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp) // Espaciado uniforme entre elementos
            ) {
                // Sección de la Imagen
                if (!imageUrl.isNullOrEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp) // Padding horizontal para la tarjeta
                            .aspectRatio(16f / 9f),
                        shape = MaterialTheme.shapes.large, // Forma expresiva con esquinas redondeadas
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp), // Elevación sutil
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest // Fondo sutil para la tarjeta
                        )
                    ) {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = title ?: "Imagen de la alerta",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize() // La imagen llena la tarjeta
                        )
                    }
                }

                // Sección del Título
                Text(
                    text = title ?: "Detalle de la Alerta",
                    style = MaterialTheme.typography.headlineSmall, // Tipografía M3 para títulos
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground, // Color de alto contraste
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp) // Padding horizontal
                )

                // Divisor
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth(0.6f) // Ancho del 60%, centrado por el Column
                        .padding(vertical = 8.dp), // Espacio vertical alrededor del divisor
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant // Color sutil para el divisor
                )

                // Sección de la Descripción
                if (!description.isNullOrEmpty()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center, // Alineación original, considerar TextAlign.Start para textos largos
                        color = MaterialTheme.colorScheme.onSurfaceVariant, // Color de texto secundario
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp) // Mayor padding para bloques de texto
                    )
                } else {
                    // Espaciador si no hay descripción, para mantener el espacio antes del botón
                    Spacer(modifier = Modifier.height(0.dp)) // Arrangement.spacedBy ya maneja esto, pero podría ser explícito si es necesario
                }

                // Sección del Botón
                if (!link.isNullOrEmpty()) {
                    Button(
                        onClick = { handler.openUri(link) },
                        modifier = Modifier
                            .fillMaxWidth(0.8f) // Botón más ancho, centrado por el Column
                            .height(48.dp), // Altura estándar para botones
                        shape = MaterialTheme.shapes.medium, // Forma estándar para botones M3
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null, // Ícono decorativo
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing)) // Espacio entre ícono y texto
                        Text(
                            "Abrir en el navegador",
                            style = MaterialTheme.typography.labelLarge // Estilo M3 para texto de botón
                        )
                    }
                }
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
