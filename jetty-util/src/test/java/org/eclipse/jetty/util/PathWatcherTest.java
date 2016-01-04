//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.eclipse.jetty.util.PathWatcher.PathWatchEventType.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.PathWatcher.PathWatchEvent;
import org.eclipse.jetty.util.PathWatcher.PathWatchEventType;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

@Ignore("Disabled due to behavioral differences in various FileSystems (hard to write a single testcase that works in all scenarios)")
public class PathWatcherTest
{
    public static class PathWatchEventCapture implements PathWatcher.Listener
    {
        public final static String FINISH_TAG = "#finished#.tag";
        private static final Logger LOG = Log.getLogger(PathWatcherTest.PathWatchEventCapture.class);
        private final Path baseDir;

        /**
         * Map of relative paths seen, to their events seen (in order seen)
         */
        public Map<String, List<PathWatchEventType>> events = new HashMap<>();

        public int latchCount = 1;
        public CountDownLatch finishedLatch;
        private PathWatchEventType triggerType;
        private Path triggerPath;

        public PathWatchEventCapture(Path baseDir)
        {
            this.baseDir = baseDir;
        }
        
       public void reset()
       {
           finishedLatch = new CountDownLatch(latchCount);
           events.clear();
       }

        @Override
        public void onPathWatchEvent(PathWatchEvent event)
        {
            synchronized (events)
            {
                //if triggered by path
                if (triggerPath != null)
                {
                   
                    if (triggerPath.equals(event.getPath()) && (event.getType() == triggerType))
                    {
                        LOG.debug("Encountered finish trigger: {} on {}",event.getType(),event.getPath());
                        finishedLatch.countDown();
                    }
                }
                else if (finishedLatch != null)
                {
                    finishedLatch.countDown();
                }
                

                Path relativePath = this.baseDir.relativize(event.getPath());
                String key = relativePath.toString().replace(File.separatorChar,'/');

                List<PathWatchEventType> types = this.events.get(key);
                if (types == null)
                {
                    types = new ArrayList<>();
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

        /**
         * Set the path and type that will trigger this capture to be finished
         * 
         * @param triggerPath
         *            the trigger path we look for to know that the capture is complete
         * @param triggerType
         *            the trigger type we look for to know that the capture is complete
         */
        public void setFinishTrigger(Path triggerPath, PathWatchEventType triggerType)
        {
            this.triggerPath = triggerPath;
            this.triggerType = triggerType;
            this.latchCount = 1;
            this.finishedLatch = new CountDownLatch(1);
            LOG.debug("Setting finish trigger {} for path {}",triggerType,triggerPath);
        }
        
        public void setFinishTrigger (int count)
        {
            latchCount = count;
            finishedLatch = new CountDownLatch(latchCount);
        }

        /**
         * Await the countdown latch on the finish trigger
         * 
         * @param pathWatcher
         *            the watcher instance we are waiting on
         * @throws IOException
         *             if unable to create the finish tag file
         * @throws InterruptedException
         *             if unable to await the finish of the run
         * @see #setFinishTrigger(Path, PathWatchEventType)
         */
        public void awaitFinish(PathWatcher pathWatcher) throws IOException, InterruptedException
        {
            //assertThat("Trigger Path must be set",triggerPath,notNullValue());
            //assertThat("Trigger Type must be set",triggerType,notNullValue());
            double multiplier = 25.0;
            long awaitMillis = (long)((double)pathWatcher.getUpdateQuietTimeMillis() * multiplier);
            LOG.debug("Waiting for finish ({} ms)",awaitMillis);
            assertThat("Timed Out (" + awaitMillis + "ms) waiting for capture to finish",finishedLatch.await(awaitMillis,TimeUnit.MILLISECONDS),is(true));
            LOG.debug("Finished capture");
        }
    }

    private static void updateFile(Path path, String newContents) throws IOException
    {
        try (FileOutputStream out = new FileOutputStream(path.toFile()))
        {
            out.write(newContents.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getChannel().force(true);
            out.getFD().sync();
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

        try (FileOutputStream out = new FileOutputStream(path.toFile()))
        {
            int left = fileSize;

            while (left > 0)
            {
                int len = Math.min(left,chunkBufLen);
                out.write(chunkBuf,0,len);
                left -= chunkBufLen;
                out.flush();
                out.getChannel().force(true);
                // Force file to actually write to disk.
                // Skipping any sort of filesystem caching of the write
                out.getFD().sync();
                TimeUnit.MILLISECONDS.sleep(sleepMs);
            }
        }
    }

    /**
     * Sleep longer than the quiet time.
     * 
     * @param pathWatcher
     *            the path watcher to inspect for its quiet time
     * @throws InterruptedException
     *             if unable to sleep
     */
    private static void awaitQuietTime(PathWatcher pathWatcher) throws InterruptedException
    {
        double multiplier = 5.0;
        if (OS.IS_WINDOWS)
        {
            // Microsoft Windows filesystem is too slow for a lower multiplier
            multiplier = 6.0;
        }
        TimeUnit.MILLISECONDS.sleep((long)((double)pathWatcher.getUpdateQuietTimeMillis() * multiplier));
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
    
    
    @Test
    public void testConfig_ShouldRecurse_3() throws IOException
    {
        Path dir = testdir.getEmptyDir().toPath();
        
        //Create some deep dirs
        Files.createDirectories(dir.resolve("a/b/c/d/e/f/g"));
        
        PathWatcher.Config config = new PathWatcher.Config(dir);
        config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
        assertThat("Config.recurse[1].shouldRecurse[./a/b/c/d/g]",config.shouldRecurseDirectory(dir.resolve("a/b/c/d/g")),is(true));
        assertThat("Config.recurse[1].shouldRecurse[./a/b/c/d/f]",config.shouldRecurseDirectory(dir.resolve("a/b/c/d/f")),is(true));
        assertThat("Config.recurse[1].shouldRecurse[./a/b/c/d/e]",config.shouldRecurseDirectory(dir.resolve("a/b/c/d/e")),is(true));
        assertThat("Config.recurse[1].shouldRecurse[./a/b/c/d]",config.shouldRecurseDirectory(dir.resolve("a/b/c/d")),is(true));
        assertThat("Config.recurse[1].shouldRecurse[./a/b/c]",config.shouldRecurseDirectory(dir.resolve("a/b/c")),is(true));
        assertThat("Config.recurse[1].shouldRecurse[./a/b]",config.shouldRecurseDirectory(dir.resolve("a/b")),is(true));
        assertThat("Config.recurse[1].shouldRecurse[./a]",config.shouldRecurseDirectory(dir.resolve("a")),is(true));
        assertThat("Config.recurse[1].shouldRecurse[./]",config.shouldRecurseDirectory(dir),is(true));
    }
    
    @Test
    public void testRestart() throws Exception
    {
        Path dir = testdir.getEmptyDir().toPath();
        Files.createDirectories(dir.resolve("b/c"));
        Files.createFile(dir.resolve("a.txt"));
        Files.createFile(dir.resolve("b.txt"));
        
        PathWatcher pathWatcher = new PathWatcher();
        pathWatcher.setNotifyExistingOnStart(true);
        pathWatcher.setUpdateQuietTime(500,TimeUnit.MILLISECONDS);
        
        // Add listener
        PathWatchEventCapture capture = new PathWatchEventCapture(dir);
        capture.setFinishTrigger(2);
        pathWatcher.addListener(capture);

      
        PathWatcher.Config config = new PathWatcher.Config(dir);
        config.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
        config.addIncludeGlobRelative("*.txt");
        pathWatcher.watch(config);
        try
        {
            pathWatcher.start();

            // Let quiet time do its thing
            awaitQuietTime(pathWatcher);

            Map<String, PathWatchEventType[]> expected = new HashMap<>();
            
         
            expected.put("a.txt",new PathWatchEventType[] {ADDED});
            expected.put("b.txt",new PathWatchEventType[] {ADDED});

         
            capture.assertEvents(expected);
            
            //stop it
            pathWatcher.stop();
            
            capture.reset();
            
            Thread.currentThread().sleep(1000);
            
            pathWatcher.start();
            
            awaitQuietTime(pathWatcher);
            
            capture.assertEvents(expected);
            
        }
        finally
        {
            pathWatcher.stop();
        }
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
        baseDirConfig.addIncludeGlobRelative("*.war");
        baseDirConfig.addIncludeGlobRelative("*/WEB-INF/web.xml");
        pathWatcher.watch(baseDirConfig);

        try
        {
            pathWatcher.start();

            // Let quiet time do its thing
            awaitQuietTime(pathWatcher);

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
    public void testGlobPattern () throws Exception
    {
        Path dir = testdir.getEmptyDir().toPath();

        // Files we are interested in
        Files.createFile(dir.resolve("a.txt"));
        Files.createDirectories(dir.resolve("b/b.txt"));
        Files.createDirectories(dir.resolve("c/d"));
        Files.createFile(dir.resolve("c/d/d.txt"));
        Files.createFile(dir.resolve(".foo.txt"));

        // Files we don't care about
        Files.createFile(dir.resolve("txt.foo"));
        Files.createFile(dir.resolve("b/foo.xml"));
    

        PathWatcher pathWatcher = new PathWatcher();
        pathWatcher.setUpdateQuietTime(300,TimeUnit.MILLISECONDS);

        // Add listener
        PathWatchEventCapture capture = new PathWatchEventCapture(dir);
        capture.setFinishTrigger(3);
        pathWatcher.addListener(capture);

        // Add test dir configuration
        PathWatcher.Config baseDirConfig = new PathWatcher.Config(dir);
        baseDirConfig.setRecurseDepth(PathWatcher.Config.UNLIMITED_DEPTH);
        baseDirConfig.addExcludeHidden();
        baseDirConfig.addIncludeGlobRelative("**.txt");
        pathWatcher.watch(baseDirConfig);

        try
        {
            pathWatcher.start();

            // Let quiet time do its thing
            awaitQuietTime(pathWatcher);

            Map<String, PathWatchEventType[]> expected = new HashMap<>();

            expected.put("a.txt",new PathWatchEventType[] { ADDED });
            expected.put("b/b.txt",new PathWatchEventType[] { ADDED });
            expected.put("c/d/d.txt",new PathWatchEventType[] { ADDED });
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
        capture.setFinishTrigger(5);
        pathWatcher.addListener(capture);

        // Add test dir configuration
        PathWatcher.Config baseDirConfig = new PathWatcher.Config(dir);
        baseDirConfig.setRecurseDepth(2);
        baseDirConfig.addExcludeHidden();
        baseDirConfig.addIncludeGlobRelative("*.war");
        baseDirConfig.addIncludeGlobRelative("*/WEB-INF/web.xml");
        pathWatcher.watch(baseDirConfig);

        try
        {
            pathWatcher.start();

            // Pretend that startup occurred
            awaitQuietTime(pathWatcher);

            // Update web.xml
            Path webFile = dir.resolve("bar/WEB-INF/web.xml");
            //capture.setFinishTrigger(webFile,MODIFIED);
            updateFile(webFile,"Hello Update");

            // Delete war
            Files.delete(dir.resolve("foo.war"));

            // Add a another new war
            Files.createFile(dir.resolve("bar.war"));

            // Let capture complete
            capture.awaitFinish(pathWatcher);

            Map<String, PathWatchEventType[]> expected = new HashMap<>();

            expected.put("bar/WEB-INF/web.xml",new PathWatchEventType[] { ADDED, MODIFIED });
            expected.put("foo.war",new PathWatchEventType[] { ADDED, DELETED });
            expected.put("bar.war",new PathWatchEventType[] { ADDED });

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
        baseDirConfig.addIncludeGlobRelative("*.war");
        baseDirConfig.addIncludeGlobRelative("*/WEB-INF/web.xml");
        pathWatcher.watch(baseDirConfig);

        try
        {
            pathWatcher.start();

            // Pretend that startup occurred
            awaitQuietTime(pathWatcher);

            // New war added
            Path warFile = dir.resolve("hello.war");
            capture.setFinishTrigger(warFile,MODIFIED);
            updateFile(warFile,"Hello Update");

            // Let capture finish
            capture.awaitFinish(pathWatcher);

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
        pathWatcher.setUpdateQuietTime(500,TimeUnit.MILLISECONDS);

        // Add listener
        PathWatchEventCapture capture = new PathWatchEventCapture(dir);
        pathWatcher.addListener(capture);

        // Add test dir configuration
        PathWatcher.Config baseDirConfig = new PathWatcher.Config(dir);
        baseDirConfig.setRecurseDepth(2);
        baseDirConfig.addExcludeHidden();
        baseDirConfig.addIncludeGlobRelative("*.war");
        baseDirConfig.addIncludeGlobRelative("*/WEB-INF/web.xml");
        pathWatcher.watch(baseDirConfig);

        try
        {
            pathWatcher.start();

            // Pretend that startup occurred
            awaitQuietTime(pathWatcher);

            // New war added (slowly)
            Path warFile = dir.resolve("hello.war");
            capture.setFinishTrigger(warFile,MODIFIED);
            updateFileOverTime(warFile,50 * MB,3,TimeUnit.SECONDS);

            // Let capture finish
            capture.awaitFinish(pathWatcher);

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
