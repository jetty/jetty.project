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

package org.eclipse.jetty.ee9.test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.awaitility.Awaitility;
import org.eclipse.jetty.ee9.nested.Authentication;
import org.eclipse.jetty.ee9.nested.ServletConstraint;
import org.eclipse.jetty.ee9.nested.SessionHandler;
import org.eclipse.jetty.ee9.security.ConstraintMapping;
import org.eclipse.jetty.ee9.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee9.security.ServerAuthException;
import org.eclipse.jetty.ee9.security.UserAuthentication;
import org.eclipse.jetty.ee9.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.ee9.security.authentication.SessionAuthentication;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.FileSessionDataStoreFactory;
import org.eclipse.jetty.session.NullSessionCacheFactory;
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
    private ConstraintSecurityHandler _securityHandler;

    @BeforeEach
    public void setup() throws Exception
    {
        _server = new Server();
        _localConnector = new LocalConnector(_server);
        _server.addConnector(_localConnector);
        _securityHandler = new ConstraintSecurityHandler();
        ConstraintMapping constraintMapping = new ConstraintMapping();
        constraintMapping.setPathSpec("/*");
        ServletConstraint servletConstraint = new ServletConstraint();
        servletConstraint.setAuthenticate(true);
        servletConstraint.setRoles(new String[]{"**"});
        constraintMapping.setConstraint(servletConstraint);
        _securityHandler.addConstraintMapping(constraintMapping);
        HashLoginService loginService = new HashLoginService();
        UserStore userStore = new UserStore();
        userStore.addUser("foo", Credential.getCredential("bar"), new String[]{"admin"});
        loginService.setUserStore(userStore);
        _securityHandler.setLoginService(loginService);

        _securityHandler.setAuthenticator(new LoginAuthenticator()
        {
            @Override
            public String getAuthMethod()
            {
                return "TEST";
            }

            @Override
            public UserIdentity login(String username, Object password, ServletRequest request)
            {

                UserIdentity user = super.login(username, password, request);
                if (user != null)
                {

                    HttpSession session = ((HttpServletRequest)request).getSession(true);
                    Authentication cached = newSessionAuthentication(getAuthMethod(), user, password);
                    session.setAttribute(SessionAuthentication.__J_AUTHENTICATED, cached);
                }
                return user;
            }

            @Override
            public Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory) throws ServerAuthException
            {
                HttpServletRequest httpServletRequest = (HttpServletRequest)request;
                HttpServletResponse httpServletResponse = (HttpServletResponse)response;

                // Look for cached authentication
                HttpSession session = httpServletRequest.getSession(false);
                Authentication authenticationState = session == null ? null : (Authentication)session.getAttribute(SessionAuthentication.__J_AUTHENTICATED);
                if (LOG.isDebugEnabled())
                    LOG.debug("auth {}", authenticationState);
                // Has authentication been revoked?
                if (authenticationState instanceof AuthenticationState.Succeeded succeeded && _loginService != null && !_loginService.validate(succeeded.getUserIdentity()))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("auth revoked {}", authenticationState);
                    session.removeAttribute(SessionAuthentication.__J_AUTHENTICATED);
                    authenticationState = null;
                }

                if (authenticationState != null)
                {
                    httpServletResponse.setHeader("source", "session");
                    return authenticationState;
                }

                httpServletResponse.setHeader("source", "login");
                UserIdentity userIdentity = login("foo", "bar", request);
                return new UserAuthentication("TEST", userIdentity);
            }

            @Override
            public boolean secureResponse(ServletRequest request, ServletResponse response, boolean mandatory, Authentication.User validatedUser) throws org.eclipse.jetty.ee9.security.ServerAuthException
            {
                return false;
            }
        });
        FileSessionDataStoreFactory sessionDataStoreFactory = new FileSessionDataStoreFactory();
        sessionDataStoreFactory.setStoreDir(_tempDir);
        _server.addBean(sessionDataStoreFactory);
        _server.addBean(new NullSessionCacheFactory());

        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setHandler(_securityHandler);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setSessionHandler(sessionHandler);
        servletContextHandler.setSecurityHandler(_securityHandler);
        _server.setHandler(servletContextHandler);

        servletContextHandler.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                resp.setStatus(HttpStatus.OK_200);
            }
        }), "/");
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
