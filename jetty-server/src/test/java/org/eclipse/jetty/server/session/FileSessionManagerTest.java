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

package org.eclipse.jetty.server.session;

import java.io.File;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class FileSessionManagerTest
{
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
    
    
    
    @Test
    public void testDangerousSessionIdRemoval() throws Exception
    {
        Server server = new Server();
        SessionHandler handler = new SessionHandler();
        handler.setServer(server);
        final HashSessionIdManager idmgr = new HashSessionIdManager(server);
        idmgr.setServer(server);
        server.setSessionIdManager(idmgr);
        
        final FileSessionManager manager = new FileSessionManager();
        manager.getSessionDataStore().setDeleteUnrestorableFiles(true);
        //manager.setLazyLoad(true);
        File testDir = MavenTestingUtils.getTargetTestingDir("hashes");
        testDir.mkdirs();
        manager.getSessionDataStore().setStoreDir(testDir);
        manager.setSessionIdManager(idmgr);
        handler.setSessionManager(manager);
        manager.start();
        
        //Create a file that is in the parent dir of the session storeDir
        String expectedFilename =  "_0.0.0.0_dangerFile";    
        MavenTestingUtils.getTargetFile(expectedFilename).createNewFile();
        Assert.assertTrue("File should exist!", MavenTestingUtils.getTargetFile(expectedFilename).exists());

        //Verify that passing in the relative filename of an unrecoverable session does not lead
        //to deletion of file outside the session dir (needs deleteUnrecoverableFiles(true))
        Session session = manager.getSession("../_0.0.0.0_dangerFile");
        Assert.assertTrue(session == null);
        Assert.assertTrue("File should exist!", MavenTestingUtils.getTargetFile(expectedFilename).exists());

    }

    @Test
    public void testValidSessionIdRemoval() throws Exception
    {      
        Server server = new Server();
        SessionHandler handler = new SessionHandler();
        handler.setServer(server);
        final HashSessionIdManager idmgr = new HashSessionIdManager(server);
        idmgr.setServer(server);
        server.setSessionIdManager(idmgr);
        final FileSessionManager manager = new FileSessionManager();
        manager.getSessionDataStore().setDeleteUnrestorableFiles(true);
        manager.setSessionIdManager(idmgr);
        handler.setSessionManager(manager);
        File testDir = MavenTestingUtils.getTargetTestingDir("hashes");
        FS.ensureEmpty(testDir);

        manager.getSessionDataStore().setStoreDir(testDir);
        manager.start();

        String expectedFilename = "_0.0.0.0_validFile123";
        
        Assert.assertTrue(new File(testDir, expectedFilename).createNewFile());

        Assert.assertTrue("File should exist!", new File(testDir, expectedFilename).exists());

        Session session = manager.getSession("validFile123");

        Assert.assertTrue("File shouldn't exist!", !new File(testDir,expectedFilename).exists());
    }

    @Test
    public void testHashSession() throws Exception
    {
        File testDir = MavenTestingUtils.getTargetTestingDir("saved");
        IO.delete(testDir);
        testDir.mkdirs();

        Server server = new Server();
        SessionHandler handler = new SessionHandler();
        handler.setServer(server);
        FileSessionManager manager = new FileSessionManager();
        manager.getSessionDataStore().setStoreDir(testDir);
        manager.setMaxInactiveInterval(5);
        Assert.assertTrue(testDir.exists());
        Assert.assertTrue(testDir.canWrite());
        handler.setSessionManager(manager);
        
        AbstractSessionIdManager idManager = new HashSessionIdManager(server);
        idManager.setServer(server);
        idManager.setWorkerName("foo");
        manager.setSessionIdManager(idManager);
        server.setSessionIdManager(idManager);
        
        server.start();
        manager.start();
        
        Session session = (Session)manager.newHttpSession(new Request(null, null));
        String sessionId = session.getId();
        
        session.setAttribute("one", new Integer(1));
        session.setAttribute("two", new Integer(2));    
        
        //stop will persist sessions
        manager.setMaxInactiveInterval(30); // change max inactive interval for *new* sessions
        manager.stop();
        
        String expectedFilename = "_0.0.0.0_"+session.getId();
        Assert.assertTrue("File should exist!", new File(testDir, expectedFilename).exists());
        
        
        manager.start();
        
        //restore session
        Session restoredSession = (Session)manager.getSession(sessionId);
        Assert.assertNotNull(restoredSession);
        
        Object o = restoredSession.getAttribute("one");
        Assert.assertNotNull(o);
        
        Assert.assertEquals(1, ((Integer)o).intValue());
        Assert.assertEquals(5, restoredSession.getMaxInactiveInterval());     
        
        server.stop();
    }
}
