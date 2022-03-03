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

package org.eclipse.jetty.nosql.mongodb;

import java.net.UnknownHostException;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import org.eclipse.jetty.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.session.SessionDataStore;
import org.eclipse.jetty.session.SessionManager;
import org.eclipse.jetty.util.StringUtil;

/**
 * MongoSessionDataStoreFactory
 */
public class MongoSessionDataStoreFactory extends AbstractSessionDataStoreFactory
{
    String _dbName;
    String _collectionName;
    String _host;
    String _connectionString;
    int _port = -1;

    /**
     * @return the host
     */
    public String getHost()
    {
        return _host;
    }

    /**
     * @param host the host to set
     */
    public void setHost(String host)
    {
        _host = host;
    }

    /**
     * @return the port
     */
    public int getPort()
    {
        return _port;
    }

    /**
     * @param port the port to set
     */
    public void setPort(int port)
    {
        _port = port;
    }

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
     * @return the connectionString
     */
    public String getConnectionString()
    {
        return _connectionString;
    }

    /**
     * @param connectionString the connection string to set. This has priority over dbHost and port
     */
    public void setConnectionString(String connectionString)
    {
        _connectionString = connectionString;
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
     * @throws Exception {@link UnknownHostException} if any issue while resolving MongoDB Host
     * @see org.eclipse.jetty.session.SessionDataStoreFactory#getSessionDataStore(SessionManager)
     */
    @Override
    public SessionDataStore getSessionDataStore(SessionManager sessionManager) throws Exception
    {
        MongoSessionDataStore store = new MongoSessionDataStore();
        store.setGracePeriodSec(getGracePeriodSec());
        store.setSavePeriodSec(getSavePeriodSec());
        MongoClient mongo;

        if (!StringUtil.isBlank(getConnectionString()))
            mongo = new MongoClient(new MongoClientURI(getConnectionString()));
        else if (!StringUtil.isBlank(getHost()) && getPort() != -1)
            mongo = new MongoClient(getHost(), getPort());
        else if (!StringUtil.isBlank(getHost()))
            mongo = new MongoClient(getHost());
        else
            mongo = new MongoClient();
        store.setDBCollection(mongo.getDB(getDbName()).getCollection(getCollectionName()));
        return store;
    }
}
