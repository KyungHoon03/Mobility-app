package app.shimpyo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shimpyo.ui.ShimpyoScreen
import app.shimpyo.ui.ShimpyoTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShimpyoTheme {
                val state by vm.ui.collectAsStateWithLifecycle()
                ShimpyoScreen(state = state, vm = vm)
            }
        }
    }
}
