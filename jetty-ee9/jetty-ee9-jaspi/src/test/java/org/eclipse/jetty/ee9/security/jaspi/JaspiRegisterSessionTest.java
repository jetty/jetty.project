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

package org.eclipse.jetty.ee9.security.jaspi;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.MessagePolicy;
import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.module.ServerAuthModule;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.ServletConstraint;
import org.eclipse.jetty.ee9.security.ConstraintMapping;
import org.eclipse.jetty.ee9.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee9.security.jaspi.provider.JaspiAuthConfigProvider;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests the {@code jakarta.servlet.http.registerSession} property of the Servlet Container
 * Profile, with a module that continues a registered authentication session the way the profile
 * describes: by naming the caller it reads back from {@code getUserPrincipal}.
 */
@Isolated("Sets the global AuthConfigFactory")
public class JaspiRegisterSessionTest
{
    private Server _server;
    private LocalConnector _connector;
    private ServletContextHandler _context;
    private AuthConfigFactory _factory;
    private RegisterSessionAuthModule _authModule;

    @BeforeEach
    public void setUp() throws Exception
    {
        _factory = new DefaultAuthConfigFactory();
        AuthConfigFactory.setFactory(_factory);

        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        _context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        _context.setContextPath("/ctx");
        _server.setHandler(_context);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        _context.setSecurityHandler(security);
        security.setAuthenticatorFactory(new JaspiAuthenticatorFactory());

        ServletConstraint constraint = new ServletConstraint("All", "architect");
        constraint.setAuthenticate(true);
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/secured/*");
        mapping.setConstraint(constraint);
        security.addConstraintMapping(mapping);

        _authModule = new RegisterSessionAuthModule();
        _factory.registerConfigProvider(new JaspiAuthConfigProvider(_authModule),
            "HttpServlet", "null /ctx", "test provider");
        _context.addServlet(new ServletHolder(new AuthInfoServlet()), "/");
        _server.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
        AuthConfigFactory.setFactory(null);
    }

    @Test
    public void testRegisteredSessionIsRestoredBeforeValidateRequest() throws Exception
    {
        HttpTester.Response response = request("/ctx/secured/resource", "X-Login: true", null);
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("principal=test"));
        assertThat(response.getContent(), containsString("architect=true"));

        String session = sessionCookie(response);
        assertThat(session, notNullValue());

        // The module sees the registered caller and continues the session, and the registered roles come back though it only named the caller.
        response = request("/ctx/secured/resource", "X-Continue-Session: true", session);
        assertThat(_authModule.getValidateRequestCount(), equalTo(2));
        assertThat(_authModule.getPrincipalSeen(), equalTo("test"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("principal=test"));
        assertThat(response.getContent(), containsString("architect=true"));
    }

    @Test
    public void testTheAuthTypeOfTheRegisteredSessionIsContinuedWithIt() throws Exception
    {
        HttpTester.Response response = request("/ctx/secured/resource", "X-Login: true", null);
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("authType=TEST-AUTH"));
        String session = sessionCookie(response);

        // The module names no authType again, so the one the authentication session holds is restored with it.
        response = request("/ctx/secured/resource", "X-Continue-Session: true", session);
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("authType=TEST-AUTH"));
    }

    @Test
    public void testTheRegisteredSessionIsContinuedByNamingTheCaller() throws Exception
    {
        HttpTester.Response response = request("/ctx/secured/resource", "X-Login: true", null);
        assertThat(response.getStatus(), equalTo(200));
        String session = sessionCookie(response);

        // Naming the caller is the profile's other way to continue the session, and the registered roles come back as they do when the principal is passed.
        response = request("/ctx/secured/resource", "X-Continue-Session-By-Name: true", session);
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("principal=test"));
        assertThat(response.getContent(), containsString("architect=true"));
    }

    @Test
    public void testTheGroupsAModuleEstablishesOnAContinuationAreTheOnesThatStand() throws Exception
    {
        HttpTester.Response response = request("/ctx/secured/resource", "X-Login: true", null);
        assertThat(response.getStatus(), equalTo(200));
        String session = sessionCookie(response);

        // The module establishes the caller's groups itself, so the architect role of the registered identity is not restored over them.
        response = request("/ctx/secured/resource", "X-Downgrade-Session: true", session);
        assertThat(response.getStatus(), equalTo(403));
    }

    @Test
    public void testACallerWithNoNameDoesNotContinueTheRegisteredSession() throws Exception
    {
        HttpTester.Response response = request("/ctx/secured/resource", "X-Login-Nameless: true", null);
        assertThat(response.getStatus(), equalTo(200));
        String session = sessionCookie(response);

        // Two principals that both have no name are not the same caller, so naming one does not continue the session registered for the other.
        response = request("/ctx/secured/resource", "X-Nameless-Caller: true", session);
        assertThat(response.getStatus(), equalTo(403));
    }

    @Test
    public void testContinuingTheRegisteredSessionIsTheModulesChoice() throws Exception
    {
        HttpTester.Response response = request("/ctx/secured/resource", "X-Login: true", null);
        assertThat(response.getStatus(), equalTo(200));
        String session = sessionCookie(response);

        // The registration does not authenticate by itself: the module is still asked, and here it declines, so the request is unauthenticated.
        response = request("/ctx/secured/resource", null, session);
        assertThat(_authModule.getValidateRequestCount(), equalTo(2));
        assertThat(_authModule.getPrincipalSeen(), equalTo("test"));
        assertThat(response.getStatus(), equalTo(403));
    }

    @Test
    public void testAModuleNamingAnotherCallerIsNotGivenTheRegisteredOne() throws Exception
    {
        HttpTester.Response response = request("/ctx/secured/resource", "X-Login: true", null);
        assertThat(response.getStatus(), equalTo(200));
        String session = sessionCookie(response);

        // The registered caller is restored for the module to read, but the module names a different one and that is who the request is authenticated as.
        response = request("/ctx/secured/resource", "X-Other-Caller: true", session);
        assertThat(_authModule.getPrincipalSeen(), equalTo("test"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("principal=other"));
        assertThat(response.getContent(), containsString("architect=true"));
    }

    @Test
    public void testRegisteringAnAuthenticationSessionRenewsTheSessionId() throws Exception
    {
        // A session the client had before it authenticated, whose id an attacker may know.
        HttpTester.Response response = request("/ctx/open/resource?action=session", null, null);
        assertThat(response.getStatus(), equalTo(200));
        String before = sessionCookie(response);
        assertThat(before, notNullValue());

        response = request("/ctx/secured/resource", "X-Login: true", before);
        assertThat(response.getStatus(), equalTo(200));
        String after = sessionCookie(response);
        assertThat(after, notNullValue());
        assertThat(after, not(equalTo(before)));

        // The registration is in the renewed session, so the module continues it from there.
        response = request("/ctx/secured/resource", "X-Continue-Session: true", after);
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("principal=test"));
        assertThat(response.getContent(), containsString("architect=true"));

        // The id presented before authenticating is no longer a session, so nothing is registered for a client that only knows it.
        response = request("/ctx/secured/resource", "X-Continue-Session: true", before);
        assertThat(response.getStatus(), equalTo(403));
    }

    @Test
    public void testLogoutClearsTheRegisteredSession() throws Exception
    {
        HttpTester.Response response = request("/ctx/secured/resource", "X-Login: true", null);
        assertThat(response.getStatus(), equalTo(200));
        String session = sessionCookie(response);

        response = request("/ctx/secured/resource?action=logout", "X-Continue-Session: true", session);
        assertThat(response.getStatus(), equalTo(200));

        // The registration is gone, so the module has no caller to continue the session with.
        response = request("/ctx/secured/resource", "X-Continue-Session: true", session);
        assertThat(_authModule.getPrincipalSeen(), nullValue());
        assertThat(response.getStatus(), equalTo(403));
    }

    private HttpTester.Response request(String target, String header, String sessionCookie) throws Exception
    {
        StringBuilder request = new StringBuilder("GET ").append(target).append(" HTTP/1.1\r\nHost: local\r\n");
        if (header != null)
            request.append(header).append("\r\n");
        if (sessionCookie != null)
            request.append("Cookie: ").append(sessionCookie).append("\r\n");
        request.append("Connection: close\r\n\r\n");
        return HttpTester.parseResponse(_connector.getResponse(request.toString()));
    }

    private static String sessionCookie(HttpTester.Response response)
    {
        String setCookie = response.get(HttpHeader.SET_COOKIE);
        if (setCookie == null)
            return null;
        int semi = setCookie.indexOf(';');
        return semi < 0 ? setCookie : setCookie.substring(0, semi);
    }

    /**
     * A module that registers an authentication session when asked to log in, and on a later
     * request continues it only if it is asked to and the container has restored the caller.
     */
    public static class RegisterSessionAuthModule implements ServerAuthModule
    {
        private final AtomicInteger _validateRequestCount = new AtomicInteger();
        private final AtomicReference<String> _principalSeen = new AtomicReference<>();
        private CallbackHandler _handler;

        @Override
        @SuppressWarnings("rawtypes")
        public Class[] getSupportedMessageTypes()
        {
            return new Class[]{HttpServletRequest.class, HttpServletResponse.class};
        }

        @Override
        @SuppressWarnings("rawtypes")
        public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy,
                               CallbackHandler handler, Map options)
        {
            _handler = handler;
        }

        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject)
            throws AuthException
        {
            _validateRequestCount.incrementAndGet();
            HttpServletRequest request = (HttpServletRequest)messageInfo.getRequestMessage();
            Principal userPrincipal = request.getUserPrincipal();
            _principalSeen.set(userPrincipal == null ? null : userPrincipal.getName());

            Callback[] callbacks;
            if (userPrincipal != null && request.getHeader("X-Continue-Session") != null)
            {
                callbacks = new Callback[]{new CallerPrincipalCallback(clientSubject, userPrincipal)};
            }
            else if (userPrincipal != null && request.getHeader("X-Continue-Session-By-Name") != null)
            {
                callbacks = new Callback[]{new CallerPrincipalCallback(clientSubject, userPrincipal.getName())};
            }
            else if (userPrincipal != null && request.getHeader("X-Downgrade-Session") != null)
            {
                callbacks = new Callback[]{
                    new CallerPrincipalCallback(clientSubject, userPrincipal.getName()),
                    new GroupPrincipalCallback(clientSubject, new String[]{"users"})};
            }
            else if (request.getHeader("X-Login-Nameless") != null)
            {
                callbacks = new Callback[]{
                    new CallerPrincipalCallback(clientSubject, new NamelessPrincipal()),
                    new GroupPrincipalCallback(clientSubject, new String[]{"architect"})};
                messageInfo.getMap().put(JaspiMessageInfo.REGISTER_SESSION_KEY, "true");
            }
            else if (request.getHeader("X-Nameless-Caller") != null)
            {
                callbacks = new Callback[]{new CallerPrincipalCallback(clientSubject, new NamelessPrincipal())};
            }
            else if (request.getHeader("X-Other-Caller") != null)
            {
                callbacks = new Callback[]{
                    new CallerPrincipalCallback(clientSubject, "other"),
                    new GroupPrincipalCallback(clientSubject, new String[]{"architect"})};
            }
            else if (request.getHeader("X-Login") != null)
            {
                callbacks = new Callback[]{
                    new CallerPrincipalCallback(clientSubject, "test"),
                    new GroupPrincipalCallback(clientSubject, new String[]{"architect"})};
                messageInfo.getMap().put(JaspiMessageInfo.AUTHENTICATION_TYPE_KEY, "TEST-AUTH");
                messageInfo.getMap().put(JaspiMessageInfo.REGISTER_SESSION_KEY, "true");
            }
            else
            {
                callbacks = new Callback[]{new CallerPrincipalCallback(clientSubject, (Principal)null)};
            }

            try
            {
                _handler.handle(callbacks);
            }
            catch (Exception e)
            {
                throw (AuthException)new AuthException().initCause(e);
            }
            return AuthStatus.SUCCESS;
        }

        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject)
        {
            return AuthStatus.SEND_SUCCESS;
        }

        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject)
        {
        }

        public int getValidateRequestCount()
        {
            return _validateRequestCount.get();
        }

        public String getPrincipalSeen()
        {
            return _principalSeen.get();
        }
    }

    private static class AuthInfoServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("logout".equals(action))
                request.logout();
            else if ("session".equals(action))
                request.getSession(true);

            Principal principal = request.getUserPrincipal();
            response.getWriter().println("principal=" + (principal == null ? null : principal.getName()));
            response.getWriter().println("architect=" + request.isUserInRole("architect"));
            response.getWriter().println("authType=" + request.getAuthType());
        }
    }

    /**
     * A caller principal with no name, which no Jetty login service produces but a module may
     * establish with a {@link CallerPrincipalCallback}.
     */
    private static class NamelessPrincipal implements Principal
    {
        @Override
        public String getName()
        {
            return null;
        }
    }
}
