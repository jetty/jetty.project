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

package org.eclipse.jetty.tests;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenIdProvider extends ContainerLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(OpenIdProvider.class);

    private static final String CONFIG_PATH = "/.well-known/openid-configuration";
    private static final String AUTH_PATH = "/auth";
    private static final String TOKEN_PATH = "/token";
    private static final String END_SESSION_PATH = "/end_session";
    private final Map<String, User> issuedAuthCodes = new HashMap<>();

    protected final String clientId;
    protected final String clientSecret;
    protected final List<String> redirectUris = new ArrayList<>();
    private final ServerConnector connector;
    private final Server server;
    private int port = 0;
    private String provider;
    private User preAuthedUser;
    private final CounterStatistic loggedInUsers = new CounterStatistic();
    private long _idTokenDuration = Duration.ofSeconds(10).toMillis();

    public static void main(String[] args) throws Exception
    {
        String clientId = "CLIENT_ID123";
        String clientSecret = "PASSWORD123";
        int port = 5771;
        String redirectUri = "http://localhost:8080/j_security_check";

        OpenIdProvider openIdProvider = new OpenIdProvider(clientId, clientSecret);
        openIdProvider.addRedirectUri(redirectUri);
        openIdProvider.setPort(port);
        openIdProvider.start();
        try
        {
            openIdProvider.join();
        }
        finally
        {
            openIdProvider.stop();
        }
    }

    public OpenIdProvider()
    {
        this("clientId" + StringUtil.randomAlphaNumeric(4), StringUtil.randomAlphaNumeric(10));
    }

    public OpenIdProvider(String clientId, String clientSecret)
    {
        this.clientId = clientId;
        this.clientSecret = clientSecret;

        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        server.setHandler(new OpenIdProviderHandler());
        addBean(server);
    }

    public String getClientId()
    {
        return clientId;
    }

    public String getClientSecret()
    {
        return clientSecret;
    }

    public void setIdTokenDuration(long duration)
    {
        _idTokenDuration = duration;
    }

    public long getIdTokenDuration()
    {
        return _idTokenDuration;
    }

    public void join() throws InterruptedException
    {
        server.join();
    }

    public CounterStatistic getLoggedInUsers()
    {
        return loggedInUsers;
    }

    @Override
    protected void doStart() throws Exception
    {
        connector.setPort(port);
        super.doStart();
        provider = "http://localhost:" + connector.getLocalPort();
    }

    public void setPort(int port)
    {
        if (isStarted())
            throw new IllegalStateException();
        this.port = port;
    }

    public void setUser(User user)
    {
        this.preAuthedUser = user;
    }

    public String getProvider()
    {
        if (!isStarted() && port == 0)
            throw new IllegalStateException("Port of OpenIdProvider not configured");
        return provider;
    }

    public void addRedirectUri(String uri)
    {
        redirectUris.add(uri);
    }

    public class OpenIdProviderHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            String pathInContext = Request.getPathInContext(request);
            switch (pathInContext)
            {
                case CONFIG_PATH -> doGetConfigServlet(request, response, callback);
                case AUTH_PATH -> doAuthEndpoint(request, response, callback);
                case TOKEN_PATH -> doTokenEndpoint(request, response, callback);
                case END_SESSION_PATH -> doEndSessionEndpoint(request, response, callback);
                default -> Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
            }

            return true;
        }
    }

    protected void doAuthEndpoint(Request request, Response response, Callback callback) throws Exception
    {
        String method = request.getMethod();
        switch (method)
        {
            case "GET" -> doGetAuthEndpoint(request, response, callback);
            case "POST" -> doPostAuthEndpoint(request, response, callback);
            default -> throw new BadMessageException("Unsupported HTTP method: " + method);
        }
    }

    protected void doGetAuthEndpoint(Request request, Response response, Callback callback) throws Exception
    {
        Fields parameters = Request.getParameters(request);

        if (!clientId.equals(parameters.getValue("client_id")))
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "invalid client_id");
            return;
        }

        String redirectUri = parameters.getValue("redirect_uri");
        if (!redirectUris.contains(redirectUri))
        {
            LOG.warn("invalid redirectUri {}", redirectUri);
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "invalid redirect_uri");
            return;
        }

        String scopeString = parameters.getValue("scope");
        List<String> scopes = (scopeString == null) ? Collections.emptyList() : Arrays.asList(scopeString.split(" "));
        if (!scopes.contains("openid"))
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "no openid scope");
            return;
        }

        if (!"code".equals(parameters.getValue("response_type")))
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "response_type must be code");
            return;
        }

        String state = parameters.getValue("state");
        if (state == null)
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "no state param");
            return;
        }

        if (preAuthedUser == null)
        {
            String responseContent = String.format("""
                <h2>Login to OpenID Connect Provider</h2>
                <form action="%s" method="post">
                <input type="text" autocomplete="off" placeholder="Username" name="username" required>
                <input type="hidden" name="redirectUri" value="%s">
                <input type="hidden" name="state" value="%s">
                <input type="submit">
                </form>
                """, AUTH_PATH, redirectUri, state);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/html");
            response.write(true, BufferUtil.toBuffer(responseContent), callback);
        }
        else
        {
            redirectUser(request, response, callback, preAuthedUser, redirectUri, state);
        }
    }

    protected void doPostAuthEndpoint(Request request, Response response, Callback callback) throws Exception
    {
        Fields parameters = Request.getParameters(request);
        String redirectUri = parameters.getValue("redirectUri");
        if (!redirectUris.contains(redirectUri))
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "invalid redirect_uri");
            return;
        }

        String state = parameters.getValue("state");
        if (state == null)
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "no state param");
            return;
        }

        String username = parameters.getValue("username");
        if (username == null)
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "no username");
            return;
        }

        User user = new User(username);
        redirectUser(request, response, callback, user, redirectUri, state);
    }

    public void redirectUser(Request request, Response response, Callback callback, User user, String redirectUri, String state) throws IOException
    {
        String authCode = UUID.randomUUID().toString().replace("-", "");
        issuedAuthCodes.put(authCode, user);

        try
        {
            redirectUri += "?code=" + authCode + "&state=" + state;
            Response.sendRedirect(request, response, callback, redirectUri);
        }
        catch (Throwable t)
        {
            issuedAuthCodes.remove(authCode);
            throw t;
        }
    }

    protected void doTokenEndpoint(Request request, Response response, Callback callback) throws Exception
    {
        if (!HttpMethod.POST.is(request.getMethod()))
            throw new BadMessageException("Unsupported HTTP method for token Endpoint: " + request.getMethod());

        Fields parameters = Request.getParameters(request);
        String code = parameters.getValue("code");

        if (!clientId.equals(parameters.getValue("client_id")) ||
            !clientSecret.equals(parameters.getValue("client_secret")) ||
            !redirectUris.contains(parameters.getValue("redirect_uri")) ||
            !"authorization_code".equals(parameters.getValue("grant_type")) ||
            code == null)
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "bad auth request");
            return;
        }

        User user = issuedAuthCodes.remove(code);
        if (user == null)
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "invalid auth code");
            return;
        }

        String accessToken = "ABCDEFG";
        long accessTokenDuration = Duration.ofMinutes(10).toSeconds();
        String responseContent = "{" +
            "\"access_token\": \"" + accessToken + "\"," +
            "\"id_token\": \"" + JwtEncoder.encode(user.getIdToken(provider, clientId, _idTokenDuration)) + "\"," +
            "\"expires_in\": " + accessTokenDuration + "," +
            "\"token_type\": \"Bearer\"" +
            "}";

        loggedInUsers.increment();
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
        response.write(true, BufferUtil.toBuffer(responseContent), callback);
    }

    protected void doEndSessionEndpoint(Request request, Response response, Callback callback) throws Exception
    {
        Fields parameters = Request.getParameters(request);
        String idToken = parameters.getValue("id_token_hint");
        if (idToken == null)
        {
            Response.writeError(request, response, callback, HttpStatus.BAD_REQUEST_400, "no id_token_hint");
            return;
        }

        String logoutRedirect = parameters.getValue("post_logout_redirect_uri");
        if (logoutRedirect == null)
        {
            response.setStatus(HttpStatus.OK_200);
            response.write(true, BufferUtil.toBuffer("logout success on end_session_endpoint"), callback);
            return;
        }

        loggedInUsers.decrement();
        Response.sendRedirect(request, response, callback, logoutRedirect);
    }

    protected void doGetConfigServlet(Request request, Response response, Callback callback) throws IOException
    {
        String discoveryDocument = "{" +
            "\"issuer\": \"" + provider + "\"," +
            "\"authorization_endpoint\": \"" + provider + AUTH_PATH + "\"," +
            "\"token_endpoint\": \"" + provider + TOKEN_PATH + "\"," +
            "\"end_session_endpoint\": \"" + provider + END_SESSION_PATH + "\"," +
            "}";

        response.write(true, BufferUtil.toBuffer(discoveryDocument), callback);
    }

    public static class User
    {
        private final String subject;
        private final String name;

        public User(String name)
        {
            this(UUID.nameUUIDFromBytes(name.getBytes()).toString(), name);
        }

        public User(String subject, String name)
        {
            this.subject = subject;
            this.name = name;
        }

        public String getName()
        {
            return name;
        }

        public String getSubject()
        {
            return subject;
        }

        public String getIdToken(String provider, String clientId, long duration)
        {
            long expiryTime = Instant.now().plusMillis(duration).getEpochSecond();
            return JwtEncoder.createIdToken(provider, clientId, subject, name, expiryTime);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (!(obj instanceof User))
                return false;
            return Objects.equals(subject, ((User)obj).subject) && Objects.equals(name, ((User)obj).name);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(subject, name);
        }
    }
}
