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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import org.eclipse.jetty.deploy.BarContextHandler;
import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ContextProvider} as it starts up for the first time.
 */
@ExtendWith(WorkDirExtension.class)
public class ContextProviderStartupTest
{
    public WorkDir testdir;
    private static XmlConfiguredJetty jetty;

    @BeforeEach
    public void setupEnvironment() throws Exception
    {
        Path p = testdir.getEmptyPathDir();
        jetty = new XmlConfiguredJetty(p);

        Path resourceBase = jetty.getJettyBasePath().resolve("resourceBase");
        FS.ensureDirExists(resourceBase);
        jetty.setProperty("test.bar.resourceBase", resourceBase.toUri().toASCIIString());

        Files.writeString(resourceBase.resolve("text.txt"), "This is the resourceBase text");

        jetty.addConfiguration("jetty.xml");
        jetty.addConfiguration("jetty-http.xml");
        jetty.addConfiguration("jetty-deploymgr-contexts.xml");

        // Setup initial context
        jetty.copyWebapp("bar-core-context.xml", "bar.xml");

        // Should not throw an Exception
        jetty.load();
    }

    public void startJetty() throws Exception
    {
        // Start it
        jetty.start();
    }

    @AfterEach
    public void teardownEnvironment() throws Exception
    {
        LifeCycle.stop(jetty);
    }

    @Test
    public void testStartupContext() throws Exception
    {
        startJetty();

        // Check Server for Handlers
        jetty.assertContextHandlerExists("/bar");

    }

    @Test
    public void testStartupWithRelativeEnvironmentContext() throws Exception
    {
        Path jettyBase = jetty.getJettyBasePath();
        Path propsFile = Files.writeString(jettyBase.resolve("webapps/core.properties"), Deployable.ENVIRONMENT_XML + " = etc/core-context.xml", StandardOpenOption.CREATE_NEW);
        assertTrue(Files.exists(propsFile));
        Files.copy(MavenPaths.findTestResourceFile("etc/core-context.xml"), jettyBase.resolve("etc/core-context.xml"), StandardCopyOption.REPLACE_EXISTING);
        jetty.copyWebapp("bar-core-context.properties", "bar.properties");
        startJetty();

        //check environment context xml was applied to the produced context
        ContextHandler context = jetty.getContextHandler("/bar");
        assertNotNull(context);
        assertThat(context.getAttribute("somename"), equalTo("somevalue"));
        assertTrue(context instanceof BarContextHandler);

    }

    @Test
    public void testStartupWithAbsoluteEnvironmentContext() throws Exception
    {
        Path jettyBase = jetty.getJettyBasePath();
        Path propsFile = Files.writeString(jettyBase.resolve("webapps/core.properties"), Deployable.ENVIRONMENT_XML + " = " +
            MavenPaths.findTestResourceFile("etc/core-context.xml"), StandardOpenOption.CREATE_NEW);
        assertTrue(Files.exists(propsFile));
        Files.copy(MavenPaths.findTestResourceFile("etc/core-context.xml"), jettyBase.resolve("etc/core-context.xml"), StandardCopyOption.REPLACE_EXISTING);
        jetty.copyWebapp("bar-core-context.properties", "bar-core-context.properties");
        startJetty();

        //check environment context xml was applied to the produced context
        ContextHandler context = jetty.getContextHandler("/bar");
        assertNotNull(context);
        assertThat(context.getAttribute("somename"), equalTo("somevalue"));
    }
}
