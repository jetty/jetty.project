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


package org.eclipse.jetty.gcloud.session;

import org.eclipse.jetty.server.session.AbstractSessionDataStoreTest;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.junit.jupiter.api.AfterEach;

/**
 * GCloudSessionDataStoreTest
 *
 *
 */
public class GCloudSessionDataStoreTest extends AbstractSessionDataStoreTest
{

    @AfterEach
    public void teardown () throws Exception
    {
       GCloudTestSuite.__testSupport.deleteSessions();
    }
    

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return GCloudSessionTestSupport.newSessionDataStoreFactory(GCloudTestSuite.__testSupport.getDatastore());
    }

  
    @Override
    public void persistSession(SessionData data) throws Exception
    {
        GCloudTestSuite.__testSupport.createSession(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
                                                    data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(), 
                                                    data.getCookieSet(), data.getLastSaved(), data.getAllAttributes());

    }

   
    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {

        GCloudTestSuite.__testSupport.createSession(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
                                                    data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(), 
                                                    data.getCookieSet(), data.getLastSaved(), null);
    }

    
    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        return GCloudTestSuite.__testSupport.checkSessionExists(data.getId());
    }


    /** 
     * 
     */
    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        return GCloudTestSuite.__testSupport.checkSessionPersisted(data);
    }

}
