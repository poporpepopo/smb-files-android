package io.github.poporpepopo.smbdocs

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * A doorway with no UI of its own: it hands the share straight to the system document
 * browser and gets out of the way.
 *
 * This exists because there is otherwise no way in. DocumentsUI — the thing that actually
 * renders SAF roots — ships without a launcher icon on Pixel, and Files by Google only
 * offers to launch this app rather than browsing the root inline. Without this activity the
 * share is reachable only from another app's file picker.
 */
class OpenShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfg = SmbSettings.load(this)
        if (!cfg.isComplete) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        val rootUri = Uri.parse(
            "content://${SmbDocumentsProvider.AUTHORITY}/root/${SmbDocumentsProvider.ROOT_ID}"
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(rootUri, "vnd.android.document/root")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_document_browser, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        finish()
    }
}
