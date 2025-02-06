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

package org.eclipse.jetty.deploy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link DeploymentScanner} behaviors when in Deferred Startup mode
 */
@ExtendWith(WorkDirExtension.class)
public class DeploymentScannerDeferredStartupTest extends AbstractCleanEnvironmentTest
{
    public WorkDir testdir;
    private static XmlConfiguredJetty jetty;

    @AfterEach
    public void teardownEnvironment() throws Exception
    {
        jetty.stop();
    }

    @Test
    public void testDelayedDeploy() throws Exception
    {
        Path realBase = testdir.getEmptyPathDir();

        // Set jetty up on the real base
        jetty = new XmlConfiguredJetty(realBase);
        jetty.addConfiguration(MavenPaths.findTestResourceFile("jetty.xml"));
        jetty.addConfiguration(MavenPaths.findTestResourceFile("jetty-http.xml"));
        jetty.addConfiguration(MavenPaths.projectBase().resolve("src/main/config/etc/jetty-deployment-manager.xml"));
        jetty.addConfiguration(MavenPaths.projectBase().resolve("src/main/config/etc/jetty-deployment-scanner.xml"));
        jetty.addConfiguration(MavenPaths.findTestResourceFile("jetty-core-deploy-custom.xml"));

        // Put a context into the base
        jetty.copyWebapp("bar-core-context.xml", "bar.xml");

        // Setup /bar resourceBase
        Path resourceBase = jetty.getJettyBasePath().resolve("resourceBase");
        FS.ensureDirExists(resourceBase);
        jetty.setProperty("test.bar.resourceBase", resourceBase.toUri().toASCIIString());

        Files.writeString(resourceBase.resolve("text.txt"), "This is the resourceBase text");

        // Set defer initial scan property
        jetty.setProperty("jetty.deploy.deferInitialScan", "true");
        Server server = jetty.load();

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
                    if (event instanceof DeploymentScanner)
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

            DeploymentManager deploymentManager = server.getBean(DeploymentManager.class);
            DeploymentScanner defaultProvider = deploymentManager.getBean(DeploymentScanner.class);
            assertNotNull(defaultProvider, "Should have found DeploymentScanner");
            assertTrue(defaultProvider.isDeferInitialScan(), "The DeferInitialScan configuration should be true");

            defaultProvider.addEventListener(eventCaptureListener);
            defaultProvider.addEventListener(new Container.InheritedListener()
            {
                @Override
                public void beanAdded(Container parent, Object child)
                {
                    if (child instanceof Scanner scanner)
                    {
                        scanner.addEventListener(eventCaptureListener);
                        scanner.addListener(new Scanner.ScanCycleListener()
                        {
                            @Override
                            public void scanStarted(int cycle)
                            {
                                eventQueue.add("Scan Started [" + cycle + "]");
                            }

                            @Override
                            public void scanEnded(int cycle)
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
                List<ContextHandler> children = server.getDescendants(ContextHandler.class);
                if (children == null || children.isEmpty())
                    return false;
                ContextHandler contextHandler = children.get(0);
                if (contextHandler.isStarted())
                    return contextHandler.getContextPath();
                return null;
            }, is("/bar"));

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
}
