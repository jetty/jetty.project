//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

/**
 * CachingSessionDataStoreFactory
 */
public class CachingSessionDataStoreFactory extends AbstractSessionDataStoreFactory
{

    /**
     * The SessionDataStore that will store session data.
     */
    protected SessionDataStoreFactory _sessionStoreFactory;

    protected SessionDataMapFactory _mapFactory;

    /**
     * @return the SessionDataMapFactory
     */
    public SessionDataMapFactory getMapFactory()
    {
        return _mapFactory;
    }

    /**
     * @param mapFactory the SessionDataMapFactory
     */
    public void setSessionDataMapFactory(SessionDataMapFactory mapFactory)
    {
        _mapFactory = mapFactory;
    }

    /**
     * @param factory The factory for the actual SessionDataStore that the
     * CachingSessionDataStore will delegate to
     */
    public void setSessionStoreFactory(SessionDataStoreFactory factory)
    {
        _sessionStoreFactory = factory;
    }

    /**
     * @see org.eclipse.jetty.server.session.SessionDataStoreFactory#getSessionDataStore(org.eclipse.jetty.server.session.SessionHandler)
     */
    @Override
    public SessionDataStore getSessionDataStore(SessionHandler handler) throws Exception
    {
        return new CachingSessionDataStore(_mapFactory.getSessionDataMap(), _sessionStoreFactory.getSessionDataStore(handler));
    }
}
