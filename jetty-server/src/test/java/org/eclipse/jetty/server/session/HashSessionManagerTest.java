//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import junit.framework.Assert;

import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HashSessionManagerTest
{
    
    @After
    public void enableStacks()
    {
        enableStacks(true);
    }

    @Before
    public void quietStacks()
    {
        enableStacks(false);
    }
    
    protected void enableStacks(boolean enabled)
    {
        StdErrLog log = (StdErrLog)Log.getLogger("org.eclipse.jetty.server.session");
        log.setHideStacks(!enabled);
    }
    
    @Test
    public void testDangerousSessionIdRemoval() throws Exception
    {
        final HashSessionManager manager = new HashSessionManager();
        manager.setDeleteUnrestorableSessions(true);
        manager.setLazyLoad(true);
        File testDir = MavenTestingUtils.getTargetTestingDir("hashes");
        testDir.mkdirs();
        manager.setStoreDirectory(testDir);
        
        MavenTestingUtils.getTargetFile("dangerFile.session").createNewFile();
        
        Assert.assertTrue("File should exist!", MavenTestingUtils.getTargetFile("dangerFile.session").exists());

        manager.getSession("../../dangerFile.session");
        
        Assert.assertTrue("File should exist!", MavenTestingUtils.getTargetFile("dangerFile.session").exists());

    }
    
    @Test
    public void testValidSessionIdRemoval() throws Exception
    {
        final HashSessionManager manager = new HashSessionManager();
        manager.setDeleteUnrestorableSessions(true);
        manager.setLazyLoad(true);
        File testDir = MavenTestingUtils.getTargetTestingDir("hashes");
        testDir.mkdirs();
        manager.setStoreDirectory(testDir);
        
        new File(testDir, "validFile.session").createNewFile();
        
        Assert.assertTrue("File should exist!", new File(testDir, "validFile.session").exists());
       
        manager.getSession("validFile.session");

        Assert.assertTrue("File shouldn't exist!", !new File(testDir,"validFile.session").exists());

    }
}
