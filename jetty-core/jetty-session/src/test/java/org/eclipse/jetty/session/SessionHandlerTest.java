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

package org.eclipse.jetty.session;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * SimpleSessionHandlerTest
 */
public class SessionHandlerTest
{
    private Server _server;
    private LocalConnector _connector;

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionHandlerTest.class);

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _server = new Server();

        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setSessionCookie("SIMPLE");
        sessionHandler.setUsingCookies(true);
        sessionHandler.setUsingURLs(false);
        sessionHandler.setSessionPath("/");
        _server.setHandler(sessionHandler);

        sessionHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String pathInContext = Request.getPathInContext(request);
                String[] split = pathInContext.substring(1).split("/");

                Session session = request.getSession(false);

                int n = 0;
                while (n < split.length)
                {
                    String action = split[n++];
                    switch (action)
                    {
                        case "set" ->
                        {
                            if (session == null)
                            {
                                callback.failed(new IllegalStateException("No Session"));
                                return true;
                            }

                            session.setAttribute(split[n++], split[n++]);
                        }

                        case "remove" ->
                        {
                            if (session == null)
                            {
                                callback.failed(new IllegalStateException("No Session"));
                                return true;
                            }

                            if (split.length > 1)
                                session.setAttribute(split[n++], null);
                        }

                        case "create" ->
                        {
                            if (session != null)
                            {
                                callback.failed(new IllegalStateException("Session already created"));
                                return true;
                            }
                            session = request.getSession(true);
                        }

                        case "invalidate" ->
                        {
                            if (session == null)
                            {
                                callback.failed(new IllegalStateException("No Session"));
                                return true;
                            }
                            session.invalidate();
                        }

                        case "change" ->
                        {
                            if (session == null)
                            {
                                callback.failed(new IllegalStateException("No Session"));
                                return true;
                            }
                            session.renewId(request, response);
                        }
                    }
                }

                StringBuilder out = new StringBuilder();
                if (session == null)
                    out.append("No Session\n");
                else
                {
                    out.append("Session=").append(session.getId()).append('\n');
                    if (session.isValid())
                    {
                        if (session.isNew())
                            out.append("New\n");
                        for (String name : session.getAttributeNameSet())
                            out.append("Attribute ").append(name).append(" = ").append(session.getAttribute(name)).append('\n');
                    }
                    else
                    {
                        out.append("Invalid\n");
                    }
                }

                Content.Sink.write(response, true, out.toString(), callback);
                return true;
            }
        });
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testNoSession() throws Exception
    {
        _server.start();

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
        _server.start();

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

        String setCookie = response.get(HttpHeader.SET_COOKIE);
        String id = setCookie.substring(setCookie.indexOf("SIMPLE=") + 7, setCookie.indexOf("; Path=/"));
        assertThat(id, not(equalTo("oldCookieId")));

        String content = response.getContent();
        assertThat(content, startsWith("Session="));

        endPoint.addInput("""
            GET / HTTP/1.1
            Host: localhost
            Cookie: SIMPLE=%s
            
            """.formatted(id));

        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
        assertThat(response.getStatus(), equalTo(200));
        content = response.getContent();
        assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
    }

    @Test
    public void testSetAttribute() throws Exception
    {
        _server.start();

        LocalConnector.LocalEndPoint endPoint = _connector.connect();
        endPoint.addInput("""
            GET /create HTTP/1.1
            Host: localhost
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        String setCookie = response.get(HttpHeader.SET_COOKIE);
        String id = setCookie.substring(setCookie.indexOf("SIMPLE=") + 7, setCookie.indexOf("; Path=/"));
        String content = response.getContent();
        assertThat(content, startsWith("Session="));

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
        assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
        content = response.getContent();
        assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
        assertThat(content, containsString("attribute = value"));

        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        content = response.getContent();
        assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
        assertThat(content, containsString("attribute = value"));
        assertThat(content, containsString("another = attribute"));
    }

    @Test
    public void testChangeSessionId() throws Exception
    {
        _server.start();

        LocalConnector.LocalEndPoint endPoint = _connector.connect();
        endPoint.addInput("""
            GET /create HTTP/1.1
            Host: localhost
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        String content = response.getContent();
        assertThat(content, startsWith("Session="));

        String setCookie = response.get(HttpHeader.SET_COOKIE);
        String id = setCookie.substring(setCookie.indexOf("SIMPLE=") + 7, setCookie.indexOf("; Path=/"));

        endPoint.addInput("""
            GET /set/attribute/value HTTP/1.1
            Host: localhost
            Cookie: SIMPLE=%s
            
            GET /change HTTP/1.1
            Host: localhost
            Cookie: SIMPLE=%s
            
            """.formatted(id, id));

        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
        content = response.getContent();
        assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
        assertThat(content, containsString("attribute = value"));

        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        setCookie = response.get(HttpHeader.SET_COOKIE);
        String newId = setCookie.substring(setCookie.indexOf("SIMPLE=") + 7, setCookie.indexOf("; Path=/"));
        assertThat(newId, not(id));
        id = newId;

        content = response.getContent();
        assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
        assertThat(content, containsString("attribute = value"));

        endPoint.addInput("""
            GET / HTTP/1.1
            Host: localhost
            Cookie: SIMPLE=%s
            
            """.formatted(id));

        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
        assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
        assertThat(content, containsString("attribute = value"));
    }

    @Test
    public void testSessionLifeCycleListener() throws Exception
    {
        List<String> history = new CopyOnWriteArrayList<>();
        _server.getContext().setAttribute("slcl", new Session.LifeCycleListener()
        {
            @Override
            public void onSessionIdChanged(Session session, String oldId)
            {
                LOGGER.debug("testSessionLifeCycleListener#onSessionIdChanged");
                history.add("changed %s->%s".formatted(oldId, session.getId()));
            }

            @Override
            public void onSessionCreated(Session session)
            {
                LOGGER.debug("testSessionLifeCycleListener#onSessionCreated");
                history.add("created %s".formatted(session.getId()));
            }

            @Override
            public void onSessionDestroyed(Session session)
            {
                LOGGER.debug("testSessionLifeCycleListener#onSessionDestroyed");
                history.add("destroyed %s".formatted(session.getId()));
            }
        });

        _server.start();

        LocalConnector.LocalEndPoint endPoint = _connector.connect();
        endPoint.addInput("""
            GET /create/change/invalidate HTTP/1.1
            Host: localhost
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        String content = response.getContent();
        assertThat(content, startsWith("Session="));
        assertThat(content, containsString("Invalid"));
        assertThat(history.size(), equalTo(3));
        assertThat(history.get(0), startsWith("created "));
        assertThat(history.get(1), startsWith("changed "));
        assertThat(history.get(2), startsWith("destroyed "));
    }

    @Test
    public void testSessionValueAttributeListener() throws Exception
    {
        List<String> history = new CopyOnWriteArrayList<>();
        _server.getContext().setAttribute("slcl", new Session.LifeCycleListener()
        {
            @Override
            public void onSessionCreated(Session session)
            {
                session.setAttribute("listener", new Session.ValueListener()
                {
                    @Override
                    public void onSessionAttributeUpdate(Session session, String name, Object oldValue, Object newValue)
                    {
                        LOGGER.debug("testSessionValueAttributeListener#onSessionAttributeUpdate");
                        history.add("attribute %s %s: %s->%s".formatted(session.getId(), name, oldValue, newValue));
                    }

                    @Override
                    public void onSessionActivation(Session session)
                    {
                        LOGGER.debug("testSessionValueAttributeListener#onSessionActivation");
                        history.add("activate %s".formatted(session.getId()));
                    }

                    @Override
                    public void onSessionPassivation(Session session)
                    {
                        LOGGER.debug("testSessionValueAttributeListener#onSessionPassivation");
                        history.add("passivate %s".formatted(session.getId()));
                    }

                    @Override
                    public String toString()
                    {
                        return "SVL";
                    }
                });
            }
        });

        _server.start();

        LocalConnector.LocalEndPoint endPoint = _connector.connect();
        endPoint.addInput("""
            GET /create/set/n1/v1/set/n2/v2/set/n1/V1/remove/n2 HTTP/1.1
            Host: localhost
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));

        assertThat(history.size(), equalTo(5));
        assertThat(history.get(0), containsString("listener: null->SVL"));
        assertThat(history.get(1), containsString("n1: null->v1"));
        assertThat(history.get(2), containsString("n2: null->v2"));
        assertThat(history.get(3), containsString("n1: v1->V1"));
        assertThat(history.get(4), containsString("n2: v2->null"));
    }
}
