//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.deploy.providers;

import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests {@link ScanningAppProvider} as it starts up for the first time.
 */
public class ScanningAppProviderStartupTest
{
        @Rule
        public TestingDir testdir = new TestingDir();
    private static XmlConfiguredJetty jetty;

    @Before
    public void setupEnvironment() throws Exception
    {
        jetty = new XmlConfiguredJetty(testdir);
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-http.xml");
        jetty.addConfiguration("jetty-deploymgr-contexts.xml");

        // Setup initial context
        jetty.copyWebapp("foo.xml","foo.xml");
        jetty.copyWebapp("foo-webapp-1.war","foo.war");

        // Should not throw an Exception
        jetty.load();

        // Start it
        jetty.start();
    }

    @After
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
