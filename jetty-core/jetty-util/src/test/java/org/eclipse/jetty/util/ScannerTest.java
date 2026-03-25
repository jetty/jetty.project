//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.awaitility.Awaitility;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Scanner.Notification;
import org.eclipse.jetty.util.component.LifeCycle;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.toolchain.test.ExtraMatchers.ordered;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
@Isolated
public class ScannerTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ScannerTest.class);
    public WorkDir workDir;
    private Scanner _scanner;

    @AfterEach
    public void cleanup() throws Exception
    {
        LifeCycle.stop(_scanner);
    }

    record Event(String filename, Scanner.Notification notification)
    {
    }

    @Test
    public void testDiscreteDepth() throws Exception
    {
        Path root = workDir.getEmptyPathDir().resolve("root");
        FS.ensureDirExists(root);
        touch(root.resolve("foo.foo"));
        touch(root.resolve("foo2.foo"));
        Path dir = root.resolve("xxx");
        FS.ensureDirExists(dir);
        touch(dir.resolve("xxx.foo"));
        touch(dir.resolve("xxx2.foo"));
        Path dir2 = dir.resolve("yyy");
        FS.ensureDirExists(dir2);
        touch(dir2.resolve("yyy.foo"));
        touch(dir2.resolve("yyy2.foo"));

        DiscreteQueueListener queue = new DiscreteQueueListener();
        _scanner = new Scanner();
        _scanner.setScanInterval(0);
        _scanner.setScanDepth(0);
        _scanner.setReportDirs(true);
        _scanner.setReportExistingFilesOnStartup(true);
        _scanner.addDirectory(root);
        _scanner.addListener(queue);

        _scanner.start();
        Event e = queue.take();
        assertNotNull(e);
        assertEquals(Notification.ADDED, e.notification);
        assertTrue(e.filename.endsWith(root.getFileName().toString()));
        queue.clear();
        _scanner.stop();
        _scanner.reset();

        //Depth one should report the dir itself and its file and dir direct children
        _scanner.setScanDepth(1);
        _scanner.addDirectory(root);
        _scanner.start();
        assertEquals(4, queue.size());
        queue.clear();
        _scanner.stop();
        _scanner.reset();

        //Depth 2 should report the dir itself, all file children, xxx and xxx's children
        _scanner.setScanDepth(2);
        _scanner.addDirectory(root);
        _scanner.start();

        assertEquals(7, queue.size());
        _scanner.stop();
    }

    @Test
    public void testDiscretePatterns() throws Exception
    {
        // test include and exclude patterns
        Path root = workDir.getEmptyPathDir().resolve("proot");
        FS.ensureDirExists(root);

        Path ttt = root.resolve("ttt.txt");
        touch(ttt);
        touch(root.resolve("ttt.foo"));
        Path dir = root.resolve("xxx");
        FS.ensureDirExists(dir);

        Path x1 = dir.resolve("ttt.xxx");
        touch(x1);
        Path x2 = dir.resolve("xxx.txt");
        touch(x2);

        Path dir2 = dir.resolve("yyy");
        FS.ensureDirExists(dir2);
        Path y1 = dir2.resolve("ttt.yyy");
        touch(y1);
        Path y2 = dir2.resolve("yyy.txt");
        touch(y2);

        DiscreteQueueListener queue = new DiscreteQueueListener();
        //only scan the *.txt files for changes
        _scanner = new Scanner();
        IncludeExcludeSet<PathMatcher, Path> pattern = _scanner.addDirectory(root);
        pattern.exclude(root.getFileSystem().getPathMatcher("glob:**/*.foo"));
        pattern.exclude(root.getFileSystem().getPathMatcher("glob:**/ttt.xxx"));
        _scanner.setScanInterval(0);
        _scanner.setScanDepth(2); //should never see any files from subdir yyy
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(queue);

        _scanner.start();
        assertTrue(queue.isEmpty());

        Thread.sleep(1100); // make sure time in seconds changes
        touch(ttt);
        touch(x2);
        touch(x1);
        touch(y2);
        _scanner.scan();
        _scanner.scan(); //2 scans for file to be considered settled

        List<Event> results = new ArrayList<>();
        queue.drainTo(results);
        assertThat(results.size(), Matchers.equalTo(2));
        for (Event e : results)
        {
            assertTrue(e.filename.endsWith("ttt.txt") || e.filename.endsWith("xxx.txt"));
        }
    }

    @Test
    public void testDiscreteAddedChangeRemove() throws Exception
    {
        Path directory = workDir.getEmptyPathDir();
        DiscreteQueueListener discreteQueue = new DiscreteQueueListener();
        _scanner = new Scanner();
        _scanner.addDirectory(directory);
        _scanner.setScanInterval(0);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(discreteQueue);
        _scanner.start();
        _scanner.scan();
        Path fileA0 = directory.resolve("a0");
        touch(fileA0);

        // -- takes 2 scans to notice a0 and check that it is stable
        _scanner.scan();
        _scanner.scan();

        Event event = discreteQueue.poll(5, TimeUnit.SECONDS);
        assertNotNull(event, "Event should not be null");
        assertEquals(fileA0.toString(), event.filename);
        assertEquals(Notification.ADDED, event.notification);

        // -- add 3 more files
        Path fileA1 = directory.resolve("a1");
        Path fileA2 = directory.resolve("a2");
        Path fileA3 = directory.resolve("a3");
        touch(fileA1, (ft) -> ft + 1100);
        touch(fileA2, (ft) -> ft + 1100);
        touch(fileA3, (ft) -> ft + 1100);

        // -- not stable after 1 scan so should not be seen yet
        _scanner.scan();
        event = discreteQueue.poll();
        assertNull(event);

        // -- Keep a2 unstable and remove a3 before it stabilized
        touch(fileA2, (ft) -> ft + 1100);
        delete(fileA3);

        // -- only a1 is stable so it should be seen, a3 is deleted
        _scanner.scan();
        List<Event> actualEvents = new ArrayList<>();
        discreteQueue.drainTo(actualEvents);
        assertEquals(2, actualEvents.size());
        Event a1 = new Event(fileA1.toString(), Notification.ADDED);
        Event a3 = new Event(fileA3.toString(), Notification.REMOVED);
        assertThat(actualEvents, containsInAnyOrder(a1, a3));
        assertTrue(discreteQueue.isEmpty());

        // -- Now a2 is stable
        _scanner.scan();
        event = discreteQueue.poll();
        assertNotNull(event);
        assertEquals(fileA2.toString(), event.filename);
        assertEquals(Notification.ADDED, event.notification);
        assertTrue(discreteQueue.isEmpty());

        // -- touch a1 and a2
        touch(fileA1, (ft) -> ft + 1100);
        touch(fileA2, (ft) -> ft + 1100);
        // -- not stable after 1scan so nothing should not be seen yet
        _scanner.scan();
        event = discreteQueue.poll();
        assertNull(event);

        // -- Keep a2 unstable
        touch(fileA2, (ft) -> ft + 1100);

        // -- only a1 is stable so it should be seen
        _scanner.scan();
        event = discreteQueue.poll();
        assertNotNull(event);
        assertEquals(fileA1.toString(), event.filename);
        assertEquals(Notification.CHANGED, event.notification);
        assertTrue(discreteQueue.isEmpty());

        // -- Now a2 is stable
        _scanner.scan();
        event = discreteQueue.poll();
        assertNotNull(event);
        assertEquals(fileA2.toString(), event.filename);
        assertEquals(Notification.CHANGED, event.notification);
        assertTrue(discreteQueue.isEmpty());

        // -- delete a1 and a2
        delete(fileA1);
        delete(fileA2);

        // -- Immediate notification of deletes
        _scanner.scan();
        a1 = new Event(fileA1.toString(), Notification.REMOVED);
        Event a2 = new Event(fileA2.toString(), Notification.REMOVED);
        actualEvents = new ArrayList<>();
        discreteQueue.drainTo(actualEvents);
        assertEquals(2, actualEvents.size());
        assertThat(actualEvents, containsInAnyOrder(a1, a2));
        assertTrue(discreteQueue.isEmpty());

        // -- recreate a2
        touch(fileA2, (ft) -> ft + 1100);

        // -- a2 not stable yet, shouldn't be seen
        _scanner.scan();
        event = discreteQueue.poll();
        assertNull(event);
        assertTrue(discreteQueue.isEmpty());

        // -- Now a2 is reported as ADDED
        _scanner.scan();
        event = discreteQueue.poll();
        assertNotNull(event);
        assertEquals(fileA2.toString(), event.filename);
        assertEquals(Notification.ADDED, event.notification);
        assertTrue(discreteQueue.isEmpty());
    }

    @Test
    public void testDiscreteSizeChange() throws Exception
    {
        Path directory = workDir.getEmptyPathDir();
        DiscreteQueueListener discreteQueue = new DiscreteQueueListener();
        _scanner = new Scanner();
        _scanner.addDirectory(directory);
        _scanner.setScanInterval(0);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(discreteQueue);
        _scanner.start();
        _scanner.scan();
        Path fileTsc0 = directory.resolve("tsc0");
        touch(fileTsc0);

        _scanner.scan();
        _scanner.scan();

        // takes 2 scans to notice tsc0 and check that it is stable.
        Event event = discreteQueue.poll();
        assertNotNull(event);
        assertEquals(fileTsc0.toString(), event.filename);
        assertEquals(Notification.ADDED, event.notification);

        // Create a new file by writing to it.
        FileTime now = FileTime.from(Instant.now());
        Path fileSt = directory.resolve("st");
        try (OutputStream out = Files.newOutputStream(fileSt, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND))
        {
            out.write('x');
            out.flush();
            touch(fileSt);
            Files.setLastModifiedTime(fileSt, now);

            // Not stable yet so no notification.
            _scanner.scan();
            event = discreteQueue.poll();
            assertNull(event);

            // Modify size only
            out.write('x');
            out.flush();
            Files.setLastModifiedTime(fileSt, now);

            // Still not stable yet so no notification.
            _scanner.scan();
            event = discreteQueue.poll();
            assertNull(event);

            // now stable so finally see the ADDED
            _scanner.scan();
            event = discreteQueue.poll();
            assertNotNull(event);
            assertEquals(directory.resolve("st").toString(), event.filename);
            assertEquals(Notification.ADDED, event.notification);

            // Modify size only
            out.write('x');
            out.flush();
            Files.setLastModifiedTime(fileSt, now);

            // Still not stable yet so no notification.
            _scanner.scan();
            event = discreteQueue.poll();
            assertNull(event);

            // now stable so finally see the ADDED
            _scanner.scan();
            event = discreteQueue.poll();
            assertNotNull(event);
            assertEquals(fileSt.toString(), event.filename);
            assertEquals(Notification.CHANGED, event.notification);
        }
    }

    @Test
    public void testBulkPathsAdd() throws Exception
    {
        Path directory = workDir.getEmptyPathDir();
        BulkPathsQueueListener bulkQueue = new BulkPathsQueueListener();
        _scanner = new Scanner();
        _scanner.addDirectory(directory);
        _scanner.setScanInterval(0);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(bulkQueue);
        _scanner.start();
        _scanner.scan();

        Path fileA0 = directory.resolve("A0");
        Path fileA1 = directory.resolve("A1");
        touch(fileA0);
        touch(fileA1);

        _scanner.scan();
        _scanner.scan();
        Set<Path> paths = bulkQueue.poll();
        assertThat(paths, containsInAnyOrder(fileA0, fileA1));
    }

    @Test
    public void testBulkPathsAddRemove() throws Exception
    {
        Path directory = workDir.getEmptyPathDir();
        BulkPathsQueueListener bulkQueue = new BulkPathsQueueListener();
        _scanner = new Scanner();
        _scanner.addDirectory(directory);
        _scanner.setScanInterval(0);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(bulkQueue);
        _scanner.start();
        _scanner.scan();

        // -- create A0 and A1
        Path fileA0 = directory.resolve("A0");
        Path fileA1 = directory.resolve("A1");
        touch(fileA0);
        touch(fileA1);

        _scanner.scan();
        _scanner.scan();
        Set<Path> paths = bulkQueue.poll();
        assertThat(paths, containsInAnyOrder(fileA0, fileA1));

        // -- delete A0
        delete(fileA0);
        _scanner.scan();

        paths = bulkQueue.poll();
        assertThat(paths, containsInAnyOrder(fileA0));
    }

    @Test
    public void testBulkPathsAddChange() throws Exception
    {
        Path directory = workDir.getEmptyPathDir();
        BulkPathsQueueListener bulkQueue = new BulkPathsQueueListener();
        _scanner = new Scanner();
        _scanner.addDirectory(directory);
        _scanner.setScanInterval(0);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(bulkQueue);
        _scanner.start();
        _scanner.scan();

        // -- create A0 and A1
        Path fileA0 = directory.resolve("A0");
        Path fileA1 = directory.resolve("A1");
        touch(fileA0);
        touch(fileA1);

        _scanner.scan();
        _scanner.scan();
        Set<Path> paths = bulkQueue.poll();
        assertThat(paths, containsInAnyOrder(fileA0, fileA1));

        // -- touch A0
        touch(fileA0, (ft) -> ft + 1100);

        paths = Awaitility.await().atMost(5, TimeUnit.SECONDS)
            .until(() ->
            {
                _scanner.scan();
                return bulkQueue.poll();
            },
                (pathSet) ->
                    !pathSet.isEmpty()
            );

        assertThat(paths, containsInAnyOrder(fileA0));
    }

    @Test
    public void testBulkFilesAdd() throws Exception
    {
        Path directory = workDir.getEmptyPathDir();
        BulkFilesQueueListener bulkQueue = new BulkFilesQueueListener();
        _scanner = new Scanner();
        _scanner.addDirectory(directory);
        _scanner.setScanInterval(0);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(bulkQueue);
        _scanner.start();
        _scanner.scan();

        Path fileA0 = directory.resolve("A0");
        Path fileA1 = directory.resolve("A1");
        touch(fileA0);
        touch(fileA1);

        _scanner.scan();
        _scanner.scan();
        Set<String> files = bulkQueue.poll();
        assertThat(files, containsInAnyOrder(fileA0.toString(), fileA1.toString()));
    }

    @Test
    public void testBulkFilesAddRemove() throws Exception
    {
        Path directory = workDir.getEmptyPathDir();
        BulkFilesQueueListener bulkQueue = new BulkFilesQueueListener();
        _scanner = new Scanner();
        _scanner.addDirectory(directory);
        _scanner.setScanInterval(0);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(bulkQueue);
        _scanner.start();
        _scanner.scan();

        // -- create A0 and A1
        Path fileA0 = directory.resolve("A0");
        Path fileA1 = directory.resolve("A1");
        touch(fileA0);
        touch(fileA1);

        _scanner.scan();
        _scanner.scan();
        Set<String> files = bulkQueue.poll();
        assertThat(files, containsInAnyOrder(fileA0.toString(), fileA1.toString()));

        // -- delete A0
        delete(fileA0);
        _scanner.scan();

        files = bulkQueue.poll();
        assertThat(files, containsInAnyOrder(fileA0.toString()));
    }

    @Test
    public void testBulkFilesAddChange() throws Exception
    {
        Path directory = workDir.getEmptyPathDir();
        BulkFilesQueueListener bulkQueue = new BulkFilesQueueListener();
        _scanner = new Scanner();
        _scanner.addDirectory(directory);
        _scanner.setScanInterval(0);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(bulkQueue);
        _scanner.start();
        _scanner.scan();

        // -- create A0 and A1
        Path fileA0 = directory.resolve("A0");
        Path fileA1 = directory.resolve("A1");
        touch(fileA0);
        touch(fileA1);

        _scanner.scan();
        _scanner.scan();
        Set<String> files = bulkQueue.poll();
        assertThat(files, containsInAnyOrder(fileA0.toString(), fileA1.toString()));

        // -- touch A0
        touch(fileA0);
        _scanner.scan();

        files = bulkQueue.poll();
        assertThat("The changes to A0 are not stable yet", files, nullValue());

        _scanner.scan();
        files = bulkQueue.poll();
        assertThat(files, containsInAnyOrder(fileA0.toString()));
    }

    @Test
    public void testBulkPathsChangedAdd() throws Exception
    {
        Path directory = workDir.getEmptyPathDir();
        PathsChangedQueueListener pathsChangedQueue = new PathsChangedQueueListener();
        _scanner = new Scanner();
        _scanner.addDirectory(directory);
        _scanner.setScanInterval(0);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(pathsChangedQueue);
        _scanner.start();
        _scanner.scan();

        Path fileA0 = directory.resolve("A0");
        Path fileA1 = directory.resolve("A1");
        touch(fileA0);
        touch(fileA1);

        _scanner.scan();
        _scanner.scan();
        List<String> actualChanges = pathsChangedQueue.pollOrderedChanges();
        List<String> expected = List.of(
            "ADDED|" + fileA0,
            "ADDED|" + fileA1
        );
        assertThat(actualChanges, ordered(expected));
    }

    @Test
    public void testBulkPathsChangedAddRemove() throws Exception
    {
        Path directory = workDir.getEmptyPathDir();
        PathsChangedQueueListener pathsChangedQueue = new PathsChangedQueueListener();
        _scanner = new Scanner();
        _scanner.addDirectory(directory);
        _scanner.setScanInterval(0);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(pathsChangedQueue);
        _scanner.start();
        _scanner.scan();

        // -- create A0 and A1
        Path fileA0 = directory.resolve("A0");
        Path fileA1 = directory.resolve("A1");
        touch(fileA0);
        touch(fileA1);

        _scanner.scan();
        _scanner.scan();
        List<String> actualChanges = pathsChangedQueue.pollOrderedChanges();
        List<String> expected = List.of(
            "ADDED|" + fileA0,
            "ADDED|" + fileA1
        );
        assertThat(actualChanges, ordered(expected));

        // -- delete A0
        delete(fileA0);
        _scanner.scan();

        actualChanges = pathsChangedQueue.pollOrderedChanges();
        expected = List.of(
            "REMOVED|" + fileA0
        );
        assertThat(actualChanges, ordered(expected));
    }

    @Test
    public void testBulkPathsChangedAddChange() throws Exception
    {
        Path directory = workDir.getEmptyPathDir();
        PathsChangedQueueListener pathsChangedQueue = new PathsChangedQueueListener();
        _scanner = new Scanner();
        _scanner.addDirectory(directory);
        _scanner.setScanInterval(0);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(pathsChangedQueue);
        _scanner.start();
        _scanner.scan();

        // -- create A0 and A1
        Path fileA0 = directory.resolve("A0");
        Path fileA1 = directory.resolve("A1");
        touch(fileA0);
        touch(fileA1);

        _scanner.scan();
        _scanner.scan();
        List<String> actualChanges = pathsChangedQueue.pollOrderedChanges();
        List<String> expected = List.of(
            "ADDED|" + fileA0,
            "ADDED|" + fileA1
        );
        assertThat(actualChanges, ordered(expected));

        // -- touch A0
        touch(fileA0, (ft) -> ft + 1100);

        actualChanges = Awaitility.await().atMost(5, TimeUnit.SECONDS)
            .until(() ->
                {
                    _scanner.scan();
                    return pathsChangedQueue.pollOrderedChanges();
                },
                Objects::nonNull);

        expected = List.of(
            "CHANGED|" + fileA0
        );
        assertThat(actualChanges, ordered(expected));
    }

    @Test
    public void testBulkPathsChangedAddChangeRemove() throws Exception
    {
        Path directory = workDir.getEmptyPathDir();
        PathsChangedQueueListener pathsChangedQueue = new PathsChangedQueueListener();
        _scanner = new Scanner();
        _scanner.addDirectory(directory);
        _scanner.setScanInterval(0);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(pathsChangedQueue);
        _scanner.start();
        _scanner.scan();

        // -- create A0 and A1
        Path fileA0 = directory.resolve("A0");
        Path fileA1 = directory.resolve("A1");
        touch(fileA0);
        touch(fileA1);

        _scanner.scan();
        _scanner.scan();
        List<String> actualChanges = pathsChangedQueue.pollOrderedChanges();
        List<String> expected = List.of(
            "ADDED|" + fileA0,
            "ADDED|" + fileA1
        );
        assertThat(actualChanges, ordered(expected));

        // -- touch A0, and add B2
        touch(fileA0);
        Path fileB2 = directory.resolve("B2");
        touch(fileB2);
        _scanner.scan();

        actualChanges = pathsChangedQueue.pollOrderedChanges();
        assertThat("The changes to A0 and B2 are not stable yet", actualChanges, nullValue());

        // -- remove A1
        delete(fileA1);

        _scanner.scan();
        actualChanges = pathsChangedQueue.pollOrderedChanges();
        expected = List.of(
            "ADDED|" + fileB2,
            "CHANGED|" + fileA0,
            "REMOVED|" + fileA1
        );
        assertThat(actualChanges, ordered(expected));
    }

    private static void delete(Path path)
    {
        FS.deleteFile(path);
    }

    private static void touch(Path file) throws IOException
    {
        touch(file, (ft) -> ft + 1L);
    }

    private static void touch(Path file, Function<Long, Long> fileTimeDelta) throws IOException
    {
        if (Files.exists(file))
        {
            FileTime timeOrig = Files.getLastModifiedTime(file);
            long newTime = fileTimeDelta.apply(timeOrig.toMillis());
            Files.setLastModifiedTime(file, FileTime.from(newTime, TimeUnit.MILLISECONDS));
            FileTime timeNow = Files.getLastModifiedTime(file);
            // Verify that timestamp was actually updated.
            assertThat("Timestamp updated", timeOrig, not(equalTo(timeNow)));
        }
        else
        {
            Files.createFile(file);
            assertTrue(Files.exists(file), "Created new file?: " + file);
        }
    }

    private static class DiscreteQueueListener extends LinkedBlockingQueue<Event> implements Scanner.DiscreteListener
    {
        private static final Logger LOG = LoggerFactory.getLogger(ScannerTest.LOG.getName() + "." + DiscreteQueueListener.class.getSimpleName());

        @Override
        public void fileRemoved(String filename)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("fileRemoved: {}", filename);
            this.add(new Event(filename, Notification.REMOVED));
        }

        @Override
        public void fileChanged(String filename)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("fileChanged: {}", filename);
            this.add(new Event(filename, Notification.CHANGED));
        }

        @Override
        public void fileAdded(String filename)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("fileAdded: {}", filename);
            this.add(new Event(filename, Notification.ADDED));
        }
    }

    @SuppressWarnings("removal")
    @Deprecated // remove this test class in the future
    private static class BulkPathsQueueListener extends LinkedBlockingQueue<Set<Path>> implements Scanner.BulkListener
    {
        private static final Logger LOG = LoggerFactory.getLogger(ScannerTest.LOG.getName() + "." + BulkPathsQueueListener.class.getSimpleName());

        @Override
        public void pathsChanged(Set<Path> paths)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("pathsChanged: {}", paths);
            add(paths);
        }

        @Override
        public void filesChanged(Set<String> filenames)
        {
            // not interested about this method
        }
    }

    @SuppressWarnings("removal")
    @Deprecated // remove this test class in the future
    private static class BulkFilesQueueListener extends LinkedBlockingQueue<Set<String>> implements Scanner.BulkListener
    {
        private static final Logger LOG = LoggerFactory.getLogger(ScannerTest.LOG.getName() + "." + BulkFilesQueueListener.class.getSimpleName());

        @Override
        public void filesChanged(Set<String> filenames)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("filesChanged: {}", filenames);
            add(filenames);
        }
    }

    private static class PathsChangedQueueListener extends LinkedBlockingQueue<Map<Path, Notification>> implements Scanner.BulkListener
    {
        private static final Logger LOG = LoggerFactory.getLogger(ScannerTest.LOG.getName() + "." + PathsChangedQueueListener.class.getSimpleName());

        @Override
        public void pathsChanged(Map<Path, Notification> pathsChanged)
        {
            if (LOG.isDebugEnabled())
                LOG.atDebug().addArgument(pathsChanged).log("pathsChanged: {}");
            add(pathsChanged);
        }

        public List<String> pollOrderedChanges()
        {
            Map<Path, Notification> pathsChanged = poll();
            if (pathsChanged == null)
                return null;
            return pathsChanged.entrySet().stream()
                .map(e -> String.format("%s|%s", e.getValue(), e.getKey()))
                .sorted()
                .toList();
        }
    }
}
