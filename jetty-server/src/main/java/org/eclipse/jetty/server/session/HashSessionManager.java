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
 * HashSessionManager
 *
 * In memory-only session manager.
 * 
 */
public class HashSessionManager extends SessionManager
{
    protected NullSessionDataStore _sessionDataStore = new NullSessionDataStore();
    

    /**
     * 
     */
    public HashSessionManager ()
    {
        setSessionStore(new MemorySessionStore(this));
    }
    
    @Override
    public void doStart() throws Exception
    {
        ((AbstractSessionStore)_sessionStore).setSessionDataStore(_sessionDataStore);
        
        super.doStart();
    }

    @Override
    public void doStop() throws Exception
    {
        super.doStop();
    }
    
}
