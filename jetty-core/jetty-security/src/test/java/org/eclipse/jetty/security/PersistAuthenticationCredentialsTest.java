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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.security.authentication.SessionAuthentication;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.session.FileSessionDataStoreFactory;
import org.eclipse.jetty.session.NullSessionCacheFactory;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.security.Credential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class PersistAuthenticationCredentialsTest
{
    private static final Logger LOG = LoggerFactory.getLogger(PersistAuthenticationCredentialsTest.class);

    @TempDir
    private File _tempDir;
    private LocalConnector _localConnector;
    private Server _server;
    private SecurityHandler.PathMapped _securityHandler;

    @BeforeEach
    public void setup() throws Exception
    {
        _server = new Server();
        _localConnector = new LocalConnector(_server);
        _server.addConnector(_localConnector);
        _securityHandler = new SecurityHandler.PathMapped();
        _securityHandler.put("/*", Constraint.ANY_USER);
        HashLoginService loginService = new HashLoginService();
        UserStore userStore = new UserStore();
        userStore.addUser("foo", Credential.getCredential("bar"), new String[]{"admin"});
        loginService.setUserStore(userStore);
        _securityHandler.setLoginService(loginService);

        _securityHandler.setAuthenticator(new LoginAuthenticator()
        {
            @Override
            public String getAuthenticationType()
            {
                return "TEST";
            }

            @Override
            public UserIdentity login(String username, Object password, Request request, Response response)
            {
                UserIdentity user = super.login(username, password, request, response);
                if (user != null)
                {
                    Session session = request.getSession(true);
                    AuthenticationState cached = newSessionAuthentication(getAuthenticationType(), user, password);
                    session.setAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE, cached);
                }
                return user;
            }

            @Override
            public AuthenticationState validateRequest(Request request, Response response, Callback callback) throws ServerAuthException
            {
                // Look for cached authentication
                Session session = request.getSession(false);
                AuthenticationState authenticationState = session == null ? null : (AuthenticationState)session.getAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE);
                if (LOG.isDebugEnabled())
                    LOG.debug("auth {}", authenticationState);
                // Has authentication been revoked?
                if (authenticationState instanceof AuthenticationState.Succeeded succeeded && _loginService != null && !_loginService.validate(succeeded.getUserIdentity()))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("auth revoked {}", authenticationState);
                    session.removeAttribute(SessionAuthentication.AUTHENTICATED_ATTRIBUTE);
                    authenticationState = null;
                }

                if (authenticationState != null)
                {
                    response.getHeaders().add("source", "session");
                    return authenticationState;
                }

                response.getHeaders().add("source", "login");
                UserIdentity userIdentity = login("foo", "bar", request, response);
                return new LoginAuthenticator.UserAuthenticationSucceeded("TEST", userIdentity);
            }
        });
        FileSessionDataStoreFactory sessionDataStoreFactory = new FileSessionDataStoreFactory();
        sessionDataStoreFactory.setStoreDir(_tempDir);
        _server.addBean(sessionDataStoreFactory);
        _server.addBean(new NullSessionCacheFactory());
        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setHandler(_securityHandler);
        _securityHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.setStatus(200);
                callback.succeeded();
                return true;
            }
        });

        _server.setHandler(new ContextHandler(sessionHandler, "/"));
    }

    @AfterEach
    public void shutdown() throws Exception
    {
        _server.stop();
    }

    private File[] listFiles()
    {
        File[] files = _tempDir.listFiles();
        if (files == null)
            return new File[0];
        return files;
    }

    private String getSessionData() throws IOException
    {
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> listFiles().length > 0);
        File[] files = listFiles();
        if (files.length != 1)
            throw new IllegalStateException("Expected exactly one session file, but found: " + Arrays.toString(files));
        return new String(Files.readAllBytes(files[0].toPath()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testPersistAuthenticationCredentials(boolean persistAuthenticationCredentials) throws Exception
    {
        if (persistAuthenticationCredentials)
            _securityHandler.setPersistAuthenticationCredentials(true);
        _server.start();

        HttpTester.Response response = HttpTester.parseResponse(_localConnector.getResponse("""
            GET /hello HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.get("source"), equalTo("login"));
        String sessionId = response.get(HttpHeader.SET_COOKIE).split("JSESSIONID=")[1].split(";")[0];

        // Check session data on disk to ensure "bar" password is not in it
        if (persistAuthenticationCredentials)
            assertThat(getSessionData(), containsString("bar"));
        else
            assertThat(getSessionData(), not(containsString("bar")));

        // Verify that we can load it from disk again.
        response = HttpTester.parseResponse(_localConnector.getResponse("""
            GET /hello HTTP/1.1\r
            Host: local\r
            Connection: close\r
            Cookie: JSESSIONID=%s\r
            \r
            """.formatted(sessionId)));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.get("source"), equalTo("session"));
    }

    @Test
    public void testLegacySessionCredentialsCompatibility() throws Exception
    {
        _securityHandler.setPersistAuthenticationCredentials(true);
        _server.start();

        HttpTester.Response response = HttpTester.parseResponse(_localConnector.getResponse("""
            GET /hello HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.get("source"), equalTo("login"));
        String sessionId = response.get(HttpHeader.SET_COOKIE).split("JSESSIONID=")[1].split(";")[0];

        // Check session data on disk to ensure "bar" password is in it
        assertThat(getSessionData(), containsString("bar"));

        // Restart the server with the mode to not save credentials to verify we can still read it.
        _server.stop();
        _securityHandler.setPersistAuthenticationCredentials(false);
        _server.start();

        // Verify that we can load it from disk again.
        response = HttpTester.parseResponse(_localConnector.getResponse("""
            GET /hello HTTP/1.1\r
            Host: local\r
            Connection: close\r
            Cookie: JSESSIONID=%s\r
            \r
            """.formatted(sessionId)));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.get("source"), equalTo("session"));

        // The credential is no longer stored in the SessionData.
        assertThat(getSessionData(), not(containsString("bar")));
    }

    @Test
    public void testEnablePersistCredentials() throws Exception
    {
        _securityHandler.setPersistAuthenticationCredentials(false);
        _server.start();

        HttpTester.Response response = HttpTester.parseResponse(_localConnector.getResponse("""
            GET /hello HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.get("source"), equalTo("login"));
        String sessionId = response.get(HttpHeader.SET_COOKIE).split("JSESSIONID=")[1].split(";")[0];

        // The credential is not stored in the SessionData.
        assertThat(getSessionData(), not(containsString("bar")));

        // Restart the server with the setting to persist the credential.
        _server.stop();
        _securityHandler.setPersistAuthenticationCredentials(true);
        _server.start();

        // The persisted authentication has no credentials, and credentials are now required to
        // re-login, so the cached authentication cannot be restored. Rather than failing the
        // request, it is treated as unauthenticated and the user must authenticate.
        response = HttpTester.parseResponse(_localConnector.getResponse("""
            GET /hello HTTP/1.1\r
            Host: local\r
            Connection: close\r
            Cookie: JSESSIONID=%s\r
            \r
            """.formatted(sessionId)));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.get("source"), equalTo("login"));

        // Now that persistence is enabled the re-login persists the credential.
        assertThat(getSessionData(), containsString("bar"));
    }
}
