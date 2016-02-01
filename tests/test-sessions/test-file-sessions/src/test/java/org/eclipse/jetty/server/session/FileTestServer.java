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

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.SessionManager;
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
    
    
    
    public FileTestServer(int port)
    {
        super(port, 30, 10);
    }

    public FileTestServer(int port, int maxInactivePeriod, int scavengePeriod)
    {
        super(port, maxInactivePeriod, scavengePeriod);
    }


    public SessionIdManager newSessionIdManager(Object config)
    {
        HashSessionIdManager mgr = new HashSessionIdManager(_server);
        mgr.setWorkerName("worker"+(__workers++));
        return mgr;
    }

    public SessionManager newSessionManager()
    {
        FileSessionManager manager = new FileSessionManager();
        manager.getSessionDataStore().setStoreDir(_tmpDir);
        return manager;
    }

    public SessionHandler newSessionHandler(SessionManager sessionManager)
    {
        return new SessionHandler(sessionManager);
    }

}
