package com.machiav3lli.backup.utils

import android.content.Context
import android.net.Uri
import com.machiav3lli.backup.handler.LogsHandler
import com.machiav3lli.backup.handler.ShellHandler
import com.machiav3lli.backup.handler.ShellHandler.Companion.quote
import com.machiav3lli.backup.handler.ShellHandler.Companion.runAsRoot
import com.machiav3lli.backup.handler.ShellHandler.FileInfo.FileType
import com.machiav3lli.backup.handler.ShellHandler.ShellCommandFailedException
import com.machiav3lli.backup.items.StorageFile
import com.machiav3lli.backup.utils.FileUtils.BackupLocationIsAccessibleException
import com.machiav3lli.backup.utils.FileUtils.getBackupDirUri
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import org.apache.commons.io.IOUtils
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

const val binaryMimeType = "application/octet-stream"

@Throws(BackupLocationIsAccessibleException::class, StorageLocationNotConfiguredException::class)
fun Context.getBackupDir(): StorageFile =
    StorageFile.fromUri(this, getBackupDirUri(this))

@Throws(IOException::class)
fun StorageFile.ensureDirectory(dirName: String): StorageFile {
    return findFile(dirName)
        ?: createDirectory(dirName)
        ?: throw IOException("Could not ensure directory: $dirName")
}

fun Uri.deleteRecursive(context: Context): Boolean =
    StorageFile.fromUri(context, this).deleteRecursive()

private fun StorageFile.deleteRecursive(): Boolean = when {
    isFile ->
        delete()
    isDirectory -> try {
        val contents = listFiles()
        var result = true
        contents.forEach { file ->
            result = result && file.deleteRecursive()
        }
        if (result)
            delete()
        else
            result
    } catch (e: FileNotFoundException) {
        false
    } catch (e: Throwable) {
        LogsHandler.unhandledException(e, uri)
        false
    }
    else -> false
}

@Throws(IOException::class)
fun suRecursiveCopyFileToDocument(
    context: Context,
    filesToBackup: List<ShellHandler.FileInfo>,
    targetUri: Uri
) {
    for (file in filesToBackup) {
        try {
            val parentUri = targetUri
                .buildUpon()
                .appendEncodedPath(File(file.filePath).parent)
                .build()
            val parentFile = StorageFile.fromUri(context, parentUri)
            when (file.fileType) {
                FileType.REGULAR_FILE ->
                    suCopyFileToDocument(file, parentFile)
                FileType.DIRECTORY -> parentFile.createDirectory(file.filename)
                else -> Timber.e("SAF does not support ${file.fileType} for ${file.filePath}")
            }
        } catch (e: Throwable) {
            LogsHandler.logException(e)
        }
    }
}

/**
 * Note: This method is bugged, because libsu file might set eof flag in the middle of the file
 * Use the method with the ShellHandler.FileInfo object as parameter instead
 *
 * @param resolver   ContentResolver context to use
 * @param sourcePath filepath to open and read from
 * @param targetDir  file to write the contents to
 * @throws IOException on I/O related errors or FileNotFoundException
 */
@Throws(IOException::class)
fun suCopyFileToDocument(sourcePath: String, targetDir: StorageFile) {
    SuFileInputStream.open(sourcePath).use { inputFile ->
        targetDir.createFile(binaryMimeType, File(sourcePath).name)?.let { newFile ->
            newFile.outputStream!!.use { outputFile ->
                IOUtils.copy(inputFile, outputFile)
            }
        }
    }
}

@Throws(IOException::class)
fun suCopyFileToDocument(
    sourceFileInfo: ShellHandler.FileInfo,
    targetDir: StorageFile
) {
    targetDir.createFile(binaryMimeType, sourceFileInfo.filename)?.let { newFile ->
        newFile.outputStream!!.use { outputStream ->
            ShellHandler.quirkLibsuReadFileWorkaround(sourceFileInfo, outputStream)
        }
    } ?: throw Exception()
}

@Throws(IOException::class, ShellCommandFailedException::class)
fun suRecursiveCopyFileFromDocument(sourceDir: StorageFile, targetPath: String?) {
    for (sourceFile in sourceDir.listFiles()) {
        sourceFile.name?.also { name ->
            if (sourceFile.isDirectory) {
                runAsRoot("mkdir -p ${quote(File(targetPath, name))}")
            } else if (sourceFile.isFile) {
                suCopyFileFromDocument(sourceFile, File(targetPath, name).absolutePath)
            }
        }
    }
}

@Throws(IOException::class)
fun suCopyFileFromDocument(sourceFile: StorageFile, targetPath: String) {
    SuFileOutputStream.open(targetPath).use { outputStream ->
        sourceFile.inputStream!!.use { inputStream ->
            IOUtils.copy(inputStream, outputStream)
        }
    }
}
