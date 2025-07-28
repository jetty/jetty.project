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

import java.io.File;
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
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SessionHandlerTest
 */
public class SessionHandlerTest
{
    private Server _server;
    private LocalConnector _connector;
    private SessionHandler _sessionHandler;

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionHandlerTest.class);

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _server = new Server();

        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        _sessionHandler = new SessionHandler();
        _sessionHandler.setSessionCookie("SESSION_ID");
        _sessionHandler.setSessionIdPathParameterName("session_id");
        _sessionHandler.setUsingCookies(true);
        _sessionHandler.setUsingUriParameters(false);
        _sessionHandler.setSessionPath("/");
        _server.setHandler(_sessionHandler);

        _sessionHandler.setHandler(new Handler.Abstract()
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
                        out.append("URI [")
                            .append(session.encodeURI(request, "/some/path", request.getHeaders().contains(HttpHeader.COOKIE)))
                            .append("]\n");
                        out.append("RELATIVE URI [")
                            .append(session.encodeURI(request, "../", request.getHeaders().contains(HttpHeader.COOKIE)))
                            .append("]\n");
                        out.append("ABSOLUTE URI [")
                            .append(session.encodeURI(request, "http://localhost:80/foo/bar/", request.getHeaders().contains(HttpHeader.COOKIE)))
                            .append("]\n");
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
            Cookie: SESSION_ID=oldCookieId

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
            Cookie: SESSION_ID=oldCookieId
            
            GET /create HTTP/1.1
            Host: localhost
            Cookie: SESSION_ID=oldCookieId
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("No Session"));

        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));

        String setCookie = response.get(HttpHeader.SET_COOKIE);
        String id = setCookie.substring(setCookie.indexOf("SESSION_ID=") + 11, setCookie.indexOf("; Path=/"));
        assertThat(id, not(equalTo("oldCookieId")));

        String content = response.getContent();
        assertThat(content, startsWith("Session="));

        endPoint.addInput("""
            GET / HTTP/1.1
            Host: localhost
            Cookie: SESSION_ID=%s
            
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
        String id = setCookie.substring(setCookie.indexOf("SESSION_ID=") + 11, setCookie.indexOf("; Path=/"));
        String content = response.getContent();
        assertThat(content, startsWith("Session="));

        endPoint.addInput("""
            GET /set/attribute/value HTTP/1.1
            Host: localhost
            Cookie: SESSION_ID=%s
            
            GET /set/another/attribute HTTP/1.1
            Host: localhost
            Cookie: SESSION_ID=%s
            
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
        String id = setCookie.substring(setCookie.indexOf("SESSION_ID=") + 11, setCookie.indexOf("; Path=/"));

        endPoint.addInput("""
            GET /set/attribute/value HTTP/1.1
            Host: localhost
            Cookie: SESSION_ID=%s
            
            GET /change HTTP/1.1
            Host: localhost
            Cookie: SESSION_ID=%s
            
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
        String newId = setCookie.substring(setCookie.indexOf("SESSION_ID=") + 11, setCookie.indexOf("; Path=/"));
        assertThat(newId, not(id));
        id = newId;

        content = response.getContent();
        assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
        assertThat(content, containsString("attribute = value"));

        endPoint.addInput("""
            GET / HTTP/1.1
            Host: localhost
            Cookie: SESSION_ID=%s
            
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

    @Test
    public void testCookieAndURI() throws Exception
    {
        _sessionHandler.setUsingCookies(true);
        _sessionHandler.setUsingUriParameters(true);
        _server.start();

        try (LocalConnector.LocalEndPoint endPoint = _connector.connect())
        {
            endPoint.addInput("""
                GET /create HTTP/1.1
                Host: localhost
                            
                """);

            HttpTester.Response response;

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.getStatus(), equalTo(200));

            String setCookie = response.get(HttpHeader.SET_COOKIE);
            String id = setCookie.substring(setCookie.indexOf("SESSION_ID=") + 11, setCookie.indexOf("; Path=/"));

            String content = response.getContent();
            assertThat(content, startsWith("Session="));
            assertThat(content, containsString("URI [/some/path;session_id=%s]".formatted(id))); // Cookies not known to be in use
            assertThat(content, containsString("RELATIVE URI [../;session_id=%s]".formatted(id))); // Cookies not known to be in use
            assertThat(content, containsString("ABSOLUTE URI [http://localhost:80/foo/bar/;session_id=%s]".formatted(id))); // Cookies not known to be in use

            // Get with cookie
            endPoint.addInput("""
                GET / HTTP/1.1
                Host: localhost
                Cookie: SESSION_ID=%s
                            
                """.formatted(id));

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
            assertThat(response.getStatus(), equalTo(200));
            content = response.getContent();
            assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
            assertThat(content, containsString("URI [/some/path]")); // Cookies known to be in use
            assertThat(content, containsString("RELATIVE URI [../]"));
            assertThat(content, containsString("ABSOLUTE URI [http://localhost:80/foo/bar/"));

            // Get with parameter
            endPoint.addInput("""
                GET /;session_id=%s HTTP/1.1
                Host: localhost
                            
                """.formatted(id));

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
            assertThat(response.getStatus(), equalTo(200));
            content = response.getContent();
            assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
            assertThat(content, containsString("URI [/some/path;session_id=%s]".formatted(id))); // Cookies not in use
            assertThat(content, containsString("RELATIVE URI [../;session_id=%s]".formatted(id)));
            assertThat(content, containsString("ABSOLUTE URI [http://localhost:80/foo/bar/;session_id=%s]".formatted(id)));

            // Get with both, but param wrong
            endPoint.addInput("""
                GET /;session_id=wrong HTTP/1.1
                Host: localhost
                Cookie: SESSION_ID=%s
                            
                """.formatted(id));

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
            assertThat(response.getStatus(), equalTo(200));
            content = response.getContent();
            assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
            assertThat(content, containsString("URI [/some/path]")); // Cookies known to be in use
            assertThat(content, containsString("RELATIVE URI [../]"));
            assertThat(content, containsString("ABSOLUTE URI [http://localhost:80/foo/bar/]"));

            // Get with both, but cookie wrong
            endPoint.addInput("""
                GET /;session_id=%s HTTP/1.1
                Host: localhost
                Cookie: SESSION_ID=wrong
                            
                """.formatted(id));

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
            assertThat(response.getStatus(), equalTo(200));
            content = response.getContent();
            assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
            assertThat(content, containsString("URI [/some/path]")); // Cookies known to be in use
            assertThat(content, containsString("RELATIVE URI [../]"));
            assertThat(content, containsString("ABSOLUTE URI [http://localhost:80/foo/bar/]"));
        }
    }

    @Test
    public void testCookieOnly() throws Exception
    {
        _sessionHandler.setUsingCookies(true);
        _sessionHandler.setUsingUriParameters(false);
        _server.start();

        try (LocalConnector.LocalEndPoint endPoint = _connector.connect())
        {
            endPoint.addInput("""
                GET /create HTTP/1.1
                Host: localhost
                            
                """);

            HttpTester.Response response;

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.getStatus(), equalTo(200));

            String setCookie = response.get(HttpHeader.SET_COOKIE);
            String id = setCookie.substring(setCookie.indexOf("SESSION_ID=") + 11, setCookie.indexOf("; Path=/"));

            String content = response.getContent();
            assertThat(content, startsWith("Session="));
            assertThat(content, containsString("URI [/some/path]"));

            // Get with cookie
            endPoint.addInput("""
                GET / HTTP/1.1
                Host: localhost
                Cookie: SESSION_ID=%s
                            
                """.formatted(id));

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
            assertThat(response.getStatus(), equalTo(200));
            content = response.getContent();
            assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
            assertThat(content, containsString("URI [/some/path]")); // Cookies known to be in use

            // Get with multiple cookie
            endPoint.addInput("""
                GET / HTTP/1.1
                Host: localhost
                Cookie: SESSION_ID=wrong
                Cookie: SESSION_ID=%s
                Cookie: SESSION_ID=other
                            
                """.formatted(id));

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
            assertThat(response.getStatus(), equalTo(200));
            content = response.getContent();
            assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
            assertThat(content, containsString("URI [/some/path]")); // Cookies known to be in use

            // Try with parameter
            endPoint.addInput("""
                GET /;session_id=%s HTTP/1.1
                Host: localhost
                            
                """.formatted(id));

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
            assertThat(response.getStatus(), equalTo(200));
            content = response.getContent();
            assertThat(content, containsString("No Session"));

            // Get with bad cookies
            endPoint.addInput("""
                GET /;session_id=%s HTTP/1.1
                Host: localhost
                Cookie: SESSION_ID
                Cookie: SESSION_ID=
                            
                """.formatted(id));

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
            assertThat(response.getStatus(), equalTo(200));
            content = response.getContent();
            assertThat(content, containsString("No Session"));
        }
    }

    @Test
    public void testUriParameterOnly() throws Exception
    {
        _sessionHandler.setUsingCookies(false);
        _sessionHandler.setUsingUriParameters(true);
        _server.start();

        try (LocalConnector.LocalEndPoint endPoint = _connector.connect())
        {
            endPoint.addInput("""
                GET /create HTTP/1.1
                Host: localhost
                            
                """);

            HttpTester.Response response;

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.getStatus(), equalTo(200));

            assertNull(response.get(HttpHeader.SET_COOKIE));

            String content = response.getContent();
            assertThat(content, startsWith("Session="));
            int i = content.indexOf("Session=");
            String id = content.substring(i + 8, content.indexOf("\n", i + 8)) + ".node0";
            assertThat(content, containsString("URI [/some/path;session_id=%s]".formatted(id)));

            // Try with cookie
            endPoint.addInput("""
                GET / HTTP/1.1
                Host: localhost
                Cookie: SESSION_ID=%s
                            
                """.formatted(id));

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
            assertThat(response.getStatus(), equalTo(200));
            content = response.getContent();
            assertThat(content, containsString("No Session"));

            // Get with parameter
            endPoint.addInput("""
                GET /;session_id=%s HTTP/1.1
                Host: localhost
                            
                """.formatted(id));

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
            assertThat(response.getStatus(), equalTo(200));
            content = response.getContent();
            assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
            assertThat(content, containsString("URI [/some/path;session_id=%s]".formatted(id)));

            // Get with multiple parameters
            endPoint.addInput("""
                GET /;session_id=wrong;session_id=%s;session_id=other;session_id=;session_id HTTP/1.1
                Host: localhost
                            
                """.formatted(id));

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
            assertThat(response.getStatus(), equalTo(200));
            content = response.getContent();
            assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
            assertThat(content, containsString("URI [/some/path;session_id=%s]".formatted(id)));

            // Get with bad parameter
            endPoint.addInput("""
                GET /;session_id=;session_id HTTP/1.1
                Host: localhost
                            
                """);

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
            assertThat(response.getStatus(), equalTo(200));
            content = response.getContent();
            assertThat(content, containsString("No Session"));
        }
    }

    @Test
    public void testFlushOnResponseCommit() throws Exception
    {
        // Setup temporary datastore with write-through null cache

        File datastoreDir = MavenTestingUtils.getTargetTestingDir("datastore");
        IO.delete(datastoreDir);

        FileSessionDataStore datastore = new FileSessionDataStore();
        datastore.setStoreDir(datastoreDir);

        NullSessionCache cache = new NullSessionCache(_sessionHandler);
        cache.setSessionDataStore(datastore);
        cache.setFlushOnResponseCommit(true);

        _sessionHandler.setSessionCache(cache);

        _server.insertHandler(new GracefulHandler());
        _server.setStopTimeout(5_000);
        _server.start();

        String id;
        try (LocalConnector.LocalEndPoint endPoint = _connector.connect())
        {
            endPoint.addInput("""
                GET /create HTTP/1.1
                Host: localhost
                
                """);

            HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.getStatus(), equalTo(200));
            String setCookie = response.get(HttpHeader.SET_COOKIE);
            id = setCookie.substring(setCookie.indexOf("SESSION_ID=") + 11, setCookie.indexOf("; Path=/"));
            String content = response.getContent();
            assertThat(content, startsWith("Session="));

            endPoint.addInput("""
                GET /set/attribute/value HTTP/1.1
                Host: localhost
                Cookie: SESSION_ID=%s
                
                """.formatted(id));

            response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.getStatus(), equalTo(200));
            assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
            content = response.getContent();
            assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
            assertThat(content, containsString("attribute = value"));
        }

        // Session should persist through restart
        _server.stop();
        _server.start();

        try (LocalConnector.LocalEndPoint endPoint = _connector.connect())
        {
            endPoint.addInput("""
                GET /set/attribute/value HTTP/1.1
                Host: localhost
                Cookie: SESSION_ID=%s
                
                """.formatted(id));

            HttpTester.Response  response = HttpTester.parseResponse(endPoint.getResponse());
            assertThat(response.getStatus(), equalTo(200));
            assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
            String content = response.getContent();
            assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
            assertThat(content, containsString("attribute = value"));
        }
    }
}
