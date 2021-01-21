//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * WebAppObjectInSessionTest
 */
@Testcontainers(disabledWithoutDocker = true)
public class WebAppObjectInSessionTest extends AbstractWebAppObjectInSessionTest
{

    /**
     * @see org.eclipse.jetty.server.session.AbstractTestBase#createSessionDataStoreFactory()
     */
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        Resource.setDefaultUseCaches(false);
        return JdbcTestHelper.newSessionDataStoreFactory();
    }

    @Test
    public void testWebappObjectInSession() throws Exception
    {
        super.testWebappObjectInSession();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        JdbcTestHelper.shutdown(null);
    }
}
