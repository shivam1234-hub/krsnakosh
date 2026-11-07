package app.tagledger.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    /** True when Google Pay (or another app) opened us through Share; we return there after saving. */
    private var openedFromShare = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            val dark = isDark(vm.themeMode)
            // Status and navigation bar icons follow the app's theme, not just the phone's.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            TagLedgerTheme(dark) {
                AppRoot(
                    vm = vm,
                    onShareSaved = {
                        if (openedFromShare) {
                            Toast.makeText(this, "Saved to KrsnaKosh", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    },
                    onShareCancelled = { if (openedFromShare) finish() },
                    shareFile = ::shareFile,
                    open = ::open,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        val text = i.getStringExtra(Intent.EXTRA_TEXT)
        if (uri == null && text.isNullOrBlank()) return
        openedFromShare = true
        vm.onShared(uri, text)
    }

    private fun shareFile(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Export to"))
    }

    private fun open(target: String, mime: String?) {
        val i = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (mime != null) i.setDataAndType(Uri.parse(target), mime) else i.data = Uri.parse(target)
        try { startActivity(i) } catch (_: ActivityNotFoundException) { Toast.makeText(this, "No app can open this", Toast.LENGTH_SHORT).show() }
    }
}
