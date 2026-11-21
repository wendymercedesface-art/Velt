package com.veltrax.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.veltrax.music.ui.VeltraXScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val link = androidx.compose.runtime.mutableStateOf("")

    private fun accept(i: android.content.Intent?) {
        if (i?.action == android.content.Intent.ACTION_SEND)
            link.value =
                i.getStringExtra(android.content.Intent.EXTRA_TEXT)
                    .orEmpty()
                    .split(Regex("\\s+"))
                    .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
                    .orEmpty()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        accept(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) accept(intent)
        setContent {
            val model: MainViewModel = viewModel()
            var permission by remember { mutableStateOf(hasAudioPermission()) }
            val launcher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    permission = it
                }
            val notifications =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            val owner = LocalLifecycleOwner.current
            val scope = rememberCoroutineScope()
            DisposableEffect(owner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        permission = hasAudioPermission()
                        scope.launch { model.refreshSongs() }
                    }
                }
                owner.lifecycle.addObserver(observer)
                onDispose { owner.lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(Unit) {
                if (
                    Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            LaunchedEffect(permission) { model.refreshSongs() }
            VeltraXScreen(
                model,
                permission,
                shared = link.value,
                consumed = { link.value = "" },
                onPermission = {
                    launcher.launch(
                        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                        else Manifest.permission.READ_EXTERNAL_STORAGE
                    )
                },
            )
        }
    }

    private fun hasAudioPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
