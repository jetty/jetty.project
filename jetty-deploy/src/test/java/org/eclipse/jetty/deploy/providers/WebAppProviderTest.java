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

package org.eclipse.jetty.deploy.providers;

import java.io.File;
import java.net.URL;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.condition.OS.LINUX;
import static org.junit.jupiter.api.condition.OS.MAC;

@ExtendWith(WorkDirExtension.class)
public class WebAppProviderTest
{
    public WorkDir testdir;
    private static XmlConfiguredJetty jetty;
    private boolean symlinkSupported = false;

    @BeforeEach
    public void setupEnvironment() throws Exception
    {
        Path p = testdir.getEmptyPathDir();
        jetty = new XmlConfiguredJetty(p);
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-http.xml");
        jetty.addConfiguration("jetty-deploy-wars.xml");

        // Setup initial context
        jetty.copyWebapp("foo-webapp-1.war", "foo.war");

        // Make symlink
        Path pathWar3 = MavenTestingUtils.getTestResourcePathFile("webapps/foo-webapp-3.war");
        Path pathBar = jetty.getJettyDir("webapps/bar.war").toPath();
        try
        {
            Files.createSymbolicLink(pathBar, pathWar3);
            symlinkSupported = true;
        }
        catch (UnsupportedOperationException | FileSystemException e)
        {
            // if unable to create symlink, no point testing that feature
            // this is the path that Microsoft Windows takes.
            symlinkSupported = false;
        }

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

    @Disabled("See issue #1200")
    @Test
    public void testStartupContext()
    {
        // Check Server for Handlers
        jetty.assertWebAppContextsExists("/bar", "/foo");

        File workDir = jetty.getJettyDir("workish");

        // Test for regressions
        assertDirNotExists("root of work directory", workDir, "webinf");
        assertDirNotExists("root of work directory", workDir, "jsp");

        // Test for correct behaviour
        assertTrue(hasJettyGeneratedPath(workDir, "foo.war"), "Should have generated directory in work directory: " + workDir);
    }
    
    @Disabled("See issue #1200")
    @Test
    public void testStartupSymlinkContext()
    {
        assumeTrue(symlinkSupported);

        // Check for path
        File barLink = jetty.getJettyDir("webapps/bar.war");
        assertTrue(barLink.exists(), "bar.war link exists: " + barLink.toString());
        assertTrue(barLink.isFile(), "bar.war link isFile: " + barLink.toString());

        // Check Server for expected Handlers
        jetty.assertWebAppContextsExists("/bar", "/foo");

        // Test for expected work/temp directory behaviour
        File workDir = jetty.getJettyDir("workish");
        assertTrue(hasJettyGeneratedPath(workDir, "bar.war"), "Should have generated directory in work directory: " + workDir);
    }
    
    @Test
    @EnabledOnOs({LINUX})
    public void testWebappSymlinkDir() throws Exception
    {
        jetty.stop(); //reconfigure jetty
        
        testdir.ensureEmpty();

        jetty = new XmlConfiguredJetty(testdir.getEmptyPathDir());
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-http.xml");
        jetty.addConfiguration("jetty-deploy-wars.xml");

        assumeTrue(symlinkSupported);

        //delete the existing webapps directory
        File webapps = jetty.getJettyDir("webapps");
        assertTrue(IO.delete(webapps));

        //make a different directory to contain webapps
        File x = jetty.getJettyDir("x");
        Files.createDirectory(x.toPath());

        //Put a webapp into it
        File srcDir = MavenTestingUtils.getTestResourceDir("webapps");
        File fooWar = new File(x, "foo.war");
        IO.copy(new File(srcDir, "foo-webapp-1.war"), fooWar);
        assertTrue(Files.exists(fooWar.toPath()));

        //make a link from x to webapps
        Files.createSymbolicLink(jetty.getJettyDir("webapps").toPath(), x.toPath());
        assertTrue(Files.exists(jetty.getJettyDir("webapps").toPath()));

        jetty.load();
        jetty.start();

        //only webapp in x should be deployed, not x itself
        jetty.assertWebAppContextsExists("/foo");
    }
    
    @Test
    @EnabledOnOs({LINUX})
    public void testBaseDirSymlink() throws Exception
    {
        jetty.stop(); //reconfigure jetty
        
        testdir.ensureEmpty();

        Path realBase = testdir.getEmptyPathDir();
        
        //set jetty up on the real base 
        jetty = new XmlConfiguredJetty(realBase);
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-http.xml");
        jetty.addConfiguration("jetty-deploy-wars.xml");
        
        //Put a webapp into the base
        jetty.copyWebapp("foo-webapp-1.war", "foo.war");

        //create the jetty structure
        jetty.load();
        jetty.start();
        Path jettyHome = jetty.getJettyHome().toPath();
        
        jetty.stop();
        
        //Make a symbolic link to the real base
        File testsDir = MavenTestingUtils.getTargetTestingDir();
        Path symlinkBase = Files.createSymbolicLink(testsDir.toPath().resolve("basedirsymlink-" + System.currentTimeMillis()), jettyHome);
        Map<String, String> properties = new HashMap<>();
        properties.put("jetty.home", jettyHome.toString());
        //Start jetty, but this time running from the symlinked base 
        System.setProperty("jetty.home", properties.get("jetty.home"));
        
        List<URL> configurations = jetty.getConfigurations();
        Server server = XmlConfiguredJetty.loadConfigurations(configurations, properties);

        try
        {
            server.start();
            HandlerCollection handlers = (HandlerCollection)server.getHandler();
            Handler[] children = server.getChildHandlersByClass(WebAppContext.class);
            assertEquals(1, children.length);
            assertEquals("/foo", ((WebAppContext)children[0]).getContextPath());
        }
        finally
        {
            server.stop();
        }
    }
    
    private Map<String, String> setupJettyProperties(Path jettyHome)
    {
        Map<String, String> properties = new HashMap<>();
        properties.put("jetty.home", jettyHome.toFile().getAbsolutePath());
        return properties;
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
            System.err.println("did not find " + expectedWarFilename + " in " + Arrays.asList(paths));
        }
        return false;
    }

    public static void assertDirNotExists(String msg, File workDir, String subdir)
    {
        File dir = new File(workDir, subdir);
        assertFalse(dir.exists(), "Should not have " + subdir + " in " + msg + " - " + workDir);
    }
}
