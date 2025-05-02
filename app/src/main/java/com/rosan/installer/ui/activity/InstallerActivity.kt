package com.rosan.installer.ui.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rosan.installer.data.installer.model.entity.ProgressEntity
import com.rosan.installer.data.installer.repo.InstallerRepo
import com.rosan.installer.ui.page.installer.InstallerPage
import com.rosan.installer.ui.theme.InstallerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf

class InstallerActivity : ComponentActivity(), KoinComponent {
    companion object {
        const val KEY_ID = "installer_id"
    }

    private var installer by mutableStateOf<InstallerRepo?>(null)
    @OptIn(KoinInternalApi::class)
    private val logger = getKoin().logger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreInstaller(savedInstanceState)
        logIntent(intent)
        showContent()
    }

    private fun logIntent(intent: Intent?) {
        if (intent == null) {
            logger.error("Intent is null")
            return
        }

        val sb = StringBuilder("\n")
        sb.appendLine("====== Intent Details ======")

        // Basic info
        sb.appendLine("Action: ${intent.action ?: "null"}")
        sb.appendLine("Data: ${intent.dataString ?: "null"}")
        sb.appendLine("Type: ${intent.type ?: "null"}")
        sb.appendLine("Package: ${intent.`package` ?: "null"}")
        sb.appendLine("Component: ${intent.component?.flattenToString() ?: "null"}")
        sb.appendLine("Flags: 0x${Integer.toHexString(intent.flags)} (${intent.flags})")

        // Categories
        if (intent.categories != null) {
            sb.appendLine("Categories:")
            for (category in intent.categories) {
                sb.appendLine("  - $category")
            }
        } else {
            sb.appendLine("Categories: null")
        }

        // Extras
        if (intent.extras != null) {
            sb.appendLine("Extras:")
            for (key in intent.extras!!.keySet()) {
                val value = intent.extras!!.get(key)
                sb.appendLine("  - $key (${value?.javaClass?.simpleName ?: "null"}): $value")
            }
        } else {
            sb.appendLine("Extras: null")
        }

        // ClipData
        if (intent.clipData != null) {
            sb.appendLine("ClipData:")
            for (i in 0 until intent.clipData!!.itemCount) {
                val item = intent.clipData!!.getItemAt(i)
                sb.appendLine("  - Item $i: ${item.text}")
                if (item.uri != null) {
                    sb.appendLine("    URI: ${item.uri}")
                }
            }
        } else {
            sb.appendLine("ClipData: null")
        }

        sb.appendLine("===========================")
        logger.info(sb.toString())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_ID, installer?.id)
        super.onSaveInstanceState(outState)
    }

/*    override fun onNewIntent(intent: Intent?) {
        this.intent = intent
        super.onNewIntent(intent!!)
        restoreInstaller()
    }*/

    private var job: Job? = null

    override fun finish() {
        super.finish()
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        super.onDestroy()
    }

    private fun restoreInstaller(savedInstanceState: Bundle? = null) {
        job?.cancel()
        val installerId = if (savedInstanceState == null) intent?.getStringExtra(KEY_ID)
        else savedInstanceState.getString(KEY_ID)
        val installer: InstallerRepo = get {
            parametersOf(installerId)
        }
        installer.background(false)
        this.installer = installer
        val scope = CoroutineScope(Dispatchers.IO)
        job = scope.launch {
            launch {
                installer.progress.collect { progress ->
                    when (progress) {
                        is ProgressEntity.Ready -> {
                            installer.resolve(this@InstallerActivity)
                        }

                        is ProgressEntity.Finish -> {
                            val activity = this@InstallerActivity
                            if (!activity.isFinishing) activity.finish()
                        }

                        else -> {}
                    }
                }
            }
            launch {
                installer.background.collect {
                    if (it) this@InstallerActivity.finish()
                }
            }
        }
    }

    private fun showContent() {
        setContent {
            val installer = installer ?: return@setContent
            val background by installer.background.collectAsState(false)
            val progress by installer.progress.collectAsState(ProgressEntity.Ready)
            if (
                background ||
                progress is ProgressEntity.Ready ||
                progress is ProgressEntity.Resolving ||
                progress is ProgressEntity.Finish
            ) return@setContent
            InstallerTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    InstallerPage(installer)
                }
            }
        }
    }
}