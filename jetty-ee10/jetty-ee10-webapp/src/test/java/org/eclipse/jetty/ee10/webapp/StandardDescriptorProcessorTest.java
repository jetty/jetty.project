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

package org.eclipse.jetty.ee10.webapp;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalToIgnoringCase;
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
    public void testVisitSessionConfig(WorkDir workDir) throws Exception
    {
        File webXml = MavenTestingUtils.getTestResourceFile("web-session-config.xml");
        WebAppContext wac = new WebAppContext();
        wac.setServer(_server);
        Path docroot = workDir.getEmptyPathDir();
        wac.setBaseResourceAsPath(docroot);
        wac.setDescriptor(webXml.toURI().toURL().toString());
        wac.start();
        assertEquals(54, TimeUnit.SECONDS.toMinutes(wac.getSessionHandler().getMaxInactiveInterval()));
        
        //test the CookieConfig attributes and getters, and the getters on SessionHandler
        //name
        assertEquals("SPECIALSESSIONID", wac.getSessionHandler().getSessionCookieConfig().getName());
        assertEquals("SPECIALSESSIONID", wac.getSessionHandler().getSessionCookieConfig().getAttribute("Name"));
        assertEquals("SPECIALSESSIONID", wac.getSessionHandler().getSessionCookie());
        
        //comment
        assertEquals("nocomment", wac.getSessionHandler().getSessionCookieConfig().getComment());
        assertEquals("nocomment", wac.getSessionHandler().getSessionCookieConfig().getAttribute("Comment"));
        assertEquals("nocomment", wac.getSessionHandler().getSessionComment());
        
        //domain
        assertEquals("universe", wac.getSessionHandler().getSessionCookieConfig().getDomain());
        assertEquals("universe", wac.getSessionHandler().getSessionCookieConfig().getAttribute("Domain"));
        assertEquals("universe", wac.getSessionHandler().getSessionDomain());
        
        //path
        assertEquals("foo", wac.getSessionHandler().getSessionCookieConfig().getPath());
        assertEquals("foo", wac.getSessionHandler().getSessionCookieConfig().getAttribute("Path"));
        assertEquals("foo", wac.getSessionHandler().getSessionPath());
        
        //max-age
        assertEquals(10, wac.getSessionHandler().getSessionCookieConfig().getMaxAge());
        assertEquals("10", wac.getSessionHandler().getSessionCookieConfig().getAttribute("Max-Age"));
        assertEquals(10, wac.getSessionHandler().getMaxCookieAge());
        
        //secure
        assertEquals(false, wac.getSessionHandler().getSessionCookieConfig().isSecure());
        assertEquals("false", wac.getSessionHandler().getSessionCookieConfig().getAttribute("Secure"));
        assertEquals(false, wac.getSessionHandler().isSecureCookies());
        
        //httponly
        assertEquals(false, wac.getSessionHandler().getSessionCookieConfig().isHttpOnly());
        assertEquals("false", wac.getSessionHandler().getSessionCookieConfig().getAttribute("HttpOnly"));
        assertEquals(false, wac.getSessionHandler().isHttpOnly());

        //SessionCookieConfig javadoc states that all setters must be also represented as attributes
        Map<String, String> attributes = wac.getSessionHandler().getSessionCookieConfig().getAttributes();
        assertThat(attributes.keySet(),
            containsInAnyOrder(Arrays.asList(
                equalToIgnoringCase("name"),
                equalToIgnoringCase("comment"), 
                equalToIgnoringCase("domain"),
                equalToIgnoringCase("path"),
                equalToIgnoringCase("max-age"),
                equalToIgnoringCase("secure"),
                equalToIgnoringCase("httponly"),
                equalToIgnoringCase("length"),
                equalToIgnoringCase("width"),
                equalToIgnoringCase("SameSite"))));

        //test the attributes on SessionHandler do NOT contain the name
        Map<String, String> sessionAttributes = wac.getSessionHandler().getSessionAttributes();
        sessionAttributes.keySet().forEach(System.err::println);
        assertThat(sessionAttributes.keySet(),
            containsInAnyOrder(Arrays.asList(
                equalToIgnoringCase("comment"),
                equalToIgnoringCase("domain"),
                equalToIgnoringCase("path"),
                equalToIgnoringCase("max-age"),
                equalToIgnoringCase("secure"),
                equalToIgnoringCase("httponly"),
                equalToIgnoringCase("length"),
                equalToIgnoringCase("width"),
                equalToIgnoringCase("SameSite"))));
    }
}
