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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Similar in scope to {@link ContextProviderStartupTest}, except is concerned with the modification of existing
 * deployed contexts due to incoming changes identified by the {@link ContextProvider}.
 */
@ExtendWith(WorkDirExtension.class)
public class ContextProviderRuntimeUpdatesTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextProviderRuntimeUpdatesTest.class);

    private static XmlConfiguredJetty jetty;
    private final AtomicInteger _scans = new AtomicInteger();
    private int _providerCount;

    public void createJettyBase(Path testdir) throws Exception
    {
        jetty = new XmlConfiguredJetty(testdir);

        Path resourceBase = jetty.getJettyBasePath().resolve("resourceBase");
        FS.ensureDirExists(resourceBase);
        jetty.setProperty("test.bar.resourceBase", resourceBase.toUri().toASCIIString());

        Path tmpBase = jetty.getJettyBasePath().resolve("tmp");
        FS.ensureDirExists(tmpBase);
        jetty.setProperty("test.tmpBase", tmpBase.toFile().getAbsolutePath());
        System.err.println(tmpBase.toFile().getAbsolutePath());

        Files.writeString(resourceBase.resolve("text.txt"), "This is the resourceBase text");

        Path resourceBaseAlt = jetty.getJettyBasePath().resolve("resourceBase-alt");
        FS.ensureDirExists(resourceBaseAlt);
        jetty.setProperty("test.bar.resourceBase.alt", resourceBaseAlt.toUri().toASCIIString());

        Files.writeString(resourceBaseAlt.resolve("alt.txt"), "This is the resourceBase-alt text");
    }

    public void startJetty() throws Exception
    {
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
            if (provider instanceof ScanningAppProvider scanningAppProvider)
            {
                _providerCount++;
                scanningAppProvider.addScannerListener(new Scanner.ScanCycleListener()
                {
                    @Override
                    public void scanEnded(int cycle)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Scan ended: {}", cycle);
                        _scans.incrementAndGet();
                    }
                });
            }
        }
    }

    @AfterEach
    public void teardownEnvironment() throws Exception
    {
        LifeCycle.stop(jetty);
    }

    public void waitForDirectoryScan()
    {
        int scan = _scans.get() + _providerCount;
        await().atMost(5, TimeUnit.SECONDS).until(() -> _scans.get() > scan);
    }

    /**
     * Test that if a war file has a context xml sibling, it will only be redeployed when the
     * context xml changes, not the war.
     */
    @Test
    public void testSelectiveDeploy(WorkDir workDir) throws Exception
    {
        Path testdir = workDir.getEmptyPathDir();
        createJettyBase(testdir);
        startJetty();

        Path webappsDir = jetty.getJettyBasePath().resolve("webapps");
        Files.createFile(webappsDir.resolve("simple.war"));
        jetty.copyWebapp("simple.xml", "simple.xml");
        waitForDirectoryScan();
        jetty.assertContextHandlerExists("/simple");
        ContextHandler contextHandler = jetty.getContextHandler("/simple");
        assertNotNull(contextHandler);
        assertEquals(jetty.getJettyBasePath().resolve("tmp").toFile().getAbsolutePath(), contextHandler.getTempDirectory().getAbsolutePath());

        //touch the context xml and check the context handler was redeployed
        jetty.copyWebapp("simple.xml", "simple.xml");
        waitForDirectoryScan();
        ContextHandler contextHandler2 = jetty.getContextHandler("/simple");
        assertNotNull(contextHandler2);
        assertNotSame(contextHandler, contextHandler2);

        //touch the war file and check the context handler was NOT redeployed
        Thread.sleep(1000L); //ensure at least a millisecond has passed
        Files.setLastModifiedTime(webappsDir.resolve("simple.war"), FileTime.fromMillis(System.currentTimeMillis()));
        waitForDirectoryScan();
        ContextHandler contextHandler3 = jetty.getContextHandler("/simple");
        assertNotNull(contextHandler3);
        assertSame(contextHandler2, contextHandler3);
    }

    /**
     * Simple webapp deployment after startup of server.
     *
     * @throws IOException on test failure
     */
    @Test
    public void testAfterStartupContext(WorkDir workDir) throws Exception
    {
        Path testdir = workDir.getEmptyPathDir();
        createJettyBase(testdir);
        startJetty();

        jetty.copyWebapp("bar-core-context.xml", "bar.xml");
        waitForDirectoryScan();
        jetty.assertContextHandlerExists("/bar");
    }

    /**
     * Simple webapp deployment after startup of server, and then removal of the webapp.
     *
     * @throws IOException on test failure
     */
    @Test
    public void testAfterStartupThenRemoveContext(WorkDir workDir) throws Exception
    {
        Path testdir = workDir.getEmptyPathDir();
        createJettyBase(testdir);
        startJetty();

        jetty.copyWebapp("bar-core-context.xml", "bar.xml");
        waitForDirectoryScan();
        jetty.assertContextHandlerExists("/bar");

        jetty.removeWebapp("bar.xml");
        waitForDirectoryScan();
        jetty.assertNoContextHandlers();
    }

    /**
     * Simple webapp deployment after startup of server, and then removal of the webapp.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testAfterStartupThenUpdateContext(WorkDir workDir) throws Exception
    {
        Path testdir = workDir.getEmptyPathDir();
        createJettyBase(testdir);

        startJetty();

        jetty.copyWebapp("bar-core-context.xml", "bar.xml");

        waitForDirectoryScan();

        jetty.assertContextHandlerExists("/bar");

        // Test that response is expected from original resourceBase
        jetty.assertResponseContains("/bar/text.txt", "This is the resourceBase text");

        waitForDirectoryScan();

        // Replace the existing bar.xml being replaced with the new bar.xml pointing to different resourceBase
        jetty.copyWebapp("bar-core-context-alt.xml", "bar.xml");

        waitForDirectoryScan();

        jetty.assertContextHandlerExists("/bar");

        // Test that deployed app now has updated resourceBase
        jetty.assertResponseContains("/bar/alt.txt", "This is the resourceBase-alt text");
    }
}
