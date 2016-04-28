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
 * JDBCSessionStoreFactory
 *
 *
 */
public class JDBCSessionStoreFactory extends AbstractSessionStoreFactory
{
    
    DatabaseAdaptor _adaptor;
    JDBCSessionStore.SessionTableSchema _schema;
    boolean _deleteUnloadableSessions;
    int _loadAttempts;


    /** 
     * @see org.eclipse.jetty.server.session.SessionStoreFactory#getSessionDataStore(org.eclipse.jetty.server.session.SessionHandler)
     */
    @Override
    public SessionStore getSessionStore(SessionHandler handler)
    {
        JDBCSessionStore ds = new JDBCSessionStore();
        ds.setDatabaseAdaptor(_adaptor);
        ds.setSessionTableSchema(_schema);
        ds.setDeleteUnloadableSessions(_deleteUnloadableSessions);
        ds.setGracePeriodSec(_gracePeriodSec);
        ds.setLoadAttempts(_loadAttempts);
        return ds;
    }

    
    /**
     * @param adaptor
     */
    public void setDatabaseAdaptor (DatabaseAdaptor adaptor)
    {
        _adaptor = adaptor;
    }
    
    
    /**
     * @param schema
     */
    public void setSessionTableSchema (JDBCSessionStore.SessionTableSchema schema)
    {
        _schema = schema;
    }
}
