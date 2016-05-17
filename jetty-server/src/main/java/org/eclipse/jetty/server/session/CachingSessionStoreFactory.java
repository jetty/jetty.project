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

/**
 * CachingSessionStoreFactory
 *
 *
 */
public class CachingSessionStoreFactory extends AbstractSessionDataStoreFactory
{

    /**
     * The SessionDataStore that will store session data.
     */
    protected  SessionDataStoreFactory _backingSessionStoreFactory;
    
    
    
    
    /**
     * @param factory The factory for the actual SessionDataStore that the
     * CachingSessionStore will delegate to
     */
    public void setBackingSessionStoreFactory (SessionDataStoreFactory factory)
    {
        _backingSessionStoreFactory = factory;
    }
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStoreFactory#getSessionDataStore(org.eclipse.jetty.server.session.SessionHandler)
     */
    @Override
    public SessionDataStore getSessionDataStore(SessionHandler handler) throws Exception
    {
        // TODO configure and create a cache!
        return new CachingSessionStore(null, _backingSessionStoreFactory.getSessionDataStore(handler));    
    }

}
