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

package org.eclipse.jetty.security;

import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.session.SessionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNull;

public class FormAuthenticatorTest
{
    public static final String SET_COOKIE_JSESSIONID = "Set-Cookie: JSESSIONID=";
    private Server _server;
    private LocalConnector _connector;
    private LocalConnector _connectorS;
    private SessionHandler _sessionHandler;
    private SecurityHandler.PathMapped _securityHandler;

    public void configureServer(boolean dispatch) throws Exception
    {
        _server = new Server();

        _server.addBean(new AuthenticationTestHandler.CustomLoginService(new AuthenticationTestHandler.TestIdentityService()));

        HttpConnectionFactory http = new HttpConnectionFactory();
        HttpConfiguration httpConfiguration = http.getHttpConfiguration();
        httpConfiguration.setSecurePort(9999);
        httpConfiguration.setSecureScheme("BWTP");
        httpConfiguration.addCustomizer(new ForwardedRequestCustomizer());
        _connector = new LocalConnector(_server, http);
        _connector.setIdleTimeout(300000);

        HttpConnectionFactory https = new HttpConnectionFactory();
        https.getHttpConfiguration().addCustomizer((request, responseHeaders) ->
        {
            HttpURI.Mutable uri = HttpURI.build(request.getHttpURI()).scheme(HttpScheme.HTTPS);
            return new Request.Wrapper(request)
            {
                @Override
                public HttpURI getHttpURI()
                {
                    return uri;
                }

                @Override
                public boolean isSecure()
                {
                    return true;
                }
            };
        });

        _connectorS = new LocalConnector(_server, https);
        _server.setConnectors(new Connector[]{_connector, _connectorS});

        ContextHandler contextHandler = new ContextHandler();
        _sessionHandler = new SessionHandler();

        contextHandler.setContextPath("/ctx");
        _server.setHandler(contextHandler);
        contextHandler.setHandler(_sessionHandler);

        _securityHandler = new SecurityHandler.PathMapped();
        _sessionHandler.setHandler(_securityHandler);

        _securityHandler.setHandler(new AuthenticationTestHandler());

        _securityHandler.put("/any/*", Constraint.ANY_USER);
        _securityHandler.put("/known/*", Constraint.KNOWN_ROLE);
        _securityHandler.put("/admin/*", Constraint.from("admin"));
        _securityHandler.setAuthenticator(new FormAuthenticator("/login", "/error", dispatch));
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (_server.isRunning())
        {
            _server.stop();
            _server.join();
        }
    }

    @Test
    public void testLoginRedirect() throws Exception
    {
        configureServer(false);
        _server.start();

        String response;
        response = _connector.getResponse("GET /ctx/some/thing HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Deferred"));

        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nHost:host:8888\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /ctx/login"));
        assertThat(response, containsString("Set-Cookie: JSESSIONID="));

        response = _connector.getResponse("GET /ctx/known/user HTTP/1.0\r\nHost:host:8888\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /ctx/login"));
        assertThat(response, containsString("Set-Cookie: JSESSIONID="));

        response = _connector.getResponse("GET /ctx/admin/user HTTP/1.0\r\nHost:host:8888\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /ctx/login"));
        assertThat(response, containsString("Set-Cookie: JSESSIONID="));
    }

    private static String sessionId(String response)
    {
        int cookie = response.indexOf(SET_COOKIE_JSESSIONID);
        if (cookie < 0)
            return null;
        int semi = response.indexOf(";", cookie);
        return response.substring(cookie + SET_COOKIE_JSESSIONID.length(), semi);
    }

    @Test
    public void testUseExistingSession() throws Exception
    {
        configureServer(false);
        _server.start();

        String response;
        response = _connector.getResponse("GET /ctx/some/thing?action=session HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Set-Cookie: JSESSIONID="));
        assertThat(response, containsString("Deferred"));
        String sessionId = sessionId(response);

        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nHost:host:8888\r\nCookie: JSESSIONID=" + sessionId + "\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /ctx/login"));
        assertThat(response, not(containsString("Set-Cookie: JSESSIONID=")));
    }

    @Test
    public void testError() throws Exception
    {
        configureServer(false);
        _server.start();

        String response;

        String sessionId = "unknown";
        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nHost:host:8888\r\nCookie: JSESSIONID=" + sessionId + "\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /ctx/login"));
        assertThat(response, containsString("Set-Cookie: JSESSIONID="));
        sessionId = sessionId(response);

        response = _connector.getResponse("GET /ctx/j_security_check?j_username=user&j_password=wrong HTTP/1.0\r\nHost:host:8888\r\nCookie: JSESSIONID=" + sessionId + "\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /ctx/error"));
        assertThat(response, not(containsString("Set-Cookie: JSESSIONID=")));

        response = _connector.getResponse("""
            POST /ctx/j_security_check HTTP/1.0\r
            Host: host:8888\r
            Content-Length: 32\r
            Content-Type: application/x-www-form-urlencoded\r
            Cookie: JSESSIONID=" + sessionId + "\r
            \r
            j_username=user&j_password=wrong
            """);
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /ctx/error"));
        assertThat(response, not(containsString("Set-Cookie: JSESSIONID=")));
    }

    @Test
    public void testLoginQuery() throws Exception
    {
        configureServer(false);
        _server.start();

        String response;

        String sessionId = "unknown";
        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nHost:host:8888\r\nCookie: JSESSIONID=" + sessionId + "\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /ctx/login"));
        assertThat(response, containsString("Set-Cookie: JSESSIONID="));
        sessionId = sessionId(response);

        response = _connector.getResponse("GET /ctx/j_security_check?j_username=user&j_password=password HTTP/1.0\r\nHost:host:8888\r\nCookie: JSESSIONID=" + sessionId + "\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /ctx/any/user"));
        assertThat(response, containsString("Set-Cookie: JSESSIONID="));
        String unsafeSessionId = sessionId;
        sessionId = sessionId(response);
        assertThat(sessionId, not(equalTo(unsafeSessionId)));

        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nHost:host:8888\r\nCookie: JSESSIONID=" + sessionId + "\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, not(containsString("Set-Cookie: JSESSIONID=")));
        assertThat(response, containsString("user is OK"));

        response = _connector.getResponse("GET /ctx/admin/user HTTP/1.0\r\nHost:host:8888\r\nCookie: JSESSIONID=" + sessionId + "\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));
        assertThat(response, containsString("!authorized"));
        assertThat(response, not(containsString("OK")));

        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nHost:host:8888\r\nCookie: JSESSIONID=" + unsafeSessionId + "\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /ctx/login"));
        assertThat(response, containsString("Set-Cookie: JSESSIONID="));
    }

    @Test
    public void testLoginForm() throws Exception
    {
        configureServer(false);
        _server.start();

        String response;

        String sessionId = "unknown";
        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nHost:host:8888\r\nCookie: JSESSIONID=" + sessionId + "\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /ctx/login"));
        assertThat(response, containsString("Set-Cookie: JSESSIONID="));
        sessionId = sessionId(response);

        response = _connector.getResponse("""
            POST /ctx/j_security_check HTTP/1.0\r
            Host: host:8888\r
            Content-Length: 35\r
            Content-Type: application/x-www-form-urlencoded\r
            Cookie: JSESSIONID=%s\r
            \r
            j_username=user&j_password=password
            """.formatted(sessionId));
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /ctx/any/user"));
        assertThat(response, containsString("Set-Cookie: JSESSIONID="));
        String unsafeSessionId = sessionId;
        sessionId = sessionId(response);
        assertThat(sessionId, not(equalTo(unsafeSessionId)));

        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nHost:host:8888\r\nCookie: JSESSIONID=" + sessionId + "\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, not(containsString("Set-Cookie: JSESSIONID=")));
        assertThat(response, containsString("user is OK"));

        response = _connector.getResponse("GET /ctx/admin/user HTTP/1.0\r\nHost:host:8888\r\nCookie: JSESSIONID=" + sessionId + "\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));
        assertThat(response, containsString("!authorized"));
        assertThat(response, not(containsString("OK")));

        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nHost:host:8888\r\nCookie: JSESSIONID=" + unsafeSessionId + "\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /ctx/login"));
        assertThat(response, containsString("Set-Cookie: JSESSIONID="));
    }

    @Test
    public void testRedirectToPost() throws Exception
    {
        configureServer(false);
        _server.start();

        String response;
        String sessionId = "unknown";

        response = _connector.getResponse("""
            POST /ctx/any/user?action=form HTTP/1.1\r
            Host: host:8888\r
            Content-Length: 25\r
            Content-Type: application/x-www-form-urlencoded\r
            Cookie: JSESSIONID=%s\r
            Connection: close\r
            \r
            name1=value1&name2=value2\r
            """.formatted(sessionId));
        assertThat(response, containsString("HTTP/1.1 303 See Other"));
        assertThat(response, containsString("Location: /ctx/login"));
        assertThat(response, containsString("Set-Cookie: JSESSIONID="));
        sessionId = sessionId(response);

        response = _connector.getResponse("GET /ctx/j_security_check?j_username=user&j_password=password HTTP/1.0\r\nHost:host:8888\r\nCookie: JSESSIONID=" + sessionId + "\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /ctx/any/user?action=form"));
        assertThat(response, containsString("Set-Cookie: JSESSIONID="));
        String unsafeSessionId = sessionId;
        sessionId = sessionId(response);
        assertThat(sessionId, not(equalTo(unsafeSessionId)));

        response = _connector.getResponse("GET /ctx/any/user?action=form HTTP/1.0\r\nHost:host:8888\r\nCookie: JSESSIONID=" + sessionId + "\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, not(containsString("Set-Cookie: JSESSIONID=")));
        assertThat(response, containsString("name1:value1,"));
        assertThat(response, containsString("name2:value2,"));
        assertThat(response, containsString("user is OK"));
    }

    @Test
    public void testLoginDispatch() throws Exception
    {
        configureServer(true);
        _server.start();

        String response = _connector.getResponse("GET /ctx/admin/user HTTP/1.0\r\nHost:host:8888\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Deferred"));
        assertThat(response, containsString("path=/ctx/login,"));
    }

    @Test
    public void testErrorDispatch() throws Exception
    {
        configureServer(true);
        _server.start();

        String response = _connector.getResponse("GET /ctx/j_security_check?j_username=user&j_password=wrong HTTP/1.0\r\nHost:host:8888\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Deferred"));
        assertThat(response, containsString("path=/ctx/error,"));
    }

    public static Stream<Arguments> onAuthenticationTests()
    {
        return Stream.of(
            Arguments.of(false, 0),
            Arguments.of(false, -1),
            Arguments.of(false, 2400),
            Arguments.of(true, 0),
            Arguments.of(true, -1),
            Arguments.of(true, 2400)
        );
    }

    @ParameterizedTest
    @MethodSource("onAuthenticationTests")
    public void testSessionOnAuthentication(boolean sessionRenewOnAuthentication, int sessionMaxInactiveIntervalOnAuthentication) throws Exception
    {
        final int UNAUTH_SECONDS = 1200;
        configureServer(false);

        // Use a FormAuthenticator as an example of session authentication
        _securityHandler.setAuthenticator(new FormAuthenticator("/testLoginPage", "/testErrorPage", false));
        _sessionHandler.setMaxInactiveInterval(UNAUTH_SECONDS);
        _securityHandler.setSessionRenewedOnAuthentication(sessionRenewOnAuthentication);
        _securityHandler.setSessionMaxInactiveIntervalOnAuthentication(sessionMaxInactiveIntervalOnAuthentication);
        _server.start();

        String response;

        response = _connector.getResponse("GET /ctx/any/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString(" 302 Found"));
        assertThat(response, containsString("/ctx/testLoginPage"));
        assertThat(response, containsString("JSESSIONID="));
        String sessionId = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));

        response = _connector.getResponse("GET /ctx/testLoginPage HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + sessionId + "\r\n" +
            "\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("path=/ctx/testLoginPage"));
        assertThat(response, not(containsString("JSESSIONID=" + sessionId)));

        response = _connector.getResponse("POST /ctx/j_security_check HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + sessionId + "\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: 35\r\n" +
            "\r\n" +
            "j_username=user&j_password=password");
        assertThat(response, startsWith("HTTP/1.1 302 "));
        assertThat(response, containsString("Location"));
        assertThat(response, containsString("/ctx/any/info"));

        if (sessionRenewOnAuthentication)
        {
            // check session ID has changed.
            assertNull(_sessionHandler.getManagedSession(sessionId));
            assertThat(response, containsString("Set-Cookie:"));
            assertThat(response, containsString("JSESSIONID="));
            assertThat(response, not(containsString("JSESSIONID=" + sessionId)));
            sessionId = response.substring(response.indexOf("JSESSIONID=") + 11, response.indexOf("; Path=/ctx"));
        }
        else
        {
            // check session ID has not changed.
            assertThat(response, not(containsString("Set-Cookie:")));
            assertThat(response, not(containsString("JSESSIONID=")));
        }

        ManagedSession session = _sessionHandler.getManagedSession(sessionId);
        if (sessionMaxInactiveIntervalOnAuthentication == 0)
        {
            // check max interval has not been updated
            assertThat(session.getMaxInactiveInterval(), is(UNAUTH_SECONDS));
        }
        else
        {
            // check max interval has not been updated
            assertThat(session.getMaxInactiveInterval(), is(sessionMaxInactiveIntervalOnAuthentication));
        }

        // check session still there.
        response = _connector.getResponse("GET /ctx/any/info HTTP/1.0\r\n" +
            "Cookie: JSESSIONID=" + sessionId + "\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }
}
