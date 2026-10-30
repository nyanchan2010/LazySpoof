package com.muse.lazyspoof.utils;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Lets a hooked target process tell LazySpoof "I am in scope" without root.
 * The hook runs as the target app's UID, so it cannot write LazySpoof's own
 * preferences directly; inserting here runs this provider in LazySpoof's
 * process, which records the package in the scope_seen preferences that the
 * UI reads. Only a package name is ever stored, nothing about the app's data.
 */
public class ScopeSeenProvider extends ContentProvider {

    public static final String AUTHORITY = "com.muse.lazyspoof.scope_seen";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/seen");

    private static final int SEEN = 1;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "seen", SEEN);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (MATCHER.match(uri) != SEEN || values == null) return null;
        String pkg = values.getAsString("package");
        if (pkg == null || pkg.isEmpty()) return null;
        if (getContext() != null) {
            ScopeReader.recordSeen(getContext(), pkg);
        }
        return Uri.withAppendedPath(CONTENT_URI, pkg);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"package"});
        if (getContext() != null) {
            for (String pkg : ScopeReader.readSeenPackages(getContext())) {
                cursor.addRow(new Object[]{pkg});
            }
        }
        return cursor;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.lazyspoof.seen";
    }
}
