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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
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

/**
 * Similar in scope to {@link ContextProviderStartupTest}, except is concerned with the modification of existing
 * deployed contexts due to incoming changes identified by the {@link ContextProvider}.
 */
@ExtendWith(WorkDirExtension.class)
public class ContextProviderRuntimeUpdatesTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextProviderRuntimeUpdatesTest.class);

    public WorkDir testdir;
    private static XmlConfiguredJetty jetty;
    private final AtomicInteger _scans = new AtomicInteger();
    private int _providerCount;

    public void createJettyBase() throws Exception
    {
        testdir.ensureEmpty();
        jetty = new XmlConfiguredJetty(testdir.getEmptyPathDir());

        Path resourceBase = jetty.getJettyBasePath().resolve("resourceBase");
        FS.ensureDirExists(resourceBase);
        jetty.setProperty("test.bar.resourceBase", resourceBase.toUri().toASCIIString());

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
     * Simple webapp deployment after startup of server.
     *
     * @throws IOException on test failure
     */
    @Test
    public void testAfterStartupContext() throws Exception
    {
        createJettyBase();
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
    public void testAfterStartupThenRemoveContext() throws Exception
    {
        createJettyBase();
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
    public void testAfterStartupThenUpdateContext() throws Exception
    {
        createJettyBase();

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
