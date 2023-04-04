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

import java.io.File;
import java.io.PrintStream;
import java.security.Principal;
import java.util.Map;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.security.Authentication;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.session.FileSessionDataStoreFactory;
import org.eclipse.jetty.session.SimpleSessionHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

@SuppressWarnings("unchecked")
public class OpenIdAuthenticationTest
{
    public static final String CLIENT_ID = "testClient101";
    public static final String CLIENT_SECRET = "secret37989798";

    private OpenIdProvider openIdProvider;
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    @BeforeEach
    public void setup() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        // Set up a local OIDC provider and add its configuration to the Server.
        openIdProvider = new OpenIdProvider(CLIENT_ID, CLIENT_SECRET);
        openIdProvider.start();
        server.addBean(new OpenIdConfiguration(openIdProvider.getProvider(), CLIENT_ID, CLIENT_SECRET));

        // Configure SecurityHandler.
        SecurityHandler.Mapped securityHandler = new SecurityHandler.Mapped();
        assertThat(securityHandler.getKnownAuthenticatorFactories().size(), greaterThanOrEqualTo(2));
        securityHandler.setAuthMethod(Authenticator.OPENID_AUTH);
        securityHandler.setRealmName(openIdProvider.getProvider());

        // Configure Contexts.
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        securityHandler.setHandler(contexts);
        SimpleSessionHandler sessionHandler = new SimpleSessionHandler();
        sessionHandler.setHandler(securityHandler);
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setHandler(sessionHandler);
        server.setHandler(contextHandler);
        contexts.addHandler(new LoginPage("/login"));
        contexts.addHandler(new LogoutPage("/logout"));
        contexts.addHandler(new HomePage("/"));
        contexts.addHandler(new ErrorPage("/error"));

        // Configure security constraints.
        securityHandler.put("/login", Constraint.AUTHENTICATED);
        securityHandler.put("/profile", Constraint.AUTHENTICATED);
        securityHandler.put("/admin", Constraint.from("admin"));

        // Configure Jetty to use the local OIDC provider we have previously configured.
        securityHandler.setParameter(OpenIdAuthenticator.REDIRECT_PATH, "/redirect_path");
        securityHandler.setParameter(OpenIdAuthenticator.ERROR_PAGE, "/error");
        securityHandler.setParameter(OpenIdAuthenticator.LOGOUT_REDIRECT_PATH, "/");

        File datastoreDir = MavenTestingUtils.getTargetTestingDir("datastore");
        IO.delete(datastoreDir);
        FileSessionDataStoreFactory fileSessionDataStoreFactory = new FileSessionDataStoreFactory();
        fileSessionDataStoreFactory.setStoreDir(datastoreDir);
        server.addBean(fileSessionDataStoreFactory);

        // Start the server and add the Servers RedirectURI to the Provider.
        server.start();
        String redirectUri = "http://localhost:" + connector.getLocalPort() + "/redirect_path";
        openIdProvider.addRedirectUri(redirectUri);

        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        openIdProvider.stop();
        server.stop();
    }

    @Test
    public void testLoginLogout() throws Exception
    {
        openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));

        String appUriString = "http://localhost:" + connector.getLocalPort();

        // Initially not authenticated
        ContentResponse response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String content = response.getContentAsString();
        assertThat(content, containsString("not authenticated"));

        // Request to log in is success.
        response = client.GET(appUriString + "/login");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("success"));

        // Now authenticated we can get info
        response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("userId: 123456789"));
        assertThat(content, containsString("name: Alice"));
        assertThat(content, containsString("email: Alice@example.com"));

        // Request to admin page gives 403 as we do not have admin role
        response = client.GET(appUriString + "/admin");
        assertThat(response.getStatus(), is(HttpStatus.FORBIDDEN_403));

        // We can restart the server and still be logged in as we have persistent session datastore.
        server.stop();
        server.start();
        appUriString = "http://localhost:" + connector.getLocalPort();

        // After restarting server the authentication is saved as a session authentication.
        response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("userId: 123456789"));
        assertThat(content, containsString("name: Alice"));
        assertThat(content, containsString("email: Alice@example.com"));

        // We are no longer authenticated after logging out
        response = client.GET(appUriString + "/logout");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("not authenticated"));

        // Test that the user was logged out successfully on the openid provider.
        assertThat(openIdProvider.getLoggedInUsers().getCurrent(), equalTo(0L));
        assertThat(openIdProvider.getLoggedInUsers().getMax(), equalTo(1L));
        assertThat(openIdProvider.getLoggedInUsers().getTotal(), equalTo(1L));
    }

    public static class LoginPage extends ContextHandler
    {
        public LoginPage(String contextPath)
        {
            super(contextPath);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().add(HttpHeader.CONTENT_TYPE, "text/html");
            try (PrintStream output = new PrintStream(Content.Sink.asOutputStream(response)))
            {
                output.println("success");
                output.println("<br><a href=\"/\">Home</a>");
                output.close();
                callback.succeeded();
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }

            return true;
        }
    }

    public static class LogoutPage extends ContextHandler
    {
        public LogoutPage(String contextPath)
        {
            super(contextPath);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            Authentication.logout(request, response);
            callback.succeeded();
            return true;
        }
    }

    public static class AdminPage extends ContextHandler
    {
        public AdminPage(String contextPath)
        {
            super(contextPath);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().add(HttpHeader.CONTENT_TYPE, "text/html");
            try (PrintStream output = new PrintStream(Content.Sink.asOutputStream(response)))
            {
                Map<String, Object> userInfo = (Map<String, Object>)request.getSession(false).getAttribute(OpenIdAuthenticator.CLAIMS);
                output.println(userInfo.get("sub") + ": success");
                output.close();
                callback.succeeded();
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }

            return true;
        }
    }

    public static class HomePage extends ContextHandler
    {
        public HomePage(String contextPath)
        {
            super(contextPath);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().add(HttpHeader.CONTENT_TYPE, "text/html");

            try (PrintStream output = new PrintStream(Content.Sink.asOutputStream(response)))
            {
                Principal userPrincipal = Authentication.getUserPrincipal(request);
                if (userPrincipal != null)
                {
                    Map<String, Object> userInfo = (Map<String, Object>)request.getSession(false).getAttribute(OpenIdAuthenticator.CLAIMS);
                    output.println("userId: " + userInfo.get("sub") + "<br>");
                    output.println("name: " + userInfo.get("name") + "<br>");
                    output.println("email: " + userInfo.get("email") + "<br>");
                    output.println("<br><a href=\"/logout\">Logout</a>");
                }
                else
                {
                    output.println("not authenticated");
                    output.println("<br><a href=\"/login\">Login</a>");
                }

                output.close();
                callback.succeeded();
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }

            return true;
        }
    }

    public static class ErrorPage extends ContextHandler
    {
        public ErrorPage(String contextPath)
        {
            super(contextPath);
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().add(HttpHeader.CONTENT_TYPE, "text/html");
            try (PrintStream output = new PrintStream(Content.Sink.asOutputStream(response)))
            {
                output.println("not authorized");
                output.println("<br><a href=\"/\">Home</a>");
                output.close();
                callback.succeeded();
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }

            return true;
        }
    }
}
