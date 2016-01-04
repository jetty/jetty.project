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

import java.io.File;
import java.util.Arrays;

import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WebAppProviderTest
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
        jetty.addConfiguration("jetty-deploy-wars.xml");

        // Setup initial context
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

        File workDir = jetty.getJettyDir("workish");

        System.err.println("workDir="+workDir);

        // Test for regressions
        assertDirNotExists("root of work directory",workDir,"webinf");
        assertDirNotExists("root of work directory",workDir,"jsp");

        // Test for correct behaviour
        Assert.assertTrue("Should have generated directory in work directory: " + workDir,hasJettyGeneratedPath(workDir,"foo.war"));
    }

    private static boolean hasJettyGeneratedPath(File basedir, String expectedWarFilename)
    {
        File[] paths = basedir.listFiles();
        if (paths != null)
        {
            for (File path : paths)
            {
                if (path.exists() && path.isDirectory() && path.getName().startsWith("jetty-") && path.getName().contains(expectedWarFilename))
                {
                    System.err.println("Found expected generated directory: " + path);
                    return true;
                }
            }
            System.err.println("did not find "+expectedWarFilename+" in "+Arrays.asList(paths));
        }
        return false;
    }

    public static void assertDirNotExists(String msg, File workDir, String subdir)
    {
        File dir = new File(workDir,subdir);
        Assert.assertFalse("Should not have " + subdir + " in " + msg + " - " + workDir,dir.exists());
    }
}
