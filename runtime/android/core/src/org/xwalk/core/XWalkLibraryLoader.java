// Copyright (c) 2015 Intel Corporation. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.xwalk.core;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.DownloadManager.Request;
import android.app.DownloadManager.Query;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.Thread;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.Arrays;

import junit.framework.Assert;

/**
 * XWalkLibraryLoader is a low level inteface to schedule decompressing, downloading, activating
 * the Crosswalk runtime. Normal user is recommended to use XWalkActivity or XWalkInitializer which
 * is simpler and more user-friendly.
 *
 * The appropriate invocation order is:
 * prepareToInit() -
 * [ if the Crosswalk runtime is supposed to be compressed - startDecompress() ] -
 * startActivate() -
 * [ if the Crosswalk runtime doesn't match - download suitable version - startActivate() ] - over
 */

class XWalkLibraryLoader {
    /**
     * Interface used to decompress the Crosswalk runtime
     */
    public interface DecompressListener {
        /**
         * Run on the UI thread to notify decompression is started.
         *
         * <p> This method will not be invoked if the Crosswalk runtime is not compressed or has
         * already been decompressed.
         */
        public void onDecompressStarted();

        /**
         * Run on the UI thread to notify decompression is cancelled.
         */
        public void onDecompressCancelled();

        /**
         * Run on the UI thread to notify decompression is completed successfully.
         */
        public void onDecompressCompleted();
    }

    /**
     * Interface used to activate the Crosswalk runtime
     */
    public interface ActivateListener {
        /**
         * Run on the UI thread to notify activation is started
         */
        public void onActivateStarted();

        /**
         * Run on the UI thread to notify activation failed
         */
        public void onActivateFailed();

        /**
         * Run on the UI thread to notify activation is completed successfully
         */
        public void onActivateCompleted();
    }

    /**
     * Interface used to download the Crosswalk runtime
     */
    public interface DownloadListener {
        /**
         * Run on the UI thread to notify download is started
         */
        public void onDownloadStarted();

        /**
         * Run on the UI thread to notify the download progress
         * @param percentage Shows the download progress in percentage
         */
        public void onDownloadUpdated(int percentage);

        /**
         * Run on the UI thread to notify download is cancelled
         */
        public void onDownloadCancelled();

        /**
         * Run on the UI thread to notify download is completed successfully
         * @param uri The Uri where the downloaded file is stored
         */
        public void onDownloadCompleted(Uri uri);

        /**
         * Run on the UI thread to notify download failed
         *
         * @param status The download status defined in android.app.DownloadManager.
         *               The value maybe STATUS_FAILED or STATUS_PAUSED
         * @param error The download error defined in android.app.DownloadManager.
         *              This parameter only makes sense when the status is STATUS_FAILED
         */
        public void onDownloadFailed(int status, int error);
    }

    private static final String DEFAULT_DOWNLOAD_FILE_NAME = "xwalk_download.tmp";
    private static final String DOWNLOAD_WITHOUT_NOTIFICATION =
            "android.permission.DOWNLOAD_WITHOUT_NOTIFICATION";
    private static final String TAG = "XWalkLib";

    private static AsyncTask<Void, Integer, Integer> sActiveTask;

    /**
     * Return true if running in shared mode, false if in embedded mode.
     *
     * <p>This method must be invoked after the Crosswalk runtime has already been initialized
     * successfully.
     */
    public static boolean isSharedLibrary() {
        return XWalkCoreWrapper.getInstance().isSharedMode();
    }

    /**
     * Return true if the Crosswalk runtime has already been initialized successfully either in
     * embedded mode or shared mode, false otherwise.
     */
    public static boolean isLibraryReady() {
        return XWalkCoreWrapper.getInstance() != null;
    }

    /**
     * Return the library status defined in {@link XWalkLibraryInterface}.
     */
    public static int getLibraryStatus() {
        return XWalkCoreWrapper.getCoreStatus();
    }

    /**
     * Prepare to start initializing before all other procedures.
     *
     * <p>This method must be invoked on the UI thread.
     */
    public static void prepareToInit(Activity activity) {
        XWalkCoreWrapper.handlePreInit(activity.getClass().getName());
    }

    /**
     * Start decompressing the Crosswalk runtime in background
     *
     * <p>This method must be invoked on the UI thread.
     *
     * @param listener The {@link DecompressListener} to use
     * @param context The context of the package that holds the compressed Crosswalk runtime
     */
    public static void startDecompress(DecompressListener listener, Context context) {
        new DecompressTask(listener, context).execute();
    }

    /**
     * Attempt to cancel decompression
     *
     * @return False if decompression is not running or could not be cancelled, true otherwise
     */
    public static boolean cancelDecompress() {
        DecompressTask task = (DecompressTask) sActiveTask;
        return task != null && task.cancel(true);
    }

    /**
     * Start activating the Crosswalk runtime in background. The activation is not cancelable.
     *
     * <p>This method must be invoked on the UI thread.
     *
     * @param listener The {@link ActivateListener} to use
     */
    public static void startActivate(ActivateListener listener, Activity activity) {
        new ActivateTask(listener, activity).execute();
    }

    /**
     * Start downloading the Crosswalk runtime in background via Android DownlomadManager
     *
     * <p>This method must be invoked on the UI thread.
     *
     * @param listener The {@link DownloadListener} to use
     * @param context The context to get DownloadManager
     * @param url The URL of the Crosswalk runtime
     * @param downloadMode true if it works under download mode.
     */
    public static void startDownload(DownloadListener listener, Context context, String url,
            boolean downloadMode) {
        if (downloadMode) {
            new XWalkDownloadTask(listener, context, url).execute();
        } else {
            new DownloadTask(listener, context, url).execute();
        }
    }

    /**
     * Attempt to cancel download
     *
     * @return False if download is not running or could not be cancelled, true otherwise
     */
    public static boolean cancelDownload() {
        return sActiveTask != null && sActiveTask.cancel(true);
    }

    private static class DecompressTask extends AsyncTask<Void, Integer, Integer> {
        DecompressListener mListener;
        Context mContext;
        boolean mIsCompressed;
        boolean mIsDecompressed;

        DecompressTask(DecompressListener listener, Context context) {
            super();
            mListener = listener;
            mContext = context;
        }

        @Override
        protected void onPreExecute() {
            Log.d(TAG, "DecompressTask started");
            sActiveTask = this;

            mIsCompressed = XWalkLibraryDecompressor.isCompressed(mContext);
            if (mIsCompressed) mIsDecompressed = XWalkLibraryDecompressor.isDecompressed(mContext);
            if (mIsCompressed && !mIsDecompressed) mListener.onDecompressStarted();
        }

        @Override
        protected Integer doInBackground(Void... params) {
            if (!mIsCompressed || mIsDecompressed) return 0;

            if (!XWalkLibraryDecompressor.decompressLibrary(mContext)) return 1;

            return 0;
        }

        @Override
        protected void onCancelled(Integer result) {
            Log.d(TAG, "DecompressTask cancelled");
            sActiveTask = null;
            mListener.onDecompressCancelled();
        }

        @Override
        protected void onPostExecute(Integer result) {
            Log.d(TAG, "DecompressTask finished, " + result);
            Assert.assertEquals(result.intValue(), 0);
            sActiveTask = null;
            mListener.onDecompressCompleted();
        }
    }

    private static class ActivateTask extends AsyncTask<Void, Integer, Integer> {
        ActivateListener mListener;
        Activity mActivity;

        ActivateTask(ActivateListener listener, Activity activity) {
            super();
            mListener = listener;
            mActivity = activity;
        }

        @Override
        protected void onPreExecute() {
            Log.d(TAG, "ActivateTask started");
            sActiveTask = this;
            mListener.onActivateStarted();
        }

        @Override
        protected Integer doInBackground(Void... params) {
            if (XWalkCoreWrapper.getInstance() != null) return -1;
            return XWalkCoreWrapper.attachXWalkCore(mActivity);
        }

        @Override
        protected void onPostExecute(Integer result) {
            if (result == XWalkLibraryInterface.STATUS_MATCH) {
                XWalkCoreWrapper.dockXWalkCore();
            }
            if (XWalkCoreWrapper.getInstance() != null) {
                XWalkCoreWrapper.handlePostInit(mActivity.getClass().getName());
            }

            Log.d(TAG, "ActivateTask finished, " + result);
            sActiveTask = null;
            if (result > XWalkLibraryInterface.STATUS_MATCH) {
                mListener.onActivateFailed();
            } else {
                mListener.onActivateCompleted();
            }
        }
    }

    private static class DownloadTask extends AsyncTask<Void, Integer, Integer> {
        private static final int QUERY_INTERVAL_MS = 100;
        private static final int MAX_PAUSED_COUNT = 6000; // 10 minutes

        private DownloadListener mListener;
        private Context mContext;
        private String mDownloadUrl;
        private DownloadManager mDownloadManager;
        private long mDownloadId;

        DownloadTask(DownloadListener listener, Context context, String url) {
            super();
            mListener = listener;
            mContext = context;
            mDownloadUrl = url;
            mDownloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        }

        @Override
        protected void onPreExecute() {
            Log.d(TAG, "DownloadTask started, " + mDownloadUrl);
            sActiveTask = this;

            String savedFile = DEFAULT_DOWNLOAD_FILE_NAME;
            try {
                String name = new File(new URL(mDownloadUrl).getPath()).getName();
                if (!name.isEmpty()) savedFile = name;
            } catch (MalformedURLException | NullPointerException e) {
                Log.e(TAG, "Invalid download URL " + mDownloadUrl);
                mDownloadUrl = null;
                return;
            }

            File downloadDir = mContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            File downloadFile = new File(downloadDir, savedFile);
            if (downloadFile.isFile()) downloadFile.delete();

            Request request = new Request(Uri.parse(mDownloadUrl));
            request.setDestinationInExternalFilesDir(
                    mContext, Environment.DIRECTORY_DOWNLOADS, savedFile);
            if (isSilentDownload()) {
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
            }
            mDownloadId = mDownloadManager.enqueue(request);

            mListener.onDownloadStarted();
        }

        @Override
        protected Integer doInBackground(Void... params) {
            if (mDownloadUrl == null) return DownloadManager.STATUS_FAILED;

            Query query = new Query().setFilterById(mDownloadId);
            int pausedCount = 0;

            while (!isCancelled()) {
                try {
                    Thread.sleep(QUERY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }

                Cursor cursor = mDownloadManager.query(query);
                if (cursor == null || !cursor.moveToFirst()) continue;

                int totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                int downloadIdx = cursor.getColumnIndex(
                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                int totalSize = cursor.getInt(totalIdx);
                int downloadSize = cursor.getInt(downloadIdx);
                if (totalSize > 0) publishProgress(downloadSize, totalSize);

                int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int status = cursor.getInt(statusIdx);
                if (status == DownloadManager.STATUS_FAILED ||
                        status == DownloadManager.STATUS_SUCCESSFUL) {
                    return status;
                } else if (status == DownloadManager.STATUS_PAUSED) {
                    if (++pausedCount == MAX_PAUSED_COUNT) return status;
                }
            }

            return DownloadManager.STATUS_RUNNING;
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            Log.d(TAG, "DownloadTask updated: " + progress[0] + "/" + progress[1]);
            int percentage = 0;
            if (progress[1] > 0) percentage = (int) (progress[0] * 100.0 / progress[1]);
            mListener.onDownloadUpdated(percentage);
        }

        @Override
        protected void onCancelled(Integer result) {
            mDownloadManager.remove(mDownloadId);

            Log.d(TAG, "DownloadTask cancelled");
            sActiveTask = null;
            mListener.onDownloadCancelled();
        }

        @Override
        protected void onPostExecute(Integer result) {
            Log.d(TAG, "DownloadTask finished, " + result);
            sActiveTask = null;

            if (result == DownloadManager.STATUS_SUCCESSFUL) {
                Uri uri = mDownloadManager.getUriForDownloadedFile(mDownloadId);
                mListener.onDownloadCompleted(uri);
            } else {
                int error = DownloadManager.ERROR_UNKNOWN;
                if (result == DownloadManager.STATUS_FAILED) {
                    Query query = new Query().setFilterById(mDownloadId);
                    Cursor cursor = mDownloadManager.query(query);
                    if (cursor != null && cursor.moveToFirst()) {
                        int reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                        error = cursor.getInt(reasonIdx);
                    }
                }
                mListener.onDownloadFailed(result, error);
            }
        }

        private boolean isSilentDownload() {
            try {
                PackageManager packageManager = mContext.getPackageManager();
                PackageInfo packageInfo = packageManager.getPackageInfo(
                        mContext.getPackageName(), PackageManager.GET_PERMISSIONS);
                return Arrays.asList(packageInfo.requestedPermissions).contains(
                        DOWNLOAD_WITHOUT_NOTIFICATION);
            } catch (NameNotFoundException | NullPointerException e) {
            }
            return false;
        }
    }

    // This is used only in download mode where we want to save the downloaded file to application
    // private storage and it's also intended to solve the exception found in XWALK-5951
    private static class XWalkDownloadTask extends AsyncTask<Void, Integer, Integer> {
        private static final String XWALK_DOWNLOAD_DIR = "xwalk_download";
        private static final int DOWNLOAD_SUCCESS = 0;
        private static final int DOWNLOAD_FAILED = -1;

        private DownloadListener mListener;
        private Context mContext;
        private String mDownloadUrl;
        private File mDownloadedFile;

        XWalkDownloadTask(DownloadListener listener, Context context, String url) {
            super();
            mListener = listener;
            mContext = context;
            mDownloadUrl = url;
        }

        @Override
        protected void onPreExecute() {
            Log.d(TAG, "XWalkDownloadTask started, " + mDownloadUrl);
            sActiveTask = this;

            String savedFile = DEFAULT_DOWNLOAD_FILE_NAME;
            try {
                String name = new File(new URL(mDownloadUrl).getPath()).getName();
                if (!name.isEmpty()) savedFile = name;
            } catch (MalformedURLException | NullPointerException e) {
                Log.e(TAG, "Invalid download URL " + mDownloadUrl);
                mDownloadUrl = null;
                return;
            }
            mDownloadedFile = new File(mContext.getDir(XWALK_DOWNLOAD_DIR, Context.MODE_PRIVATE),
                    savedFile);

            mListener.onDownloadStarted();
        }

        @Override
        protected Integer doInBackground(Void... params) {
            if (mDownloadUrl == null) return DOWNLOAD_FAILED;
            if (mDownloadedFile.exists()) mDownloadedFile.delete();

            InputStream input = null;
            OutputStream output = null;
            HttpURLConnection connection = null;
            try {
                URL url = new URL(mDownloadUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.connect();

                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "Server returned HTTP " + connection.getResponseCode()
                            + " " + connection.getResponseMessage());
                    return DOWNLOAD_FAILED;
                }

                int fileLength = connection.getContentLength();

                input = connection.getInputStream();
                output = new FileOutputStream(mDownloadedFile);

                byte data[] = new byte[4096];
                long total = 0;
                int count;
                while ((count = input.read(data)) != -1) {
                    if (isCancelled()) return DOWNLOAD_FAILED;
                    total += count;
                    publishProgress((int)total, fileLength);
                    output.write(data, 0, count);
                }
                output.flush();
            } catch (Exception e) {
                return DOWNLOAD_FAILED;
            } finally {
                try {
                    if (output != null) output.close();
                    if (input != null) input.close();
                } catch (IOException ignored) {
                }

                if (connection != null) connection.disconnect();
            }
            return DOWNLOAD_SUCCESS;
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            Log.d(TAG, "XWalkDownloadTask updated: " + progress[0] + "/" + progress[1]);
            int percentage = 0;
            if (progress[1] > 0) percentage = (int) (progress[0] * 100.0 / progress[1]);
            mListener.onDownloadUpdated(percentage);
        }

        @Override
        protected void onCancelled(Integer result) {
            Log.d(TAG, "XWalkDownloadTask cancelled");
            sActiveTask = null;
            mListener.onDownloadCancelled();
        }

        @Override
        protected void onPostExecute(Integer result) {
            Log.d(TAG, "XWalkDownloadTask finished, " + result);
            sActiveTask = null;

            if (result == DOWNLOAD_SUCCESS) {
                mListener.onDownloadCompleted(Uri.fromFile(mDownloadedFile));
            } else {
                // Error codes is not used in download mode.
                mListener.onDownloadFailed(DOWNLOAD_FAILED, 0);
            }
        }
    }
}
