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
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.session.FileSessionDataStoreFactory;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.tests.OpenIdProvider;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.security.Password;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SuppressWarnings("unchecked")
public class OpenIdAuthenticationTest
{
    public static final String CLIENT_ID = "testClient101";
    public static final String CLIENT_SECRET = "secret37989798";

    private OpenIdProvider openIdProvider;
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    public void setup(LoginService loginService) throws Exception
    {
        setup(loginService, false);
    }

    public void setup(LoginService loginService, boolean logoutWhenIdTokenIsExpired) throws Exception
    {
        setup(loginService, provider -> {}, builder -> builder.logoutWhenIdTokenIsExpired(logoutWhenIdTokenIsExpired));
    }

    public void setup(LoginService loginService, Consumer<OpenIdProvider> configureProvider, Consumer<OpenIdConfiguration.Builder> configureClient) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        // Set up a local OIDC provider and add its configuration to the Server.
        openIdProvider = new OpenIdProvider(CLIENT_ID, CLIENT_SECRET);
        configureProvider.accept(openIdProvider);
        openIdProvider.start();

        OpenIdConfiguration.Builder builder = new OpenIdConfiguration.Builder(openIdProvider.getProvider(), CLIENT_ID, CLIENT_SECRET);
        configureClient.accept(builder);
        OpenIdConfiguration openIdConfiguration = builder.build();
        server.addBean(openIdConfiguration);

        // Configure SecurityHandler.
        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        assertThat(securityHandler.getKnownAuthenticatorFactories().size(), greaterThanOrEqualTo(2));
        securityHandler.setLoginService(loginService);
        securityHandler.setAuthenticationType(Authenticator.OPENID_AUTH);
        securityHandler.setRealmName(openIdProvider.getProvider());

        // Configure Contexts.
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        securityHandler.setHandler(contexts);
        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setHandler(securityHandler);
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setHandler(sessionHandler);
        server.setHandler(contextHandler);
        // TODO this is a VERY strange usage of ContextHandlerCollection, as normally
        //      the Session and Security handlers would be inside a single context.
        contexts.addHandler(new LoginPage("/login"));
        contexts.addHandler(new LogoutPage("/logout"));
        contexts.addHandler(new HomePage("/"));
        contexts.addHandler(new ErrorPage("/error"));

        // Configure security constraints.
        securityHandler.put("/login", Constraint.ANY_USER);
        securityHandler.put("/profile", Constraint.ANY_USER);
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
        LifeCycle.stop(openIdProvider);
        LifeCycle.stop(server);
    }

    @Test
    public void testLoginLogout() throws Exception
    {
        setup(null);
        openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));

        String appUriString = "http://localhost:" + connector.getLocalPort();

        // Initially not authenticated
        ContentResponse response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String content = response.getContentAsString();
        assertThat(content, containsString("not authenticated"));

        // Request to login is success
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

    @Test
    public void testPkceLogin() throws Exception
    {
        setup(null, provider -> provider.setRequirePkce(true), builder -> {});
        openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));
        String appUriString = "http://localhost:" + connector.getLocalPort();

        ContentResponse response = client.GET(appUriString + "/login");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("success"));

        // The challenge is S256 over a 43 character verifier, and the token request carries that verifier.
        assertThat(openIdProvider.getAuthorizationRequests().size(), is(1));
        MultiMap<String> authorization = UrlEncoded.decodeQuery(openIdProvider.getAuthorizationRequests().get(0));
        String codeChallenge = authorization.getValue("code_challenge");
        assertThat(authorization.getValue("code_challenge_method"), is("S256"));
        assertThat(codeChallenge, matchesRegex("[A-Za-z0-9_-]{43}"));
        assertThat(openIdProvider.getTokenRequests().size(), is(1));
        String codeVerifier = openIdProvider.getTokenRequests().get(0).getValue("code_verifier");
        assertThat(codeVerifier, matchesRegex("[A-Za-z0-9_-]{43}"));
        assertThat(OpenIdProvider.codeChallenge(codeVerifier), is(codeChallenge));
        assertThat(OpenIdAuthenticator.codeChallenge(codeVerifier), is(codeChallenge));

        response = client.GET(appUriString + "/");
        assertThat(response.getContentAsString(), containsString("userId: 123456789"));
    }

    @Test
    public void testPkceVerifierBelongsToItsState() throws Exception
    {
        setup(null, provider -> provider.setRequirePkce(true), builder -> {});
        openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));
        String appUriString = "http://localhost:" + connector.getLocalPort();

        // Two challenges in one session, each with its own state and challenge.
        ContentResponse first = client.newRequest(appUriString + "/login").followRedirects(false).send();
        ContentResponse second = client.newRequest(appUriString + "/login").followRedirects(false).send();
        String firstChallenge = UrlEncoded.decodeQuery(URI.create(first.getHeaders().get(HttpHeader.LOCATION)).getRawQuery()).getValue("code_challenge");
        String secondChallenge = UrlEncoded.decodeQuery(URI.create(second.getHeaders().get(HttpHeader.LOCATION)).getRawQuery()).getValue("code_challenge");
        assertThat(firstChallenge, not(secondChallenge));

        // Completing the second flow redeems with the second flow's verifier.
        ContentResponse response = client.GET(second.getHeaders().get(HttpHeader.LOCATION));
        assertThat(response.getContentAsString(), containsString("success"));
        String codeVerifier = openIdProvider.getTokenRequests().get(0).getValue("code_verifier");
        assertThat(OpenIdProvider.codeChallenge(codeVerifier), is(secondChallenge));
        assertThat(OpenIdProvider.codeChallenge(codeVerifier), not(firstChallenge));
    }

    @Test
    public void testPkceWrongVerifierRefused() throws Exception
    {
        setup(null, provider -> provider.setRequirePkce(true), builder -> {});
        openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));
        String appUriString = "http://localhost:" + connector.getLocalPort();

        // Take the code the provider issues for the challenge, and redeem it with a different verifier.
        ContentResponse challenge = client.newRequest(appUriString + "/login").followRedirects(false).send();
        ContentResponse callback = client.newRequest(challenge.getHeaders().get(HttpHeader.LOCATION)).followRedirects(false).send();
        String code = UrlEncoded.decodeQuery(URI.create(callback.getHeaders().get(HttpHeader.LOCATION)).getRawQuery()).getValue("code");
        OpenIdConfiguration configuration = server.getBean(OpenIdConfiguration.class);
        OpenIdCredentials credentials = new OpenIdCredentials(code, appUriString + "/redirect_path", "A".repeat(43));
        credentials.redeemAuthCode(configuration);
        assertThat(credentials.getErrorFields(), notNullValue());
        assertThat(credentials.getErrorFields().getValue("error"), is("invalid_grant"));
    }

    @Test
    public void testPkceDisabled() throws Exception
    {
        setup(null, provider -> {}, builder -> builder.pkce(false));
        openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));
        String appUriString = "http://localhost:" + connector.getLocalPort();

        ContentResponse response = client.GET(appUriString + "/login");
        assertThat(response.getContentAsString(), containsString("success"));
        MultiMap<String> authorization = UrlEncoded.decodeQuery(openIdProvider.getAuthorizationRequests().get(0));
        assertThat(authorization.getValue("code_challenge"), nullValue());
        assertThat(authorization.getValue("code_challenge_method"), nullValue());
        assertThat(openIdProvider.getTokenRequests().get(0).getValue("code_verifier"), nullValue());
    }

    @Test
    public void testPkceRequiredButDisabledFails() throws Exception
    {
        setup(null, provider -> provider.setRequirePkce(true), builder -> builder.pkce(false));
        openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));
        String appUriString = "http://localhost:" + connector.getLocalPort();

        ContentResponse response = client.GET(appUriString + "/login");
        assertThat(response.getContentAsString(), containsString("not authorized"));
        assertThat(openIdProvider.getTokenRequests().size(), is(0));
    }

    @Test
    public void testPublicClient() throws Exception
    {
        setup(null,
            provider ->
            {
                provider.setPublicClient(true);
                provider.setRequirePkce(true);
            },
            builder -> builder.clientSecret(null).authenticationMethod(OpenIdConfiguration.AUTHENTICATION_METHOD_NONE));
        openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));
        String appUriString = "http://localhost:" + connector.getLocalPort();

        ContentResponse response = client.GET(appUriString + "/login");
        assertThat(response.getContentAsString(), containsString("success"));
        Fields tokenRequest = openIdProvider.getTokenRequests().get(0);
        assertThat(tokenRequest.getValue("client_id"), is(CLIENT_ID));
        assertThat(tokenRequest.get("client_secret"), nullValue());
    }

    @Test
    public void testClientSecretRequiredUnlessPublic()
    {
        assertThrows(IllegalArgumentException.class, () -> new OpenIdConfiguration.Builder("https://example.com", CLIENT_ID, null).build());
        assertThrows(IllegalArgumentException.class, () -> new OpenIdConfiguration.Builder("https://example.com", CLIENT_ID, null)
            .authenticationMethod("client_secret_post").build());
        assertThrows(IllegalArgumentException.class, () -> new OpenIdConfiguration.Builder("https://example.com", CLIENT_ID, null)
            .authenticationMethod("client_secret_basic").build());
        OpenIdConfiguration configuration = new OpenIdConfiguration.Builder("https://example.com", CLIENT_ID, null)
            .authenticationMethod(OpenIdConfiguration.AUTHENTICATION_METHOD_NONE).build();
        assertThat(configuration.getClientSecret(), nullValue());
        assertThat(configuration.isPkce(), is(true));
    }

    @Test
    public void testUnknownAuthenticationMethodFailsRedeem() throws Exception
    {
        setup(null, provider -> {}, builder -> builder.authenticationMethod("unknown_method"));
        OpenIdConfiguration configuration = server.getBean(OpenIdConfiguration.class);
        OpenIdCredentials credentials = new OpenIdCredentials("code", "http://localhost/redirect_path", null);
        credentials.redeemAuthCode(configuration);
        assertThat(credentials.getErrorFields(), notNullValue());
        assertThat(credentials.getErrorFields().getValue(OpenIdAuthenticator.ERROR_PARAMETER), containsString("unknown_method"));
        assertThat(openIdProvider.getTokenRequests().size(), is(0));
    }

    @Test
    public void testNestedLoginService() throws Exception
    {
        AtomicBoolean loggedIn = new AtomicBoolean(true);
        setup(new AbstractLoginService()
        {

            @Override
            protected List<RolePrincipal> loadRoleInfo(UserPrincipal user)
            {
                return List.of(new RolePrincipal("admin"));
            }

            @Override
            protected UserPrincipal loadUserInfo(String username)
            {
                return new UserPrincipal(username, new Password(""));
            }

            @Override
            public boolean validate(UserIdentity user)
            {
                if (!loggedIn.get())
                    return false;
                return super.validate(user);
            }
        });

        openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));

        String appUriString = "http://localhost:" + connector.getLocalPort();

        // Initially not authenticated
        ContentResponse response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String content = response.getContentAsString();
        assertThat(content, containsString("not authenticated"));

        // Request to login is success
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

        // The nested login service has supplied the admin role.
        response = client.GET(appUriString + "/admin");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));

        // This causes any validation of UserIdentity in the LoginService to fail
        // causing subsequent requests to be redirected to the auth endpoint for login again.
        loggedIn.set(false);
        client.setFollowRedirects(false);
        response = client.GET(appUriString + "/admin");
        assertThat(response.getStatus(), is(HttpStatus.SEE_OTHER_303));
        String location = response.getHeaders().get(HttpHeader.LOCATION);
        assertThat(location, containsString(openIdProvider.getProvider() + "/auth"));

        // Note that we couldn't follow "OpenID Connect RP-Initiated Logout 1.0" because we redirect straight to auth endpoint.
        assertThat(openIdProvider.getLoggedInUsers().getCurrent(), equalTo(1L));
        assertThat(openIdProvider.getLoggedInUsers().getMax(), equalTo(1L));
        assertThat(openIdProvider.getLoggedInUsers().getTotal(), equalTo(1L));
    }

    @Test
    public void testExpiredIdToken() throws Exception
    {
        setup(null, true);
        long idTokenExpiryTime = 2000;
        openIdProvider.setIdTokenDuration(idTokenExpiryTime);
        openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));

        String appUriString = "http://localhost:" + connector.getLocalPort();

        // Initially not authenticated
        ContentResponse response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String content = response.getContentAsString();
        assertThat(content, containsString("not authenticated"));

        // Request to login is success
        response = client.GET(appUriString + "/login");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("success"));

        // Now authenticated we can get info
        client.setFollowRedirects(false);
        response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("userId: 123456789"));
        assertThat(content, containsString("name: Alice"));
        assertThat(content, containsString("email: Alice@example.com"));

        // After waiting past ID_Token expiry time we are no longer authenticated.
        // Even though this page is non-mandatory authentication the OpenId attributes should be cleared.
        // This then attempts re-authorization the first time even though it is non-mandatory page.
        Thread.sleep(idTokenExpiryTime * 2);
        response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.SEE_OTHER_303));
        assertThat(response.getHeaders().get(HttpHeader.LOCATION), startsWith(openIdProvider.getProvider() + "/auth"));

        // User was never redirected to logout page.
        assertThat(openIdProvider.getLoggedInUsers().getCurrent(), equalTo(1L));
        assertThat(openIdProvider.getLoggedInUsers().getMax(), equalTo(1L));
        assertThat(openIdProvider.getLoggedInUsers().getTotal(), equalTo(1L));
    }

    @Test
    public void testExpiredIdTokenDisabled() throws Exception
    {
        setup(null);
        long idTokenExpiryTime = 2000;
        openIdProvider.setIdTokenDuration(idTokenExpiryTime);
        openIdProvider.setUser(new OpenIdProvider.User("123456789", "Alice"));

        String appUriString = "http://localhost:" + connector.getLocalPort();

        // Initially not authenticated
        ContentResponse response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String content = response.getContentAsString();
        assertThat(content, containsString("not authenticated"));

        // Request to login is success
        response = client.GET(appUriString + "/login");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("success"));

        // Now authenticated we can get info
        client.setFollowRedirects(false);
        response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("userId: 123456789"));
        assertThat(content, containsString("name: Alice"));
        assertThat(content, containsString("email: Alice@example.com"));

        // After waiting past ID_Token expiry time we are still authenticated because logoutWhenIdTokenIsExpired is false by default.
        Thread.sleep(idTokenExpiryTime * 2);
        response = client.GET(appUriString + "/");
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContentAsString();
        assertThat(content, containsString("userId: 123456789"));
        assertThat(content, containsString("name: Alice"));
        assertThat(content, containsString("email: Alice@example.com"));
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
            AuthenticationState.logout(request, response);
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
                Principal userPrincipal = AuthenticationState.getUserPrincipal(request);
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
