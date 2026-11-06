package com.ahmadnayfeh.reel

import android.app.Activity
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Delete / rename / move through MediaStore. On Android 11+ the system shows its own confirmation
 * dialog (this is required by Android); on older versions files are changed directly.
 */
class FileOps(private val act: AppCompatActivity, private val onChanged: () -> Unit) {
    private var pending: (() -> Unit)? = null
    private val cr get() = act.contentResolver

    private val launcher = act.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val op = pending
        pending = null
        if (r.resultCode == Activity.RESULT_OK) runCatching { op?.invoke() }.onFailure { fail() }
        onChanged()
    }

    private fun fail() = Toast.makeText(act, R.string.op_failed, Toast.LENGTH_SHORT).show()

    fun delete(uris: List<Uri>, paths: List<String?>) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            pending = null
            launcher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(cr, uris).intentSender).build())
        } else {
            uris.forEachIndexed { i, u ->
                runCatching { paths.getOrNull(i)?.let { File(it).delete() } }
                runCatching { cr.delete(u, null, null) }
            }
            onChanged()
        }
    }

    fun rename(uri: Uri, path: String?, newName: String) {
        if (Build.VERSION.SDK_INT >= 30) {
            pending = { cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null) }
            launcher.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(cr, listOf(uri)).intentSender).build())
        } else {
            val f = File(path ?: return fail())
            val dest = File(f.parentFile, newName)
            if (f.renameTo(dest)) legacyRescan(uri, dest) else fail()
        }
    }

    /** [relPath] like "Movies/Series/". */
    fun move(items: List<Pair<Uri, String?>>, relPath: String) {
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            pending = {
                items.forEach { (u, _) ->
                    cr.update(u, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relPath) }, null, null)
                }
            }
            launcher.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(cr, items.map { it.first }).intentSender).build())
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStorageDirectory(), relPath).apply { mkdirs() }
            items.forEach { (u, p) ->
                val f = File(p ?: return@forEach)
                val dest = File(dir, f.name)
                if (f.renameTo(dest)) legacyRescan(u, dest) else fail()
            }
        }
    }

    private fun legacyRescan(old: Uri, dest: File) {
        runCatching { cr.delete(old, null, null) }
        MediaScannerConnection.scanFile(act, arrayOf(dest.path), null) { _, _ -> act.runOnUiThread { onChanged() } }
    }
}
