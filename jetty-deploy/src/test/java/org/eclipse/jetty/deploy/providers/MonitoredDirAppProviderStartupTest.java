// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.deploy.providers;

import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests {@link MonitoredDirAppProvider} as it starts up for the first time.
 */
public class MonitoredDirAppProviderStartupTest
{
    private static XmlConfiguredJetty jetty;

    @BeforeClass
    public static void setupEnvironment() throws Exception
    {
        jetty = new XmlConfiguredJetty();
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-deploymgr-contexts.xml");

        // Setup initial context
        jetty.copyContext("foo.xml","foo.xml");
        jetty.copyWebapp("foo-webapp-1.war","foo.war");

        // Should not throw an Exception
        jetty.load();

        // Start it
        jetty.start();
    }

    @AfterClass
    public static void teardownEnvironment() throws Exception
    {
        // Stop jetty.
        jetty.stop();
    }

    @Test
    public void testStartupContext()
    {
        // Check Server for Handlers
        jetty.printHandlers(System.out);
        jetty.assertWebAppContextsExists("/foo");
    }
}
