package com.forganizer.app.fs

import android.content.Context
import android.media.MediaScannerConnection
import com.forganizer.core.LocalFileSource

/** All-files-access backend: java.io.File + media scanner for old and new paths. */
class FileBackend(context: Context) : LocalFileSource(
    onChanged = { paths ->
        MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null, null)
    },
)
