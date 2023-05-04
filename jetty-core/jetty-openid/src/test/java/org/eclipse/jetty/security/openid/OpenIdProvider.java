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

package org.eclipse.jetty.security.openid;

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
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
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

    public OpenIdProvider(String clientId, String clientSecret)
    {
        this.clientId = clientId;
        this.clientSecret = clientSecret;

        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        contexts.addHandler(new ConfigServlet(CONFIG_PATH));
        contexts.addHandler(new AuthEndpoint(AUTH_PATH));
        contexts.addHandler(new TokenEndpoint(TOKEN_PATH));
        contexts.addHandler(new EndSessionEndpoint(END_SESSION_PATH));
        server.setHandler(contexts);

        addBean(server);
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

    public OpenIdConfiguration getOpenIdConfiguration()
    {
        String provider = getProvider();
        String authEndpoint = provider + AUTH_PATH;
        String tokenEndpoint = provider + TOKEN_PATH;
        return new OpenIdConfiguration(provider, authEndpoint, tokenEndpoint, clientId, clientSecret, null);
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

    public class AuthEndpoint extends ContextHandler
    {
        public AuthEndpoint(String contextPath)
        {
            super(contextPath);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            switch (request.getMethod())
            {
                case "GET":
                    doGet(request, response, callback);
                    break;
                case "POST":
                    doPost(request, response, callback);
                    break;
                default:
                    throw new BadMessageException("Unsupported HTTP Method");
            }

            return true;
        }

        protected void doGet(Request request, Response response, Callback callback) throws Exception
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
                Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "invalid redirect_uri");
                return;
            }

            String scopeString = parameters.getValue("scope");
            List<String> scopes = (scopeString == null) ? Collections.emptyList() : Arrays.asList(StringUtil.csvSplit(scopeString));
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
                response.getHeaders().add(HttpHeader.CONTENT_TYPE, "text/html");

                String content =
                "<h2>Login to OpenID Connect Provider</h2>" +
                "<form action=\"" + AUTH_PATH + "\" method=\"post\">" +
                "<input type=\"text\" autocomplete=\"off\" placeholder=\"Username\" name=\"username\" required>" +
                "<input type=\"hidden\" name=\"redirectUri\" value=\"" + redirectUri + "\">" +
                "<input type=\"hidden\" name=\"state\" value=\"" + state + "\">" +
                "<input type=\"submit\">" +
                "</form>";
                response.write(true, BufferUtil.toBuffer(content), callback);
            }
            else
            {
                redirectUser(request, response, callback, preAuthedUser, redirectUri, state);
            }
        }

        protected void doPost(Request request, Response response, Callback callback) throws Exception
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
    }

    private class TokenEndpoint extends ContextHandler
    {
        public TokenEndpoint(String contextPath)
        {
            super(contextPath);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            Fields parameters = Request.getParameters(request);

            String code = parameters.getValue("code");

            if (!clientId.equals(parameters.getValue("client_id")) ||
                !clientSecret.equals(parameters.getValue("client_secret")) ||
                !redirectUris.contains(parameters.getValue("redirect_uri")) ||
                !"authorization_code".equals(parameters.getValue("grant_type")) ||
                code == null)
            {
                Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "bad auth request");
                return true;
            }

            User user = issuedAuthCodes.remove(code);
            if (user == null)
            {
                Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "invalid auth code");
                return true;
            }

            String accessToken = "ABCDEFG";
            long accessTokenDuration = Duration.ofMinutes(10).toSeconds();
            String content = "{" +
                "\"access_token\": \"" + accessToken + "\"," +
                "\"id_token\": \"" + JwtEncoder.encode(user.getIdToken(provider, clientId, _idTokenDuration)) + "\"," +
                "\"expires_in\": " + accessTokenDuration + "," +
                "\"token_type\": \"Bearer\"" +
                "}";

            loggedInUsers.increment();
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
            response.write(true, BufferUtil.toBuffer(content), callback);
            return true;
        }
    }

    private class EndSessionEndpoint extends ContextHandler
    {
        public EndSessionEndpoint(String contextPath)
        {
            super(contextPath);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            Fields parameters = Request.getParameters(request);

            String idToken = parameters.getValue("id_token_hint");
            if (idToken == null)
            {
                Response.writeError(request, response, callback, HttpStatus.BAD_REQUEST_400, "no id_token_hint");
                return true;
            }

            String logoutRedirect = parameters.getValue("post_logout_redirect_uri");
            if (logoutRedirect == null)
            {
                response.setStatus(HttpStatus.OK_200);
                response.write(true, BufferUtil.toBuffer("logout success on end_session_endpoint"), callback);
                return true;
            }

            loggedInUsers.decrement();
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
            Response.sendRedirect(request, response, callback, logoutRedirect);
            return true;
        }
    }

    private class ConfigServlet extends ContextHandler
    {
        public ConfigServlet(String contextPath)
        {
            super(contextPath);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            String discoveryDocument = "{" +
                "\"issuer\": \"" + provider + "\"," +
                "\"authorization_endpoint\": \"" + provider + AUTH_PATH + "\"," +
                "\"token_endpoint\": \"" + provider + TOKEN_PATH + "\"," +
                "\"end_session_endpoint\": \"" + provider + END_SESSION_PATH + "\"," +
                "}";

            response.write(true, BufferUtil.toBuffer(discoveryDocument), callback);
            return true;
        }
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
