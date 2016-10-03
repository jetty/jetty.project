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

    /**
     * @param port
     * @param maxInactivePeriod
     * @param scavengePeriod
     * @param evictionPolicy
     * @throws Exception TODO
     */
    public GCloudTestServer(int port, int maxInactivePeriod, int scavengePeriod, int evictionPolicy) throws Exception
    {
        super(port, maxInactivePeriod, scavengePeriod, evictionPolicy);
    }



    /** 
     * @see org.eclipse.jetty.server.session.AbstractTestServer#newSessionHandler()
     */
    @Override
    public SessionHandler newSessionHandler()
    {
        SessionHandler handler =  new SessionHandler();
        handler.setSessionIdManager(_sessionIdManager);
        GCloudSessionDataStore ds = new GCloudSessionDataStore();
        ds.setDatastore(GCloudTestSuite.__testSupport.getDatastore());
        DefaultSessionCache ss = new DefaultSessionCache(handler);
        ss.setSessionDataStore(ds);
        handler.setSessionCache(ss);
        return handler;
    }

}
