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

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.deploy.test.XmlConfiguredJetty;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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
        jetty = new XmlConfiguredJetty(testdir.getEmptyPathDir());

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

        // Start it
        jetty.start();
    }

    @AfterEach
    public void teardownEnvironment() throws Exception
    {
        LifeCycle.stop(jetty);
    }

    @Test
    public void testStartupContext()
    {
        // Check Server for Handlers
        jetty.assertContextHandlerExists("/bar");
    }
}
