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

package org.eclipse.jetty.server.handler;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MovedContextHandlerTest
{
    private Server server;
    private LocalConnector connector;

    private void start(MovedContextHandler handler) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        server.setHandler(handler);

        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testRelativeURIDiscardPathInContextDiscardQuery() throws Exception
    {
        MovedContextHandler handler = new MovedContextHandler();
        handler.setContextPath("/ctx");
        handler.setRedirectURI("/moved");
        handler.setDiscardPathInContext(true);
        handler.setDiscardQuery(true);
        start(handler);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET /ctx/path?query HTTP/1.1
            Host: localhost
                        
            """));

        assertEquals(HttpStatus.SEE_OTHER_303, response.getStatus());
        String location = response.get(HttpHeader.LOCATION);
        assertNotNull(location);
        assertThat(location, endsWith("/moved"));
    }

    @Test
    public void testRelativeURIPreservePathInContextDiscardQuery() throws Exception
    {
        MovedContextHandler handler = new MovedContextHandler();
        handler.setContextPath("/ctx");
        handler.setRedirectURI("/moved");
        handler.setDiscardPathInContext(false);
        handler.setDiscardQuery(true);
        start(handler);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET /ctx/path?query HTTP/1.1
            Host: localhost
                        
            """));

        assertEquals(HttpStatus.SEE_OTHER_303, response.getStatus());
        String location = response.get(HttpHeader.LOCATION);
        assertNotNull(location);
        assertThat(location, endsWith("/moved/path"));
    }

    @Test
    public void testRelativeURIPreservePathInContextPreserveQuery() throws Exception
    {
        MovedContextHandler handler = new MovedContextHandler();
        handler.setContextPath("/ctx");
        handler.setRedirectURI("/moved");
        handler.setDiscardPathInContext(false);
        handler.setDiscardQuery(false);
        start(handler);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET /ctx/path?query HTTP/1.1
            Host: localhost
                        
            """));

        assertEquals(HttpStatus.SEE_OTHER_303, response.getStatus());
        String location = response.get(HttpHeader.LOCATION);
        assertNotNull(location);
        assertThat(location, endsWith("/moved/path?query"));
    }

    @Test
    public void testRelativeURIPreservePathInContextCoalesceQuery() throws Exception
    {
        MovedContextHandler handler = new MovedContextHandler();
        handler.setContextPath("/ctx");
        handler.setRedirectURI("/moved?a=b");
        handler.setDiscardPathInContext(false);
        handler.setDiscardQuery(false);
        start(handler);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET /ctx/path?query HTTP/1.1
            Host: localhost
                        
            """));

        assertEquals(HttpStatus.SEE_OTHER_303, response.getStatus());
        String location = response.get(HttpHeader.LOCATION);
        assertNotNull(location);
        assertThat(location, endsWith("/moved/path?query&a=b"));
    }

    @Test
    public void testAbsoluteURIPreservePathInContextPreserveQuery() throws Exception
    {
        MovedContextHandler handler = new MovedContextHandler();
        handler.setContextPath("/ctx");
        handler.setRedirectURI("https://host/moved-path");
        handler.setDiscardPathInContext(false);
        handler.setDiscardQuery(false);
        start(handler);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET /ctx/path?query HTTP/1.1
            Host: localhost
                        
            """));

        assertEquals(HttpStatus.SEE_OTHER_303, response.getStatus());
        String location = response.get(HttpHeader.LOCATION);
        assertNotNull(location);
        assertThat(location, endsWith("https://host/moved-path/path?query"));
    }

    @Test
    public void testCacheControl() throws Exception
    {
        MovedContextHandler handler = new MovedContextHandler();
        handler.setContextPath("/ctx");
        handler.setRedirectURI("/moved");
        start(handler);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET /ctx/path?query HTTP/1.1
            Host: localhost
                        
            """));

        assertEquals(HttpStatus.SEE_OTHER_303, response.getStatus());
        String location = response.get(HttpHeader.LOCATION);
        assertNotNull(location);
        assertThat(location, endsWith("/moved"));
        assertFalse(response.contains(HttpHeader.CACHE_CONTROL));

        handler.setCacheControl("max-age=5");

        response = HttpTester.parseResponse(connector.getResponse("""
            GET /ctx/path?query HTTP/1.1
            Host: localhost
                        
            """));

        assertEquals(HttpStatus.SEE_OTHER_303, response.getStatus());
        location = response.get(HttpHeader.LOCATION);
        assertNotNull(location);
        assertThat(location, endsWith("/moved"));
        String cacheControl = response.get(HttpHeader.CACHE_CONTROL);
        assertNotNull(cacheControl);
        assertEquals("max-age=5", cacheControl);
    }

    @Test
    public void testStatusCode() throws Exception
    {
        MovedContextHandler handler = new MovedContextHandler();
        handler.setContextPath("/ctx");
        handler.setRedirectURI("/moved");
        handler.setStatusCode(HttpStatus.MOVED_PERMANENTLY_301);
        start(handler);

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse("""
            GET /ctx/path?query HTTP/1.1
            Host: localhost
                        
            """));

        assertEquals(HttpStatus.MOVED_PERMANENTLY_301, response.getStatus());
        String location = response.get(HttpHeader.LOCATION);
        assertNotNull(location);
        assertThat(location, endsWith("/moved"));
    }
}
