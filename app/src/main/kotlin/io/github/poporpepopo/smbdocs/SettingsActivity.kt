package io.github.poporpepopo.smbdocs

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/**
 * The only screen this app has. Browsing happens in the system Files app; all this does is
 * collect credentials and prove they work before the provider starts advertising a root.
 */
class SettingsActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var host: EditText
    private lateinit var share: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var status: TextView
    private lateinit var openShare: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applyInsets(findViewById(R.id.content))

        host = findViewById(R.id.host)
        share = findViewById(R.id.share)
        user = findViewById(R.id.user)
        pass = findViewById(R.id.pass)
        status = findViewById(R.id.status)
        openShare = findViewById(R.id.open_share)

        val cfg = SmbSettings.load(this)
        host.setText(cfg.host)
        share.setText(cfg.share)
        user.setText(cfg.user)
        pass.setText(cfg.password)

        findViewById<MaterialButton>(R.id.save).setOnClickListener { saveAndTest() }
        openShare.setOnClickListener {
            startActivity(Intent(this, OpenShareActivity::class.java))
        }
        openShare.visibility = if (cfg.isComplete) View.VISIBLE else View.GONE
    }

    /**
     * From Android 15 the window is always edge-to-edge, so the content would otherwise sit
     * under the status and navigation bars. Pad by whichever is larger, the system bars or
     * the keyboard, so the password field stays visible while typing.
     */
    private fun applyInsets(content: View) {
        val basePaddingTop = content.paddingTop
        val basePaddingBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                top = basePaddingTop + bars.top,
                bottom = basePaddingBottom + maxOf(bars.bottom, ime.bottom),
            )
            insets
        }
    }

    private fun saveAndTest() {
        val cfg = SmbConfigData(
            host = host.text.toString().trim(),
            share = share.text.toString().trim(),
            user = user.text.toString().trim(),
            password = pass.text.toString(),
        )
        if (!cfg.isComplete) {
            status.text = "ホストと共有名は必須です"
            return
        }

        status.text = "接続中…"
        io.execute {
            val result = runCatching {
                SmbConnection.invalidate()
                SmbConnection.withShare(cfg) { disk -> disk.list("").size }
            }
            runOnUiThread {
                result.onSuccess { count ->
                    SmbSettings.save(this, cfg)
                    SmbConnection.invalidate()
                    notifyRootsChanged()
                    openShare.visibility = View.VISIBLE
                    status.text = "接続成功 — ルートに $count 件。\n" +
                        "「ファイル」アプリの左メニューに表示されます。"
                    Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    status.text = "接続失敗: ${e.message}"
                }
            }
        }
    }

    /** Makes the system Files app pick up the root without a reboot. */
    private fun notifyRootsChanged() {
        val uri = DocumentsContract.buildRootsUri(SmbDocumentsProvider.AUTHORITY)
        contentResolver.notifyChange(uri, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}
