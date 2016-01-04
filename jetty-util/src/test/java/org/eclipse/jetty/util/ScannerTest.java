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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.Scanner.Notification;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AdvancedRunner.class)
public class ScannerTest
{
    static File _directory;
    static Scanner _scanner;
    static BlockingQueue<Event> _queue = new LinkedBlockingQueue<Event>();
    static BlockingQueue<List<String>> _bulk = new LinkedBlockingQueue<List<String>>();

    @BeforeClass
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
        _scanner.addListener(new Scanner.DiscreteListener()
        {
            public void fileRemoved(String filename) throws Exception
            {
                _queue.add(new Event(filename,Notification.REMOVED));
            }

            public void fileChanged(String filename) throws Exception
            {
                _queue.add(new Event(filename,Notification.CHANGED));
            }

            public void fileAdded(String filename) throws Exception
            {
                _queue.add(new Event(filename,Notification.ADDED));
            }
        });
        _scanner.addListener(new Scanner.BulkListener()
        {
            public void filesChanged(List<String> filenames) throws Exception
            {
                _bulk.add(filenames);
            }
        });
        _scanner.start();

        _scanner.scan();
        
        Assert.assertTrue(_queue.isEmpty());
        Assert.assertTrue(_bulk.isEmpty());
    }

    @AfterClass
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
            _filename=filename;
            _notification=notification;
        }
    }

    @Test
    @Slow
    public void testAddedChangeRemove() throws Exception
    {
        // TODO needs to be further investigated
        Assume.assumeTrue(!OS.IS_WINDOWS);

        touch("a0");

        // takes 2 scans to notice a0 and check that it is stable
        _scanner.scan();
        _scanner.scan();
        Event event = _queue.poll();
        Assert.assertNotNull("Event should not be null", event);
        Assert.assertEquals(_directory+"/a0",event._filename);
        Assert.assertEquals(Notification.ADDED,event._notification);

        // add 3 more files
        Thread.sleep(1100); // make sure time in seconds changes
        touch("a1");
        touch("a2");
        touch("a3");

        // not stable after 1 scan so should not be seen yet.
        _scanner.scan();
        event = _queue.poll();
        Assert.assertTrue(event==null);

        // Keep a2 unstable and remove a3 before it stabalized
        Thread.sleep(1100); // make sure time in seconds changes
        touch("a2");
        delete("a3");

        // only a1 is stable so it should be seen.
        _scanner.scan();
        event = _queue.poll();
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/a1",event._filename);
        Assert.assertEquals(Notification.ADDED,event._notification);
        Assert.assertTrue(_queue.isEmpty());

        // Now a2 is stable
        _scanner.scan();
        event = _queue.poll();
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/a2",event._filename);
        Assert.assertEquals(Notification.ADDED,event._notification);
        Assert.assertTrue(_queue.isEmpty());

        // We never see a3 as it was deleted before it stabalised

        // touch a1 and a2
        Thread.sleep(1100); // make sure time in seconds changes
        touch("a1");
        touch("a2");
        // not stable after 1scan so nothing should not be seen yet.
        _scanner.scan();
        event = _queue.poll();
        Assert.assertTrue(event==null);

        // Keep a2 unstable
        Thread.sleep(1100); // make sure time in seconds changes
        touch("a2");

        // only a1 is stable so it should be seen.
        _scanner.scan();
        event = _queue.poll();
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/a1",event._filename);
        Assert.assertEquals(Notification.CHANGED,event._notification);
        Assert.assertTrue(_queue.isEmpty());

        // Now a2 is stable
        _scanner.scan();
        event = _queue.poll();
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/a2",event._filename);
        Assert.assertEquals(Notification.CHANGED,event._notification);
        Assert.assertTrue(_queue.isEmpty());


        // delete a1 and a2
        delete("a1");
        delete("a2");
        // not stable after 1scan so nothing should not be seen yet.
        _scanner.scan();
        event = _queue.poll();
        Assert.assertTrue(event==null);

        // readd a2
        touch("a2");

        // only a1 is stable so it should be seen.
        _scanner.scan();
        event = _queue.poll();
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/a1",event._filename);
        Assert.assertEquals(Notification.REMOVED,event._notification);
        Assert.assertTrue(_queue.isEmpty());

        // Now a2 is stable and is a changed file rather than a remove
        _scanner.scan();
        event = _queue.poll();
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/a2",event._filename);
        Assert.assertEquals(Notification.CHANGED,event._notification);
        Assert.assertTrue(_queue.isEmpty());

    }

    @Test
    public void testSizeChange() throws Exception
    {
        // TODO needs to be further investigated
        Assume.assumeTrue(!OS.IS_WINDOWS);

        touch("tsc0");
        _scanner.scan();
        _scanner.scan();

        // takes 2s to notice tsc0 and check that it is stable.  This syncs us with the scan
        Event event = _queue.poll();
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/tsc0",event._filename);
        Assert.assertEquals(Notification.ADDED,event._notification);


        // Create a new file by writing to it.
        long now = System.currentTimeMillis();
        File file = new File(_directory,"st");
        try (OutputStream out = new FileOutputStream(file,true))
        {
            out.write('x');
            out.flush();
            file.setLastModified(now);

            // Not stable yet so no notification.
            _scanner.scan();
            event = _queue.poll();
            Assert.assertTrue(event==null);

            // Modify size only
            out.write('x');
            out.flush();
            file.setLastModified(now);

            // Still not stable yet so no notification.
            _scanner.scan();
            event = _queue.poll();
            Assert.assertTrue(event==null);

            // now stable so finally see the ADDED
            _scanner.scan();
            event = _queue.poll();
            Assert.assertTrue(event!=null);
            Assert.assertEquals(_directory+"/st",event._filename);
            Assert.assertEquals(Notification.ADDED,event._notification);

            // Modify size only
            out.write('x');
            out.flush();
            file.setLastModified(now);


            // Still not stable yet so no notification.
            _scanner.scan();
            event = _queue.poll();
            Assert.assertTrue(event==null);

            // now stable so finally see the ADDED
            _scanner.scan();
            event = _queue.poll();
            Assert.assertTrue(event!=null);
            Assert.assertEquals(_directory+"/st",event._filename);
            Assert.assertEquals(Notification.CHANGED,event._notification);
        }
    }

    private void delete(String string) throws IOException
    {
        File file = new File(_directory,string);
        if (file.exists())
            IO.delete(file);
    }

    private void touch(String string) throws IOException
    {
        File file = new File(_directory,string);
        if (file.exists())
            file.setLastModified(System.currentTimeMillis());
        else
            file.createNewFile();
    }
}
