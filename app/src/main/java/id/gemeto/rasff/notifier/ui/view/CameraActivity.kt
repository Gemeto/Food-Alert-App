package id.gemeto.rasff.notifier.ui.view

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.rememberAsyncImagePainter
import com.google.android.datatransport.BuildConfig
import id.gemeto.rasff.notifier.ui.theme.MyApplicationTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppContent()
                }
            }
        }
    }

    @Composable
    fun AppContent() {
        val context = LocalContext.current
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
                Toast.makeText(context, "Permission Granted", Toast.LENGTH_SHORT).show()
                cameraLauncher.launch(uri)
            } else {
                Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
            }
        }
        Column(Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(onClick = {
                val permissionCheckResult =
                    ContextCompat.checkSelfPermission(context, "android.permission.CAMERA")
                if (permissionCheckResult == PackageManager.PERMISSION_GRANTED) {
                    cameraLauncher.launch(uri)
                } else {
                    permissionLauncher.launch("android.permission.CAMERA")
                }
            }) {
                Text(text = "Tomar la foto")
            }
        }
        if (capturedImageUri.path?.isNotEmpty() == true) {
            Image(
                modifier = Modifier
                    .padding(16.dp, 8.dp),
                painter = rememberAsyncImagePainter(capturedImageUri),
                contentDescription = null
            )
        }
    }

    fun Context.createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date())
        val imageFileName = "JPEG_" + timeStamp + "_"
        return File.createTempFile(
            imageFileName, /* prefix */
            ".jpg", /* suffix */
            externalCacheDir      /* directory */
        ) }

    @Preview(showBackground = true)
    @Composable
    fun CameraPreview() {
        MyApplicationTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                AppContent()
            }
        }
    }
}
