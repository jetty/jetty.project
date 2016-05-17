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


package org.eclipse.jetty.gcloud.session;

import org.eclipse.jetty.server.session.AbstractTestServer;
import org.eclipse.jetty.server.session.DefaultSessionCache;
import org.eclipse.jetty.server.session.SessionHandler;

/**
 * GCloudTestServer
 *
 *
 */
public class GCloudTestServer extends AbstractTestServer
{
    static protected int __maxInactivePeriod = 30;
    static protected int __scavengePeriod = 10;
    static protected int __idlePeriod = 2;

    

    /**
     * @param port
     * @param maxInactivePeriod
     * @param scavengePeriod
     * @param sessionIdMgrConfig
     */
    public GCloudTestServer(int port, int maxInactivePeriod, int scavengePeriod, int idlePassivatePeriod, GCloudConfiguration config)
    {
        super(port, maxInactivePeriod, scavengePeriod, idlePassivatePeriod, config);
    }

    /**
     * @param port
     * @param configuration
     */
    public GCloudTestServer(int port, GCloudConfiguration configuration)
    {
        super(port, 30,10, __idlePeriod, configuration);
    }


    /** 
     * @see org.eclipse.jetty.server.session.AbstractTestServer#newSessionHandler(org.eclipse.jetty.server.SessionManager)
     */
    @Override
    public SessionHandler newSessionHandler()
    {
        SessionHandler handler =  new SessionHandler();
        handler.setSessionIdManager(_sessionIdManager);
        GCloudSessionDataStore ds = new GCloudSessionDataStore();
        ds.setGCloudConfiguration((GCloudConfiguration)_config);
        DefaultSessionCache ss = new DefaultSessionCache(handler);
        ss.setSessionDataStore(ds);
        handler.setSessionCache(ss);
        return handler;
    }

}
