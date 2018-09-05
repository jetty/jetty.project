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


package org.eclipse.jetty.server.session;

import org.junit.After;
import org.junit.Before;

/**
 * FileSessionDataStoreTest
 *
 *
 */
public class FileSessionDataStoreTest extends AbstractSessionDataStoreTest
{
    @Before
    public void before() throws Exception
    {
       FileTestHelper.setup();
    }
    
    @After 
    public void after()
    {
       FileTestHelper.teardown();
    }
 

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return FileTestHelper.newSessionDataStoreFactory();
    }

    
    @Override
    public void persistSession(SessionData data) throws Exception
    {
        FileTestHelper.createFile(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
                                  data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(), data.getCookieSet(), data.getAllAttributes());
    }

   
    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {
        FileTestHelper.createFile(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
                                  data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(), data.getCookieSet(), null);
    }

   
    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        return (FileTestHelper.getFile(data.getId()) != null);
    }

    /** 
     * 
     */
    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        return FileTestHelper.checkSessionPersisted(data);
    }

}
