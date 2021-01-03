package com.example.uscatterbrain.db.file;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.uscatterbrain.R;
import com.example.uscatterbrain.ScatterRoutingService;
import com.example.uscatterbrain.db.ScatterbrainDatastore;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.disposables.Disposable;

@Singleton
public class DatastoreImportProviderImpl extends DocumentsProvider
        implements DatastoreImportProvider {
    private static final String TAG = "DatastoreImportProvider";
    private static final int USER_ROOT_ID = 0;
    private static final int CACHE_ROOT_ID = 1;
    private static final String DOC_ID_ROOT = "root";

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
            Document.COLUMN_SIZE
    };

    @Inject
    public Context ctx;

    @Inject
    public FileStore fileStore;

    @Inject
    public ScatterbrainDatastore datastore;

    public DatastoreImportProviderImpl() { }

    private Map<String, Serializable> getFileMetadataRootNode(int root) {
        final HashMap<String, Serializable> result = new HashMap<>();
        result.put(Document.COLUMN_DOCUMENT_ID, getRootId(root));
        result.put(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
        result.put(Document.COLUMN_DISPLAY_NAME, "root");
        result.put(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE); //TODO: is this enough?
        result.put(Document.COLUMN_SIZE, 0);
        return result;
    }

    private void addFileRow(MatrixCursor result, Map<String, Serializable> fileMetaData) {
        if (fileMetaData.size() > 0) {
            final MatrixCursor.RowBuilder row = result.newRow();
            for (String column : DEFAULT_DOCUMENT_PROJECTION) {
                row.add(column, fileMetaData.get(column));
            }
            Log.v(TAG, "adding file row: " + row);
        } else {
            Log.e(TAG, "file metadata was zero");
        }
    }

    private String getRootId(int rootid) {
        return DOC_ID_ROOT + rootid;
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
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result =  new MatrixCursor(
                (projection == null || projection.length == 0) ? DEFAULT_ROOT : projection
        );

        initilizeRoot(result, USER_ROOT_ID, "Scatterbrain shared files", Root.FLAG_SUPPORTS_CREATE);
        initilizeRoot(result, CACHE_ROOT_ID, "Scatterbrain received files", Root.FLAG_SUPPORTS_RECENTS);

        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result =  new MatrixCursor(
                (projection == null || projection.length == 0) ? DEFAULT_DOCUMENT_PROJECTION : projection
        );

        final  Map<String, Serializable> fileMetaData;

        if (documentId.equals(getRootId(USER_ROOT_ID))) {
            addFileRow(result, getFileMetadataRootNode(USER_ROOT_ID));
        } else if (documentId.equals(getRootId(CACHE_ROOT_ID))) {
            addFileRow(result, getFileMetadataRootNode(CACHE_ROOT_ID));
        } else {
            fileMetaData = datastore.getFileMetadataSync(new File(documentId));
            addFileRow(result, fileMetaData);
        }
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final MatrixCursor result =  new MatrixCursor(
                (projection == null || projection.length == 0) ? DEFAULT_DOCUMENT_PROJECTION : projection
        );

        final File f;


        if (parentDocumentId.equals(getRootId(USER_ROOT_ID))) {
            f = fileStore.getUserDir();
        } else if (parentDocumentId.equals(getRootId(CACHE_ROOT_ID))) {
            f = fileStore.getCacheDir();
        } else {
            f = new File(parentDocumentId);
        }


        File[] fileList = f.listFiles();
        if (fileList != null) {
            for (File file : fileList) {
                addFileRow(result, datastore.getFileMetadataSync(file));
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, @Nullable CancellationSignal signal) throws FileNotFoundException {
        if (documentId.equals(getRootId(USER_ROOT_ID))) {
            return fileStore.getDescriptor(fileStore.getUserDir().toPath(), mode);
        } else if (documentId.equals(getRootId(CACHE_ROOT_ID))) {
            return fileStore.getDescriptor(fileStore.getCacheDir().toPath(), mode);
        } else {
            return fileStore.getDescriptor(new File(documentId).toPath(), mode);
        }
    }

    private void onInject() {
    }

    @Override
    public boolean onCreate() {
        Disposable ignored = ScatterRoutingService.getComponent()
                .doOnSuccess(success -> Log.v(TAG, "injecting DatastoreImportProvider"))
                .subscribe(component -> {
                    component.inject(this);
                    onInject();
                });
        return true;
    }
}
