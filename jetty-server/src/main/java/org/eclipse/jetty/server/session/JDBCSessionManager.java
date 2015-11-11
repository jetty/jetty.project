//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
 * JDBCSessionManager
 *
 */
public class JDBCSessionManager extends SessionManager
{
  
    protected DatabaseAdaptor _db = new DatabaseAdaptor();
    protected JDBCSessionDataStore _sessionDataStore = new JDBCSessionDataStore();

    
    public JDBCSessionManager()
    {
        _sessionStore = new MemorySessionStore();
    }

    @Override
    public void doStart() throws Exception
    {
        _sessionDataStore.setDatabaseAdaptor(_db);
        ((AbstractSessionStore)_sessionStore).setSessionDataStore(_sessionDataStore);
        
        super.doStart();
    }

    @Override
    public void doStop() throws Exception
    {
        super.doStop();
    }

    
    /**
     * Get the db adaptor to configure jdbc settings
     * @return
     */
    public DatabaseAdaptor getDatabaseAdaptor()
    {
        return _db;
    }
    
    /**
     * Get the SessionDataStore to configure it
     * @return
     */
    public JDBCSessionDataStore getSessionDataStore ()
    {
        return _sessionDataStore;
    }
    
}
