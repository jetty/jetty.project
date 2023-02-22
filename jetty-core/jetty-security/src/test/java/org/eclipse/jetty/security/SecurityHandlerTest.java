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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.session.SimpleSessionHandler;
import org.eclipse.jetty.util.Callback;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;

public class SecurityHandlerTest
{
    private Server _server;
    private LocalConnector _connector;
    private LocalConnector _connectorS;
    private SimpleSessionHandler _sessionHandler;
    private SecurityHandler _securityHandler;

    @BeforeEach
    public void configureServer()
    {
        _server = new Server();

        HttpConnectionFactory http = new HttpConnectionFactory();
        http.getHttpConfiguration().setSecurePort(9999);
        http.getHttpConfiguration().setSecureScheme("BWTP");
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

        _securityHandler = new SecurityHandler()
        {
            @Override
            protected Constraint getConstraint(String pathInContext, Request request)
            {
                return null;
            }
        };
        _sessionHandler.setHandler(_securityHandler);

        _securityHandler.setHandler(new TestHandler());
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
    public void testIntegral() throws Exception
    {
        _server.start();

        String response;
        response = _connector.getResponse("GET /ctx/some/thing HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));

        response = _connector.getResponse("GET /ctx/integral/info HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 302 Found"));
        assertThat(response, Matchers.containsString("Location: BWTP://"));
        assertThat(response, Matchers.containsString(":9999"));

        response = _connectorS.getResponse("GET /ctx/integral/info HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));
    }

    public static class TestHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=UTF-8");
            Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
            return true;
        }
    }

    private class CustomLoginService implements LoginService
    {
        private IdentityService identityService;

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
                return new DefaultUserIdentity(null, null, new String[]{"admin"});
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
