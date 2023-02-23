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

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.session.SimpleSessionHandler;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

public class BasicAuthenticatorTest
{
    private Server _server;
    private LocalConnector _connector;
    private LocalConnector _connectorS;
    private SimpleSessionHandler _sessionHandler;
    private SecurityHandler.Mapped _securityHandler;

    @BeforeEach
    public void configureServer() throws Exception
    {
        _server = new Server();

        _server.addBean(new CustomLoginService(new DefaultIdentityService()));

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
        _sessionHandler = new SimpleSessionHandler();

        contextHandler.setContextPath("/ctx");
        _server.setHandler(contextHandler);
        contextHandler.setHandler(_sessionHandler);

        _securityHandler = new SecurityHandler.Mapped();
        _sessionHandler.setHandler(_securityHandler);

        _securityHandler.setHandler(new OkHandler());

        _securityHandler.add("/admin/*", Constraint.from("admin"));
        _securityHandler.add("/any/*", Constraint.AUTHENTICATED);
        _securityHandler.add("/known/*", Constraint.AUTHENTICATED_IN_KNOWN_ROLE);
        _securityHandler.setAuthenticator(new BasicAuthenticator());
        _server.start();
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
    public void testBasic() throws Exception
    {
        String response;
        response = _connector.getResponse("GET /ctx/some/thing HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("You are OK"));

        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 401 Unauthorized"));
        assertThat(response, not(containsString("You are OK")));

        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("wrong", "user")));
        assertThat(response, containsString("HTTP/1.1 401 Unauthorized"));
        assertThat(response, not(containsString("You are OK")));

        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("user", "password")));
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("user is OK"));

        response = _connector.getResponse("GET /ctx/any/user HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("admin", "password")));
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("admin is OK"));

        response = _connector.getResponse("GET /ctx/admin/user HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("user", "password")));
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));
        assertThat(response, containsString("!role"));
        assertThat(response, not(containsString("OK")));

        response = _connector.getResponse("GET /ctx/admin/user HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("admin", "password")));
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("admin is OK"));

        response = _connector.getResponse("GET /ctx/known/user HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("user", "password")));
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));
        assertThat(response, containsString("!role"));
        assertThat(response, not(containsString("OK")));
    }

    public static class OkHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            Authentication authentication = Authentication.getAuthentication(request);
            if (authentication instanceof UserAuthentication user)
                Content.Sink.write(response, true, user.getUserIdentity().getUserPrincipal() + " is OK", callback);
            else if (authentication instanceof Authentication.Deferred)
                Content.Sink.write(response, true, "Somebody might be OK", callback);
            else if (authentication == null)
                Content.Sink.write(response, true, "You are OK", callback);
            else
                Content.Sink.write(response, true, authentication + " is not OK", callback);
            return true;
        }
    }

    private static class CustomLoginService implements LoginService
    {
        private final IdentityService identityService;

        public CustomLoginService(IdentityService identityService)
        {
            this.identityService = identityService;
        }

        @Override
        public String getName()
        {
            return "name";
        }

        @Override
        public UserIdentity login(String username, Object credentials, Request request)
        {
            if ("admin".equals(username) && "password".equals(credentials))
                return new DefaultUserIdentity(null, new UserPrincipal("admin", null), new String[]{"admin"});
            if ("user".equals(username) && "password".equals(credentials))
                return new DefaultUserIdentity(null, new UserPrincipal("user", null), new String[]{"user"});
            return null;
        }

        @Override
        public boolean validate(UserIdentity user)
        {
            return false;
        }

        @Override
        public IdentityService getIdentityService()
        {
            return identityService;
        }

        @Override
        public void setIdentityService(IdentityService service)
        {
        }

        @Override
        public void logout(UserIdentity user)
        {
        }
    }
}
