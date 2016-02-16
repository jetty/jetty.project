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

package org.eclipse.jetty.session.infinispan;

import org.eclipse.jetty.server.session.AbstractSessionStore;
import org.eclipse.jetty.server.session.MemorySessionStore;
import org.eclipse.jetty.server.session.SessionManager;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;



/**
 * InfinispanSessionManager
 * 
 * Convenience class to create a MemorySessionStore and an InfinispanSessionDataStore.
 * 
 */
public class InfinispanSessionManager extends SessionManager
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    

    protected InfinispanSessionDataStore _sessionDataStore;


    public InfinispanSessionManager()
    {
        _sessionStore = new MemorySessionStore();
        _sessionDataStore = new InfinispanSessionDataStore();
    }
    
    
    
    /**
     * Start the session manager.
     *
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#doStart()
     */
    @Override
    public void doStart() throws Exception
    {
        if (_sessionIdManager == null)
            throw new IllegalStateException("No session id manager defined");
        
        ((AbstractSessionStore)_sessionStore).setSessionDataStore(_sessionDataStore);
        _sessionDataStore.setSessionIdManager(_sessionIdManager);
       
        super.doStart();
    }


    /**
     * Stop the session manager.
     *
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#doStop()
     */
    @Override
    public void doStop() throws Exception
    {
        super.doStop();

    }



    /**
     * @return
     */
    public InfinispanSessionDataStore getSessionDataStore()
    {
        return _sessionDataStore;
    }

}
