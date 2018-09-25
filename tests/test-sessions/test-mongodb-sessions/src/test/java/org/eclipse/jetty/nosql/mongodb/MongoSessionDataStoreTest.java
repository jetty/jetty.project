//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.server.session.AbstractSessionDataStoreTest;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * MongoSessionDataStoreTest
 *
 *
 */
public class MongoSessionDataStoreTest extends AbstractSessionDataStoreTest
{
    @BeforeEach
    public  void beforeClass() throws Exception
    {
        MongoTestHelper.dropCollection();
        MongoTestHelper.createCollection();
    }

    @AfterEach
    public  void afterClass() throws Exception
    {
        MongoTestHelper.dropCollection();
    }
     

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return MongoTestHelper.newSessionDataStoreFactory();
    }


    @Override
    public void persistSession(SessionData data) throws Exception
    {
        MongoTestHelper.createSession(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
                                      data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(), data.getAllAttributes());
    }


    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {
        MongoTestHelper.createUnreadableSession(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
                                      data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(), null);
    }


    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        return MongoTestHelper.checkSessionExists(data.getId());
    }


    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
       return MongoTestHelper.checkSessionPersisted(data);
    }
}
