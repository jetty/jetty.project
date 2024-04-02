//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
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
     * Get the host.
     * @return the host
     */
    public String getHost()
    {
        return _host;
    }

    /**
     * Set the host to set.
     * @param host the host to set
     */
    public void setHost(String host)
    {
        _host = host;
    }

    /**
     * Get the port.
     * @return the port
     */
    public int getPort()
    {
        return _port;
    }

    /**
     * Set the port to set.
     * @param port the port to set
     */
    public void setPort(int port)
    {
        _port = port;
    }

    /**
     * Get the dbName.
     * @return the dbName
     */
    public String getDbName()
    {
        return _dbName;
    }

    /**
     * Set the dbName to set.
     * @param dbName the dbName to set
     */
    public void setDbName(String dbName)
    {
        _dbName = dbName;
    }

    /**
     * Get the connectionString.
     * @return the connectionString
     */
    public String getConnectionString()
    {
        return _connectionString;
    }

    /**
     * Set the connection string to set. This has priority over dbHost and port.
     * @param connectionString the connection string to set. This has priority over dbHost and port
     */
    public void setConnectionString(String connectionString)
    {
        _connectionString = connectionString;
    }

    /**
     * Get the collectionName.
     * @return the collectionName
     */
    public String getCollectionName()
    {
        return _collectionName;
    }

    /**
     * Set the collectionName to set.
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
            mongo = MongoClients.create(getConnectionString());
        else if (!StringUtil.isBlank(getHost()) && getPort() != -1)
            mongo = MongoClients.create(getHost()+":"+getPort());
        else if (!StringUtil.isBlank(getHost()))
            mongo = MongoClients.create(getHost());
        else
            mongo = MongoClients.create();
        store.setDBCollection(mongo.getDatabase(getDbName()).getCollection(getCollectionName()));
        return store;
    }
}
