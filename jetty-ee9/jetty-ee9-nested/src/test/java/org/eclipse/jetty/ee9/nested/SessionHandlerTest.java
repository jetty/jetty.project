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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.function.Function;

import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpCookieUtils;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.session.AbstractSessionCache;
import org.eclipse.jetty.session.DefaultSessionIdManager;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.session.NullSessionDataStore;
import org.eclipse.jetty.session.SessionConfig;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SessionHandlerTest
{
    private Server _server;
    private LocalConnector _connector;
    private SessionHandler _sessionHandler;
    private ContextHandler _contextHandler;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        _contextHandler = new ContextHandler();
        _server.setHandler(_contextHandler);

        _sessionHandler = new SessionHandler();
        _sessionHandler.setSessionCookie("JSESSIONID");
        _sessionHandler.setUsingCookies(true);
        _sessionHandler.setUsingUriParameters(false);
        _sessionHandler.setSessionPath("/");
        _contextHandler.setHandler(_sessionHandler);

        _sessionHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                String pathInContext = request.getPathInfo();
                String[] split = pathInContext.substring(1).split("/");

                String requestedSessionId = request.getRequestedSessionId();
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
                            session = null;
                        }

                        case "change" ->
                        {
                            if (session == null)
                                throw new IllegalStateException("No Session");
                            request.changeSessionId();
                        }
                    }
                }

                StringBuilder out = new StringBuilder();
                out.append("requestedSessionId=" + requestedSessionId).append('\n');
                out.append("requestedSessionIdValid=" + request.isRequestedSessionIdValid()).append('\n');


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
    public void testSessionCookieConfig() throws Exception
    {
        Server server = new Server();
        MockSessionIdManager idMgr = new MockSessionIdManager(server);
        idMgr.setWorkerName("node1");
        SessionHandler mgr = new SessionHandler();
        MockSessionCache cache = new MockSessionCache(mgr.getSessionManager());
        cache.setSessionDataStore(new NullSessionDataStore());
        mgr.setSessionCache(cache);
        mgr.setSessionIdManager(idMgr);

        long now = System.currentTimeMillis();

        ManagedSession session = new ManagedSession(mgr.getSessionManager(), new SessionData("123", "_foo", "0.0.0.0", now, now, now, 30));
        session.setExtendedId("123.node1");
        SessionCookieConfig sessionCookieConfig = mgr.getSessionCookieConfig();
        sessionCookieConfig.setName("SPECIAL");
        sessionCookieConfig.setDomain("universe");
        sessionCookieConfig.setHttpOnly(false);
        sessionCookieConfig.setSecure(false);
        sessionCookieConfig.setPath("/foo");
        sessionCookieConfig.setMaxAge(99);

        //test setting SameSite and Partitioned the old way in the comment
        sessionCookieConfig.setComment(Response.PARTITIONED_COMMENT + " " + Response.SAME_SITE_STRICT_COMMENT);
        
        HttpCookie cookie = mgr.getSessionManager().getSessionCookie(session, false);
        assertEquals("SPECIAL", cookie.getName());
        assertEquals("universe", cookie.getDomain());
        assertEquals("/foo", cookie.getPath());
        assertFalse(cookie.isHttpOnly());
        assertFalse(cookie.isSecure());
        assertTrue(cookie.isPartitioned());
        assertEquals(99, cookie.getMaxAge());
        assertEquals(HttpCookie.SameSite.STRICT, cookie.getSameSite());

        String cookieStr = HttpCookieUtils.getRFC6265SetCookie(cookie);
        assertThat(cookieStr, containsString("; Partitioned; SameSite=Strict"));
    }

    @Test
    public void testSessionCookieViaSetters() throws Exception
    {
        Server server = new Server();
        MockSessionIdManager idMgr = new MockSessionIdManager(server);
        idMgr.setWorkerName("node1");
        SessionHandler mgr = new SessionHandler();
        MockSessionCache cache = new MockSessionCache(mgr.getSessionManager());
        cache.setSessionDataStore(new NullSessionDataStore());
        mgr.setSessionCache(cache);
        mgr.setSessionIdManager(idMgr);

        long now = System.currentTimeMillis();

        ManagedSession session = new ManagedSession(mgr.getSessionManager(), new SessionData("123", "_foo", "0.0.0.0", now, now, now, 30));
        session.setExtendedId("123.node1");

        //test setting up session cookie via setters on SessionHandler
        mgr.setSessionCookie("SPECIAL");
        mgr.setSessionDomain("universe");
        mgr.setHttpOnly(false);
        mgr.setSecureCookies(false);
        mgr.setSessionPath("/foo");
        mgr.setMaxCookieAge(99);
        mgr.setSameSite(HttpCookie.SameSite.STRICT);
        mgr.setPartitioned(true);

        HttpCookie cookie = mgr.getSessionManager().getSessionCookie(session, false);
        assertEquals("SPECIAL", cookie.getName());
        assertEquals("universe", cookie.getDomain());
        assertEquals("/foo", cookie.getPath());
        assertFalse(cookie.isHttpOnly());
        assertFalse(cookie.isSecure());
        assertTrue(cookie.isPartitioned());
        assertEquals(99, cookie.getMaxAge());
        assertEquals(HttpCookie.SameSite.STRICT, cookie.getSameSite());

        String cookieStr = HttpCookieUtils.getRFC6265SetCookie(cookie);
        assertThat(cookieStr, containsString("; Partitioned; SameSite=Strict"));
    }

    @Test
    public void testSessionCookieConfigByInitParam() throws Exception
    {
        Server server = new Server();
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/test");
        SessionHandler sessionHandler = new SessionHandler();
        contextHandler.setHandler(sessionHandler);
        server.setHandler(contextHandler);
        server.start();

        assertEquals(SessionConfig.__DefaultSessionCookie, sessionHandler.getSessionCookie());
        assertEquals(null, sessionHandler.getSessionDomain());
        assertEquals(SessionConfig.__DefaultSessionIdPathParameterName, sessionHandler.getSessionIdPathParameterName());
        assertEquals("/test", sessionHandler.getSessionPath());
        assertEquals(-1, sessionHandler.getMaxCookieAge());
        assertEquals(false, sessionHandler.isCheckingRemoteSessionIdEncoding());

        server.stop();

        //make a new ContextHandler and SessionHandler that can be configured
        contextHandler = new ContextHandler();
        contextHandler.setContextPath("/test");
        sessionHandler = new SessionHandler();
        contextHandler.setHandler(sessionHandler);
        server.setHandler(contextHandler);

        contextHandler.setInitParameter(SessionConfig.__SessionCookieProperty, "TEST_SESSION_COOKIE");
        contextHandler.setInitParameter(SessionConfig.__SessionDomainProperty, "TEST_DOMAIN");
        contextHandler.setInitParameter(SessionConfig.__SessionIdPathParameterNameProperty, "TEST_SESSION_ID_PATH_PARAM");
        contextHandler.setInitParameter(SessionConfig.__SessionPathProperty, "/mypath");
        contextHandler.setInitParameter(SessionConfig.__MaxAgeProperty, "1000");
        contextHandler.setInitParameter(SessionConfig.__CheckRemoteSessionEncodingProperty, "true");

        server.start();

        assertEquals("TEST_SESSION_COOKIE", sessionHandler.getSessionCookie());
        assertEquals("TEST_DOMAIN", sessionHandler.getSessionDomain());
        assertEquals("TEST_SESSION_ID_PATH_PARAM", sessionHandler.getSessionIdPathParameterName());
        assertEquals("/mypath", sessionHandler.getSessionPath());
        assertEquals(1000, sessionHandler.getMaxCookieAge());
        assertEquals(true, sessionHandler.isCheckingRemoteSessionIdEncoding());
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

        Session session = new ManagedSession(_sessionHandler.getSessionManager(), new SessionData("aa", "_", "0.0", 0, 0, 0, 0));
        _sessionHandler.getSessionManager().onSessionCreated(session);
        _sessionHandler.getSessionManager().onSessionDestroyed(session);
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
        _server.stop();
        _sessionHandler.setSessionPath(null);
        _contextHandler.setContextPath("/");
        _server.start();
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
        String setCookie = response.get("SET-COOKIE");
        assertThat(setCookie, containsString("Path=/"));
        String content = response.getContent();
        assertThat(content, containsString("Session="));
        String id = content.substring(content.indexOf("Session=") + 8);
        id = id.trim();
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
    public void testRequestedSessionIdFromCookie() throws Exception
    {
        _server.stop();
        _sessionHandler.setSessionPath(null);
        _contextHandler.setContextPath("/");
        _server.start();

        //test with no session cookie
        LocalConnector.LocalEndPoint endPoint = _connector.connect();
        endPoint.addInput("""
            GET / HTTP/1.1
            Host: localhost
                        
            """);

        HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("No Session"));
        assertThat(response.getContent(), containsString("requestedSessionIdValid=false"));

        //test with a cookie for non-existant session
        endPoint.addInput("""
            GET / HTTP/1.1
            Host: localhost
            Cookie: JSESSIONID=%s
                        
            """.formatted("123456789"));
        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("No Session"));
        assertThat(response.getContent(), containsString("requestedSessionIdValid=false"));

        //Make a real session
        endPoint.addInput("""
            GET /create HTTP/1.1
            Host: localhost
                        
            """);

        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        String content = response.getContent();
        assertThat(content, containsString("Session="));
        String id = content.substring(content.indexOf("Session=") + 8);
        id = id.trim();

        //Check the requestedSessionId is valid
        endPoint.addInput("""
            GET / HTTP/1.1
            Host: localhost
            Cookie: JSESSIONID=%s
                        
            """.formatted(id));
        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getContent(), containsString("requestedSessionIdValid=true"));

        //Invalidate and check requestedSessionId is invalid
        endPoint.addInput("""
            GET /invalidate HTTP/1.1
            Host: localhost
            Cookie: JSESSIONID=%s
                        
            """.formatted(id));
        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getContent(), containsString("requestedSessionIdValid=false"));
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
        assertThat(content, containsString("Session="));
        String id = content.substring(content.indexOf("Session=") + 8);
        id = id.trim();

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

    @Test
    public void testChangeSessionId() throws Exception
    {
        LocalConnector.LocalEndPoint endPoint = _connector.connect();
        endPoint.addInput("""
            GET /create HTTP/1.1
            Host: localhost
            
            """);

        HttpTester.Response response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        String content = response.getContent();
        assertThat(content, containsString("Session="));

        String setCookie = response.get(HttpHeader.SET_COOKIE);
        String id = setCookie.substring(setCookie.indexOf("JSESSIONID=") + 11, setCookie.indexOf("; Path=/"));

        endPoint.addInput("""
            GET /set/attribute/value HTTP/1.1
            Host: localhost
            Cookie: JSESSIONID=%s
            
            GET /change HTTP/1.1
            Host: localhost
            Cookie: JSESSIONID=%s
            
            """.formatted(id, id));

        // response to set request
        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
        assertThat(response.getStatus(), equalTo(200));
        content = response.getContent();
        assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
        assertThat(content, containsString("attribute = value"));

        // response to change request
        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        setCookie = response.get(HttpHeader.SET_COOKIE);
        String newId = setCookie.substring(setCookie.indexOf("JSESSIONID=") + 11, setCookie.indexOf("; Path=/"));
        assertThat(newId, not(id));
        id = newId;

        content = response.getContent();
        assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
        assertThat(content, containsString("attribute = value"));

        endPoint.addInput("""
            GET / HTTP/1.1
            Host: localhost
            Cookie: JSESSIONID=%s
            
            """.formatted(id));

        response = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.get(HttpHeader.SET_COOKIE), nullValue());
        assertThat(content, containsString("Session=" + id.substring(0, id.indexOf(".node0"))));
        assertThat(content, containsString("attribute = value"));
    }

    public class MockSessionCache extends AbstractSessionCache
    {

        public MockSessionCache(SessionManager manager)
        {
            super(manager);
        }

        @Override
        public void shutdown()
        {
        }

        @Override
        public ManagedSession doGet(String key)
        {
            return null;
        }

        @Override
        public Session doPutIfAbsent(String key, ManagedSession session)
        {
            return null;
        }

        @Override
        public ManagedSession doDelete(String key)
        {
            return null;
        }

        @Override
        public boolean doReplace(String id, ManagedSession oldValue, ManagedSession newValue)
        {
            return false;
        }

        @Override
        public ManagedSession newSession(SessionData data)
        {
            return null;
        }

        @Override
        protected ManagedSession doComputeIfAbsent(String id, Function<String, ManagedSession> mappingFunction)
        {
            return mappingFunction.apply(id);
        }
    }

    public class MockSessionIdManager extends DefaultSessionIdManager
    {
        public MockSessionIdManager(Server server)
        {
            super(server);
        }

        @Override
        public boolean isIdInUse(String id)
        {
            return false;
        }

        @Override
        public void expireAll(String id)
        {

        }

        @Override
        public String renewSessionId(String oldClusterId, String oldNodeId, org.eclipse.jetty.server.Request request)
        {
            return "";
        }
    }
    
}
