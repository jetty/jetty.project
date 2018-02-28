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
import org.junit.After;
import org.junit.Before;

/**
 * MongoSessionDataStoreTest
 *
 *
 */
public class MongoSessionDataStoreTest extends AbstractSessionDataStoreTest
{

    @Before
    public  void beforeClass() throws Exception
    {
        MongoTestHelper.dropCollection();
        MongoTestHelper.createCollection();
    }

    @After
    public  void afterClass() throws Exception
    {
        MongoTestHelper.dropCollection();
    }
    
    

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStoreTest#createSessionDataStoreFactory()
     */
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return MongoTestHelper.newSessionDataStoreFactory();
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStoreTest#persistSession(org.eclipse.jetty.server.session.SessionData)
     */
    @Override
    public void persistSession(SessionData data) throws Exception
    {
        MongoTestHelper.createSession(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
                                      data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(), data.getAllAttributes());
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStoreTest#persistUnreadableSession(org.eclipse.jetty.server.session.SessionData)
     */
    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {
        MongoTestHelper.createSession(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
                                      data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(), null);
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStoreTest#checkSessionPersisted(org.eclipse.jetty.server.session.SessionData)
     */
    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        // TODO Auto-generated method stub
        return false;
    }

}
