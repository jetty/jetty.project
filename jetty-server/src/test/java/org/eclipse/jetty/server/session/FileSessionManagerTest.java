//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Collections;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class FileSessionManagerTest
{
    public static final long ONE_DAY = (1000L*60L*60L*24L);
    private static StdErrLog _log;
    private static boolean _stacks;
  
    
    @BeforeClass
    public static void beforeClass ()
    {
        _log = ((StdErrLog)Log.getLogger("org.eclipse.jetty.server.session"));
        _stacks = _log.isHideStacks();
        _log.setHideStacks(true);
    }
    
    @AfterClass
    public static void afterClass()
    {
        _log.setHideStacks(_stacks);
    }
    
    @After
    public void after()
    {
        File testDir = MavenTestingUtils.getTargetTestingDir("hashes");
        if (testDir.exists())
            FS.ensureEmpty(testDir);
    }
    
    
    @Test
    public void testDangerousSessionIdRemoval() throws Exception
    {  
        String expectedFilename =  "_0.0.0.0_dangerFile";    
        File targetFile = MavenTestingUtils.getTargetFile(expectedFilename);

        try
        {
            Server server = new Server();
            SessionHandler handler = new SessionHandler();
            handler.setServer(server);
            final DefaultSessionIdManager idmgr = new DefaultSessionIdManager(server);
            idmgr.setServer(server);
            server.setSessionIdManager(idmgr);

            FileSessionDataStore ds = new FileSessionDataStore();
            ds.setDeleteUnrestorableFiles(true);
            DefaultSessionCache ss = new DefaultSessionCache(handler);
            handler.setSessionCache(ss);
            ss.setSessionDataStore(ds);
            File testDir = MavenTestingUtils.getTargetTestingDir("hashes");
            FS.ensureEmpty(testDir);
            ds.setStoreDir(testDir);
            handler.setSessionIdManager(idmgr);
            handler.start();

            //Create a file that is in the parent dir of the session storeDir

            targetFile.createNewFile();
            Assert.assertTrue("File should exist!", MavenTestingUtils.getTargetFile(expectedFilename).exists());

            //Verify that passing in a relative filename outside of the storedir does not lead
            //to deletion of file (needs deleteUnrecoverableFiles(true))
            Session session = handler.getSession("../_0.0.0.0_dangerFile");
            Assert.assertTrue(session == null);
            Assert.assertTrue("File should exist!", MavenTestingUtils.getTargetFile(expectedFilename).exists());
        }
        finally
        {
            if (targetFile.exists())
                IO.delete(targetFile);
        }
    }
    
    
    
    /**
     * When starting the filestore, check that really old expired
     * files are deleted irrespective of context session belongs to.
     * 
     * @throws Exception
     */
    @Test
    public void testDeleteOfOlderFiles() throws Exception
    {
        Server server = new Server();
        SessionHandler handler = new SessionHandler();
        handler.setServer(server);
        final DefaultSessionIdManager idmgr = new DefaultSessionIdManager(server);
        idmgr.setServer(server);
        server.setSessionIdManager(idmgr);
        
        FileSessionDataStore ds = new FileSessionDataStore();
        ds.setDeleteUnrestorableFiles(false); //turn off deletion of unreadable session files
        DefaultSessionCache ss = new DefaultSessionCache(handler);
        handler.setSessionCache(ss);
        ss.setSessionDataStore(ds);
        
        File testDir = MavenTestingUtils.getTargetTestingDir("hashes");
        FS.ensureEmpty(testDir);
        ds.setStoreDir(testDir);
        
        //create a really old file for session abc
        String name1 =  "100__0.0.0.0_abc";    
        File f1 = new File(testDir, name1);
        if (f1.exists())
            Assert.assertTrue(f1.delete());
        f1.createNewFile();

        //create another really old file for session abc
        Thread.sleep(1100);
        String name2 = "101__0.0.0.0_abc"; 
        File f2 = new File(testDir, name2);
        if (f2.exists())
            Assert.assertTrue(f2.delete());
        f2.createNewFile();

        //make one file for session abc that should not have expired
        Thread.sleep(1100);
        long exp = System.currentTimeMillis() + ONE_DAY;
        String name3 = Long.toString(exp)+"__0.0.0.0_abc";
        File f3 = new File(testDir, name3);
        if (f3.exists())
            Assert.assertTrue(f3.delete());       
        f3.createNewFile();
        
        //make a file that is for a different context
        //that expired a long time ago - should be
        //removed by sweep on startup
        Thread.sleep(1100); 
        String name4 = "1099_foo_0.0.0.0_abc";
        File f4 = new File(testDir, name4);
        if (f4.exists())
            Assert.assertTrue(f4.delete());       
        f4.createNewFile();
        
        //make a file that is for a different context
        //that should not have expired - ensure it is
        //not removed
        exp = System.currentTimeMillis() + ONE_DAY;
        String name5 = Long.toString(exp)+"_foo_0.0.0.0_abcdefg";
        File f5 = new File(testDir, name5);
        if (f5.exists())
            Assert.assertTrue(f5.delete());       
        f5.createNewFile();
        
        //make a file that is for a different context
        //that expired, but only recently - it should
        //not be removed by the startup process
        exp = System.currentTimeMillis() - 1000L;
        String name6 = Long.toString(exp)+"_foo_0.0.0.0_abcdefg";
        File f6 = new File(testDir, name5);
        if (f6.exists())
            Assert.assertTrue(f6.delete());       
        f6.createNewFile();
        
        handler.setSessionIdManager(idmgr);
        handler.start();

        Assert.assertTrue(!f1.exists()); 
        Assert.assertTrue(!f2.exists());
        Assert.assertTrue(f3.exists());
        Assert.assertTrue(!f4.exists());
        Assert.assertTrue(f5.exists());
        Assert.assertTrue(f6.exists());
    }
    
    
    /**
     * Tests that only the most recent file will be
     * loaded into the cache, even if it is already
     * expired. Other recently expired files for
     * same session should be deleted.
     * @throws Exception
     */
    @Test
    public void testLoadOnlyMostRecent() throws Exception
    {
        Server server = new Server();
        SessionHandler handler = new SessionHandler();
        handler.setServer(server);
        final DefaultSessionIdManager idmgr = new DefaultSessionIdManager(server);
        idmgr.setServer(server);
        server.setSessionIdManager(idmgr);
        
        FileSessionDataStore ds = new FileSessionDataStore();
        ds.setGracePeriodSec(100); //set graceperiod to 100sec to control what we consider as very old
        ds.setDeleteUnrestorableFiles(false); //turn off deletion of unreadable session files
        DefaultSessionCache ss = new DefaultSessionCache(handler);
        handler.setSessionCache(ss);
        ss.setSessionDataStore(ds);
        
        File testDir = MavenTestingUtils.getTargetTestingDir("hashes");
        FS.ensureEmpty(testDir);
        ds.setStoreDir(testDir);
        
        long now =  System.currentTimeMillis();
        
        //create a file for session abc that expired 5sec ago
        long exp = now -5000L; 
        String name1 =  Long.toString(exp)+"__0.0.0.0_abc";    
        File f1 = new File(testDir, name1);
        if (f1.exists())
            Assert.assertTrue(f1.delete());
        f1.createNewFile();
        
        //create a file for same session that expired 4 sec ago
        exp = now - 4000L;
        String name2 = Long.toString(exp)+"__0.0.0.0_abc"; 
        File f2 = new File(testDir, name2);
        if (f2.exists())
            Assert.assertTrue(f2.delete());
        f2.createNewFile(); 


        //make a file for same session that expired 3 sec ago
        exp = now - 3000L;
        String name3 = Long.toString(exp)+"__0.0.0.0_abc";
        File f3 = new File(testDir, name3);
        if (f3.exists())
            Assert.assertTrue(f3.delete());
        f3.createNewFile();

        handler.setSessionIdManager(idmgr);
        handler.start();

        Assert.assertFalse(f1.exists());
        Assert.assertFalse(f2.exists());
        Assert.assertTrue(f3.exists());
    }
    
    

    
    
    @Test
    public void testUnrestorableFileRemoval() throws Exception
    {      
        Server server = new Server();
        SessionHandler handler = new SessionHandler();
        handler.setServer(server);
        final DefaultSessionIdManager idmgr = new DefaultSessionIdManager(server);
        idmgr.setServer(server);
        server.setSessionIdManager(idmgr);
      
        File testDir = MavenTestingUtils.getTargetTestingDir("hashes");
        FS.ensureEmpty(testDir);
        String expectedFilename = (System.currentTimeMillis()+ 10000)+"__0.0.0.0_validFile123";
        
        Assert.assertTrue(new File(testDir, expectedFilename).createNewFile());
        Assert.assertTrue("File should exist!", new File(testDir, expectedFilename).exists());
        
        DefaultSessionCache ss = new DefaultSessionCache(handler);
        FileSessionDataStore ds = new FileSessionDataStore();
        ss.setSessionDataStore(ds);
        handler.setSessionCache(ss);
        ds.setDeleteUnrestorableFiles(true); //invalid file will be removed
        handler.setSessionIdManager(idmgr);
        ds.setStoreDir(testDir);
        handler.start();

        Session session = handler.getSession("validFile123");

        Assert.assertTrue("File shouldn't exist!", !new File(testDir,expectedFilename).exists());
    }

    @Test
    public void testHashSession() throws Exception
    {
        File testDir = MavenTestingUtils.getTargetTestingDir("saved");
        FS.ensureEmpty(testDir);

        Server server = new Server();
        SessionHandler handler = new SessionHandler();
        handler.setServer(server);

        DefaultSessionCache ss = new DefaultSessionCache(handler);
        FileSessionDataStore ds = new FileSessionDataStore();
        ss.setSessionDataStore(ds);
        handler.setSessionCache(ss);
        ds.setStoreDir(testDir);
        handler.setMaxInactiveInterval(5);
        Assert.assertTrue(testDir.exists());
        Assert.assertTrue(testDir.canWrite());

        
        DefaultSessionIdManager idManager = new DefaultSessionIdManager(server);
        idManager.setServer(server);
        idManager.setWorkerName("foo");
        handler.setSessionIdManager(idManager);
        server.setSessionIdManager(idManager);
        
        server.start();
        handler.start();
        
        Session session = (Session)handler.newHttpSession(new Request(null, null));
        String sessionId = session.getId();
        
        session.setAttribute("one", new Integer(1));
        session.setAttribute("two", new Integer(2));    
        
        //stop will persist sessions
        handler.setMaxInactiveInterval(30); // change max inactive interval for *new* sessions
        handler.stop();
        
        final String expectedFilename = "_0.0.0.0_"+session.getId();
    
        File[] files = testDir.listFiles(new FilenameFilter(){

            @Override
            public boolean accept(File dir, String name)
            {
               return name.contains(expectedFilename);
            }
            
        });
        Assert.assertNotNull(files);
        Assert.assertEquals(1, files.length);
        Assert.assertTrue("File should exist!", files[0].exists());
        
       
        
        handler.start();
        
        //restore session
        Session restoredSession = (Session)handler.getSession(sessionId);
        Assert.assertNotNull(restoredSession);
        
        Object o = restoredSession.getAttribute("one");
        Assert.assertNotNull(o);
        
        Assert.assertEquals(1, ((Integer)o).intValue());
        Assert.assertEquals(5, restoredSession.getMaxInactiveInterval());     
        
        server.stop();
    }

    @Test
    public void testIrregularFilenames() throws Exception
    {
        Server server = new Server();
        SessionHandler handler = new SessionHandler();
        handler.setServer(server);
        final DefaultSessionIdManager idmgr = new DefaultSessionIdManager(server);
        idmgr.setServer(server);
        server.setSessionIdManager(idmgr);

        FileSessionDataStore ds = new FileSessionDataStore();
        ds.setDeleteUnrestorableFiles(true);
        DefaultSessionCache ss = new DefaultSessionCache(handler);
        handler.setSessionCache(ss);
        ss.setSessionDataStore(ds);
        File testDir = MavenTestingUtils.getTargetTestingDir("hashes");
        FS.ensureEmpty(testDir);
        ds.setStoreDir(testDir);
        handler.setSessionIdManager(idmgr);
        handler.start();

        //Create a file in the session storeDir that has no underscore.
        File noUnderscore = new File(testDir, "spuriousFile");
        noUnderscore.createNewFile();
        try
        {
            Assert.assertTrue("Expired should be empty!", ds.getExpired(Collections.emptySet()).isEmpty());
        }
        finally
        {
            noUnderscore.delete();
        }

        //Create a file that starts with a non-number before an underscore
        File nonNumber = new File(testDir, "nonNumber_0.0.0.0_spuriousFile");
        nonNumber.createNewFile();
        try
        {
            Assert.assertTrue("Expired should be empty!", ds.getExpired(Collections.emptySet()).isEmpty());
        }
        finally
        {
            nonNumber.delete();
        }
    }
 
}
