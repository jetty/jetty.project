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


package org.eclipse.jetty.nosql.mongodb;

import java.net.UnknownHostException;

import org.eclipse.jetty.server.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.server.session.SessionDataStore;


import com.mongodb.Mongo;
import com.mongodb.MongoException;

/**
 * MongoSessionDataStoreFactory
 *
 *
 */
public class MongoSessionDataStoreFactory extends AbstractSessionDataStoreFactory
{
    String _dbName;  
    String _collectionName;
    
    
    /**
     * @return the dbName
     */
    public String getDbName()
    {
        return _dbName;
    }

    /**
     * @param dbName the dbName to set
     */
    public void setDbName(String dbName)
    {
        _dbName = dbName;
    }

    /**
     * @return the collectionName
     */
    public String getCollectionName()
    {
        return _collectionName;
    }

    /**
     * @param collectionName the collectionName to set
     */
    public void setCollectionName(String collectionName)
    {
        _collectionName = collectionName;
    }

    
    /** 
     * @throws MongoException 
     * @throws UnknownHostException 
     * @see org.eclipse.jetty.server.session.SessionDataStoreFactory#getSessionDataStore(org.eclipse.jetty.server.session.SessionHandler)
     */
    @Override
    public SessionDataStore getSessionDataStore(SessionHandler handler) throws Exception
    {
        MongoSessionDataStore store = new MongoSessionDataStore();
        store.setGracePeriodSec(getGracePeriodSec());
        store.setDBCollection(new Mongo().getDB(getDbName()).getCollection(getCollectionName()));
        return store;
    }

}
