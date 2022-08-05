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

package org.eclipse.jetty.ee9.webapp;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class StandardDescriptorProcessorTest
{
    //TODO add tests for other methods
    Server _server;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
        _server = new Server();
        _server.start();
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        _server.stop();
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @Test
    public void testVisitSessionConfig() throws Exception
    {
        File webXml = MavenTestingUtils.getTargetFile("test-classes/web-session-config.xml");
        WebAppContext wac = new WebAppContext();
        wac.setServer(_server);
        wac.setResourceBase(MavenTestingUtils.getTargetTestingDir("testSessionConfig").getAbsolutePath());
        wac.setDescriptor(webXml.toURI().toURL().toString());
        wac.start();
        assertEquals(54, TimeUnit.SECONDS.toMinutes(wac.getSessionHandler().getMaxInactiveInterval()));
    }
}
