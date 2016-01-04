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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Similar in scope to {@link ScanningAppProviderStartupTest}, except is concerned with the modification of existing
 * deployed webapps due to incoming changes identified by the {@link ScanningAppProvider}.
 */
public class ScanningAppProviderRuntimeUpdatesTest
{
    private static final Logger LOG = Log.getLogger(ScanningAppProviderRuntimeUpdatesTest.class);

    @Rule
    public TestTracker tracker = new TestTracker();
    
    @Rule
    public TestingDir testdir = new TestingDir();
    private static XmlConfiguredJetty jetty;
    private final AtomicInteger _scans = new AtomicInteger();
    private int _providers;

    @Before
    public void setupEnvironment() throws Exception
    {
        testdir.ensureEmpty();
        Resource.setDefaultUseCaches(false);
        
        jetty = new XmlConfiguredJetty(testdir);
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-http.xml");
        jetty.addConfiguration("jetty-deploymgr-contexts.xml");

        // Should not throw an Exception
        jetty.load();

        // Start it
        jetty.start();

        // monitor tick
        DeploymentManager dm = jetty.getServer().getBean(DeploymentManager.class);
        for (AppProvider provider : dm.getAppProviders())
        {
            if (provider instanceof ScanningAppProvider)
            {
                _providers++;
                ((ScanningAppProvider)provider).addScannerListener(new Scanner.ScanListener()
                {
                    public void scan()
                    {
                        _scans.incrementAndGet();
                    }
                });
            }
        }

    }

    @After
    public void teardownEnvironment() throws Exception
    {
        // Stop jetty.
        jetty.stop();
    }

    public void waitForDirectoryScan()
    {
        int scan=_scans.get()+(2*_providers);
        do
        {
            try
            {
                Thread.sleep(200);
            }
            catch(InterruptedException e)
            {
                LOG.warn(e);
            }
        }
        while(_scans.get()<scan);
    }

    /**
     * Simple webapp deployment after startup of server.
     * @throws IOException on test failure
     */
    @Test
    public void testAfterStartupContext() throws IOException
    {
        jetty.copyWebapp("foo-webapp-1.war","foo.war");
        jetty.copyWebapp("foo.xml","foo.xml");

        waitForDirectoryScan();
        waitForDirectoryScan();

        jetty.assertWebAppContextsExists("/foo");
    }

    /**
     * Simple webapp deployment after startup of server, and then removal of the webapp.
     * @throws IOException on test failure
     */
    @Test
    public void testAfterStartupThenRemoveContext() throws IOException
    {
        jetty.copyWebapp("foo-webapp-1.war","foo.war");
        jetty.copyWebapp("foo.xml","foo.xml");

        waitForDirectoryScan();
        waitForDirectoryScan();

        jetty.assertWebAppContextsExists("/foo");

        jetty.removeWebapp("foo.war");
        jetty.removeWebapp("foo.xml");

        waitForDirectoryScan();
        waitForDirectoryScan();

        jetty.assertNoWebAppContexts();
    }

    /**
     * Simple webapp deployment after startup of server, and then removal of the webapp.
     * @throws Exception on test failure
     */
    @Test
    public void testAfterStartupThenUpdateContext() throws Exception
    {
        // This test will not work on Windows as second war file would
        // not be written over the first one because of a file lock
        Assume.assumeTrue(!OS.IS_WINDOWS);
        Assume.assumeTrue(!OS.IS_OSX); // build server has issues with finding itself apparently


        jetty.copyWebapp("foo-webapp-1.war","foo.war");
        jetty.copyWebapp("foo.xml","foo.xml");

        waitForDirectoryScan();
        waitForDirectoryScan();

        jetty.assertWebAppContextsExists("/foo");

        // Test that webapp response contains "-1"
        jetty.assertResponseContains("/foo/info","FooServlet-1");

        waitForDirectoryScan();
        //System.err.println("Updating war files");
        jetty.copyWebapp("foo.xml","foo.xml"); // essentially "touch" the context xml
        jetty.copyWebapp("foo-webapp-2.war","foo.war");

        // This should result in the existing foo.war being replaced with the new foo.war
        waitForDirectoryScan();
        waitForDirectoryScan();
        jetty.assertWebAppContextsExists("/foo");

        // Test that webapp response contains "-2"
        jetty.assertResponseContains("/foo/info","FooServlet-2");
    }
}
