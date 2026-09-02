package io.github.poporpepopo.smbdocs

import android.os.StrictMode
import android.util.Log
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Holds one live DiskShare and hands it out, reconnecting when the router has dropped the
 * session. Consumer-grade SMB servers idle out aggressively, so every call goes through
 * [withShare], which retries once on a connection-level failure.
 */
object SmbConnection {
    private const val TAG = "SmbConnection"

    /**
     * Every SMB call runs here rather than on the caller's thread. A binder transaction
     * hands the provider the *caller's* StrictMode policy, and DocumentsUI runs with
     * detectNetwork(), so doing socket I/O on the incoming thread throws
     * NetworkOnMainThreadException. This thread is ours and sets its own policy.
     */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())
            r.run()
        }, "smb-io").apply { isDaemon = true }
    }

    private val lock = Any()
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var openedWith: SmbConfigData? = null

    private fun newClient(): SMBClient {
        val config = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .withDfsEnabled(false)
            .build()
        return SMBClient(config)
    }

    private fun isUsable(): Boolean {
        val s = share ?: return false
        val c = connection ?: return false
        return c.isConnected && s.isConnected
    }

    private fun closeQuietly() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null; session = null; connection = null; client = null
    }

    private fun connect(cfg: SmbConfigData): DiskShare {
        closeQuietly()
        val c = newClient()
        val conn = c.connect(cfg.host)
        val auth = if (cfg.user.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(cfg.user, cfg.password.toCharArray(), cfg.domain.ifBlank { null })
        }
        val sess = conn.authenticate(auth)
        val disk = sess.connectShare(cfg.share) as? DiskShare
            ?: throw IOException("'${cfg.share}' is not a disk share")
        client = c; connection = conn; session = sess; share = disk; openedWith = cfg
        Log.i(TAG, "connected to //${cfg.host}/${cfg.share}")
        return disk
    }

    /** Drop the cached connection, e.g. after the settings changed. */
    fun invalidate() {
        synchronized(lock) { closeQuietly(); openedWith = null }
    }

    /**
     * Runs [block] against a live share. On a connection-level error the connection is
     * rebuilt once and [block] is retried, because a stale session is by far the most
     * common failure here and it is invisible until the first call fails.
     */
    fun <T> withShare(cfg: SmbConfigData, block: (DiskShare) -> T): T =
        try {
            executor.submit(Callable { withShareOnIoThread(cfg, block) }).get()
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }

    private fun <T> withShareOnIoThread(cfg: SmbConfigData, block: (DiskShare) -> T): T {
        synchronized(lock) {
            val reusable = isUsable() && openedWith == cfg
            var disk = if (reusable) share!! else connect(cfg)
            return try {
                block(disk)
            } catch (e: Exception) {
                if (!isRetryable(e)) throw e
                Log.w(TAG, "retrying after ${e.javaClass.simpleName}: ${e.message}")
                disk = connect(cfg)
                block(disk)
            }
        }
    }

    private fun isRetryable(e: Exception): Boolean = when (e) {
        is IOException -> true
        else -> e.javaClass.name.startsWith("com.hierynomus.")
    }
}
