//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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

    @Override
    public SessionDataStore getSessionDataStore(SessionHandler handler) throws Exception
    {
        return new CachingSessionDataStore(_mapFactory.getSessionDataMap(), _sessionStoreFactory.getSessionDataStore(handler));
    }
}
