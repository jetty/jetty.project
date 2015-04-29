//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import static java.nio.file.StandardOpenOption.*;
import static org.eclipse.jetty.util.PathWatcher.PathWatchEventType.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.PathWatcher.PathWatchEvent;
import org.eclipse.jetty.util.PathWatcher.PathWatchEventType;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

@Ignore
public class PathWatcherTest
{
    public static class PathWatchEventCapture implements PathWatcher.Listener
    {
        private static final Logger LOG = Log.getLogger(PathWatcherTest.PathWatchEventCapture.class);
        private final Path baseDir;

        /**
         * Map of relative paths seen, to their events seen (in order seen)
         */
        public Map<String, List<PathWatchEventType>> events = new HashMap<>();

        public PathWatchEventCapture(Path baseDir)
        {
            this.baseDir = baseDir;
        }

        @Override
        public void onPathWatchEvent(PathWatchEvent event)
        {
            synchronized (events)
            {
                Path relativePath = this.baseDir.relativize(event.getPath());
                String key = relativePath.toString().replace(File.separatorChar,'/');

                List<PathWatchEventType> types = this.events.get(key);
                if (types == null)
                {
                    types = new ArrayList<>();
                    this.events.put(key,types);
                }
                types.add(event.getType());
                this.events.put(key,types);
                LOG.debug("Captured Event: {} | {}",event.getType(),key);
            }
        }

        /**
         * Validate the events seen match expectations.
         * <p>
         * Note: order of events is only important when looking at a specific file or directory. Events for multiple
         * files can overlap in ways that this assertion doesn't care about.
         * 
         * @param expectedEvents
         *            the events expected
         */
        public void assertEvents(Map<String, PathWatchEventType[]> expectedEvents)
        {
            assertThat("Event match (file|diretory) count",this.events.size(),is(expectedEvents.size()));

            for (Map.Entry<String, PathWatchEventType[]> entry : expectedEvents.entrySet())
            {
                String relativePath = entry.getKey();
                PathWatchEventType[] expectedTypes = entry.getValue();
                assertEvents(relativePath,expectedTypes);
            }
        }

        /**
         * Validate the events seen match expectations.
         * <p>
         * Note: order of events is only important when looking at a specific file or directory. Events for multiple
         * files can overlap in ways that this assertion doesn't care about.
         * 
         * @param relativePath
         *            the test relative path to look for
         * 
         * @param expectedEvents
         *            the events expected
         */
        public void assertEvents(String relativePath, PathWatchEventType... expectedEvents)
        {
            synchronized (events)
            {
                List<PathWatchEventType> actualEvents = this.events.get(relativePath);
                assertThat("Events for path [" + relativePath + "]",actualEvents,contains(expectedEvents));
            }
        }
    }

    private static void updateFile(Path path, String newContents) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(path,StandardCharsets.UTF_8,CREATE,TRUNCATE_EXISTING,WRITE))
        {
            writer.append(newContents);
            writer.flush();
        }
    }

    /**
     * Update (optionally create) a file over time.
     * <p>
     * The file will be created in a slowed down fashion, over the time specified.
     * 
     * @param path
     *            the file to update / create
     * @param fileSize
     *            the ultimate file size to create
     * @param timeDuration
     *            the time duration to take to create the file (approximate, not 100% accurate)
     * @param timeUnit
     *            the time unit to take to create the file
     * @throws IOException
     *             if unable to write file
     * @throws InterruptedException
     *             if sleep between writes was interrupted
     */
    private void updateFileOverTime(Path path, int fileSize, int timeDuration, TimeUnit timeUnit) throws IOException, InterruptedException
    {
        // how long to sleep between writes
        int sleepMs = 100;

        // how many millis to spend writing entire file size
        long totalMs = timeUnit.toMillis(timeDuration);

        // how many write chunks to write
        int writeCount = (int)((int)totalMs / (int)sleepMs);

        // average chunk buffer
        int chunkBufLen = fileSize / writeCount;
        byte chunkBuf[] = new byte[chunkBufLen];
        Arrays.fill(chunkBuf,(byte)'x');

        try (OutputStream out = Files.newOutputStream(path,CREATE,TRUNCATE_EXISTING,WRITE))
        {
            int left = fileSize;

            while (left > 0)
            {
                int len = Math.min(left,chunkBufLen);
                out.write(chunkBuf,0,len);
                left -= chunkBufLen;
                TimeUnit.MILLISECONDS.sleep(sleepMs);
                out.flush();
            }
        }
    }

    private static final int KB = 1024;
    private static final int MB = KB * KB;

    @Rule
    public TestingDir testdir = new TestingDir();

    @Test
    public void testConfig_ShouldRecurse_0() throws IOException
    {
        Path dir = testdir.getEmptyDir().toPath();

        // Create a few directories
        Files.createDirectories(dir.resolve("a/b/c/d"));

        PathWatcher.Config config = new PathWatcher.Config(dir);

        config.setRecurseDepth(0);
        assertThat("Config.recurse[0].shouldRecurse[./a/b]",config.shouldRecurseDirectory(dir.resolve("a/b")),is(false));
        assertThat("Config.recurse[0].shouldRecurse[./a]",config.shouldRecurseDirectory(dir.resolve("a")),is(false));
        assertThat("Config.recurse[0].shouldRecurse[./]",config.shouldRecurseDirectory(dir),is(false));
    }

    @Test
    public void testConfig_ShouldRecurse_1() throws IOException
    {
        Path dir = testdir.getEmptyDir().toPath();

        // Create a few directories
        Files.createDirectories(dir.resolve("a/b/c/d"));

        PathWatcher.Config config = new PathWatcher.Config(dir);

        config.setRecurseDepth(1);
        assertThat("Config.recurse[1].shouldRecurse[./a/b]",config.shouldRecurseDirectory(dir.resolve("a/b")),is(false));
        assertThat("Config.recurse[1].shouldRecurse[./a]",config.shouldRecurseDirectory(dir.resolve("a")),is(true));
        assertThat("Config.recurse[1].shouldRecurse[./]",config.shouldRecurseDirectory(dir),is(true));
    }

    @Test
    public void testConfig_ShouldRecurse_2() throws IOException
    {
        Path dir = testdir.getEmptyDir().toPath();

        // Create a few directories
        Files.createDirectories(dir.resolve("a/b/c/d"));

        PathWatcher.Config config = new PathWatcher.Config(dir);

        config.setRecurseDepth(2);
        assertThat("Config.recurse[1].shouldRecurse[./a/b/c]",config.shouldRecurseDirectory(dir.resolve("a/b/c")),is(false));
        assertThat("Config.recurse[1].shouldRecurse[./a/b]",config.shouldRecurseDirectory(dir.resolve("a/b")),is(true));
        assertThat("Config.recurse[1].shouldRecurse[./a]",config.shouldRecurseDirectory(dir.resolve("a")),is(true));
        assertThat("Config.recurse[1].shouldRecurse[./]",config.shouldRecurseDirectory(dir),is(true));
    }

    /**
     * When starting up the PathWatcher, the events should occur
     * indicating files that are of interest that already exist
     * on the filesystem.
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testStartupFindFiles() throws Exception
    {
        Path dir = testdir.getEmptyDir().toPath();

        // Files we are interested in
        Files.createFile(dir.resolve("foo.war"));
        Files.createDirectories(dir.resolve("bar/WEB-INF"));
        Files.createFile(dir.resolve("bar/WEB-INF/web.xml"));

        // Files we don't care about
        Files.createFile(dir.resolve("foo.war.backup"));
        Files.createFile(dir.resolve(".hidden.war"));
        Files.createDirectories(dir.resolve(".wat/WEB-INF"));
        Files.createFile(dir.resolve(".wat/huh.war"));
        Files.createFile(dir.resolve(".wat/WEB-INF/web.xml"));

        PathWatcher pathWatcher = new PathWatcher();
        pathWatcher.setUpdateQuietTime(300,TimeUnit.MILLISECONDS);

        // Add listener
        PathWatchEventCapture capture = new PathWatchEventCapture(dir);
        pathWatcher.addListener(capture);

        // Add test dir configuration
        PathWatcher.Config baseDirConfig = new PathWatcher.Config(dir);
        baseDirConfig.setRecurseDepth(2);
        baseDirConfig.addExcludeHidden();
        baseDirConfig.addInclude("glob:" + dir.toAbsolutePath().toString() + "/*.war");
        baseDirConfig.addInclude("glob:" + dir.toAbsolutePath().toString() + "/*/WEB-INF/web.xml");
        pathWatcher.addDirectoryWatch(baseDirConfig);

        try
        {
            pathWatcher.start();

            // Let quiet time do its thing
            TimeUnit.MILLISECONDS.sleep(500);

            Map<String, PathWatchEventType[]> expected = new HashMap<>();

            expected.put("bar/WEB-INF/web.xml",new PathWatchEventType[] { ADDED });
            expected.put("foo.war",new PathWatchEventType[] { ADDED });

            capture.assertEvents(expected);
        }
        finally
        {
            pathWatcher.stop();
        }
    }

    @Test
    public void testDeployFiles_Update_Delete() throws Exception
    {
        Path dir = testdir.getEmptyDir().toPath();

        // Files we are interested in
        Files.createFile(dir.resolve("foo.war"));
        Files.createDirectories(dir.resolve("bar/WEB-INF"));
        Files.createFile(dir.resolve("bar/WEB-INF/web.xml"));

        PathWatcher pathWatcher = new PathWatcher();
        pathWatcher.setUpdateQuietTime(300,TimeUnit.MILLISECONDS);

        // Add listener
        PathWatchEventCapture capture = new PathWatchEventCapture(dir);
        pathWatcher.addListener(capture);

        // Add test dir configuration
        PathWatcher.Config baseDirConfig = new PathWatcher.Config(dir);
        baseDirConfig.setRecurseDepth(2);
        baseDirConfig.addExcludeHidden();
        baseDirConfig.addInclude("glob:" + dir.toAbsolutePath().toString() + "/*.war");
        baseDirConfig.addInclude("glob:" + dir.toAbsolutePath().toString() + "/*/WEB-INF/web.xml");
        pathWatcher.addDirectoryWatch(baseDirConfig);

        try
        {
            pathWatcher.start();

            // Pretend that startup occurred
            TimeUnit.MILLISECONDS.sleep(500);

            // Update web.xml
            updateFile(dir.resolve("bar/WEB-INF/web.xml"),"Hello Update");
            FS.touch(dir.resolve("bar/WEB-INF/web.xml").toFile());

            // Delete war
            Files.delete(dir.resolve("foo.war"));

            // Let quiet time elapse
            TimeUnit.MILLISECONDS.sleep(500);

            Map<String, PathWatchEventType[]> expected = new HashMap<>();

            expected.put("bar/WEB-INF/web.xml",new PathWatchEventType[] { ADDED, MODIFIED });
            expected.put("foo.war",new PathWatchEventType[] { ADDED, DELETED });

            capture.assertEvents(expected);
        }
        finally
        {
            pathWatcher.stop();
        }
    }

    @Test
    public void testDeployFiles_NewWar() throws Exception
    {
        Path dir = testdir.getEmptyDir().toPath();

        // Files we are interested in
        Files.createFile(dir.resolve("foo.war"));
        Files.createDirectories(dir.resolve("bar/WEB-INF"));
        Files.createFile(dir.resolve("bar/WEB-INF/web.xml"));

        PathWatcher pathWatcher = new PathWatcher();
        pathWatcher.setUpdateQuietTime(300,TimeUnit.MILLISECONDS);

        // Add listener
        PathWatchEventCapture capture = new PathWatchEventCapture(dir);
        pathWatcher.addListener(capture);

        // Add test dir configuration
        PathWatcher.Config baseDirConfig = new PathWatcher.Config(dir);
        baseDirConfig.setRecurseDepth(2);
        baseDirConfig.addExcludeHidden();
        baseDirConfig.addInclude("glob:" + dir.toAbsolutePath().toString() + "/*.war");
        baseDirConfig.addInclude("glob:" + dir.toAbsolutePath().toString() + "/*/WEB-INF/web.xml");
        pathWatcher.addDirectoryWatch(baseDirConfig);

        try
        {
            pathWatcher.start();

            // Pretend that startup occurred
            TimeUnit.MILLISECONDS.sleep(500);

            // New war added
            updateFile(dir.resolve("hello.war"),"Hello Update");

            // Let quiet time elapse
            TimeUnit.MILLISECONDS.sleep(500);

            Map<String, PathWatchEventType[]> expected = new HashMap<>();

            expected.put("bar/WEB-INF/web.xml",new PathWatchEventType[] { ADDED });
            expected.put("foo.war",new PathWatchEventType[] { ADDED });
            expected.put("hello.war",new PathWatchEventType[] { ADDED, MODIFIED });

            capture.assertEvents(expected);
        }
        finally
        {
            pathWatcher.stop();
        }
    }

    /**
     * Pretend to add a new war file that is large, and being copied into place
     * using some sort of technique that is slow enough that it takes a while for
     * the entire war file to exist in place.
     * <p>
     * This is to test the quiet time logic to ensure that only a single MODIFIED event occurs on this new war file
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testDeployFiles_NewWar_LargeSlowCopy() throws Exception
    {
        Path dir = testdir.getEmptyDir().toPath();

        // Files we are interested in
        Files.createFile(dir.resolve("foo.war"));
        Files.createDirectories(dir.resolve("bar/WEB-INF"));
        Files.createFile(dir.resolve("bar/WEB-INF/web.xml"));

        PathWatcher pathWatcher = new PathWatcher();
        pathWatcher.setUpdateQuietTime(300,TimeUnit.MILLISECONDS);

        // Add listener
        PathWatchEventCapture capture = new PathWatchEventCapture(dir);
        pathWatcher.addListener(capture);

        // Add test dir configuration
        PathWatcher.Config baseDirConfig = new PathWatcher.Config(dir);
        baseDirConfig.setRecurseDepth(2);
        baseDirConfig.addExcludeHidden();
        baseDirConfig.addInclude("glob:" + dir.toAbsolutePath().toString() + "/*.war");
        baseDirConfig.addInclude("glob:" + dir.toAbsolutePath().toString() + "/*/WEB-INF/web.xml");
        pathWatcher.addDirectoryWatch(baseDirConfig);

        try
        {
            pathWatcher.start();

            // Pretend that startup occurred
            TimeUnit.MILLISECONDS.sleep(500);

            // New war added (slowly)
            updateFileOverTime(dir.resolve("hello.war"),50 * MB,3,TimeUnit.SECONDS);

            // Let quiet time elapse
            TimeUnit.MILLISECONDS.sleep(500);

            Map<String, PathWatchEventType[]> expected = new HashMap<>();

            expected.put("bar/WEB-INF/web.xml",new PathWatchEventType[] { ADDED });
            expected.put("foo.war",new PathWatchEventType[] { ADDED });
            expected.put("hello.war",new PathWatchEventType[] { ADDED, MODIFIED });

            capture.assertEvents(expected);
        }
        finally
        {
            pathWatcher.stop();
        }
    }
}
