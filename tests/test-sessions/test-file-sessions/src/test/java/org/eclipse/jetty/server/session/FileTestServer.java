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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.io.File;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.util.IO;

/**
 * @version $Revision$ $Date$
 */
public class FileTestServer extends AbstractTestServer
{
    static int __workers=0;
    static File _tmpDir;
    
    public  static void setup ()
    throws Exception
    {
        
        _tmpDir = File.createTempFile("file", null);
        _tmpDir.delete();
        _tmpDir.mkdirs();
        _tmpDir.deleteOnExit();
    }
    
    
    public static void teardown ()
    {
        IO.delete(_tmpDir);
        _tmpDir = null;
    }
    
    
    public static void assertStoreDirEmpty (boolean isEmpty)
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());
        String[] files = _tmpDir.list();
        if (isEmpty)
        {
            if (files != null)
                assertEquals(0, files.length);
        }
        else
        {
            assertNotNull(files);
            assertFalse(files.length==0);
        }
    }


    public static void assertFileExists (String sessionId, boolean exists)
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());
        String[] files = _tmpDir.list();
        assertNotNull(files);
        if (exists)
            assertFalse(files.length == 0);
        boolean found = false;
        for (String name:files)
        {
            if (name.contains(sessionId))
            {
                found = true;
                break;
            }
        }
        if (exists)
            assertTrue(found);
        else
            assertFalse(found);
    }
    
    
    public static void deleteFile (String sessionId)
    {
        assertNotNull(_tmpDir);
        assertTrue(_tmpDir.exists());
        String[] files = _tmpDir.list();
        assertNotNull(files);
        assertFalse(files.length == 0);
        String filename = null;
        for (String name:files)
        {
            if (name.contains(sessionId))
            {
                filename = name;
                break;
            }
        }
        if (filename != null)
        {
            File f = new File (_tmpDir, filename);
            assertTrue(f.delete());
        }
    }
    
    
  

    public FileTestServer(int port, int maxInactivePeriod, int scavengePeriod, int idlePassivatePeriod) throws Exception
    {
        super(port, maxInactivePeriod, scavengePeriod, idlePassivatePeriod);
    }


    public SessionIdManager newSessionIdManager(Object config)
    {
        DefaultSessionIdManager mgr = new DefaultSessionIdManager(_server);
        mgr.setWorkerName("worker"+(__workers++));
        return mgr;
    }

   

    public SessionHandler newSessionHandler()
    {
        SessionHandler handler =  new SessionHandler();
        DefaultSessionCache ss = new DefaultSessionCache(handler);
        handler.setSessionCache(ss);
        FileSessionDataStore ds = new FileSessionDataStore();
        ds.setStoreDir(_tmpDir);
        ss.setSessionDataStore(ds);
        return handler;
    }

}
