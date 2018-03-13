//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

}
