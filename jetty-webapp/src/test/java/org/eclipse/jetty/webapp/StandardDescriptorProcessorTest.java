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

package org.eclipse.jetty.webapp;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StandardDescriptorProcessorTest
{
    //TODO add tests for other methods
    
    @Test
    public void testVisitSessionConfig() throws Exception
    {
        File webXml = MavenTestingUtils.getTestResourceFile("web-session-config.xml");
        Server server = new Server();
        WebAppContext wac = new WebAppContext();
        wac.setServer(server);
        wac.setResourceBase(MavenTestingUtils.getTargetTestingDir("testSessionConfig").getAbsolutePath());
        wac.setDescriptor(webXml.toURI().toURL().toString());
        wac.start();
        assertEquals(54, TimeUnit.SECONDS.toMinutes(wac.getSessionHandler().getMaxInactiveInterval()));
    }
}
