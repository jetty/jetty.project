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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;

import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.session.SessionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SessionHandlerTest
{
    private Server _server;
    private LocalConnector _connector;
    private SessionHandler _sessionHandler;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        ContextHandler contextHandler = new ContextHandler();
        _server.setHandler(contextHandler.getCoreHandler());

        _sessionHandler = new SessionHandler();
        _sessionHandler.setSessionCookie("JSESSIONID");
        _sessionHandler.setUsingCookies(true);
        _sessionHandler.setUsingURLs(false);
        contextHandler.setHandler(_sessionHandler);

        _sessionHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                String pathInContext = request.getPathInfo();
                String[] split = pathInContext.substring(1).split("/");

                HttpSession session = request.getSession(false);

                if (split.length > 0)
                {
                    switch (split[0])
                    {
                        case "set" ->
                        {
                            if (session == null)
                                throw new IllegalStateException("No Session");

                            if (split.length > 2)
                                session.setAttribute(split[1], split[2]);
                        }

                        case "remove" ->
                        {
                            if (session == null)
                                throw new IllegalStateException("No Session");

                            if (split.length > 1)
                                session.setAttribute(split[1], null);
                        }

                        case "create" ->
                        {
                            if (session != null)
                                throw new IllegalStateException("Session already created");
                            session = request.getSession(true);
                        }

                        case "invalidate" ->
                        {
                            if (session == null)
                                throw new IllegalStateException("No Session");
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
                    for (Enumeration<String> i = session.getAttributeNames(); i.hasMoreElements();)
                    {
                        String name = i.nextElement();
                        out.append("Attribute ").append(name).append(" = ").append(session.getAttribute(name)).append('\n');
                    }
                }

                response.getOutputStream().write(out.toString().getBytes(StandardCharsets.UTF_8));
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
    public void testSessionTrackingMode()
    {
        _sessionHandler.setSessionTrackingModes(new HashSet<>(Arrays.asList(SessionTrackingMode.COOKIE, SessionTrackingMode.URL)));
        _sessionHandler.setSessionTrackingModes(Collections.singleton(SessionTrackingMode.SSL));
        assertThrows(IllegalArgumentException.class, () -> _sessionHandler.setSessionTrackingModes(new HashSet<>(Arrays.asList(SessionTrackingMode.SSL, SessionTrackingMode.URL))));
    }

    @Test
    public void testSessionListenerOrdering()
        throws Exception
    {
        final StringBuffer result = new StringBuffer();

        class Listener1 implements HttpSessionListener
        {

            @Override
            public void sessionCreated(HttpSessionEvent se)
            {
                result.append("Listener1 create;");
            }

            @Override
            public void sessionDestroyed(HttpSessionEvent se)
            {
                result.append("Listener1 destroy;");
            }
        }

        class Listener2 implements HttpSessionListener
        {

            @Override
            public void sessionCreated(HttpSessionEvent se)
            {
                result.append("Listener2 create;");
            }

            @Override
            public void sessionDestroyed(HttpSessionEvent se)
            {
                result.append("Listener2 destroy;");
            }

        }

        _sessionHandler.addEventListener(new Listener1());
        _sessionHandler.addEventListener(new Listener2());
        _server.start();

        Session session = new Session(_sessionHandler.getSessionManager(), new SessionData("aa", "_", "0.0", 0, 0, 0, 0));
        _sessionHandler.getSessionManager().callSessionCreatedListeners(session);
        _sessionHandler.getSessionManager().callSessionDestroyedListeners(session);
        assertEquals("Listener1 create;Listener2 create;Listener2 destroy;Listener1 destroy;", result.toString());
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
            Cookie: JSESSIONID=oldCookieId

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
            Cookie: JSESSIONID=oldCookieId
            
            GET /create HTTP/1.1
            Host: localhost
            Cookie: JSESSIONID=oldCookieId
            
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
            Cookie: JSESSIONID=%s
            
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
            Cookie: JSESSIONID=%s
            
            GET /set/another/attribute HTTP/1.1
            Host: localhost
            Cookie: JSESSIONID=%s
            
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
