//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Scanner.Notification;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.DisabledOnOs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

public class ScannerTest
{
    static File _directory;
    static Scanner _scanner;
    static BlockingQueue<Event> _queue = new LinkedBlockingQueue<Event>();
    static BlockingQueue<List<String>> _bulk = new LinkedBlockingQueue<List<String>>();

    @BeforeAll
    public static void setUpBeforeClass() throws Exception
    {
        File testDir = MavenTestingUtils.getTargetTestingDir(ScannerTest.class.getSimpleName());
        FS.ensureEmpty(testDir);

        // Use full path, pointing to a real directory (for FileSystems that are case-insensitive, like Windows and OSX to use)
        // This is only needed for the various comparisons below to make sense.
        _directory = testDir.toPath().toRealPath().toFile();

        _scanner = new Scanner();
        _scanner.addScanDir(_directory);
        _scanner.setScanInterval(0);
        _scanner.setReportDirs(false);
        _scanner.setReportExistingFilesOnStartup(false);
        _scanner.addListener(new Scanner.DiscreteListener()
        {
            @Override
            public void fileRemoved(String filename) throws Exception
            {
                _queue.add(new Event(filename, Notification.REMOVED));
            }

            @Override
            public void fileChanged(String filename) throws Exception
            {
                _queue.add(new Event(filename, Notification.CHANGED));
            }

            @Override
            public void fileAdded(String filename) throws Exception
            {
                _queue.add(new Event(filename, Notification.ADDED));
            }
        });
        _scanner.addListener(new Scanner.BulkListener()
        {
            @Override
            public void filesChanged(List<String> filenames) throws Exception
            {
                _bulk.add(filenames);
            }
        });

        _scanner.start();
        _scanner.scan();

        assertTrue(_queue.isEmpty());
        assertTrue(_bulk.isEmpty());
    }

    @AfterAll
    public static void tearDownAfterClass() throws Exception
    {
        _scanner.stop();
        IO.delete(_directory);
    }

    static class Event
    {
        String _filename;
        Scanner.Notification _notification;

        public Event(String filename, Notification notification)
        {
            _filename = filename;
            _notification = notification;
        }
    }

    @Test
    public void testDepth() throws Exception
    {
        File root = new File(_directory, "root");
        FS.ensureDirExists(root);
        FS.touch(new File(root, "foo.foo"));
        FS.touch(new File(root, "foo2.foo"));
        File dir = new File(root, "xxx");
        FS.ensureDirExists(dir);
        File x1 = new File(dir, "xxx.foo");
        FS.touch(x1);
        File x2 = new File(dir, "xxx2.foo");
        FS.touch(x2);
        File dir2 = new File(dir, "yyy");
        FS.ensureDirExists(dir2);
        File y1 = new File(dir2, "yyy.foo");
        FS.touch(y1);
        File y2 = new File(dir2, "yyy2.foo");
        FS.touch(y2);

        BlockingQueue<Event> queue = new LinkedBlockingQueue<Event>();
        Scanner scanner = new Scanner();
        scanner.setScanInterval(0);
        scanner.setScanDepth(0);
        scanner.setReportDirs(true);
        scanner.setReportExistingFilesOnStartup(true);
        scanner.addDirectory(root.toPath());
        scanner.addListener(new Scanner.DiscreteListener()
        {
            @Override
            public void fileRemoved(String filename) throws Exception
            {
                queue.add(new Event(filename, Notification.REMOVED));
            }

            @Override
            public void fileChanged(String filename) throws Exception
            {
                queue.add(new Event(filename, Notification.CHANGED));
            }

            @Override
            public void fileAdded(String filename) throws Exception
            {
                queue.add(new Event(filename, Notification.ADDED));
            }
        });

        scanner.start();
        Event e = queue.take();
        assertNotNull(e);
        assertEquals(Notification.ADDED, e._notification);
        assertTrue(e._filename.endsWith(root.getName()));
        queue.clear();
        scanner.stop();
        scanner.reset();

        //Depth one should report the dir itself and its file and dir direct children
        scanner.setScanDepth(1);
        scanner.addDirectory(root.toPath());
        scanner.start();
        assertEquals(4, queue.size());
        queue.clear();
        scanner.stop();
        scanner.reset();

        //Depth 2 should report the dir itself, all file children, xxx and xxx's children
        scanner.setScanDepth(2);
        scanner.addDirectory(root.toPath());
        scanner.start();

        assertEquals(7, queue.size());
        scanner.stop();
    }

    @Test
    public void testPatterns() throws Exception
    {
        //test include and exclude patterns
        File root = new File(_directory, "proot");
        FS.ensureDirExists(root);

        File ttt = new File(root, "ttt.txt");
        FS.touch(ttt);
        FS.touch(new File(root, "ttt.foo"));
        File dir = new File(root, "xxx");
        FS.ensureDirExists(dir);

        File x1 = new File(dir, "ttt.xxx");
        FS.touch(x1);
        File x2 = new File(dir, "xxx.txt");
        FS.touch(x2);

        File dir2 = new File(dir, "yyy");
        FS.ensureDirExists(dir2);
        File y1 = new File(dir2, "ttt.yyy");
        FS.touch(y1);
        File y2 = new File(dir2, "yyy.txt");
        FS.touch(y2);

        BlockingQueue<Event> queue = new LinkedBlockingQueue<Event>();
        //only scan the *.txt files for changes
        Scanner scanner = new Scanner();
        IncludeExcludeSet<PathMatcher, Path> pattern = scanner.addDirectory(root.toPath());
        pattern.exclude(root.toPath().getFileSystem().getPathMatcher("glob:**/*.foo"));
        pattern.exclude(root.toPath().getFileSystem().getPathMatcher("glob:**/ttt.xxx"));
        scanner.setScanInterval(0);
        scanner.setScanDepth(2); //should never see any files from subdir yyy
        scanner.setReportDirs(false);
        scanner.setReportExistingFilesOnStartup(false);
        scanner.addListener(new Scanner.DiscreteListener()
        {
            @Override
            public void fileRemoved(String filename) throws Exception
            {
                queue.add(new Event(filename, Notification.REMOVED));
            }

            @Override
            public void fileChanged(String filename) throws Exception
            {
                queue.add(new Event(filename, Notification.CHANGED));
            }

            @Override
            public void fileAdded(String filename) throws Exception
            {
                queue.add(new Event(filename, Notification.ADDED));
            }
        });

        scanner.start();
        assertTrue(queue.isEmpty());

        Thread.sleep(1100); // make sure time in seconds changes
        FS.touch(ttt);
        FS.touch(x2);
        FS.touch(x1);
        FS.touch(y2);
        scanner.scan();
        scanner.scan(); //2 scans for file to be considered settled

        assertThat(queue.size(), Matchers.equalTo(2));
        for (Event e : queue)
        {
            assertTrue(e._filename.endsWith("ttt.txt") || e._filename.endsWith("xxx.txt"));
        }
    }

    @Test
    @DisabledOnOs(WINDOWS) // TODO: needs review
    @DisabledIfSystemProperty(named = "env", matches = "ci") // TODO: SLOW, needs review
    public void testAddedChangeRemove() throws Exception
    {
        touch("a0");

        // takes 2 scans to notice a0 and check that it is stable
        _scanner.scan();
        _scanner.scan();

        Event event = _queue.poll();
        assertNotNull(event, "Event should not be null");
        assertEquals(_directory + "/a0", event._filename);
        assertEquals(Notification.ADDED, event._notification);

        // add 3 more files
        Thread.sleep(1100); // make sure time in seconds changes
        touch("a1");
        touch("a2");
        touch("a3");

        // not stable after 1 scan so should not be seen yet.
        _scanner.scan();
        event = _queue.poll();
        assertTrue(event == null);

        // Keep a2 unstable and remove a3 before it stabalized
        Thread.sleep(1100); // make sure time in seconds changes
        touch("a2");
        delete("a3");

        // only a1 is stable so it should be seen.
        _scanner.scan();
        event = _queue.poll();
        assertTrue(event != null);
        assertEquals(_directory + "/a1", event._filename);
        assertEquals(Notification.ADDED, event._notification);
        assertTrue(_queue.isEmpty());

        // Now a2 is stable
        _scanner.scan();
        event = _queue.poll();
        assertTrue(event != null);
        assertEquals(_directory + "/a2", event._filename);
        assertEquals(Notification.ADDED, event._notification);
        assertTrue(_queue.isEmpty());

        // We never see a3 as it was deleted before it stabalised

        // touch a1 and a2
        Thread.sleep(1100); // make sure time in seconds changes
        touch("a1");
        touch("a2");
        // not stable after 1scan so nothing should not be seen yet.
        _scanner.scan();
        event = _queue.poll();
        assertTrue(event == null);

        // Keep a2 unstable
        Thread.sleep(1100); // make sure time in seconds changes
        touch("a2");

        // only a1 is stable so it should be seen.
        _scanner.scan();
        event = _queue.poll();
        assertTrue(event != null);
        assertEquals(_directory + "/a1", event._filename);
        assertEquals(Notification.CHANGED, event._notification);
        assertTrue(_queue.isEmpty());

        // Now a2 is stable
        _scanner.scan();
        event = _queue.poll();
        assertTrue(event != null);
        assertEquals(_directory + "/a2", event._filename);
        assertEquals(Notification.CHANGED, event._notification);
        assertTrue(_queue.isEmpty());

        // delete a1 and a2
        delete("a1");
        delete("a2");
        // not stable after 1scan so nothing should not be seen yet.
        _scanner.scan();
        event = _queue.poll();
        assertTrue(event == null);

        // readd a2
        touch("a2");

        // only a1 is stable so it should be seen.
        _scanner.scan();
        event = _queue.poll();
        assertTrue(event != null);
        assertEquals(_directory + "/a1", event._filename);
        assertEquals(Notification.REMOVED, event._notification);
        assertTrue(_queue.isEmpty());

        // Now a2 is stable and is a changed file rather than a remove
        _scanner.scan();
        event = _queue.poll();
        assertTrue(event != null);
        assertEquals(_directory + "/a2", event._filename);
        assertEquals(Notification.CHANGED, event._notification);
        assertTrue(_queue.isEmpty());
    }

    @Test
    @DisabledOnOs(WINDOWS) // TODO: needs review
    public void testSizeChange() throws Exception
    {
        touch("tsc0");
        _scanner.scan();
        _scanner.scan();

        // takes 2s to notice tsc0 and check that it is stable.  This syncs us with the scan
        Event event = _queue.poll();
        assertTrue(event != null);
        assertEquals(_directory + "/tsc0", event._filename);
        assertEquals(Notification.ADDED, event._notification);

        // Create a new file by writing to it.
        long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        File file = new File(_directory, "st");
        try (OutputStream out = new FileOutputStream(file, true))
        {
            out.write('x');
            out.flush();
            file.setLastModified(now);

            // Not stable yet so no notification.
            _scanner.scan();
            event = _queue.poll();
            assertTrue(event == null);

            // Modify size only
            out.write('x');
            out.flush();
            file.setLastModified(now);

            // Still not stable yet so no notification.
            _scanner.scan();
            event = _queue.poll();
            assertTrue(event == null);

            // now stable so finally see the ADDED
            _scanner.scan();
            event = _queue.poll();
            assertTrue(event != null);
            assertEquals(_directory + "/st", event._filename);
            assertEquals(Notification.ADDED, event._notification);

            // Modify size only
            out.write('x');
            out.flush();
            file.setLastModified(now);

            // Still not stable yet so no notification.
            _scanner.scan();
            event = _queue.poll();
            assertTrue(event == null);

            // now stable so finally see the ADDED
            _scanner.scan();
            event = _queue.poll();
            assertTrue(event != null);
            assertEquals(_directory + "/st", event._filename);
            assertEquals(Notification.CHANGED, event._notification);
        }
    }

    private void delete(String string) throws IOException
    {
        File file = new File(_directory, string);
        if (file.exists())
            IO.delete(file);
    }

    private void touch(String string) throws IOException
    {
        File file = new File(_directory, string);
        if (file.exists())
            file.setLastModified(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
        else
            file.createNewFile();
    }
}
