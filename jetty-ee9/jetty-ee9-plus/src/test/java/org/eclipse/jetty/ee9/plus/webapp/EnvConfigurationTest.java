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

package org.eclipse.jetty.ee9.plus.webapp;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.plus.jndi.NamingEntryUtil;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EnvConfigurationTest
{
    Server _server;

    @BeforeEach
    public void setUp()
    {
        _server = new Server();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        if (_server != null)
            _server.stop();
    }

    @Test
    public void testWithOnlyJettyWebXml() throws Exception
    {
        Path testWebappDir = MavenTestingUtils.getTargetPath("test-classes/webapp-with-jetty-env-xml");
        assertTrue(Files.exists(testWebappDir));

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        _server.setHandler(context);
        context.setWar(testWebappDir.toFile().getAbsolutePath());
        _server.start();
        assertNotNull(NamingEntryUtil.lookupNamingEntry(context, "apricot"));
    }

    @Test
    public void testWithJettyEEWebXml() throws Exception
    {
        Path testWebappDir = MavenTestingUtils.getTargetPath("test-classes/webapp-with-jetty-ee9-env-xml");
        assertTrue(Files.exists(testWebappDir));

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        _server.setHandler(context);
        context.setWar(testWebappDir.toFile().getAbsolutePath());
        _server.start();
        assertNotNull(NamingEntryUtil.lookupNamingEntry(context, "peach"));
        assertNull(NamingEntryUtil.lookupNamingEntry(context, "cabbage"));
    }
}
