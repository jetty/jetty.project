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

import java.io.IOException;

import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Similar in scope to {@link MonitoredDirAppProviderStartupTest}, except is concerned with the modification of existing
 * deployed webapps due to incoming changes identified by the {@link MonitoredDirAppProvider}.
 */
public class MonitoredDirAppProviderRuntimeUpdatesTest
{
    private static XmlConfiguredJetty jetty;

    @Before
    public void setupEnvironment() throws Exception
    {
        jetty = new XmlConfiguredJetty();
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-deploymgr-contexts.xml");

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

    /**
     * Simple webapp deployment after startup of server.
     */
    @Test
    public void testAfterStartupContext() throws IOException
    {
        jetty.copyWebapp("foo-webapp-1.war","foo.war");
        jetty.copyContext("foo.xml","foo.xml");

        jetty.waitForDirectoryScan();

        jetty.assertWebAppContextsExists("/foo");
    }

    /**
     * Simple webapp deployment after startup of server, and then removal of the webapp.
     */
    @Test
    public void testAfterStartupThenRemoveContext() throws IOException
    {
        jetty.copyWebapp("foo-webapp-1.war","foo.war");
        jetty.copyContext("foo.xml","foo.xml");

        jetty.waitForDirectoryScan();

        jetty.assertWebAppContextsExists("/foo");

        jetty.removeContext("foo.xml");

        jetty.waitForDirectoryScan();

        // FIXME: hot undeploy with removal not working! - jetty.assertNoWebAppContexts();
    }

    /**
     * Simple webapp deployment after startup of server, and then removal of the webapp.
     */
    @Test
    public void testAfterStartupThenUpdateContext() throws IOException
    {
        jetty.copyWebapp("foo-webapp-1.war","foo.war");
        jetty.copyContext("foo.xml","foo.xml");

        jetty.waitForDirectoryScan();

        jetty.assertWebAppContextsExists("/foo");

        // Test that webapp response contains "-1"
        jetty.assertResponseContains("/foo/info","FooServlet-1");

        System.out.println("Updating war files");
        jetty.copyContext("foo.xml","foo.xml"); // essentially "touch" the context xml
        jetty.copyWebapp("foo-webapp-2.war","foo.war");

        // This should result in the existing foo.war being replaced with the new foo.war
        jetty.waitForDirectoryScan();
        jetty.waitForDirectoryScan();
        jetty.assertWebAppContextsExists("/foo");

        // Test that webapp response contains "-2"
        jetty.assertResponseContains("/foo/info","FooServlet-2");
    }
}
