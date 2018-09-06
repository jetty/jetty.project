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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ClusteredOrphanedSessionTest
 */
public class ClusteredOrphanedSessionTest extends AbstractClusteredOrphanedSessionTest
{
    @BeforeEach
    public void before() throws Exception
    {
       FileTestHelper.setup();
    }
    
    @AfterEach
    public void after()
    {
       FileTestHelper.teardown();
    }
 

    /** 
     * @see org.eclipse.jetty.server.session.AbstractTestBase#createSessionDataStoreFactory()
     */
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return FileTestHelper.newSessionDataStoreFactory();
    }
    
    @Test
    @Override
    public void testOrphanedSession() throws Exception
    {
        super.testOrphanedSession();
    }
}
