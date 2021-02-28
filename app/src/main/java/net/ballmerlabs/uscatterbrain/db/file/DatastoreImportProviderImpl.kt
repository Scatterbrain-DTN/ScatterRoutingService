package net.ballmerlabs.uscatterbrain.db.file

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import io.reactivex.disposables.Disposable
import io.reactivex.functions.Consumer
import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi
import net.ballmerlabs.uscatterbrain.R
import net.ballmerlabs.uscatterbrain.RoutingServiceComponent
import net.ballmerlabs.uscatterbrain.ScatterRoutingService
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.Serializable
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.Throws

@Singleton
class DatastoreImportProviderImpl : DocumentsProvider(), DatastoreImportProvider {
    private val fileCloseHandler: Handler

    @Inject
    lateinit var ctx: Context

    @Inject
    lateinit var datastore: ScatterbrainDatastore

    private fun getDefaultFileMetadata(path: File): Map<String, Serializable> {
        val result = HashMap<String, Serializable>()
        result[DocumentsContract.Document.COLUMN_DOCUMENT_ID] = path.absolutePath
        result[DocumentsContract.Document.COLUMN_MIME_TYPE] = ScatterbrainApi.getMimeType(path)
        result[DocumentsContract.Document.COLUMN_DISPLAY_NAME] = path.name
        result[DocumentsContract.Document.COLUMN_FLAGS] = DocumentsContract.Document.FLAG_SUPPORTS_DELETE //TODO: is this enough?
        result[DocumentsContract.Document.COLUMN_SIZE] = path.length()
        result[DocumentsContract.Document.COLUMN_SUMMARY] = "not yet indexed"
        return result
    }

    private fun getFileMetadataRootNode(root: Int): Map<String, Serializable> {
        val result = HashMap<String, Serializable>()
        result[DocumentsContract.Document.COLUMN_DOCUMENT_ID] = getRootId(root)!!
        result[DocumentsContract.Document.COLUMN_MIME_TYPE] = DocumentsContract.Document.MIME_TYPE_DIR
        result[DocumentsContract.Document.COLUMN_DISPLAY_NAME] = "root"
        result[DocumentsContract.Document.COLUMN_SUMMARY] = "rootn node"
        result[DocumentsContract.Document.COLUMN_FLAGS] = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE //TODO: is this enough?
        result[DocumentsContract.Document.COLUMN_SIZE] = 0
        return result
    }

    private fun addFileRow(result: MatrixCursor, fileMetaData: Map<String, Serializable>) {
        if (fileMetaData.isNotEmpty()) {
            val row = result.newRow()
            for (column in DEFAULT_DOCUMENT_PROJECTION) {
                Log.v(TAG, "adding column: " + column + " : " + fileMetaData[column])
                row.add(column, fileMetaData[column])
            }
            Log.v(TAG, "adding file row: $row")
        } else {
            Log.e(TAG, "file metadata was zero")
        }
    }

    private fun getRootId(rootid: Int): String? {
        return when (rootid) {
            0 -> {
                USER_ROOT_ID
            }
            1 -> {
                CACHE_ROOT_ID
            }
            else -> {
                null
            }
        }
    }

    private fun initilizeRoot(result: MatrixCursor, rootid: Int, title: String, flags: Int) {
        val row = result.newRow()
        val documentId = getRootId(rootid)
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, documentId)
        row.add(DocumentsContract.Root.COLUMN_FLAGS, flags)
        row.add(DocumentsContract.Root.COLUMN_TITLE, title)
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, documentId)
        row.add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_launcher_background)
    }

    @Throws(FileNotFoundException::class)
    override fun queryRoots(projection: Array<String>): Cursor {
        val result = MatrixCursor(
                if (projection.isEmpty()) DEFAULT_ROOT else projection
        )
        initilizeRoot(result, 0, "Scatterbrain shared files", DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
        initilizeRoot(result, 1, "Scatterbrain received files", DocumentsContract.Root.FLAG_SUPPORTS_RECENTS)
        return result
    }

    @Throws(FileNotFoundException::class)
    override fun queryDocument(documentId: String, projection: Array<String>): Cursor {
        Log.v(TAG, "querying file metadata: $documentId")
        val result = MatrixCursor(
                if (projection.isEmpty()) DEFAULT_DOCUMENT_PROJECTION else projection
        )
        if (documentId == USER_ROOT_ID) {
            addFileRow(result, getFileMetadataRootNode(0))
        } else if (documentId == CACHE_ROOT_ID) {
            addFileRow(result, getFileMetadataRootNode(1))
        } else {
            val f = File(documentId)
            val fileMetaData = datastore?.getFileMetadataSync(f)!!
            if (!f.exists()) {
                return result
            } else if (fileMetaData.isNotEmpty()) {
                addFileRow(result, fileMetaData)
            } else {
                Log.e(TAG, "file does not exist")
                addFileRow(result, getDefaultFileMetadata(f))
            }
        }
        return result
    }

    @Throws(FileNotFoundException::class)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>, sortOrder: String): Cursor {
        Log.v(TAG, "queryChildDocuments: $parentDocumentId")
        val result = MatrixCursor(
                if (projection.isEmpty()) DEFAULT_DOCUMENT_PROJECTION else projection
        )
        val f: File?
        f = if (parentDocumentId == USER_ROOT_ID) {
            datastore?.userDir
        } else if (parentDocumentId == CACHE_ROOT_ID) {
            datastore?.cacheDir
        } else {
            File(parentDocumentId)
        }
        val fileList = f!!.listFiles()
        if (fileList != null) {
            for (file in fileList) {
                if (file.exists()) {
                    val r = datastore!!.getFileMetadataSync(file)
                    if (r.isNotEmpty()) {
                        addFileRow(result, r)
                    } else {
                        Log.e(TAG, "queryChildDocuments failed to retrieve file: $file")
                        addFileRow(result, getDefaultFileMetadata(f))
                    }
                }
            }
        }
        return result
    }

    @Throws(FileNotFoundException::class)
    private fun getDescriptor(file: File?, mode: String): ParcelFileDescriptor? {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode), fileCloseHandler) { e: IOException? -> }
        } catch (e: IOException) {
            null
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return documentId.startsWith(parentDocumentId)
    }

    @Throws(FileNotFoundException::class)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        Log.v(TAG, "openDocument: $documentId")
        return if (documentId == USER_ROOT_ID) {
            getDescriptor(datastore?.userDir, mode)!!
        } else if (documentId == CACHE_ROOT_ID) {
            getDescriptor(datastore?.cacheDir, mode)!!
        } else {
            val f = File(documentId)
            getDescriptor(f, mode)!!
        }
    }

    @Throws(FileNotFoundException::class)
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String? {
        val f: File
        val parent: String
        parent = if (parentDocumentId == USER_ROOT_ID) {
            datastore?.userDir?.toPath().toString()
        } else if (parentDocumentId == CACHE_ROOT_ID) {
            datastore?.cacheDir?.toPath().toString()
        } else {
            parentDocumentId
        }
        Log.v(TAG, "parentID: " + parentDocumentId.compareTo(USER_ROOT_ID))
        if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
            f = File(parent, displayName)
            if (!f.mkdirs()) {
                return null
            }
        } else {
            f = File(parent, displayName)
            Log.v(TAG, "creating file: " + f.absolutePath)
            try {
                if (!f.createNewFile()) {
                    Log.e(TAG, "failed to create new file: $displayName")
                    return null
                }
                if (!f.setReadable(true)) {
                    Log.e(TAG, "failed to set file readable")
                    return null
                }
                if (!f.setWritable(true)) {
                    Log.e(TAG, "failed to set file writable")
                    return null
                }
            } catch (e: IOException) {
                Log.e(TAG, "IOException when creating new file: $displayName")
                return null
            }
        }
        return f.absolutePath
    }

    @Throws(FileNotFoundException::class)
    override fun deleteDocument(documentId: String) {
        Log.v(TAG, "deleteDocument: $documentId")
        val file = File(documentId)
        if (!file.delete()) {
            throw FileNotFoundException("failed to delete file")
        }
        if (datastore!!.deleteByPath(file) == 0) {
            throw FileNotFoundException("failed to delete database entry")
        }
    }

    private fun onInject() {}
    override fun onCreate(): Boolean {
        val ignored: Disposable = ScatterRoutingService.Companion.getComponent()
                .doOnSuccess(Consumer<RoutingServiceComponent> { success: RoutingServiceComponent? -> Log.v(TAG, "injecting DatastoreImportProvider") })
                .subscribe(Consumer { component: RoutingServiceComponent ->
                    component.inject(this)
                    onInject()
                })
        return true
    }

    companion object {
        private const val TAG = "DatastoreImportProvider"
        private const val USER_ROOT_ID = "/userroot/"
        private const val CACHE_ROOT_ID = "/cacheroot/"
        private val DEFAULT_ROOT = arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_MIME_TYPES,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_ICON,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_SUMMARY
        )
    }

    init {
        fileCloseHandler = Handler(Looper.getMainLooper())
    }
}