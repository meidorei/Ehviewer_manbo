package com.hippo.ehviewer.subscription;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.EhDB;

import org.greenrobot.greendao.database.Database;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class DatabaseLockContractTest {
    @Test
    public void databaseAccessorDoesNotParticipateInEhDbClassLock() throws Exception {
        Field session = EhDB.class.getDeclaredField("sDaoSession");
        Method accessor = EhDB.class.getDeclaredMethod("getDatabase");

        assertTrue(Modifier.isVolatile(session.getModifiers()));
        assertFalse(Modifier.isSynchronized(accessor.getModifiers()));
    }

    @Test
    public void transactionHelpersAcceptTheExistingDatabaseHandle() throws Exception {
        Method advanceCheckpoint = SubscriptionRepository.class.getDeclaredMethod(
                "advanceCheckpoint", Database.class, CheckpointKey.class, FeedBoundary.class);
        Method readState = LocalFollowRepository.class.getDeclaredMethod(
                "readState", Database.class, String.class, String.class, String.class);
        Method writeState = LocalFollowRepository.class.getDeclaredMethod(
                "writeState", Database.class, String.class, String.class, String.class,
                int.class, TagUpdateState.State.class, String.class);

        assertFalse(Modifier.isPublic(advanceCheckpoint.getModifiers()));
        assertFalse(Modifier.isPublic(readState.getModifiers()));
        assertFalse(Modifier.isPublic(writeState.getModifiers()));
    }

    @Test
    public void emptyCheckpointUpdatesRemainDatabaseFreeNoOps() {
        SubscriptionRepository repository = SubscriptionRepository.getInstance();

        repository.advanceCheckpoint(null, null);
        repository.establishCheckpoint(null, null);
    }
}
