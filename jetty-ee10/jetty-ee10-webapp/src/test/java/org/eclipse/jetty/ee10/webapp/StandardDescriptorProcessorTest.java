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

package org.eclipse.jetty.ee10.webapp;

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
        wac.setBaseResource(MavenTestingUtils.getTargetTestingDir("testSessionConfig").getAbsoluteFile().toPath());
        wac.setDescriptor(webXml.toURI().toURL().toString());
        wac.start();
        assertEquals(54, TimeUnit.SECONDS.toMinutes(wac.getSessionHandler().getMaxInactiveInterval()));
        
        //test the attributes
        //comment
        assertEquals("nocomment", wac.getSessionHandler().getSessionCookieConfig().getComment());
        assertEquals("nocomment", wac.getSessionHandler().getSessionCookieConfig().getAttribute("Comment"));
        
        //domain
        assertEquals("universe", wac.getSessionHandler().getSessionCookieConfig().getDomain());
        assertEquals("universe", wac.getSessionHandler().getSessionCookieConfig().getAttribute("Domain"));
        
        //path
        assertEquals("foo", wac.getSessionHandler().getSessionCookieConfig().getPath());
        assertEquals("foo", wac.getSessionHandler().getSessionCookieConfig().getAttribute("Path"));
        
        //max-age
        assertEquals(10, wac.getSessionHandler().getSessionCookieConfig().getMaxAge());
        assertEquals("10", wac.getSessionHandler().getSessionCookieConfig().getAttribute("Max-Age"));
        
        //secure
        assertEquals(false, wac.getSessionHandler().getSessionCookieConfig().isSecure());
        assertEquals("false", wac.getSessionHandler().getSessionCookieConfig().getAttribute("Secure"));
        
        //httponly
        assertEquals(false, wac.getSessionHandler().getSessionCookieConfig().isHttpOnly());
        assertEquals("false", wac.getSessionHandler().getSessionCookieConfig().getAttribute("HttpOnly"));
    }
}
