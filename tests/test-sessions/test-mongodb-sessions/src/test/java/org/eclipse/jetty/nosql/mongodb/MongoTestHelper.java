//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import com.mongodb.DBCollection;
import com.mongodb.Mongo;
import com.mongodb.MongoException;


/**
 * MongoTestHelper
 * 
 */
public class MongoTestHelper
{
    static int __workers=0;
    public static final String DB_NAME = "HttpSessions";
    public static final String COLLECTION_NAME = "testsessions";
    
    
    
    public static void dropCollection () throws MongoException, UnknownHostException
    {
        new Mongo().getDB(DB_NAME).getCollection(COLLECTION_NAME).drop();
    }
    
    
    public static void createCollection() throws UnknownHostException, MongoException
    {
        new Mongo().getDB(DB_NAME).createCollection(COLLECTION_NAME, null);
    }
    
    
    public static DBCollection getCollection () throws UnknownHostException, MongoException 
    {
        return new Mongo().getDB(DB_NAME).getCollection(COLLECTION_NAME);
    }
    
    
    public static MongoSessionDataStoreFactory newSessionDataStoreFactory()
    {
        MongoSessionDataStoreFactory storeFactory = new MongoSessionDataStoreFactory();
        storeFactory.setCollectionName(COLLECTION_NAME);
        storeFactory.setDbName(DB_NAME);
        return storeFactory;
    }
}
