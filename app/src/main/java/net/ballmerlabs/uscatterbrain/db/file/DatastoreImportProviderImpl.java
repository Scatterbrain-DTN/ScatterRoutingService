package net.ballmerlabs.uscatterbrain.db.file;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Log;

import androidx.annotation.Nullable;

import net.ballmerlabs.scatterbrainsdk.ScatterbrainApi;
import net.ballmerlabs.uscatterbrain.R;
import net.ballmerlabs.uscatterbrain.ScatterRoutingService;
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * DocumentsProvider to allow importing files into the datastore
 * without using the Scatterbrain binder api using android's native
 * documents UI.
 *
 * This provides two roots. One is a read-write interface to add new
 * files to the datastore using a well-known "filesharing" application id
 *
 * the other is a readonly view of existing files
 * in the datastore from any application
 *
 * This is implemented in java because kotlin's null safety was causing
 * strange issues with the DocumentsProvider api.
 *
 * TODO: find issue and rewrite in kotlin
 */
@Singleton
public class DatastoreImportProviderImpl extends DocumentsProvider
        implements DatastoreImportProvider {
    private static final String TAG = "DatastoreImportProvider";
    private static final String USER_ROOT_ID = "/userroot/";
    private static final String CACHE_ROOT_ID = "/cacheroot/";
    private static final String[] DEFAULT_ROOT = new String[] {
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_SUMMARY
    };

    private final Handler fileCloseHandler;

    @Inject
    public Context ctx;

    @Inject
    public ScatterbrainDatastore datastore;

    public DatastoreImportProviderImpl() {
        fileCloseHandler = new Handler(Looper.getMainLooper());
    }

    private Map<String, Serializable> getDefaultFileMetadata(File path) {
        final HashMap<String, Serializable> result = new HashMap<>();
        result.put(Document.COLUMN_DOCUMENT_ID, path.getAbsolutePath());
        result.put(Document.COLUMN_MIME_TYPE, ScatterbrainApi.getMimeType(path));
        result.put(Document.COLUMN_DISPLAY_NAME, path.getName());
        result.put(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE); //TODO: is this enough?
        result.put(Document.COLUMN_SIZE, path.length());
        result.put(Document.COLUMN_SUMMARY, "not yet indexed");
        return result;
    }

    private Map<String, Serializable> getFileMetadataRootNode(int root) {
        final HashMap<String, Serializable> result = new HashMap<>();
        result.put(Document.COLUMN_DOCUMENT_ID, getRootId(root));
        result.put(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
        result.put(Document.COLUMN_DISPLAY_NAME, "root");
        result.put(Document.COLUMN_SUMMARY, "rootn node");
        result.put(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE); //TODO: is this enough?
        result.put(Document.COLUMN_SIZE, 0);
        return result;
    }

    private void addFileRow(MatrixCursor result, Map<String, Serializable> fileMetaData) {
        if (fileMetaData.size() > 0) {
            final MatrixCursor.RowBuilder row = result.newRow();
            for (String column : DEFAULT_DOCUMENT_PROJECTION) {
                Log.v(TAG, "adding column: " + column + " : "  + fileMetaData.get(column));
                row.add(column, fileMetaData.get(column));
            }
            Log.v(TAG, "adding file row: " + row);
        } else {
            Log.e(TAG, "file metadata was zero");

        }
    }

    private String getRootId(int rootid) {
        switch (rootid) {
            case 0: {
                return USER_ROOT_ID;
            }
            case 1: {
                return CACHE_ROOT_ID;
            }
            default: {
                return null;
            }
        }
    }

    private void initilizeRoot(MatrixCursor result, int rootid, String title, int flags) {
        final MatrixCursor.RowBuilder row = result.newRow();

        final String documentId = getRootId(rootid);
        row.add(Root.COLUMN_ROOT_ID, documentId);
        row.add(Root.COLUMN_FLAGS, flags);
        row.add(Root.COLUMN_TITLE, title);
        row.add(Root.COLUMN_DOCUMENT_ID, documentId);
        row.add(Root.COLUMN_ICON, R.drawable.ic_launcher_background);
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        final MatrixCursor result =  new MatrixCursor(
                (projection == null || projection.length == 0) ? DEFAULT_ROOT : projection
        );

        initilizeRoot(result, 0, "Scatterbrain shared files", Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD);
        initilizeRoot(result, 1, "Scatterbrain received files", Root.FLAG_SUPPORTS_RECENTS);

        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) {
        Log.v(TAG, "querying file metadata: " + documentId);
        final MatrixCursor result =  new MatrixCursor(
                (projection == null || projection.length == 0) ? DEFAULT_DOCUMENT_PROJECTION : projection
        );

        final  Map<String, Serializable> fileMetaData;

        if (documentId.equals(USER_ROOT_ID)) {
            addFileRow(result, getFileMetadataRootNode(0));
        } else if (documentId.equals(CACHE_ROOT_ID)) {
            addFileRow(result, getFileMetadataRootNode(1));
        } else {
            final File f = new File(documentId);
            fileMetaData = datastore.getFileMetadataSync(f);
            if (!f.exists()) {
                return result;
            } else if (fileMetaData.size() > 0) {
                addFileRow(result, fileMetaData);
            } else {
                Log.e(TAG, "file does not exist");
                addFileRow(result, getDefaultFileMetadata(f));
            }
        }
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        Log.v(TAG, "queryChildDocuments: " + parentDocumentId);
        final MatrixCursor result =  new MatrixCursor(
                (projection == null || projection.length == 0) ? DEFAULT_DOCUMENT_PROJECTION : projection
        );

        final File f;


        if (parentDocumentId.equals(USER_ROOT_ID)) {
            f = datastore.getUserDir();
        } else if (parentDocumentId.equals(CACHE_ROOT_ID)) {
            f = datastore.getCacheDir();
        } else {
            f = new File(parentDocumentId);
        }


        File[] fileList = f.listFiles();
        if (fileList != null) {
            for (File file : fileList) {
                if (file.exists()) {
                    final Map<String, Serializable> r = datastore.getFileMetadataSync(file);
                    if (r.size() > 0) {
                        addFileRow(result, r);
                    } else {
                        Log.e(TAG, "queryChildDocuments failed to retrieve file: " + file);
                        addFileRow(result, getDefaultFileMetadata(f));
                    }
                }
            }
        }
        return result;
    }

    private ParcelFileDescriptor getDescriptor(File file, String mode) {
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode), fileCloseHandler, e -> {});
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId);
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, @Nullable CancellationSignal signal) {
        Log.v(TAG, "openDocument: " + documentId);
        if (documentId.equals(USER_ROOT_ID)) {
            return getDescriptor(datastore.getUserDir(), mode);
        } else if (documentId.equals(CACHE_ROOT_ID)) {
            return getDescriptor(datastore.getCacheDir(), mode);
        } else {
            final File f = new File(documentId);
            return getDescriptor(f, mode);
        }
    }




    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName){
        final File f;
        final String parent;
        if (parentDocumentId.equals(USER_ROOT_ID)) {
            parent = datastore.getUserDir().toPath().toString();
        } else if (parentDocumentId.equals(CACHE_ROOT_ID)) {
            parent = datastore.getCacheDir().toPath().toString();
        } else {
            parent = parentDocumentId;
        }

        Log.v(TAG, "parentID: " + parentDocumentId.compareTo(USER_ROOT_ID));
        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            f = new File(parent, displayName);
            if (!f.mkdirs()) {
                return null;
            }
        } else {
            f = new File(parent, displayName);
            Log.v(TAG, "creating file: " + f.getAbsolutePath());
            try {
                if (!f.createNewFile()) {
                    Log.e(TAG, "failed to create new file: " + displayName);
                    return  null;
                }

                if (!f.setReadable(true)) {
                    Log.e(TAG, "failed to set file readable");
                    return null;
                }

                if (!f.setWritable(true)) {
                    Log.e(TAG, "failed to set file writable");
                    return null;
                }
            } catch (IOException e) {
                Log.e(TAG, "IOException when creating new file: " + displayName);
                return null;
            }
        }
        return f.getAbsolutePath();
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        Log.v(TAG, "deleteDocument: " + documentId);
        final File file = new File(documentId);
        if (!file.delete()) {
            throw new FileNotFoundException("failed to delete file");
        }

        if (datastore.deleteByPath(file) == 0) {
            throw new FileNotFoundException("failed to delete database entry");
        }
    }


    @SuppressWarnings("ResultOfMethodCallIgnored")
    @SuppressLint("CheckResult")
    @Override
    public boolean onCreate() {
        /*
         * NOTE: because we cannot inject directly into a DocumentsProvider
         * due to api limitations, we have to do this INCREDIBLY hacky trick
         * to manually inject dagger2 dependencies
         *
         * This relies on the fact that no user is inhumanly fast enough to
         * open the documents UI before injection is complete, and even if they
         * do the worst that happens is they have to refresh the page once due
         * to a thrown nullpointerexception.
         *
         * I wish I didn't have to do this but I see no other way...
         */

        ScatterRoutingService.Companion.getComponent()
                .doOnSuccess(success -> Log.v(TAG, "injecting DatastoreImportProvider"))
                .subscribe(
                        component -> component.inject(this),
                        error -> {
                         Log.e(TAG, "failed to inject dependencies for documentsprovider" +
                                 error);
                         error.printStackTrace();
                        });
        return true;
    }
}