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

package org.eclipse.jetty.session;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.SimpleSessionHandler.SessionAPI;
import org.eclipse.jetty.session.SimpleSessionHandler.SessionRequest;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * HouseKeeperTest
 */
public class SimpleSessionHandlerTest
{
    private Server _server;
    private LocalConnector _connector;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _server = new Server();

        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        SimpleSessionHandler sessionManager = new SimpleSessionHandler();
        sessionManager.setSessionCookie("SIMPLE");
        sessionManager.setUsingCookies(true);
        sessionManager.setUsingURLs(false);
        _server.setHandler(sessionManager);

        sessionManager.setHandler(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                String pathInContext = request.getPathInContext();
                String[] split = pathInContext.substring(1).split("/");

                SessionRequest sessionRequest = Request.as(request, SessionRequest.class);
                SessionAPI session = sessionRequest.getSession(false);

                if (split.length > 0)
                {
                    switch (split[0])
                    {
                        case "set" ->
                        {
                            if (session == null)
                            {
                                callback.failed(new IllegalStateException("No Session"));
                                return;
                            }

                            if (split.length > 2)
                                session.setAttribute(split[1], split[2]);
                        }

                        case "remove" ->
                        {
                            if (session == null)
                            {
                                callback.failed(new IllegalStateException("No Session"));
                                return;
                            }

                            if (split.length > 1)
                                session.setAttribute(split[1], null);
                        }

                        case "create" ->
                        {
                            if (session != null)
                            {
                                callback.failed(new IllegalStateException("Session already created"));
                                return;
                            }
                            session = sessionRequest.getSession(true);
                        }

                        case "invalidate" ->
                        {
                            if (session == null)
                            {
                                callback.failed(new IllegalStateException("No Session"));
                                return;
                            }
                            session.invalidate();
                        }
                    }
                }

                StringBuilder out = new StringBuilder();
                if (session == null)
                    out.append("No Session\n");
                else
                {
                    out.append("Session=").append(session.getId()).append('\n');
                    for (String name : session.getAttributeNames())
                        out.append("Attribute ").append(name).append(" = ").append(session.getAttribute(name)).append('\n');
                }

                response.write(true, callback, out.toString());
            }
        });

        _server.start();
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testNoSession() throws Exception
    {
        LocalConnector.LocalEndPoint endPoint = _connector.connect();
        endPoint.addInput("""
            GET / HTTP/1.1
            Host: localhost
            
            GET / HTTP/1.1
            Host: localhost
            Cookie: SIMPLE=oldCookieId

            """);

        HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("No Session"));

        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("No Session"));
    }

    @Test
    public void testCreateSession() throws Exception
    {
        LocalConnector.LocalEndPoint endPoint = _connector.connect();
        endPoint.addInput("""
            GET / HTTP/1.1
            Host: localhost
            Cookie: SIMPLE=oldCookieId
            
            GET /create HTTP/1.1
            Host: localhost
            Cookie: SIMPLE=oldCookieId
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("No Session"));

        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        String content = response.getContent();
        assertThat(content, startsWith("Session="));
        String id = content.substring(content.indexOf('=') + 1, content.indexOf('\n'));
        assertThat(id, not(equalTo("oldCookieId")));

        endPoint.addInput("""
            GET / HTTP/1.1
            Host: localhost
            Cookie: SIMPLE=%s
            
            """.formatted(id));

        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        content = response.getContent();
        assertThat(content, containsString("Session=" + id));
    }

    @Test
    public void testSetAttribute() throws Exception
    {
        LocalConnector.LocalEndPoint endPoint = _connector.connect();
        endPoint.addInput("""
            GET /create HTTP/1.1
            Host: localhost
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        String content = response.getContent();
        assertThat(content, startsWith("Session="));
        String id = content.substring(content.indexOf('=') + 1, content.indexOf('\n'));

        endPoint.addInput("""
            GET /set/attribute/value HTTP/1.1
            Host: localhost
            Cookie: SIMPLE=%s
            
            GET /set/another/attribute HTTP/1.1
            Host: localhost
            Cookie: SIMPLE=%s
            
            """.formatted(id, id));

        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        content = response.getContent();
        assertThat(content, containsString("Session=" + id));
        assertThat(content, containsString("attribute = value"));

        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        content = response.getContent();
        assertThat(content, containsString("Session=" + id));
        assertThat(content, containsString("attribute = value"));
        assertThat(content, containsString("another = attribute"));
    }
}
