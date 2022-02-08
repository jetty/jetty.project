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

package org.eclipse.jetty.deploy.providers;

import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tests {@link ScanningAppProvider} as it starts up for the first time.
 */
@ExtendWith(WorkDirExtension.class)
public class ScanningAppProviderStartupTest
{
    public WorkDir testdir;
    private static XmlConfiguredJetty jetty;

    @BeforeEach
    public void setupEnvironment() throws Exception
    {
        jetty = new XmlConfiguredJetty(testdir.getEmptyPathDir());
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-http.xml");
        jetty.addConfiguration("jetty-deploymgr-contexts.xml");

        // Setup initial context
        jetty.copyWebapp("foo.xml", "foo.xml");
        jetty.copyWebapp("foo-webapp-1.war", "foo.war");

        // Should not throw an Exception
        jetty.load();

        // Start it
        jetty.start();
    }

    @AfterEach
    public void teardownEnvironment() throws Exception
    {
        // Stop jetty.
        jetty.stop();
    }

    @Test
    public void testStartupContext()
    {
        // Check Server for Handlers
        jetty.assertWebAppContextsExists("/foo");
    }
}
