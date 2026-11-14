package com.hippo.ehviewer.download;

import static org.junit.Assert.*;

import android.database.Cursor;

import com.alibaba.fastjson.JSON;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DownloadDirnameDao;

import org.greenrobot.greendao.database.Database;
import org.greenrobot.greendao.database.DatabaseStatement;
import org.greenrobot.greendao.identityscope.IdentityScopeType;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the actual EhDB merge and greenDAO binding code. SQL is replayed on host SQLite. */
public class DownloadDirnameImportTest {
    @Test public void productionImportPreservesLocalAAndFillsOnlyMissingMappings() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.seed(1, "local-A");
            fixture.seed(2, "");
            fixture.seed(3, null);
            fixture.seed(4, " ");
            fixture.seed(5, "missing-on-this-device");
            fixture.importName(1, "backup-B");
            fixture.importName(2, "backup-B");
            fixture.importName(3, "backup-C");
            fixture.importName(4, "backup-D");
            fixture.importName(5, "backup-E");
            fixture.importName(6, "backup-F");
            fixture.importName(7, null);
            fixture.importName(8, "");
            fixture.importName(1, "backup-B");
            fixture.importName(6, "changed-backup-F");
            assertEquals("local-A", EhDB.getDownloadDirname(1));
            assertEquals("backup-B", EhDB.getDownloadDirname(2));
            assertEquals("backup-C", EhDB.getDownloadDirname(3));
            assertEquals(" ", EhDB.getDownloadDirname(4));
            assertEquals("missing-on-this-device", EhDB.getDownloadDirname(5));
            assertEquals("backup-F", EhDB.getDownloadDirname(6));
            assertFalse(fixture.rows.containsKey(7L));
            assertFalse(fixture.rows.containsKey(8L));
            assertEquals(3, fixture.writeCount);
            fixture.export("merge");
        }
    }

    @Test public void localWriteWaitingOnImportWinsAfterTheMissingMappingCheck() throws Exception {
        runConcurrentWrites(true, "import-first");
    }

    @Test public void importWaitingOnLocalWriteRechecksAndPreservesTheNewMapping() throws Exception {
        runConcurrentWrites(false, "local-first");
    }

    private void runConcurrentWrites(boolean importFirst, String trace) throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch readMissing = new CountDownLatch(1);
            CountDownLatch continueFirst = new CountDownLatch(1);
            CountDownLatch secondStarted = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            fixture.afterNextRead = () -> {
                readMissing.countDown();
                try {
                    assertTrue("first writer was not released", continueFirst.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            };
            Runnable importing = () -> fixture.importName(42, "backup-B");
            Runnable localWriting = () -> EhDB.putDownloadDirname(42, "local-A");
            Thread first = worker(importFirst ? importing : localWriting, failure);
            Thread second = worker(() -> {
                secondStarted.countDown();
                (importFirst ? localWriting : importing).run();
            }, failure);
            try {
                first.start();
                assertTrue("production read did not start", readMissing.await(5, TimeUnit.SECONDS));
                second.start();
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (second.getState() != Thread.State.BLOCKED && second.isAlive()
                        && System.nanoTime() < deadline) Thread.yield();
                assertEquals("other writer must wait for the EhDB monitor", Thread.State.BLOCKED,
                        second.getState());
                assertFalse(fixture.rows.containsKey(42L));
            } finally {
                continueFirst.countDown();
                first.join(5_000);
                if (second.getState() != Thread.State.NEW) second.join(5_000);
            }
            assertFalse("first writer did not finish", first.isAlive());
            assertFalse("second writer did not finish", second.isAlive());
            if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
            assertEquals("local-A", EhDB.getDownloadDirname(42));
            assertEquals(importFirst ? 2 : 1, fixture.writeCount);
            fixture.export(trace);
        }
    }

    private static Thread worker(Runnable action, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try { action.run(); } catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "dirname-import-test");
        thread.setDaemon(true);
        return thread;
    }

    /** Only emulates database primitives; all merge decisions and DAO SQL/bindings are production. */
    private static final class Fixture implements AutoCloseable {
        final Map<Long, String> rows = new LinkedHashMap<>();
        final List<Object[]> initial = new ArrayList<>();
        final List<String> schema = new ArrayList<>();
        final List<Map<String, Object>> operations = new ArrayList<>();
        final Field sessionField;
        final Object oldSession;
        final Method importer;
        Runnable afterNextRead;
        int writeCount;

        Fixture() throws Exception {
            sessionField = EhDB.class.getDeclaredField("sDaoSession");
            sessionField.setAccessible(true);
            importer = EhDB.class.getDeclaredMethod("importDownloadDirname", long.class, String.class);
            importer.setAccessible(true);
            Database db = (Database) Proxy.newProxyInstance(Database.class.getClassLoader(),
                    new Class<?>[]{Database.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getRawDatabase": return null;
                            case "execSQL": schema.add((String) args[0]); return null;
                            case "compileStatement": return statement((String) args[0]);
                            case "rawQuery": return query((String) args[0], (String[]) args[1]);
                            case "isDbLockedByCurrentThread": case "inTransaction": return false;
                            case "beginTransaction": case "setTransactionSuccessful":
                            case "endTransaction": case "close": return null;
                            default: throw new AssertionError("Unexpected DB call: " + method);
                        }
                    });
            DownloadDirnameDao.createTable(db, false);
            synchronized (EhDB.class) {
                oldSession = sessionField.get(null);
                sessionField.set(null, new DaoMaster(db).newSession(IdentityScopeType.None));
            }
        }

        void seed(long gid, String dirname) {
            rows.put(gid, dirname);
            initial.add(new Object[]{gid, dirname});
        }

        void importName(long gid, String dirname) {
            try { importer.invoke(null, gid, dirname); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }

        Cursor query(String sql, String[] args) {
            assertTrue(sql, sql.contains("DOWNLOAD_DIRNAME"));
            long gid = Long.parseLong(args[0]);
            Object[] row = rows.containsKey(gid) ? new Object[]{gid, rows.get(gid)} : null;
            Map<String, Object> operation = operation("query", sql, args);
            operation.put("result", row == null ? Collections.emptyList() : Collections.singletonList(row));
            operations.add(operation);
            Runnable hook = afterNextRead;
            afterNextRead = null;
            return (Cursor) Proxy.newProxyInstance(Cursor.class.getClassLoader(),
                    new Class<?>[]{Cursor.class}, (proxy, method, values) -> {
                        switch (method.getName()) {
                            case "moveToFirst": case "isLast": return row != null;
                            case "getCount": return row == null ? 0 : 1;
                            case "getLong": return ((Number) row[(int) values[0]]).longValue();
                            case "getString": return (String) row[(int) values[0]];
                            case "isNull": return row[(int) values[0]] == null;
                            case "close": if (hook != null) hook.run(); return null;
                            default: throw new AssertionError("Unexpected cursor call: " + method);
                        }
                    });
        }

        DatabaseStatement statement(String sql) {
            assertTrue(sql, sql.contains("DOWNLOAD_DIRNAME"));
            boolean insert = sql.startsWith("INSERT");
            assertTrue(sql, insert || sql.startsWith("UPDATE"));
            Object[] bindings = new Object[insert ? 2 : 3];
            return (DatabaseStatement) Proxy.newProxyInstance(DatabaseStatement.class.getClassLoader(),
                    new Class<?>[]{DatabaseStatement.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "clearBindings": Arrays.fill(bindings, null); return null;
                            case "bindLong": case "bindString":
                                bindings[(int) args[0] - 1] = args[1]; return null;
                            case "bindNull": bindings[(int) args[0] - 1] = null; return null;
                            case "execute": case "executeInsert":
                                long gid = ((Number) bindings[0]).longValue();
                                if (insert) assertFalse("duplicate insert", rows.containsKey(gid));
                                else {
                                    assertEquals(gid, ((Number) bindings[2]).longValue());
                                    assertTrue("missing update", rows.containsKey(gid));
                                }
                                rows.put(gid, (String) bindings[1]);
                                operations.add(operation("write", sql, bindings.clone()));
                                writeCount++;
                                return method.getName().equals("executeInsert") ? gid : null;
                            case "close": return null;
                            default: throw new AssertionError("Unexpected statement call: " + method);
                        }
                    });
        }

        Map<String, Object> operation(String kind, String sql, Object[] args) {
            Map<String, Object> operation = new LinkedHashMap<>();
            operation.put("kind", kind);
            operation.put("sql", sql);
            operation.put("args", args);
            return operation;
        }

        void export(String name) throws Exception {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("schema", schema);
            trace.put("initial", initial);
            trace.put("operations", operations);
            List<Object[]> expected = new ArrayList<>();
            rows.forEach((gid, dirname) -> expected.add(new Object[]{gid, dirname}));
            trace.put("expected", expected);
            Path target = Paths.get("build", "reports", "dirname-import", name + ".json");
            Files.createDirectories(target.getParent());
            Files.write(target, JSON.toJSONString(trace).getBytes(StandardCharsets.UTF_8));
        }

        @Override public void close() throws Exception {
            synchronized (EhDB.class) { sessionField.set(null, oldSession); }
        }
    }
}
