package com.hippo.ehviewer.ui;

/**
 * Standard-launch-mode reader used only by the downloaded-gallery queue.
 *
 * <p>The public {@link GalleryActivity} remains singleTask for external and online entry
 * points. Queue transitions need a fresh activity so result forwarding can close the previous
 * reader without returning to the download list.</p>
 */
public class DownloadGalleryActivity extends GalleryActivity {
}
