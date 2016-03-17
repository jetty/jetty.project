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

import org.eclipse.jetty.server.session.AbstractSessionStore;
import org.eclipse.jetty.server.session.MemorySessionStore;
import org.eclipse.jetty.server.session.SessionManager;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;



/**
 * GCloudSessionManager
 * 
 *  Convenience class to link up a MemorySessionStore with the  GCloudSessionDataStore.
 *  
 */
public class GCloudSessionManager extends SessionManager
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");


    private GCloudSessionDataStore _sessionDataStore = null;

    
    /**
     * 
     */
    public GCloudSessionManager()
    {
        _sessionDataStore = new GCloudSessionDataStore();
        _sessionStore = new MemorySessionStore();
    }

    
    

    /**
     * @return
     */
    public GCloudSessionDataStore getSessionDataStore()
    {
        return _sessionDataStore;
    }





    /**
     * Start the session manager.
     *
     * @see org.eclipse.jetty.server.session.SessionManager#doStart()
     */
    @Override
    public void doStart() throws Exception
    {
        ((AbstractSessionStore)_sessionStore).setSessionDataStore(_sessionDataStore);
        super.doStart();
    }


    /**
     * Stop the session manager.
     *
     * @see org.eclipse.jetty.server.session.SessionManager#doStop()
     */
    @Override
    public void doStop() throws Exception
    {
        super.doStop();
    }
}
