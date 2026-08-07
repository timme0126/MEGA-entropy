package org.mega.entropy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.mega.entropy.ui.theme.MegaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MegaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            contentAlignment = Alignment.Center,
                        ) {
                            MegaPlaceholder()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MegaPlaceholder() {
    // Replaced by the full welcome/navigation flow (see
    // ui/navigation/MegaNavGraph.kt) once the screen set lands.
    Text(text = "MEGA")
}
