//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.tests.distribution.openid;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.security.openid.OpenIdConfiguration;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletContextResponse;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenIdProvider extends ContainerLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(OpenIdProvider.class);

    private static final String CONFIG_PATH = "/.well-known/openid-configuration";
    private static final String AUTH_PATH = "/auth";
    private static final String TOKEN_PATH = "/token";
    private final Map<String, User> issuedAuthCodes = new HashMap<>();

    protected final String clientId;
    protected final String clientSecret;
    protected final List<String> redirectUris = new ArrayList<>();
    private final ServerConnector connector;
    private final Server server;
    private int port = 0;
    private String provider;
    private User preAuthedUser;

    public static void main(String[] args) throws Exception
    {
        String clientId = "CLIENT_ID123";
        String clientSecret = "PASSWORD123";
        int port = 5771;
        String redirectUri = "http://localhost:8080/openid/auth";

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

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        contextHandler.addServlet(new ServletHolder(new OpenIdConfigServlet()), CONFIG_PATH);
        contextHandler.addServlet(new ServletHolder(new OpenIdAuthEndpoint()), AUTH_PATH);
        contextHandler.addServlet(new ServletHolder(new OpenIdTokenEndpoint()), TOKEN_PATH);
        server.setHandler(contextHandler);

        addBean(server);
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

    public class OpenIdAuthEndpoint extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            if (!clientId.equals(req.getParameter("client_id")))
            {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "invalid client_id");
                return;
            }

            String redirectUri = req.getParameter("redirect_uri");
            if (!redirectUris.contains(redirectUri))
            {
                LOG.warn("invalid redirectUri {}", redirectUri);
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "invalid redirect_uri");
                return;
            }

            String scopeString = req.getParameter("scope");
            List<String> scopes = (scopeString == null) ? Collections.emptyList() : Arrays.asList(StringUtil.csvSplit(scopeString));
            if (!scopes.contains("openid"))
            {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "no openid scope");
                return;
            }

            if (!"code".equals(req.getParameter("response_type")))
            {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "response_type must be code");
                return;
            }

            String state = req.getParameter("state");
            if (state == null)
            {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "no state param");
                return;
            }

            if (preAuthedUser == null)
            {
                PrintWriter writer = resp.getWriter();
                resp.setContentType("text/html");
                writer.println("<h2>Login to OpenID Connect Provider</h2>");
                writer.println("<form action=\"" + AUTH_PATH + "\" method=\"post\">");
                writer.println("<input type=\"text\" autocomplete=\"off\" placeholder=\"Username\" name=\"username\" required>");
                writer.println("<input type=\"hidden\" name=\"redirectUri\" value=\"" + redirectUri + "\">");
                writer.println("<input type=\"hidden\" name=\"state\" value=\"" + state + "\">");
                writer.println("<input type=\"submit\">");
                writer.println("</form>");
            }
            else
            {
                redirectUser(req, resp, preAuthedUser, redirectUri, state);
            }
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            String redirectUri = req.getParameter("redirectUri");
            if (!redirectUris.contains(redirectUri))
            {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "invalid redirect_uri");
                return;
            }

            String state = req.getParameter("state");
            if (state == null)
            {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "no state param");
                return;
            }

            String username = req.getParameter("username");
            if (username == null)
            {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "no username");
                return;
            }

            User user = new User(username);
            redirectUser(req, resp, user, redirectUri, state);
        }

        public void redirectUser(HttpServletRequest request, HttpServletResponse response, User user, String redirectUri, String state) throws IOException
        {
            String authCode = UUID.randomUUID().toString().replace("-", "");
            issuedAuthCodes.put(authCode, user);

            try
            {
                redirectUri += "?code=" + authCode + "&state=" + state;
                
                int redirectCode = (HttpVersion.fromString(request.getProtocol()).getVersion() < HttpVersion.HTTP_1_1.getVersion()
                    ? HttpServletResponse.SC_MOVED_TEMPORARILY : HttpServletResponse.SC_SEE_OTHER);
                ServletContextResponse.getServletContextResponse(response).getServletApiResponse().sendRedirect(redirectCode, response.encodeRedirectURL(redirectUri));
            }
            catch (Throwable t)
            {
                issuedAuthCodes.remove(authCode);
                throw t;
            }
        }
    }

    public class OpenIdTokenEndpoint extends HttpServlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            String code = req.getParameter("code");

            if (!clientId.equals(req.getParameter("client_id")) ||
                !clientSecret.equals(req.getParameter("client_secret")) ||
                !redirectUris.contains(req.getParameter("redirect_uri")) ||
                !"authorization_code".equals(req.getParameter("grant_type")) ||
                code == null)
            {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "bad auth request");
                return;
            }

            User user = issuedAuthCodes.remove(code);
            if (user == null)
            {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "invalid auth code");
                return;
            }

            String accessToken = "ABCDEFG";
            long expiry = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
            String response = "{" +
                "\"access_token\": \"" + accessToken + "\"," +
                "\"id_token\": \"" + JwtEncoder.encode(user.getIdToken(provider, clientId)) + "\"," +
                "\"expires_in\": " + expiry + "," +
                "\"token_type\": \"Bearer\"" +
                "}";

            resp.setContentType("text/plain");
            resp.getWriter().print(response);
        }
    }

    public class OpenIdConfigServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            String discoveryDocument = "{" +
                "\"issuer\": \"" + provider + "\"," +
                "\"authorization_endpoint\": \"" + provider + AUTH_PATH + "\"," +
                "\"token_endpoint\": \"" + provider + TOKEN_PATH + "\"," +
                "}";

            resp.getWriter().write(discoveryDocument);
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

        public String getIdToken(String provider, String clientId)
        {
            long expiry = System.currentTimeMillis() + Duration.ofMinutes(1).toMillis();
            return JwtEncoder.createIdToken(provider, clientId, subject, name, expiry);
        }
    }
}
