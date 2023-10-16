//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.io.File;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.condition.OS.LINUX;

@ExtendWith(WorkDirExtension.class)
public class WebAppProviderTest
{
    public WorkDir testdir;
    private static XmlConfiguredJetty jetty;
    private boolean symlinkSupported = false;

    private void startEnvironment() throws Exception
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
        Path pathFoo = jetty.getJettyDir("webapps/foo.war").toPath();
        Path pathBar = jetty.getJettyDir("webapps/bar.war").toPath();
        Path pathBob = jetty.getJettyDir("webapps/bob.war").toPath();
        try
        {
            Files.createSymbolicLink(pathBar, pathWar3);
            Files.createSymbolicLink(pathBob, pathFoo);
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

    @Test
    public void testStartupContext() throws Exception
    {
        startEnvironment();
        assumeTrue(symlinkSupported);

        // Check Server for Handlers
        jetty.assertWebAppContextsExists("/bar", "/foo", "/bob");

        File workDir = jetty.getJettyDir("workish");

        // Test for regressions
        assertDirNotExists("root of work directory", workDir, "webinf");
        assertDirNotExists("root of work directory", workDir, "jsp");

        // Test for correct behaviour
        assertTrue(hasJettyGeneratedPath(workDir, "foo_war"), "Should have generated directory in work directory: " + workDir);
    }
    
    @Test
    public void testStartupSymlinkContext() throws Exception
    {
        startEnvironment();
        assumeTrue(symlinkSupported);

        // Check for path
        File barLink = jetty.getJettyDir("webapps/bar.war");
        assertTrue(barLink.exists(), "bar.war link exists: " + barLink.toString());
        assertTrue(barLink.isFile(), "bar.war link isFile: " + barLink.toString());

        // Check Server for expected Handlers
        jetty.assertWebAppContextsExists("/bar", "/foo", "/bob");

        // Check that baseResources are not aliases
        jetty.getServer().getContainedBeans(ContextHandler.class).forEach(h -> assertFalse(h.getBaseResource().isAlias()));

        // Test for expected work/temp directory behaviour
        File workDir = jetty.getJettyDir("workish");
        assertTrue(hasJettyGeneratedPath(workDir, "_war-_bar"), "Should have generated directory in work directory: " + workDir);
    }
    
    @Test
    @EnabledOnOs({LINUX})
    public void testWebappSymlinkDir() throws Exception
    {
        jetty = new XmlConfiguredJetty(testdir.getEmptyPathDir());
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-http.xml");
        jetty.addConfiguration("jetty-deploy-wars.xml");

        //delete the existing webapps directory
        File webapps = jetty.getJettyDir("webapps");
        assertTrue(IO.delete(webapps));

        //make a different directory to contain webapps
        File x = jetty.getJettyDir("x");
        Files.createDirectory(x.toPath());

        //Put a webapp into it
        Path srcDir = MavenTestingUtils.getTestResourcePathDir("webapps");
        File fooWar = new File(x, "foo.war");
        IO.copy(srcDir.resolve("foo-webapp-1.war").toFile(), fooWar);
        assertTrue(Files.exists(fooWar.toPath()));

        try
        {
            //make a link from x to webapps
            Files.createSymbolicLink(jetty.getJettyDir("webapps").toPath(), x.toPath());
            assertTrue(Files.exists(jetty.getJettyDir("webapps").toPath()));
            symlinkSupported = true;
        }
        catch (UnsupportedOperationException | FileSystemException e)
        {
            // if unable to create symlink, no point testing that feature
            // this is the path that Microsoft Windows takes.
            symlinkSupported = false;
        }
        assumeTrue(symlinkSupported);

        jetty.load();
        jetty.start();

        //only webapp in x should be deployed, not x itself
        jetty.assertWebAppContextsExists("/foo");
    }
    
    @Test
    @EnabledOnOs({LINUX})
    public void testBaseDirSymlink() throws Exception
    {
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
        Path testsDir = MavenTestingUtils.getTargetTestingPath();
        Files.createSymbolicLink(testsDir.resolve("basedirsymlink-" + System.currentTimeMillis()), jettyHome);
        Map<String, String> properties = new HashMap<>();
        properties.put("jetty.home", jettyHome.toString());
        //Start jetty, but this time running from the symlinked base 
        System.setProperty("jetty.home", properties.get("jetty.home"));
        
        List<Resource> configurations = jetty.getConfigurations();
        Server server = XmlConfiguredJetty.loadConfigurations(configurations, properties);

        try
        {
            server.start();
            Handler[] children = server.getChildHandlersByClass(WebAppContext.class);
            assertEquals(1, children.length);
            assertEquals("/foo", ((WebAppContext)children[0]).getContextPath());
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testDelayedDeploy() throws Exception
    {
        Path realBase = testdir.getEmptyPathDir();

        //set jetty up on the real base
        jetty = new XmlConfiguredJetty(realBase);
        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-http.xml");
        jetty.addConfiguration("jetty-deploy-wars.xml");

        //Put a webapp into the base
        jetty.copyWebapp("foo-webapp-1.war", "foo.war");

        Path jettyHome = jetty.getJettyHome().toPath();

        Map<String, String> properties = new HashMap<>();
        properties.put("jetty.home", jettyHome.toString());
        properties.put("jetty.deploy.deferInitialScan", "true");
        //Start jetty, but this time running from the symlinked base
        System.setProperty("jetty.home", properties.get("jetty.home"));

        List<Resource> configurations = jetty.getConfigurations();
        Server server = XmlConfiguredJetty.loadConfigurations(configurations, properties);

        try
        {
            BlockingQueue<String> eventQueue = new LinkedBlockingDeque<>();

            LifeCycle.Listener eventCaptureListener = new LifeCycle.Listener()
            {
                @Override
                public void lifeCycleStarted(LifeCycle event)
                {
                    if (event instanceof Server)
                    {
                        eventQueue.add("Server started");
                    }
                    if (event instanceof ScanningAppProvider)
                    {
                        eventQueue.add("ScanningAppProvider started");
                    }
                    if (event instanceof Scanner)
                    {
                        eventQueue.add("Scanner started");
                    }
                }
            };

            server.addEventListener(eventCaptureListener);

            ScanningAppProvider scanningAppProvider = null;
            DeploymentManager deploymentManager = server.getBean(DeploymentManager.class);
            for (AppProvider appProvider : deploymentManager.getAppProviders())
            {
                if (appProvider instanceof ScanningAppProvider)
                {
                    scanningAppProvider = (ScanningAppProvider)appProvider;
                }
            }
            assertNotNull(scanningAppProvider, "Should have found ScanningAppProvider");
            assertTrue(scanningAppProvider.isDeferInitialScan(), "The DeferInitialScan configuration should be true");

            scanningAppProvider.addEventListener(eventCaptureListener);
            scanningAppProvider.addEventListener(new Container.InheritedListener()
            {
                @Override
                public void beanAdded(Container parent, Object child)
                {
                    if (child instanceof Scanner)
                    {
                        Scanner scanner = (Scanner)child;
                        scanner.addEventListener(eventCaptureListener);
                        scanner.addListener(new Scanner.ScanCycleListener()
                        {
                            @Override
                            public void scanStarted(int cycle) throws Exception
                            {
                                eventQueue.add("Scan Started [" + cycle + "]");
                            }

                            @Override
                            public void scanEnded(int cycle) throws Exception
                            {
                                eventQueue.add("Scan Ended [" + cycle + "]");
                            }
                        });
                    }
                }

                @Override
                public void beanRemoved(Container parent, Object child)
                {
                    // no-op
                }
            });

            server.start();

            // Wait till the webapp is deployed and started
            await().atMost(Duration.ofSeconds(5)).until(() ->
            {
                Handler[] children = server.getChildHandlersByClass(WebAppContext.class);
                if (children == null || children.length == 0)
                    return false;
                WebAppContext webAppContext = (WebAppContext)children[0];
                if (webAppContext.isStarted())
                    return webAppContext.getContextPath();
                return null;
            }, is("/foo"));

            String[] expectedOrderedEvents = {
                // The deepest component starts first
                "Scanner started",
                "ScanningAppProvider started",
                "Server started",
                // We should see scan events after the server has started
                "Scan Started [1]",
                "Scan Ended [1]",
                "Scan Started [2]",
                "Scan Ended [2]"
            };

            assertThat(eventQueue.size(), is(expectedOrderedEvents.length));
            // collect string array representing ACTUAL scan events (useful for meaningful error message on failed assertion)
            String scanEventsStr = "[\"" + String.join("\", \"", eventQueue) + "\"]";
            for (int i = 0; i < expectedOrderedEvents.length; i++)
            {
                String event = eventQueue.poll(5, TimeUnit.SECONDS);
                assertThat("Expected Event [" + i + "]: " + scanEventsStr, event, is(expectedOrderedEvents[i]));
            }
        }
        finally
        {
            server.stop();
        }
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
