package org.eclipse.jetty.util;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.eclipse.jetty.util.Scanner.Notification;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ScannerTest
{
    static File _directory;
    static Scanner _scanner;
    static BlockingQueue<Event> _queue = new LinkedBlockingQueue<Event>();
    static BlockingQueue<List<String>> _bulk = new LinkedBlockingQueue<List<String>>();
    
    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        _directory = File.createTempFile("scan","");
        _directory.delete();
        _directory.mkdir();
        _directory.deleteOnExit();
        
        _scanner = new Scanner();
        _scanner.addScanDir(_directory);
        _scanner.setScanInterval(1);
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
    public void testAddedChangeRemove() throws Exception
    {
        touch("a0");

        // takes 2s to notice a0 and check that it is stable
        Event event = _queue.poll(2100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/a0",event._filename);
        Assert.assertEquals(Notification.ADDED,event._notification);

        // add 3 more files
        touch("a1");
        touch("a2");
        touch("a3");
        
        // not stable after 1s so should not be seen yet.
        event = _queue.poll(1100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event==null);

        // Keep a2 unstable and remove a3 before it stabalized
        touch("a2");
        delete("a3");
        
        // only a1 is stable so it should be seen.
        event = _queue.poll(1100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/a1",event._filename);
        Assert.assertEquals(Notification.ADDED,event._notification);
        Assert.assertTrue(_queue.isEmpty());
        
        // Now a2 is stable
        event = _queue.poll(1100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/a2",event._filename);
        Assert.assertEquals(Notification.ADDED,event._notification);
        Assert.assertTrue(_queue.isEmpty());
        
        // We never see a3 as it was deleted before it stabalised
        
        // touch a1 and a2
        touch("a1");
        touch("a2");
        // not stable after 1s so nothing should not be seen yet.
        event = _queue.poll(1100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event==null);

        // Keep a2 unstable 
        touch("a2");

        // only a1 is stable so it should be seen.
        event = _queue.poll(1100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/a1",event._filename);
        Assert.assertEquals(Notification.CHANGED,event._notification);
        Assert.assertTrue(_queue.isEmpty());

        // Now a2 is stable
        event = _queue.poll(1100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/a2",event._filename);
        Assert.assertEquals(Notification.CHANGED,event._notification);
        Assert.assertTrue(_queue.isEmpty());
        
        
        // delete a1 and a2
        delete("a1");
        delete("a2");
        // not stable after 1s so nothing should not be seen yet.
        event = _queue.poll(1100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event==null);

        // readd a2  
        touch("a2");

        // only a1 is stable so it should be seen.
        event = _queue.poll(1100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/a1",event._filename);
        Assert.assertEquals(Notification.REMOVED,event._notification);
        Assert.assertTrue(_queue.isEmpty());

        // Now a2 is stable and is a changed file rather than a remove
        event = _queue.poll(1100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/a2",event._filename);
        Assert.assertEquals(Notification.CHANGED,event._notification);
        Assert.assertTrue(_queue.isEmpty());
        
    }
    
    @Test
    public void testSizeChange() throws Exception
    {
        touch("tsc0");

        // takes 2s to notice tsc0 and check that it is stable.  This syncs us with the scan
        Event event = _queue.poll(2100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/tsc0",event._filename);
        Assert.assertEquals(Notification.ADDED,event._notification);
        
        
        // Create a new file by writing to it.
        long now = System.currentTimeMillis();
        File file = new File(_directory,"st");
        FileOutputStream out = new FileOutputStream(file,true);
        out.write('x');
        out.flush();
        file.setLastModified(now);

        // Not stable yet so no notification.
        event = _queue.poll(1100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event==null);
        
        // Modify size only
        out.write('x');
        out.flush();
        file.setLastModified(now);
        
        // Still not stable yet so no notification.
        event = _queue.poll(1100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event==null);

        // now stable so finally see the ADDED
        event = _queue.poll(1100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/st",event._filename);
        Assert.assertEquals(Notification.ADDED,event._notification);

        // Modify size only
        out.write('x');
        out.flush();
        file.setLastModified(now);
        

        // Still not stable yet so no notification.
        event = _queue.poll(1100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event==null);

        // now stable so finally see the ADDED
        event = _queue.poll(1100,TimeUnit.MILLISECONDS);
        Assert.assertTrue(event!=null);
        Assert.assertEquals(_directory+"/st",event._filename);
        Assert.assertEquals(Notification.CHANGED,event._notification);
        
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
