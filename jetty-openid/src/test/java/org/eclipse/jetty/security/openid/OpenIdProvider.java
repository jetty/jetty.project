//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.security.openid;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public class OpenIdProvider extends ContainerLifeCycle
{
    private static final String CONFIG_PATH = "/.well-known/openid-configuration";
    private static final String AUTH_PATH = "/auth";
    private static final String TOKEN_PATH = "/token";
    private final Map<String, User> issuedAuthCodes = new HashMap<>();

    protected final String clientId;
    protected final String clientSecret;
    protected final List<String> redirectUris = new ArrayList<>();
    private final ServerConnector connector;

    private String provider;
    private User preAuthedUser;

    public OpenIdProvider(String clientId, String clientSecret)
    {
        this.clientId = clientId;
        this.clientSecret = clientSecret;

        Server server = new Server();
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

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        provider = "http://localhost:" + connector.getLocalPort();
    }

    public void setUser(User user)
    {
        this.preAuthedUser = user;
    }

    public String getProvider()
    {
        if (!isStarted())
            throw new IllegalStateException();
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
                writer.println("<input type=\"text\" placeholder=\"Username\" name=\"username\" required>");
                writer.println("<input type=\"hidden\" name=\"redirectUri\" value=\"" + redirectUri + "\">");
                writer.println("<input type=\"hidden\" name=\"state\" value=\"" + state + "\">");
                writer.println("<input type=\"submit\">");
                writer.println("</form>");
            }
            else
            {
                redirectUser(req, preAuthedUser, redirectUri, state);
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
            redirectUser(req, user, redirectUri, state);
        }

        public void redirectUser(HttpServletRequest request, User user, String redirectUri, String state) throws IOException
        {
            String authCode = UUID.randomUUID().toString().replace("-", "");
            issuedAuthCodes.put(authCode, user);

            try
            {
                final Request baseRequest = Objects.requireNonNull(Request.getBaseRequest(request));
                final Response baseResponse = baseRequest.getResponse();
                redirectUri += "?code=" + authCode + "&state=" + state;
                int redirectCode = (baseRequest.getHttpVersion().getVersion() < HttpVersion.HTTP_1_1.getVersion()
                    ? HttpServletResponse.SC_MOVED_TEMPORARILY : HttpServletResponse.SC_SEE_OTHER);
                baseResponse.sendRedirect(redirectCode, baseResponse.encodeRedirectURL(redirectUri));
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
        private final long subject;
        private final String name;

        public User(String name)
        {
            this(UUID.nameUUIDFromBytes(name.getBytes()).getMostSignificantBits(), name);
        }

        public User(long subject, String name)
        {
            this.subject = subject;
            this.name = name;
        }

        public String getName()
        {
            return name;
        }

        public long getSubject()
        {
            return subject;
        }

        public String getIdToken(String provider, String clientId)
        {
            long expiry = System.currentTimeMillis() + Duration.ofMinutes(1).toMillis();
            return JwtEncoder.createIdToken(provider, clientId, Long.toString(subject), name, expiry);
        }
    }
}
