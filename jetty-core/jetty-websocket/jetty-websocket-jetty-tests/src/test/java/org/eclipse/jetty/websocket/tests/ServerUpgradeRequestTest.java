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

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerUpgradeRequestTest
{
    private Server _server;
    private WebSocketClient _client;
    private ServerConnector _connector;

    private static class TestLoginService extends AbstractLoginService
    {
        public TestLoginService(IdentityService identityService)
        {
            setIdentityService(identityService);
        }

        @Override
        protected List<RolePrincipal> loadRoleInfo(UserPrincipal user)
        {
            return List.of();
        }

        @Override
        protected UserPrincipal loadUserInfo(String username)
        {
            return new UserPrincipal(username, null)
            {
                @Override
                public boolean authenticate(Object credentials)
                {
                    return true;
                }

                @Override
                public boolean authenticate(Credential c)
                {
                    return true;
                }

                @Override
                public boolean authenticate(UserPrincipal u)
                {
                    return true;
                }
            };
        }
    }

    private static class TestAuthenticator extends LoginAuthenticator
    {
        @Override
        public String getAuthenticationType()
        {
            return "TEST";
        }

        @Override
        public AuthenticationState validateRequest(Request request, Response response, org.eclipse.jetty.util.Callback callback)
        {
            UserIdentity user = login("user123", null, request, response);
            if (user != null)
                return new UserAuthenticationSucceeded(getAuthenticationType(), user);

            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
            return AuthenticationState.SEND_FAILURE;
        }
    }

    @BeforeEach
    public void start() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        WebSocketUpgradeHandler upgradeHandler = WebSocketUpgradeHandler.from(_server, container ->
        {
            container.addMapping("/", (req, resp, cb) ->
            {
                resp.getHeaders().put("customHeader", "customHeaderValue");
                resp.setAcceptedSubProtocol(req.getSubProtocols().get(0));

                return new ServerSocket();
            });
        });

        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        securityHandler.put("/*", Constraint.ANY_USER);
        DefaultIdentityService identityService = new DefaultIdentityService();
        securityHandler.setLoginService(new TestLoginService(identityService));
        securityHandler.setIdentityService(identityService);
        securityHandler.setAuthenticator(new TestAuthenticator());
        securityHandler.setHandler(upgradeHandler);

        _server.setHandler(securityHandler);
        _server.start();

        _client = new WebSocketClient();
        _client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @WebSocket
    public static class ServerSocket extends EventSocket
    {
        @Override
        public void onMessage(String message)
        {
            StringBuilder builder = new StringBuilder();

            try
            {
                switch (message)
                {
                    case "getUpgradeRequest" ->
                    {
                        UpgradeRequest upgradeRequest = session.getUpgradeRequest();
                        builder.append("getRequestURI: ").append(upgradeRequest.getRequestURI()).append("\n");
                        builder.append("getHeaders: ").append(upgradeRequest.getHeaders()).append("\n");
                        builder.append("getExtensions: ").append(upgradeRequest.getExtensions()).append("\n");
                        builder.append("getHost: ").append(upgradeRequest.getHost()).append("\n");
                        builder.append("getHttpVersion: ").append(upgradeRequest.getHttpVersion()).append("\n");
                        builder.append("getQueryString: ").append(upgradeRequest.getQueryString()).append("\n");
                        builder.append("getSubProtocols: ").append(upgradeRequest.getSubProtocols()).append("\n");
                        builder.append("getProtocolVersion: ").append(upgradeRequest.getProtocolVersion()).append("\n");
                        builder.append("getCookies: ").append(upgradeRequest.getCookies()).append("\n");
                        builder.append("getUserPrincipal: ").append(upgradeRequest.getUserPrincipal()).append("\n");
                        builder.append("getOrigin: ").append(upgradeRequest.getOrigin()).append("\n");
                        builder.append("isSecure: ").append(upgradeRequest.isSecure()).append("\n");
                        builder.append("getParameterMap: ").append(upgradeRequest.getParameterMap()).append("\n");
                    }
                    case "getUpgradeResponse" ->
                    {
                        UpgradeResponse upgradeResponse = session.getUpgradeResponse();
                        builder.append("getHeaders: ").append(upgradeResponse.getHeaders()).append("\n");
                        builder.append("getExtensions: ").append(upgradeResponse.getExtensions()).append("\n");
                        builder.append("getStatusCode: ").append(upgradeResponse.getStatusCode()).append("\n");
                        builder.append("getAcceptedSubProtocol: ").append(upgradeResponse.getAcceptedSubProtocol()).append("\n");
                    }
                    default -> throw new IllegalStateException("Unknown message: " + message);
                }
            }
            catch (Exception e)
            {
                e.printStackTrace(System.err);
                throw e;
            }

            session.sendText(builder.toString(), Callback.NOOP);
        }
    }

    @Test
    public void testUpgradeRequest() throws Exception
    {
        URI uri = new URI("ws://localhost:" + _connector.getLocalPort() + "/?queryParam1=queryParamValue1");
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("subProtocol1", "subProtocol2");
        upgradeRequest.addExtensions("permessage-deflate");
        upgradeRequest.setHeader("Cookie", "cookieHeader1=cookieValue1");
        upgradeRequest.setHeader("Origin", "jetty-test");
        upgradeRequest.setHeader("CustomRequestHeader", "request-header-value");

        EventSocket clientEndpoint = new EventSocket();
        Session session = _client.connect(clientEndpoint, uri, upgradeRequest).get(5, TimeUnit.SECONDS);

        session.sendText("getUpgradeRequest", Callback.NOOP);
        String received = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(received, containsString("getRequestURI: " + uri));
        assertThat(received, containsString("CustomRequestHeader=[request-header-value]"));
        assertThat(received, containsString("getExtensions: [permessage-deflate]"));
        assertThat(received, containsString("getHost: localhost"));
        assertThat(received, containsString("getHttpVersion: HTTP/1.1"));
        assertThat(received, containsString("getQueryString: queryParam1=queryParamValue1"));
        assertThat(received, containsString("getSubProtocols: [subProtocol1, subProtocol2]"));
        assertThat(received, containsString("getProtocolVersion: 13"));
        assertThat(received, containsString("getCookies: [cookieHeader1=cookieValue1]"));
        assertThat(received, containsString("getUserPrincipal: user123"));
        assertThat(received, containsString("getOrigin: jetty-test"));
        assertThat(received, containsString("isSecure: false"));
        assertThat(received, containsString("getParameterMap: {queryParam1=[queryParamValue1]}"));

        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.NORMAL));
    }

    @Test
    public void testUpgradeResponse() throws Exception
    {
        URI uri = new URI("ws://localhost:" + _connector.getLocalPort());

        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("subProtocol1", "subProtocol2");
        upgradeRequest.addExtensions("permessage-deflate");

        EventSocket clientEndpoint = new EventSocket();
        Session session = _client.connect(clientEndpoint, uri, upgradeRequest).get(5, TimeUnit.SECONDS);

        session.sendText("getUpgradeResponse", Callback.NOOP);
        String received = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(received, containsString("customHeader=[customHeaderValue]"));
        assertThat(received, containsString("getExtensions: [permessage-deflate]"));
        assertThat(received, containsString("getStatusCode: 101"));
        assertThat(received, containsString("getAcceptedSubProtocol: subProtocol1"));

        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.NORMAL));
    }
}
