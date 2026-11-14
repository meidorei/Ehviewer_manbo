package com.hippo.ehviewer.ui;

import static org.junit.Assert.assertEquals;

import com.hippo.ehviewer.ui.scene.gallery.list.BookmarkDiagnosticsScene;
import com.hippo.scene.SceneFragment;
import com.hippo.scene.StageActivity;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;

public class MainActivitySceneRegistrationTest {
    @Test public void bookmarkDiagnosticsSceneIsRegistered() throws Exception {
        Class.forName(MainActivity.class.getName());
        Field launchModes = StageActivity.class.getDeclaredField("sLaunchModeMap");
        launchModes.setAccessible(true);
        Map<?, ?> registered = (Map<?, ?>) launchModes.get(null);
        assertEquals(SceneFragment.LAUNCH_MODE_SINGLE_TASK,
                registered.get(BookmarkDiagnosticsScene.class));
    }
}
