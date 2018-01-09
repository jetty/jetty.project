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


package org.eclipse.jetty.gcloud.session;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.eclipse.jetty.server.session.SessionHandler;



/**
 * GCloudTestServer
 *
 *
 */
public class GCloudTestServer extends AbstractTestServer
{
    static int __workers=0;
    public static int STALE_INTERVAL_SEC = 1;

 

    /**
     * @param port
     * @param maxInactivePeriod
     * @param scavengePeriod
     */
    public GCloudTestServer(int port, int maxInactivePeriod, int scavengePeriod)
    {
        super(port, maxInactivePeriod, scavengePeriod);
    }

    /**
     * @param port
     */
    public GCloudTestServer(int port)
    {
        super(port, 30,10);
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractTestServer#newSessionIdManager(java.lang.Object)
     */
    @Override
    public SessionIdManager newSessionIdManager(Object config)
    {
        GCloudSessionIdManager idManager = new GCloudSessionIdManager(getServer());
        idManager.setWorkerName("w"+(__workers++));
        return idManager;
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractTestServer#newSessionManager()
     */
    @Override
    public SessionManager newSessionManager()
    {
        GCloudSessionManager sessionManager = new GCloudSessionManager();
        sessionManager.setSessionIdManager((GCloudSessionIdManager)_sessionIdManager);
        sessionManager.setStaleIntervalSec(STALE_INTERVAL_SEC);
        sessionManager.setScavengeIntervalSec(_scavengePeriod);
        sessionManager.setDatastore(GCloudTestSuite.__testSupport.getDatastore());
        return sessionManager;
        
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractTestServer#newSessionHandler(org.eclipse.jetty.server.SessionManager)
     */
    @Override
    public SessionHandler newSessionHandler(SessionManager sessionManager)
    {
        return new SessionHandler(sessionManager);
    }

}
