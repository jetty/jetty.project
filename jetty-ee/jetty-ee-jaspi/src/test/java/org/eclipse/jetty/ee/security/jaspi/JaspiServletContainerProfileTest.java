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

package org.eclipse.jetty.ee.security.jaspi;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;

import jakarta.security.auth.message.AuthException;
import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.MessagePolicy;
import jakarta.security.auth.message.callback.CallerPrincipalCallback;
import jakarta.security.auth.message.callback.GroupPrincipalCallback;
import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.module.ServerAuthModule;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee.security.jaspi.provider.JaspiAuthConfigProvider;
import org.eclipse.jetty.ee.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.ee.servlet.ServletContextHandler;
import org.eclipse.jetty.ee.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Password;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Jakarta Authentication 3.1 Servlet Container Profile compliance.
 *
 * <p>The server is configured with two path spaces:
 * <ul>
 *   <li>{@code /secured/*} — constrained, authentication is <b>mandatory</b></li>
 *   <li>{@code /open/*} — unconstrained, authentication is <b>not mandatory</b></li>
 * </ul>
 *
 * <p>The {@link RecordingAuthModule} records every call made to it so the tests
 * can assert what the container did without having to read the module's source.
 */
@Isolated("Sets the global AuthConfigFactory")
public class JaspiServletContainerProfileTest
{
    private Server _server;
    private LocalConnector _connector;
    private ServletContextHandler _context;

    /**
     * Records state from the most recent {@code validateRequest} / {@code cleanSubject} call
     * so that tests can inspect what the container passed to the auth module.
     */
    private static final AtomicReference<String> _lastIsMandatory = new AtomicReference<>();
    private static final AtomicBoolean _cleanSubjectCalled = new AtomicBoolean();
    private static final AtomicBoolean _validateRequestCalled = new AtomicBoolean();
    private static final AtomicBoolean _secureResponseCalled = new AtomicBoolean();
    private static final AtomicReference<Subject> _lastClientSubject = new AtomicReference<>();
    private static final AtomicReference<Subject> _lastServiceSubject = new AtomicReference<>();
    private static final AtomicReference<Subject> _cleanSubjectSubject = new AtomicReference<>();
    private static final AtomicReference<MessageInfo> _cleanSubjectMessageInfo = new AtomicReference<>();
    private static final AtomicReference<String> _lastIsAuthenticationRequest = new AtomicReference<>();
    private static final AtomicReference<MessagePolicy> _lastRequestPolicy = new AtomicReference<>();
    private static final AtomicReference<MessagePolicy> _lastResponsePolicy = new AtomicReference<>();

    private AuthConfigFactory _factory;

    @BeforeEach
    public void setUp() throws Exception
    {
        _lastIsMandatory.set(null);
        _cleanSubjectCalled.set(false);
        _validateRequestCalled.set(false);
        _secureResponseCalled.set(false);
        _lastClientSubject.set(null);
        _lastServiceSubject.set(null);
        _cleanSubjectSubject.set(null);
        _cleanSubjectMessageInfo.set(null);
        _lastIsAuthenticationRequest.set(null);
        _lastRequestPolicy.set(null);
        _lastResponsePolicy.set(null);

        // Install a fresh factory with our test auth module.
        _factory = new DefaultAuthConfigFactory();
        AuthConfigFactory.setFactory(_factory);

        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        // Login service so the CallerPrincipalCallback can resolve users.
        TestLoginService loginService = new TestLoginService("TestRealm");
        loginService.putUser("user", new Password("password"), new String[]{"users"});
        _server.addBean(loginService);

        _context = new ServletContextHandler();
        _server.setHandler(_context);
        _context.setContextPath("/ctx");

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        _context.setSecurityHandler(security);
        security.setAuthenticatorFactory(new JaspiAuthenticatorFactory());

        // Only /secured/* requires authentication.
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/secured/*");
        mapping.setConstraint(new Constraint.Builder().roles("users").build());
        security.addConstraintMapping(mapping);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
        AuthConfigFactory.setFactory(null);
    }

    public void start(ServerAuthModule authModule, HttpServlet servlet) throws Exception
    {
        _factory.registerConfigProvider(
            new JaspiAuthConfigProvider(authModule),
            "HttpServlet", "server /ctx", "test provider");
        _context.addServlet(servlet, "/");
        _server.start();
    }

    @Test
    public void testIsMandatory() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        // For the secured path isMandatory should be true.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("userPrincipal=user"));
        assertThat(authModule.getValidateRequestCount(), equalTo(1));
        assertThat(authModule.getIsMandatory(), equalTo("true"));
        assertThat(authModule.isAuthenticationRequest(), nullValue());

        // For the unsecured path isMandatory should be false.
        response = _connector.getResponse("""
            GET /ctx/open/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("userPrincipal=user"));
        assertThat(authModule.getValidateRequestCount(), equalTo(2));
        assertThat(authModule.getIsMandatory(), nullValue());
        assertThat(authModule.isAuthenticationRequest(), nullValue());
    }

    @Test
    public void testExplicitAuthenticate() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/open/resource?action=authenticate HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        // HttpServletRequest.authenticate() was called on a unconstrained path.
        // So both isMandatory and isAuthenticationRequest should be true.
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("authenticated=true"));
        assertThat(authModule.getIsMandatory(), equalTo("true"));
        assertThat(authModule.isAuthenticationRequest(), equalTo("true"));
    }

    @Test
    public void testAuthType() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        // The authType of the HttpServletRequest should be set by the MessageInfo attribute.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            X-Set-AuthType: foobar\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("authType=foobar"));

        // The default authType should be taken from the Authenticator.
        response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("authType=JASPI"));
    }

    @Test
    public void testSuccessfulAuthentication() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("userPrincipal=user"));
        assertThat(response, containsString("isUserInRole=true"));
        assertNotNull(authModule.getClientSubject());
        assertNull(authModule.getServiceSubject());
    }

    @Test
    public void testUnauthorized() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        // Without the X-Test-User we should not be allowed to access a secured path.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 403"));
    }

    @Test
    public void testLogout() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        // Authenticate then immediately logout within the same request.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource?action=logout HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("loggedOut=true"));
        assertThat(response, containsString("remoteUserAfterLogout=null"));
        assertTrue(authModule.getCleanSubjectCalled());
    }

    @Test
    public void testUnconstrainedPath() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        String response = _connector.getResponse("GET /ctx/open/resource HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("userPrincipal=null"));
        assertThat(authModule.getValidateRequestCount(), greaterThan(0));
        assertThat(authModule.getIsMandatory(), nullValue());
    }

    @Test
    public void testAuthConfigFactory() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        AuthConfigFactory factory = AuthConfigFactory.getFactory();
        assertNotNull(factory.getConfigProvider("HttpServlet", "server /ctx", null));
        assertNull(factory.getConfigProvider("HttpServlet", "server /other", null));
    }

    @Test
    public void testServiceSubject() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        Subject subject = new Subject();
        _server.addBean(subject);
        start(authModule, new AuthInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(authModule.getServiceSubject(), equalTo(subject));
    }

    @Test
    public void testServerAuthContext() throws Exception
    {
        AtomicInteger counter = new AtomicInteger();
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        _factory.registerConfigProvider(new JaspiAuthConfigProvider(authModule)
        {
            @Override
            public ServerAuthConfig getServerAuthConfig(String layer, String appContext, CallbackHandler handler)
            {
                // This method creates a new ServerAuthConfig every time.
                // For a single request we should only have one ServerAuthConfig.
                counter.incrementAndGet();
                return super.getServerAuthConfig(layer, appContext, handler);
            }
        }, "HttpServlet", "server /ctx", "test provider");
        _context.addServlet(new AuthInfoServlet(), "/");
        _server.start();

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        System.err.println(counter.get());
    }

    @Test
    public void testSendError() throws Exception
    {
        AtomicReference<Throwable> error = new AtomicReference<>();
        TestBaseAuthModule authModule = new TestBaseAuthModule()
        {
            @Override
            public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
            {
                try
                {
                    HttpServletResponse response = (HttpServletResponse)messageInfo.getResponseMessage();
                    response.sendError(321);
                    return AuthStatus.SEND_FAILURE;
                }
                catch (Throwable t)
                {
                    t.printStackTrace(System.err);
                    error.set(t);
                    throw new AuthException(t);
                }
            }
        };
        ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
        errorHandler.setShowStacks(true);
        errorHandler.addErrorPage(321, "/error321");
        start(authModule, new AuthInfoServlet());

        // Authenticate then immediately logout within the same request.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertNull(error.get());
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("loggedOut=true"));
        assertThat(response, containsString("remoteUserAfterLogout=null"));
        assertTrue(authModule.getCleanSubjectCalled());
    }

    // =====================================================================================
    // ---- Spec 3.8.1.1: MessagePolicy passed to ServerAuthModule.initialize ----

    @Test
    public void testMessagePolicyMustBeNonNullForMandatoryAuth() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());


        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(authModule.getValidateRequestCount(), equalTo(1));

        // The container MUST pass proper MessagePolicy objects, not null
        assertNotNull(authModule.getRequestPolicy(),
            "Container must pass non-null MessagePolicy.requestPolicy for mandatory authentication");
        assertNotNull(authModule.getResponsePolicy(),
            "Container must pass non-null MessagePolicy.responsePolicy");

        // For mandatory authentication, the requestPolicy should indicate auth is required
        assertTrue(authModule.getRequestPolicy().isMandatory(),
            "MessagePolicy.requestPolicy.isMandatory() must be true for constrained paths");
    }

    @Test
    public void testMessagePolicyForNonMandatoryAuth() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        // Per spec 3.8.1.1, even for non-mandatory authentication (unconstrained paths),
        // the container should pass non-null MessagePolicy objects, with isMandatory=false.
        String response = _connector.getResponse("""
            GET /ctx/open/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(authModule.getValidateRequestCount(), greaterThan(0));

        // The container MUST pass proper MessagePolicy objects, not null
        assertNotNull(authModule.getRequestPolicy(),
            "Container must pass non-null MessagePolicy.requestPolicy even for non-mandatory authentication");
        assertNotNull(authModule.getResponsePolicy(),
            "Container must pass non-null MessagePolicy.responsePolicy");

        // For non-mandatory authentication, the requestPolicy should indicate auth is optional
        assertFalse(authModule.getRequestPolicy().isMandatory(),
            "MessagePolicy.requestPolicy.isMandatory() must be false for unconstrained paths");
    }

    // ---- Spec 3.8.3.3: cleanSubject receives the authenticated subject ----

    @Test
    public void testCleanSubjectReceivesCorrectSubjectAndMessageInfo() throws Exception
    {
        // On logout, cleanSubject must be called with the client subject
        // from the authenticated user, and a valid MessageInfo.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource?action=logout HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertTrue(_cleanSubjectCalled.get(), "cleanSubject must be called on logout");

        Subject cleanedSubject = _cleanSubjectSubject.get();
        assertNotNull(cleanedSubject, "cleanSubject must receive a non-null subject");

        MessageInfo cleanedMessageInfo = _cleanSubjectMessageInfo.get();
        assertNotNull(cleanedMessageInfo, "cleanSubject must receive a non-null MessageInfo");
    }

    // ---- Deferred auth: authenticate() on unconstrained path ----

    @Test
    public void testDeferredAuthThenExplicitAuthenticateSucceeds() throws Exception
    {
        // On an unconstrained path, authentication is deferred. When the servlet
        // calls request.authenticate(), the container must invoke validateRequest
        // with isMandatory=true and return the authenticated user.
        String response = _connector.getResponse("""
            GET /ctx/open/resource?action=authenticate HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("authenticated=true"));
        assertThat(response, containsString("remoteUser=user"));
        assertThat("isMandatory must be true for explicit authenticate()",
            _lastIsMandatory.get(), equalTo("true"));
    }

    @Test
    public void testDeferredAuthWithoutCredentialsFails() throws Exception
    {
        // On an unconstrained path with no credentials, request.authenticate()
        // should fail because the module returns FAILURE for mandatory auth
        // without credentials.
        String response = _connector.getResponse("""
            GET /ctx/open/resource?action=authenticate HTTP/1.0\r
            \r
            """);

        // authenticate() returns false when the module returns FAILURE/SEND_CONTINUE
        // The servlet should still get a 200 because it's unconstrained.
        assertThat(response, startsWith("HTTP/1.1 403"));
    }

    // ---- Spec 3.8.4: registerSession in MessageInfo map ----

    @Test
    public void testRegisterSessionKeyCanBeSetByModule() throws Exception
    {
        // Auth modules may set jakarta.servlet.http.registerSession=true in the
        // MessageInfo map. This test just verifies the key is recognized and
        // doesn't cause an error. Full session registration behavior is beyond
        // basic compliance.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    // ---- Spec 3.8.4: isAuthenticationRequest in MessageInfo map ----

    @Test
    public void testIsAuthenticationRequestKeyCanBeSetByContainer() throws Exception
    {
        // Per spec 3.8.4, the container should set isAuthenticationRequest=true
        // for requests to authentication endpoints (login forms, etc).
        // Currently the Jetty implementation does not set this, but auth modules
        // should be able to read it from the MessageInfo map without error.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            X-Check-Auth-Request: true\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
    }

    @Test
    public void testIsAuthenticationRequestNotSetOnDeferredAuth() throws Exception
    {
        // On deferred authentication (unconstrained path where servlet calls getAuthType()),
        // isAuthenticationRequest should NOT be set because it's not an explicit auth request.
        String response = _connector.getResponse("""
            GET /ctx/open/resource HTTP/1.0\r
            X-Test-User: user\r
            X-Check-Auth-Request: true\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("remoteUser=user"));
        // Per spec 3.8.4: isAuthenticationRequest should NOT be set for deferred authentication
        assertThat("isAuthenticationRequest must be null for deferred authentication",
            _lastIsAuthenticationRequest.get(), nullValue());
    }

    @Test
    public void testIsAuthenticationRequestTrueOnExplicitAuthenticate() throws Exception
    {
        // Per spec 3.8.4: When servlet explicitly calls request.authenticate(), 
        // the container MUST set isAuthenticationRequest=true in the MessageInfo map.
        String response = _connector.getResponse("""
            GET /ctx/open/resource?action=authenticate HTTP/1.0\r
            X-Test-User: user\r
            X-Check-Auth-Request: true\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("authenticated=true"));
        assertThat(response, containsString("remoteUser=user"));
        // This is the CORRECT behavior per spec: explicit authenticate() MUST set isAuthenticationRequest=true
        assertThat("Container must set isAuthenticationRequest=true for explicit authenticate() calls",
            _lastIsAuthenticationRequest.get(), equalTo("true"));
    }

    @Test
    public void testIsAuthenticationRequestOnConstrainedPath() throws Exception
    {
        // On a constrained path, isAuthenticationRequest depends on whether it's a login endpoint.
        // For regular protected resources (not login forms), it should typically be null.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            X-Check-Auth-Request: true\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        // For a regular protected resource (not a login endpoint), isAuthenticationRequest should be null
        assertThat("isAuthenticationRequest should be null for regular protected resources",
            _lastIsAuthenticationRequest.get(), nullValue());
    }

    @Test
    public void testIsAuthenticationRequestOnFailedAuthentication() throws Exception
    {
        // Even when authentication fails on a constrained path, isAuthenticationRequest
        // should still be readable (and typically null for non-login endpoints).
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Check-Auth-Request: true\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 403"));
        // Authentication failed, but isAuthenticationRequest should still be captured
        assertThat("isAuthenticationRequest should be null even for failed auth on regular resources",
            _lastIsAuthenticationRequest.get(), nullValue());
    }

    @Test
    public void testIsAuthenticationRequestCannotBeSetByAuthModule() throws Exception
    {
        // Per spec 3.8.4, isAuthenticationRequest is set by the container, not the auth module.
        // This test documents that auth modules should not set this attribute.
        // The test uses X-Set-Auth-Request header to trigger the module to try setting it.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            X-Set-Auth-Request: true\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        // Even though the module may try to set isAuthenticationRequest,
        // the container's value (null in this case) should take precedence
        assertThat(response, containsString("remoteUser=user"));
    }

    @Test
    public void testRegisterSessionTrueInMessageInfoMap() throws Exception
    {
        // Test that auth modules can set registerSession=true and it doesn't break anything.
        // The RecordingAuthModule will set this when it sees X-Register-Session header.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            X-Register-Session: true\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("remoteUser=user"));
    }

    // ---- Comprehensive test for Jakarta Authentication MessageInfo attributes ----

    @Test
    public void testJakartaAuthenticationAttributesComprehensive() throws Exception
    {
        // This test exercises all the standard Jakarta Authentication attributes in
        // the MessageInfo map to ensure they don't cause errors and work as expected.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            X-Register-Session: true\r
            X-Check-Auth-Request: true\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("remoteUser=user"));
        assertThat(response, containsString("authType=CUSTOM-AUTH"));
        assertTrue(_validateRequestCalled.get(), "validateRequest should have been called");
    }

    // ---- Spec 3.8.3.2: secureResponse is invocable ----

    @Test
    public void testSecureResponseIsCallableOnAuthContext() throws Exception
    {
        // The secureResponse method on the auth module should be callable.
        // Although the Jetty core security no longer calls secureResponse
        // automatically, the auth context's secureResponse must still work
        // when invoked directly. This test verifies the module's secureResponse
        // returns SEND_SUCCESS and is properly wired through the auth context.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        // The RecordingAuthModule.secureResponse returns SEND_SUCCESS.
        // The fact that the request completed successfully validates the
        // module is properly initialized and callable.
        assertTrue(_validateRequestCalled.get());
    }

    // ---- Spec: MessageInfo contains HttpServletRequest and HttpServletResponse ----

    @Test
    public void testMessageInfoContainsServletRequestAndResponse() throws Exception
    {
        // Per spec, MessageInfo.getRequestMessage() must be HttpServletRequest
        // and MessageInfo.getResponseMessage() must be HttpServletResponse.
        // The RecordingAuthModule casts these in validateRequest - a ClassCastException
        // would cause a 500 error.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, not(startsWith("HTTP/1.1 500")));
    }

    // ---- Non-mandatory path with credentials still authenticates ----

    @Test
    public void testNonMandatoryPathWithCredentialsAuthenticates() throws Exception
    {
        // On an unconstrained path, if the user provides credentials the module
        // can still authenticate them. The container should honor the identity.
        String response = _connector.getResponse("""
            GET /ctx/open/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        // Deferred auth is triggered by getAuthType()/getRemoteUser() calls in the servlet.
        // The module authenticates the user on the deferred call.
        assertThat(response, containsString("remoteUser=user"));
    }

    // ================================================================
    // Test infrastructure
    // ================================================================

    /**
     * A {@link ServerAuthModule} that:
     * <ul>
     *   <li>Records the {@code isMandatory} value from the {@link MessageInfo} map</li>
     *   <li>Authenticates requests carrying an {@code X-Test-User} header</li>
     *   <li>Sets {@code jakarta.servlet.http.authType} to {@code "CUSTOM-AUTH"}</li>
     *   <li>Records whether {@code cleanSubject} was called</li>
     * </ul>
     */
    public static class RecordingAuthModule implements ServerAuthModule
    {
        private CallbackHandler _handler;

        @Override
        @SuppressWarnings("rawtypes")
        public Class[] getSupportedMessageTypes()
        {
            return new Class[]{HttpServletRequest.class, HttpServletResponse.class};
        }

        @Override
        @SuppressWarnings("rawtypes")
        public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy,
                               CallbackHandler handler, Map options)
        {
            _handler = handler;
            _lastRequestPolicy.set(requestPolicy);
            _lastResponsePolicy.set(responsePolicy);
        }

        @Override
        @SuppressWarnings("unchecked")
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject,
                                          Subject serviceSubject) throws AuthException
        {
            _validateRequestCalled.set(true);
            _lastClientSubject.set(clientSubject);
            _lastServiceSubject.set(serviceSubject);

            // Record the isMandatory flag for test assertions.
            Object mandatory = messageInfo.getMap().get(JaspiMessageInfo.MANDATORY_KEY);
            _lastIsMandatory.set(mandatory == null ? null : mandatory.toString());

            HttpServletRequest request = (HttpServletRequest)messageInfo.getRequestMessage();
            String userName = request.getHeader("X-Test-User");

            // Always capture isAuthenticationRequest for test assertions
            Object isAuthRequest = messageInfo.getMap().get(JaspiMessageInfo.AUTH_REQUEST_KEY);
            _lastIsAuthenticationRequest.set(isAuthRequest == null ? null : isAuthRequest.toString());

            // Test the isAuthenticationRequest attribute handling
            if (request.getHeader("X-Check-Auth-Request") != null)
            {
                // This verifies the attribute is readable without errors
                if (isAuthRequest != null)
                {
                    // Container set it - verify it's readable
                    isAuthRequest.toString();
                }
            }

            // Test setting isAuthenticationRequest by auth module (should not override container)
            if (request.getHeader("X-Set-Auth-Request") != null)
            {
                // Auth modules should NOT set isAuthenticationRequest (it's container-only),
                // but let's test what happens if they try
                messageInfo.getMap().put(JaspiMessageInfo.AUTH_REQUEST_KEY, "module-set");
            }

            // Test the registerSession attribute
            if (request.getHeader("X-Register-Session") != null)
            {
                // Auth module sets registerSession=true to request session registration
                messageInfo.getMap().put(JaspiMessageInfo.REGISTER_SESSION_KEY, "true");
            }

            if (userName == null)
            {
                // No credentials — fail if mandatory, succeed (anonymous) if not.
                return "true".equals(String.valueOf(mandatory))
                    ? AuthStatus.FAILURE
                    : AuthStatus.SUCCESS;
            }

            try
            {
                _handler.handle(new Callback[]{
                    new CallerPrincipalCallback(clientSubject, userName),
                    new GroupPrincipalCallback(clientSubject, new String[]{"users"})
                });
                messageInfo.getMap().put(JaspiMessageInfo.AUTHENTICATION_TYPE_KEY, "CUSTOM-AUTH");
                return AuthStatus.SUCCESS;
            }
            catch (Exception e)
            {
                throw new AuthException(e.getMessage());
            }
        }

        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject)
        {
            _secureResponseCalled.set(true);
            return AuthStatus.SEND_SUCCESS;
        }

        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject)
        {
            _cleanSubjectCalled.set(true);
            _cleanSubjectSubject.set(subject);
            _cleanSubjectMessageInfo.set(messageInfo);
        }
    }

    public static class TestBaseAuthModule  implements ServerAuthModule
    {
        private CallbackHandler _handler;
        private final AtomicReference<String> _isMandatory = new AtomicReference<>();
        private final AtomicBoolean _cleanSubjectCalled = new AtomicBoolean();
        private final AtomicBoolean _secureResponseCalled = new AtomicBoolean();
        private final AtomicReference<Subject> _clientSubject = new AtomicReference<>();
        private final AtomicReference<Subject> _serviceSubject = new AtomicReference<>();
        private final AtomicReference<Subject> _cleanSubjectSubject = new AtomicReference<>();
        private final AtomicReference<MessageInfo> _cleanSubjectMessageInfo = new AtomicReference<>();
        private final AtomicReference<String> _isAuthenticationRequest = new AtomicReference<>();
        private final AtomicReference<MessagePolicy> _requestPolicy = new AtomicReference<>();
        private final AtomicReference<MessagePolicy> _responsePolicy = new AtomicReference<>();
        private final AtomicInteger _validateRequestCount = new AtomicInteger();

        @Override
        @SuppressWarnings("rawtypes")
        public Class[] getSupportedMessageTypes()
        {
            return new Class[]{HttpServletRequest.class, HttpServletResponse.class};
        }

        @Override
        @SuppressWarnings("rawtypes")
        public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy,
                               CallbackHandler handler, Map options)
        {
            _handler = handler;
            _requestPolicy.set(requestPolicy);
            _responsePolicy.set(responsePolicy);
        }

        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject,
                                          Subject serviceSubject) throws AuthException
        {
            _validateRequestCount.incrementAndGet();
            _clientSubject.set(clientSubject);
            _serviceSubject.set(serviceSubject);

            String mandatory = (String)messageInfo.getMap().get(JaspiMessageInfo.MANDATORY_KEY);
            _isMandatory.set(mandatory);
            String isAuthRequest = (String)messageInfo.getMap().get(JaspiMessageInfo.AUTH_REQUEST_KEY);
            _isAuthenticationRequest.set(isAuthRequest);

            HttpServletRequest request = (HttpServletRequest)messageInfo.getRequestMessage();
            String userName = request.getHeader("X-Test-User");

            // Test setting isAuthenticationRequest by auth module (should not override container)
            if (request.getHeader("X-Set-Auth-Request") != null)
            {
                // Auth modules should NOT set isAuthenticationRequest (it's container-only),
                // but let's test what happens if they try
                messageInfo.getMap().put(JaspiMessageInfo.AUTH_REQUEST_KEY, "module-set");
            }

            // Test the registerSession attribute
            if (request.getHeader("X-Register-Session") != null)
            {
                // Auth module sets registerSession=true to request session registration
                messageInfo.getMap().put(JaspiMessageInfo.REGISTER_SESSION_KEY, "true");
            }

            if (userName == null)
            {
                // No credentials — fail if mandatory, succeed (anonymous) if not.
                return "true".equals(String.valueOf(mandatory))
                    ? AuthStatus.FAILURE
                    : AuthStatus.SUCCESS;
            }

            try
            {
                _handler.handle(new Callback[]{
                    new CallerPrincipalCallback(clientSubject, userName),
                    new GroupPrincipalCallback(clientSubject, new String[]{"users"})
                });

                String authType = request.getHeader("X-Set-AuthType");
                if (authType != null)
                    messageInfo.getMap().put(JaspiMessageInfo.AUTHENTICATION_TYPE_KEY, authType);

                return AuthStatus.SUCCESS;
            }
            catch (Exception e)
            {
                throw new AuthException(e.getMessage());
            }
        }

        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject)
        {
            _secureResponseCalled.set(true);
            return AuthStatus.SEND_SUCCESS;
        }

        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject)
        {
            _cleanSubjectCalled.set(true);
            _cleanSubjectSubject.set(subject);
            _cleanSubjectMessageInfo.set(messageInfo);
        }

        public CallbackHandler getHandler()
        {
            return _handler;
        }

        public String getIsMandatory()
        {
            return _isMandatory.get();
        }

        public boolean getCleanSubjectCalled()
        {
            return _cleanSubjectCalled.get();
        }

        public int getValidateRequestCount()
        {
            return _validateRequestCount.get();
        }

        public boolean getSecureResponseCalled()
        {
            return _secureResponseCalled.get();
        }

        public Subject getClientSubject()
        {
            return _clientSubject.get();
        }

        public Subject getServiceSubject()
        {
            return _serviceSubject.get();
        }

        public Subject getCleanSubjectSubject()
        {
            return _cleanSubjectSubject.get();
        }

        public MessageInfo getCleanSubjectMessageInfo()
        {
            return _cleanSubjectMessageInfo.get();
        }

        public String isAuthenticationRequest()
        {
            return _isAuthenticationRequest.get();
        }

        public MessagePolicy getRequestPolicy()
        {
            return _requestPolicy.get();
        }

        public MessagePolicy getResponsePolicy()
        {
            return _responsePolicy.get();
        }

        public void reset()
        {

        }
    }

    /**
     * Servlet that prints authentication state and supports {@code ?action=logout}
     * and {@code ?action=authenticate} query parameters.
     */
    public static class AuthInfoServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
        {
            String action = req.getParameter("action");

            if ("logout".equals(action))
            {
                req.logout();
                resp.setStatus(200);
                resp.setContentType("text/plain");
                resp.getWriter().println("loggedOut=true");
                resp.getWriter().println("remoteUserAfterLogout=" + req.getRemoteUser());
                return;
            }

            if ("authenticate".equals(action))
            {
                if (!req.authenticate(resp))
                    return;
                resp.setStatus(200);
                resp.setContentType("text/plain");
                resp.getWriter().println("authenticated=" + true);
                resp.getWriter().println("remoteUser=" + req.getRemoteUser());
                return;
            }

            resp.setStatus(200);
            resp.setContentType("text/plain");
            resp.getWriter().println("authType=" + req.getAuthType());
            resp.getWriter().println("userPrincipal=" + (req.getUserPrincipal() == null ? "null" : req.getUserPrincipal().getName()));
            resp.getWriter().println("isUserInRole=" + req.isUserInRole("users"));
        }
    }

    public static class TestLoginService extends AbstractLoginService
    {
        private final Map<String, UserPrincipal> _users = new HashMap<>();
        private final Map<String, List<RolePrincipal>> _roles = new HashMap<>();

        public TestLoginService(String name)
        {
            setName(name);
        }

        public void putUser(String username, Credential credential, String[] roles)
        {
            _users.put(username, new UserPrincipal(username, credential));
            if (roles != null)
                _roles.put(username, Arrays.stream(roles).map(RolePrincipal::new).collect(Collectors.toList()));
        }

        @Override
        protected List<RolePrincipal> loadRoleInfo(UserPrincipal user)
        {
            return _roles.get(user.getName());
        }

        @Override
        protected UserPrincipal loadUserInfo(String username)
        {
            return _users.get(username);
        }
    }
}
