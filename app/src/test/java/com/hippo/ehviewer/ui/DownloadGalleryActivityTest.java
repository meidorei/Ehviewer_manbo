package com.hippo.ehviewer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

public class DownloadGalleryActivityTest {

    private static final String ANDROID_NAMESPACE =
            "http://schemas.android.com/apk/res/android";

    @Test
    public void downloadReaderUsesDedicatedStandardActivity() throws Exception {
        assertEquals(GalleryActivity.class, DownloadGalleryActivity.class.getSuperclass());

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document manifest = factory.newDocumentBuilder().parse(findManifest().toFile());
        Element regularReader = findActivity(manifest, ".ui.GalleryActivity");
        Element downloadReader = findActivity(manifest, ".ui.DownloadGalleryActivity");

        assertNotNull(regularReader);
        assertNotNull(downloadReader);
        assertEquals("singleTask",
                regularReader.getAttributeNS(ANDROID_NAMESPACE, "launchMode"));
        assertEquals("standard",
                downloadReader.getAttributeNS(ANDROID_NAMESPACE, "launchMode"));
        assertEquals("false",
                downloadReader.getAttributeNS(ANDROID_NAMESPACE, "exported"));
    }

    private static Path findManifest() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path moduleManifest = workingDirectory.resolve("src/main/AndroidManifest.xml");
        if (Files.isRegularFile(moduleManifest)) {
            return moduleManifest;
        }
        return workingDirectory.resolve("app/src/main/AndroidManifest.xml");
    }

    private static Element findActivity(Document manifest, String className) {
        NodeList activities = manifest.getElementsByTagName("activity");
        for (int i = 0; i < activities.getLength(); i++) {
            Element activity = (Element) activities.item(i);
            if (className.equals(activity.getAttributeNS(ANDROID_NAMESPACE, "name"))) {
                return activity;
            }
        }
        return null;
    }
}
