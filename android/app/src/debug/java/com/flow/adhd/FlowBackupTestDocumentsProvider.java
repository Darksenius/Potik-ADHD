package com.flow.adhd;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Tiny filesystem-backed SAF provider used only by FlowBackupStore instrumentation tests. */
public final class FlowBackupTestDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".backup-test-documents";
    static volatile boolean failBackupRename;
    private File root;

    @Override public boolean onCreate() {
        root = new File(getContext().getCacheDir(), "backup-documents");
        if (!root.isDirectory() && !root.mkdirs()) return false;
        int flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        getContext().grantUriPermission(getContext().getPackageName() + ".test", treeUri(), flags);
        return true;
    }

    static Uri treeUri() { return DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root"); }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            return "root".equals(parentDocumentId) && !"root".equals(documentId)
                    && root.getCanonicalFile().equals(fileFor(documentId).getParentFile());
        } catch (IOException e) { return false; }
    }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[] {
                DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_MIME_TYPES, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        } : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (DocumentsContract.Root.COLUMN_ROOT_ID.equals(column)) row.add("root");
            else if (DocumentsContract.Root.COLUMN_DOCUMENT_ID.equals(column)) row.add("root");
            else if (DocumentsContract.Root.COLUMN_TITLE.equals(column)) row.add("Backup test folder");
            else if (DocumentsContract.Root.COLUMN_FLAGS.equals(column)) row.add(DocumentsContract.Root.FLAG_SUPPORTS_CREATE);
            else if (DocumentsContract.Root.COLUMN_MIME_TYPES.equals(column)) row.add("*/*");
            else if (DocumentsContract.Root.COLUMN_AVAILABLE_BYTES.equals(column)) row.add(1_000_000L);
            else row.add(null);
        }
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        File file = fileFor(documentId);
        String[] columns = projection == null ? new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
        } : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        addDocumentRow(cursor, columns, documentId, file);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        File parent = fileFor(parentDocumentId);
        String[] columns = projection == null ? new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
        } : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        File[] children = parent.listFiles();
        if (children != null) for (File child : children) addDocumentRow(cursor, columns, child.getName(), child);
        return cursor;
    }

    private void addDocumentRow(MatrixCursor cursor, String[] columns, String id, File file) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) row.add(id);
            else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)) row.add("root".equals(id) ? "Backup test folder" : file.getName());
            else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)) row.add(file.isDirectory()
                    ? DocumentsContract.Document.MIME_TYPE_DIR : "application/json");
            else if (DocumentsContract.Document.COLUMN_FLAGS.equals(column)) row.add(file.isDirectory()
                    ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                    : DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                        | DocumentsContract.Document.FLAG_SUPPORTS_RENAME);
            else if (DocumentsContract.Document.COLUMN_SIZE.equals(column)) row.add(file.length());
            else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) row.add(file.lastModified());
            else row.add(null);
        }
    }

    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = fileFor(parentDocumentId);
        File file = new File(parent, displayName);
        try {
            if (!file.createNewFile()) throw new FileNotFoundException("Could not create test document");
            return file.getName();
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = fileFor(documentId);
        int flags = ParcelFileDescriptor.MODE_READ_ONLY;
        if (mode.contains("w")) flags = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                | (mode.contains("t") ? ParcelFileDescriptor.MODE_TRUNCATE : 0);
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File current = fileFor(documentId);
        if (failBackupRename && ("flow_backup.json".equals(current.getName())
                || "flow_backup.json.previous".equals(displayName))) throw new FileNotFoundException("Simulated provider rename failure");
        if (displayName.contains("/") || displayName.contains("\\")) return null;
        File renamed = new File(current.getParentFile(), displayName);
        if (renamed.exists() || !current.renameTo(renamed)) return null;
        return displayName;
    }

    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = fileFor(documentId);
        if (!file.delete()) throw new FileNotFoundException("Could not delete test document");
    }

    @Override public String getDocumentType(String documentId) throws FileNotFoundException {
        return fileFor(documentId).isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/json";
    }

    private File fileFor(String id) throws FileNotFoundException {
        File file = "root".equals(id) ? root : new File(root, id);
        try {
            File canonical = file.getCanonicalFile();
            if (!canonical.equals(root.getCanonicalFile()) && !root.getCanonicalFile().equals(canonical.getParentFile()))
                throw new FileNotFoundException("Invalid test document id");
            return canonical;
        } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
    }
}
