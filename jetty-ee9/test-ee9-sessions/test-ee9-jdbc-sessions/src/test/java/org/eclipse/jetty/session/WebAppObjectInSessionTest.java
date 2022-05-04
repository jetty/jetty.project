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

package org.eclipse.jetty.session;

import org.eclipse.jetty.ee9.session.AbstractWebAppObjectInSessionTest;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * WebAppObjectInSessionTest
 */
//TODO
@Disabled
@Testcontainers(disabledWithoutDocker = true)
public class WebAppObjectInSessionTest extends AbstractWebAppObjectInSessionTest
{
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
