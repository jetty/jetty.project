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

package org.eclipse.jetty.ee9.security.jaspi;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.ee9.nested.HttpChannel;
import org.eclipse.jetty.ee9.nested.QuietServletException;
import org.eclipse.jetty.ee9.nested.ServletConstraint;
import org.eclipse.jetty.ee9.security.ConstraintMapping;
import org.eclipse.jetty.ee9.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee9.security.jaspi.modules.BasicAuthenticationAuthModule;
import org.eclipse.jetty.ee9.security.jaspi.provider.JaspiAuthConfigProvider;
import org.eclipse.jetty.ee9.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.security.Credential;
import org.eclipse.jetty.util.security.Password;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Tests for Servlet Container Profile compliance, the {@code jetty-ee9-jaspi} half of
 * {@code jetty-ee-jaspi}'s test of the same name.</p>
 *
 * <p>The server is configured with two path spaces:
 * <ul>
 *   <li>{@code /secured/*} — constrained, authentication is <b>mandatory</b></li>
 *   <li>{@code /open/*} — unconstrained, authentication is <b>not mandatory</b></li>
 * </ul>
 *
 * <p>The auth modules record every call made to them so the tests can assert what the
 * container did without having to read the module's source.</p>
 *
 * <p>Tests of {@code jetty-ee-jaspi}'s class that are not here: the identity association tests,
 * which assert behaviour of the {@link org.eclipse.jetty.ee9.security.SecurityHandler} rather
 * than of the authenticator; the error dispatch test, since security is not re-run for one; the
 * authentication request key tests, since that key is a 3.0 addition; and the request policy
 * tests, until the provider initialises the module with one.</p>
 */
@Isolated("Sets the global AuthConfigFactory")
public class JaspiServletContainerProfileTest
{
    /**
     * Answered by the request wrapper the module installs, and by nothing else.
     */
    private static final String WRAPPED_REQUEST_HEADER = "X-Wrapped-Request";

    private static final String BASIC_CREDENTIALS =
        "Basic " + Base64.getEncoder().encodeToString("user:password".getBytes(StandardCharsets.ISO_8859_1));

    private Server _server;
    private LocalConnector _connector;
    private ServletContextHandler _context;
    private RecordingLoginService _loginService;

    private AuthConfigFactory _factory;

    /**
     * A permit is released for every request the server has finished with.
     */
    private final Semaphore _requestsCompleted = new Semaphore(0);

    @BeforeEach
    public void setUp() throws Exception
    {
        _factory = new DefaultAuthConfigFactory();
        AuthConfigFactory.setFactory(_factory);

        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        _loginService = new RecordingLoginService("TestRealm");
        _loginService.putUser("user", new Password("password"), new String[]{"users"});
        _server.addBean(_loginService);

        _context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        _context.setContextPath("/ctx");
        _context.getCoreContextHandler().insertHandler(new Handler.Wrapper()
        {
            @Override
            public boolean handle(Request request, Response response, org.eclipse.jetty.util.Callback callback) throws Exception
            {
                Request.addCompletionListener(request, failure -> _requestsCompleted.release());
                return super.handle(request, response, callback);
            }
        });
        _server.setHandler(_context);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        _context.setSecurityHandler(security);
        security.setAuthenticatorFactory(new JaspiAuthenticatorFactory());

        // Only /secured/* requires authentication.
        ServletConstraint constraint = new ServletConstraint("All", "users");
        constraint.setAuthenticate(true);
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/secured/*");
        mapping.setConstraint(constraint);
        security.addConstraintMapping(mapping);

        // Nothing may reach /forbidden/*, whoever asks.
        ServletConstraint forbidden = new ServletConstraint();
        forbidden.setName("Forbidden");
        forbidden.setAuthenticate(true);
        ConstraintMapping forbiddenMapping = new ConstraintMapping();
        forbiddenMapping.setPathSpec("/forbidden/*");
        forbiddenMapping.setConstraint(forbidden);
        security.addConstraintMapping(forbiddenMapping);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
        AuthConfigFactory.setFactory(null);
    }

    public void start(ServerAuthModule authModule, HttpServlet servlet) throws Exception
    {
        registerProvider(authModule);
        _context.addServlet(new ServletHolder(servlet), "/");
        _server.start();
    }

    private void registerProvider(ServerAuthModule authModule)
    {
        _factory.registerConfigProvider(
            new JaspiAuthConfigProvider(authModule),
            "HttpServlet", "null /ctx", "test provider");
    }

    /**
     * <p>Wait for the server to finish with a request.</p>
     * <p>{@code secureResponse} is called as the response is completed, which can be after the
     * client has read the response, so whether the module was called can only be asserted once
     * the request has completed on the server. Completion is also the point after which a module
     * that has not been called will not be called at all.</p>
     */
    private void awaitRequestCompleted() throws Exception
    {
        assertTrue(_requestsCompleted.tryAcquire(10, TimeUnit.SECONDS), "the server did not complete the request");
    }

    @Test
    public void testAuthType() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        // The default authType should be taken from the Authenticator.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("authType=JASPI"));
    }

    @Test
    public void testForbiddenRequestEstablishesTheAuthenticationResults() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
        errorHandler.addErrorPage(403, "/error403");
        _context.setErrorHandler(errorHandler);

        registerProvider(authModule);
        _context.addServlet(new ServletHolder(new AuthInfoServlet()), "/");
        _context.addServlet(new ServletHolder(new UserPrincipalServlet()), "/error403");
        _server.start();

        // The authentication results are established before authorization is decided, so the error page still reports the caller validateRequest returned.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            X-Test-Group: others\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("errorUserPrincipal=user"));
        assertThat(response, containsString("errorAuthType=JASPI"));
        assertThat(authModule.getValidateRequestCount(), equalTo(1));
    }

    @Test
    public void testForbiddenConstraintNeverReachesTheModule() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        // A forbidden constraint is rejected by the SecurityHandler itself, so the module is never asked about the request.
        String response = _connector.getResponse("""
            GET /ctx/forbidden/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(authModule.getValidateRequestCount(), equalTo(0));
    }

    @Test
    public void testLogoutCleansTheSubjectTheModuleEstablished() throws Exception
    {
        RecordingBasicAuthModule authModule = new RecordingBasicAuthModule();
        start(authModule, new AuthInfoServlet());

        String response = _connector.getResponse(
            "GET /ctx/secured/resource?action=logout HTTP/1.0\r\n" +
            "Authorization: " + BASIC_CREDENTIALS + "\r\n" +
            "\r\n");

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("loggedOut=true"));
        assertThat(response, containsString("remoteUserAfterLogout=null"));

        // cleanSubject is given the subject validateRequest named the caller in, so afterwards nothing of the caller is left.
        assertThat(authModule.getValidatedPrincipalNames(), containsInAnyOrder("user", "users"));
        assertThat(authModule.getCleanSubjectCount(), equalTo(1));
        Subject clientSubject = authModule.getClientSubject();
        assertThat(authModule.getCleanSubjectSubject(), sameInstance(clientSubject));
        assertThat(clientSubject.getPrincipals(), empty());
    }

    @Test
    public void testLoginThenLogoutLeavesTheLoginServiceIdentityIntact() throws Exception
    {
        RecordingBasicAuthModule authModule = new RecordingBasicAuthModule();
        start(authModule, new AuthInfoServlet());

        // No credentials on an unconstrained request, so the module leaves the caller unauthenticated and the application logs one in itself.
        String response = _connector.getResponse("""
            GET /ctx/open/resource?action=login HTTP/1.0\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("loginUserPrincipal=user"));
        assertThat(response, containsString("loginIsUserInRole=true"));
        assertThat(response, containsString("loginIsUserInOtherRole=false"));
        assertThat(response, containsString("loginAuthType=API"));
        assertThat(response, containsString("remoteUserAfterLogout=null"));

        // The subject of that login is the LoginService's own, so cleaning it must leave what the LoginService put there.
        UserIdentity identity = _loginService.getLastIdentity();
        assertNotNull(identity);
        assertThat(authModule.getCleanSubjectCount(), equalTo(1));
        assertThat(authModule.getCleanSubjectSubject(), sameInstance(identity.getSubject()));
        assertThat(principalNames(identity.getSubject()), containsInAnyOrder("user", "users"));
    }

    @Test
    public void testLogoutOfAnUnauthenticatedRequestCleansASubjectOfItsOwn() throws Exception
    {
        RecordingBasicAuthModule authModule = new RecordingBasicAuthModule();
        start(authModule, new AuthInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/open/resource?action=logout HTTP/1.0\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("loggedOut=true"));

        // There is no authenticated subject to clean, so the module is given an empty one, and the message is mandatory whatever the constraints say.
        assertThat(authModule.getCleanSubjectCount(), equalTo(1));
        Subject cleaned = authModule.getCleanSubjectSubject();
        assertNotNull(cleaned);
        assertThat(cleaned, not(sameInstance(authModule.getClientSubject())));
        assertThat(cleaned.getPrincipals(), empty());
        assertThat(authModule.getCleanSubjectMandatory(), equalTo("true"));
    }

    @Test
    public void testLogoutCompletesWhenCleanSubjectThrowsARuntimeException() throws Exception
    {
        ThrowingCleanSubjectAuthModule authModule = new ThrowingCleanSubjectAuthModule();
        start(authModule, new AuthInfoServlet());

        String response = _connector.getResponse(
            "GET /ctx/secured/resource?action=logout HTTP/1.0\r\n" +
            "Authorization: " + BASIC_CREDENTIALS + "\r\n" +
            "\r\n");

        // A module that fails to clean the subject must not leave the caller logged in, and logout() declares only ServletException to report it with.
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("loggedOut=true"));
        assertThat(response, containsString("remoteUserAfterLogout=null"));
        assertThat(authModule.getCleanSubjectCount(), equalTo(1));
    }

    @Test
    public void testLogoutFromWithinCleanSubjectDoesNotReenterIt() throws Exception
    {
        LogoutInCleanSubjectAuthModule authModule = new LogoutInCleanSubjectAuthModule();
        start(authModule, new AuthInfoServlet());

        String response = _connector.getResponse(
            "GET /ctx/secured/resource?action=logout HTTP/1.0\r\n" +
            "Authorization: " + BASIC_CREDENTIALS + "\r\n" +
            "\r\n");

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("loggedOut=true"));

        // The module logged the caller out from within its own cleanSubject, and that call must not come back into cleanSubject.
        assertThat(authModule.getNestedLogoutCount(), equalTo(1));
        assertThat(authModule.getCleanSubjectCount(), equalTo(1));
    }

    @Test
    public void testUnconstrainedPath() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        String response = _connector.getResponse("GET /ctx/open/resource HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("userPrincipal=null"));
        assertThat(authModule.getValidateRequestCount(), equalTo(1));
        assertThat(authModule.getIsMandatory(), nullValue());
        awaitRequestCompleted();
        assertTrue(authModule.getSecureResponseCalled(),
            "secureResponse must be called for a request that was not constrained");
    }

    @Test
    public void testDeferredGetUserPrincipalDoesNotAskTheModuleAgain() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        // The module named the caller before the dispatch, so the servlet's getUserPrincipal is answered from that and not a second, mandatory call.
        String response = _connector.getResponse("""
            GET /ctx/open/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("userPrincipal=user"));
        assertThat(authModule.getValidateRequestCount(), equalTo(1));
        assertThat(authModule.getIsMandatory(), nullValue());
    }

    @Test
    public void testAModuleCallingGetUserPrincipalInsideValidateRequestIsNotReentered() throws Exception
    {
        GetUserPrincipalAuthModule authModule = new GetUserPrincipalAuthModule();
        start(authModule, new AuthInfoServlet());

        // The profile allows the module to ask the container for the caller from inside validateRequest, and answering by asking the module again recurses.
        String response = _connector.getResponse("GET /ctx/open/resource HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("userPrincipal=null"));
        assertThat(authModule.getValidateRequestCount(), equalTo(1));
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

        // The module already named the caller before the dispatch, so authenticate() answers from that rather than asking it again.
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("authenticated=true"));
        assertThat(authModule.getValidateRequestCount(), equalTo(1));
        assertThat(authModule.getIsMandatory(), nullValue());
    }

    @Test
    public void testExplicitAuthenticateAsksTheModule() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        // Nobody was named before the dispatch, so authenticate() asks the module with the real response and both the keys the profile requires.
        String response = _connector.getResponse("""
            GET /ctx/open/resource?action=authenticate HTTP/1.0\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(authModule.getValidateRequestCount(), equalTo(2));
        assertThat(authModule.getIsMandatory(), equalTo("true"));
        assertThat(authModule.isAuthenticationRequest(), equalTo("true"));
    }

    @Test
    public void testSecureResponseIsGivenTheMessageInfoOfTheCallBeforeTheDispatch() throws Exception
    {
        TwoCallAuthModule authModule = new TwoCallAuthModule();
        start(authModule, new AuthInfoServlet());

        // Nobody was named before the dispatch, so this request reaches the module twice.
        String response = _connector.getResponse("""
            GET /ctx/open/resource?action=authenticate HTTP/1.0\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("remoteUser=user"));
        awaitRequestCompleted();
        assertThat(authModule.getValidateRequestMessageInfos().size(), equalTo(2));

        // The profile pairs secureResponse with the validateRequest made before the dispatch, so the one authenticate() makes must not take its place.
        assertThat(authModule.getSecureResponseMessageInfo(),
            sameInstance(authModule.getValidateRequestMessageInfos().get(0)));
    }

    @Test
    public void testSuccessWithoutACallerPrincipalCallbackIsNotAuthenticated() throws Exception
    {
        NoCallbackAuthModule authModule = new NoCallbackAuthModule();
        start(authModule, new AuthInfoServlet());

        // SUCCESS naming no caller leaves no caller rather than an error, so a constrained path is forbidden and the response is still secured.
        String response = _connector.getResponse("GET /ctx/secured/resource HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(authModule.getSecureResponseCount(), equalTo(1));

        // An unconstrained path is served unauthenticated.
        response = _connector.getResponse("GET /ctx/open/resource HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("userPrincipal=null"));
    }

    @Test
    public void testAuthConfigFactory() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        AuthConfigFactory factory = AuthConfigFactory.getFactory();
        assertNotNull(factory.getConfigProvider("HttpServlet", "null /ctx", null));
        assertNull(factory.getConfigProvider("HttpServlet", "null /other", null));
    }

    @Test
    public void testServiceSubjectIsNullWhenTheServerHasNone() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertNull(authModule.getServiceSubject());
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
            public ServerAuthConfig getServerAuthConfig(String layer, String appContext, CallbackHandler handler) throws AuthException
            {
                // This creates a new ServerAuthConfig every time, so a single request must only call it once.
                counter.incrementAndGet();
                return super.getServerAuthConfig(layer, appContext, handler);
            }
        }, "HttpServlet", "null /ctx", "test provider");
        _context.addServlet(new ServletHolder(new AuthInfoServlet()), "/");
        _server.start();

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        awaitRequestCompleted();
        assertTrue(authModule.getSecureResponseCalled(), "secureResponse must be called");
        assertThat(counter.get(), equalTo(1));
    }

    @Test
    public void testValidateRequestThrowingIsAnInternalServerError() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule()
        {
            @Override
            public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
            {
                throw new AuthException("validateRequest failed");
            }
        };
        start(authModule, new AuthInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 500"));
        awaitRequestCompleted();
        assertFalse(authModule.getSecureResponseCalled(),
            "nothing was validated, so there is nothing to secure");
    }

    @Test
    public void testSecureResponseWhenTheRequestIsNotAuthorized() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        // The caller is in the wrong role, so the request is not dispatched, but the profile still requires the runtime to proceed to point (3).
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            X-Test-Group: others\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 403"));
        awaitRequestCompleted();
        assertTrue(authModule.getSecureResponseCalled(),
            "secureResponse must be called for a request that was validated but not authorized");
    }

    @Test
    public void testSecureResponseWaitsForTheErrorPageOfASynchronousSendError() throws Exception
    {
        List<String> order = new CopyOnWriteArrayList<>();
        TestBaseAuthModule authModule = new TestBaseAuthModule()
        {
            @Override
            public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
            {
                order.add("secureResponse");
                return super.secureResponse(messageInfo, serviceSubject);
            }
        };
        ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
        errorHandler.addErrorPage(599, "/error599");
        _context.setErrorHandler(errorHandler);

        registerProvider(authModule);
        _context.addServlet(new ServletHolder(new SendErrorServlet()), "/");
        _context.addServlet(new ServletHolder(new RecordingServlet(order, "errorPage")), "/error599");
        _server.start();

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        // sendError discards the dispatch's output and regenerates the response as an error page, so that is what the module is given to secure.
        assertThat(response, startsWith("HTTP/1.1 599"));
        awaitRequestCompleted();
        assertThat(order, contains("errorPage", "secureResponse"));
    }

    @Test
    public void testSecureResponseSkippedWhenTheResourceThrows() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new ThrowingServlet());

        // The profile says the response is not secured when the resource throws to the runtime.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 500"));
        awaitRequestCompleted();
        assertFalse(authModule.getSecureResponseCalled(),
            "secureResponse must not be called when the resource throws");
    }

    @Test
    public void testSecureResponseThrowingReplacesAResponseNotYetSent() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule()
        {
            @Override
            public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
            {
                super.secureResponse(messageInfo, serviceSubject);
                throw new AuthException("secureResponse failed");
            }
        };
        start(authModule, new AuthInfoServlet());

        // The response has not been sent, so the failure to secure it replaces it with the 500 the profile allows the runtime to make.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """, 5, TimeUnit.SECONDS);

        assertThat(response, startsWith("HTTP/1.1 500"));
        assertThat("the unsecured response must not be sent", response, not(containsString("userPrincipal=user")));
        awaitRequestCompleted();
        assertTrue(authModule.getSecureResponseCalled());
    }

    @Test
    public void testSecureResponseSendFailureReplacesAResponseNotYetSent() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule()
        {
            @Override
            public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
            {
                super.secureResponse(messageInfo, serviceSubject);
                return AuthStatus.SEND_FAILURE;
            }
        };
        start(authModule, new AuthInfoServlet());

        // SEND_FAILURE reports the response could not be secured, and the profile allows the runtime to send a 500 of its own rather than the module's.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """, 5, TimeUnit.SECONDS);

        assertThat(response, startsWith("HTTP/1.1 500"));
        assertThat("the unsecured response must not be sent", response, not(containsString("userPrincipal=user")));
        awaitRequestCompleted();
        assertTrue(authModule.getSecureResponseCalled());
    }

    @Test
    public void testSecureResponseSuccessSendsTheResponse() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule()
        {
            @Override
            public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
            {
                super.secureResponse(messageInfo, serviceSubject);
                return AuthStatus.SUCCESS;
            }
        };
        start(authModule, new AuthInfoServlet());

        // SUCCESS is undefined for secureResponse but is the one undefined status that affirms success, so the response is sent as it stands.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """, 5, TimeUnit.SECONDS);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("userPrincipal=user"));
        awaitRequestCompleted();
        assertTrue(authModule.getSecureResponseCalled());
    }

    @Test
    public void testSecureResponseFailureReplacesAResponseNotYetSent() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule()
        {
            @Override
            public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
            {
                super.secureResponse(messageInfo, serviceSubject);
                return AuthStatus.FAILURE;
            }
        };
        start(authModule, new AuthInfoServlet());

        // FAILURE affirms nothing, so the response is treated as not secured, as it is for the SEND_FAILURE the profile does define here.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """, 5, TimeUnit.SECONDS);

        assertThat(response, startsWith("HTTP/1.1 500"));
        assertThat("the unsecured response must not be sent", response, not(containsString("userPrincipal=user")));
        awaitRequestCompleted();
        assertTrue(authModule.getSecureResponseCalled());
    }

    @Test
    public void testSecureResponseSendFailureReplacesAnAsyncResponseNotYetSent() throws Exception
    {
        AtomicBoolean asyncCompleted = new AtomicBoolean();
        TestBaseAuthModule authModule = new TestBaseAuthModule()
        {
            @Override
            public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
            {
                super.secureResponse(messageInfo, serviceSubject);
                return AuthStatus.SEND_FAILURE;
            }
        };
        start(authModule, new AsyncServlet(asyncCompleted));

        // Point (3) is only reached once the application's async thread completes, so the module's status is acted on away from the handling thread.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """, 10, TimeUnit.SECONDS);

        assertThat(response, startsWith("HTTP/1.1 500"));
        assertThat("the unsecured response must not be sent", response, not(containsString("async=done")));
        awaitRequestCompleted();
        assertTrue(asyncCompleted.get(), "the async dispatch must have written the response");
        assertTrue(authModule.getSecureResponseCalled());
    }

    @Test
    public void testSendSuccessSendsTheResponseTheModuleMade() throws Exception
    {
        AtomicInteger dispatches = new AtomicInteger();
        TestBaseAuthModule authModule = new TestBaseAuthModule()
        {
            @Override
            public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject)
            {
                // Left uncommitted, so it reaching the client is SEND_SUCCESS being honoured and not the runtime being unable to replace it.
                HttpServletResponse response = (HttpServletResponse)messageInfo.getResponseMessage();
                response.setStatus(HttpServletResponse.SC_FOUND);
                response.setHeader("Location", "/ctx/login");
                return AuthStatus.SEND_SUCCESS;
            }
        };
        start(authModule, new CountingServlet(dispatches));

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            \r
            """);

        // SEND_SUCCESS means the module has made the response itself, so it is sent as it stands and the resource is not invoked.
        assertThat(response, startsWith("HTTP/1.1 302"));
        assertThat(response, containsString("Location: /ctx/login"));
        assertThat(dispatches.get(), equalTo(0));
        awaitRequestCompleted();
        assertFalse(authModule.getSecureResponseCalled());
    }

    @Test
    public void testAProviderRegisteredAgainReplacesTheModule() throws Exception
    {
        TestBaseAuthModule first = new TestBaseAuthModule();
        start(first, new AuthInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(first.getValidateRequestCount(), equalTo(1));

        // Registering again for the same layer and appContext notifies the authenticator's listener, which drops the ServerAuthConfig it cached.
        TestBaseAuthModule second = new TestBaseAuthModule();
        registerProvider(second);

        response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(second.getValidateRequestCount(), equalTo(1));
        assertThat("the replaced module must not be asked again", first.getValidateRequestCount(), equalTo(1));
    }

    // A module that wraps the request and the response messages

    @Test
    public void testTheServletIsGivenTheRequestTheModuleWrapped() throws Exception
    {
        WrappingAuthModule authModule = new WrappingAuthModule();
        start(authModule, new WrappingInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            \r
            """);

        // Only the wrapper the module installed answers the header, so the servlet was dispatched with it and not the container's own request.
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("requestWrapper=" + authModule.getWrappedRequest().getMarker()));
    }

    @Test
    public void testTheServletWritesThroughTheResponseTheModuleWrapped() throws Exception
    {
        WrappingAuthModule authModule = new WrappingAuthModule();
        start(authModule, new WrappingInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        String body = response.substring(response.indexOf("\r\n\r\n") + 4);
        assertThat(body, not(equalTo("")));
        assertThat("everything the client received was written through the module's wrapper",
            authModule.getWrappedResponse().getWritten(), equalTo(body));
    }

    /**
     * A module may name no caller and still return SUCCESS, which is how it says it validated the
     * request and there is nobody to authenticate. The profile attaches no caller condition to the
     * messages it established, so the servlet is dispatched with them just the same.
     */
    @Test
    public void testTheServletIsGivenTheWrappedMessagesWhenTheModuleNamesNoCaller() throws Exception
    {
        WrappingAuthModule authModule = new WrappingAuthModule(null);
        start(authModule, new WrappingInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/open/resource HTTP/1.0\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("requestWrapper=" + authModule.getWrappedRequest().getMarker()));

        String body = response.substring(response.indexOf("\r\n\r\n") + 4);
        assertThat(body, not(equalTo("")));
        assertThat("everything the client received was written through the module's wrapper",
            authModule.getWrappedResponse().getWritten(), equalTo(body));
    }

    @Test
    public void testTheWrappedRequestSurvivesAForward() throws Exception
    {
        WrappingAuthModule authModule = new WrappingAuthModule();
        _context.addServlet(new ServletHolder(new DispatchedServlet()), "/dispatched");
        start(authModule, new WrappingInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource?action=forward HTTP/1.0\r
            \r
            """);

        // The forwarded servlet is given the same wrapper instance, rather than falling back to the container's own request.
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("dispatcherType=FORWARD"));
        assertThat(response, containsString("dispatchedRequestWrapper=" + authModule.getWrappedRequest().getMarker()));
        assertThat(authModule.getValidateRequestCount(), equalTo(1));

        String body = response.substring(response.indexOf("\r\n\r\n") + 4);
        assertThat(authModule.getWrappedResponse().getWritten(), equalTo(body));
    }

    @Test
    public void testSecureResponseIsGivenTheWrappedMessagesAndCanUnwrapThem() throws Exception
    {
        WrappingAuthModule authModule = new WrappingAuthModule();
        start(authModule, new WrappingInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        awaitRequestCompleted();

        // secureResponse is given the MessageInfo validateRequest wrapped, so the module can put the container's messages back.
        assertThat(authModule.getSecureResponseRequest(), sameInstance(authModule.getWrappedRequest()));
        assertThat(authModule.getSecureResponseResponse(), sameInstance(authModule.getWrappedResponse()));
        assertThat(authModule.getUnwrappedRequest(), sameInstance(authModule.getOriginalRequest()));
        assertThat(authModule.getUnwrappedResponse(), sameInstance(authModule.getOriginalResponse()));
    }

    @Test
    public void testSecureResponseIsGivenTheRequestInTheScopeItWasDispatchedIn() throws Exception
    {
        PathRecordingAuthModule authModule = new PathRecordingAuthModule();
        start(authModule, new AuthInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/open/resource HTTP/1.0\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        awaitRequestCompleted();

        // The module is given the request message it validated, so it reports the same paths and dispatcher type though the dispatch has left both scopes.
        assertThat(authModule.getSecureResponseContextPath(), equalTo("/ctx"));
        assertThat(authModule.getSecureResponseServletPath(), equalTo("/open/resource"));
        assertThat(authModule.getSecureResponseDispatcherType(), equalTo(DispatcherType.REQUEST));

        response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        awaitRequestCompleted();
        assertThat(authModule.getSecureResponseContextPath(), equalTo("/ctx"));
        assertThat(authModule.getSecureResponseServletPath(), equalTo("/secured/resource"));
        assertThat(authModule.getSecureResponseDispatcherType(), equalTo(DispatcherType.REQUEST));
    }

    @Test
    public void testSecureResponseLeavesTheRequestInTheScopeItFoundIt() throws Exception
    {
        AtomicReference<String> contextPath = new AtomicReference<>();
        AtomicReference<String> servletPath = new AtomicReference<>();
        AtomicReference<DispatcherType> dispatcherType = new AtomicReference<>();
        _connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onComplete(org.eclipse.jetty.ee9.nested.Request request)
            {
                contextPath.set(request.getContextPath());
                servletPath.set(request.getServletPath());
                dispatcherType.set(request.getDispatcherType());
            }
        });

        PathRecordingAuthModule authModule = new PathRecordingAuthModule();
        start(authModule, new AuthInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        awaitRequestCompleted();
        assertThat(authModule.getSecureResponseContextPath(), equalTo("/ctx"));

        // The dispatch had left both scopes, so the scope the task puts back for the module must be taken off again once it returns.
        assertThat(contextPath.get(), nullValue());
        assertThat(servletPath.get(), equalTo(""));
        assertThat(dispatcherType.get(), nullValue());
    }

    /**
     * The security handler is re-run for an ASYNC dispatch, which reaches the point the
     * response is secured at a second time.
     */
    @Test
    public void testSecureResponseIsCalledOnceForAnAsyncDispatch() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        _context.addServlet(new ServletHolder(new DispatchedServlet()), "/dispatched");
        start(authModule, new AsyncDispatchServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """, 10, TimeUnit.SECONDS);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("dispatcherType=ASYNC"));
        awaitRequestCompleted();
        assertThat(authModule.getSecureResponseCount(), equalTo(1));
    }

    /**
     * The Servlet Container Profile has the response secured after the service invocation, which
     * for a request that is continued asynchronously is not when the dispatch returns.
     */
    @Test
    public void testSecureResponseWaitsForTheAsyncDispatchToComplete() throws Exception
    {
        AtomicBoolean asyncCompleted = new AtomicBoolean();
        AtomicBoolean asyncCompletedWhenSecured = new AtomicBoolean();
        TestBaseAuthModule authModule = new TestBaseAuthModule()
        {
            @Override
            public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
            {
                asyncCompletedWhenSecured.set(asyncCompleted.get());
                return super.secureResponse(messageInfo, serviceSubject);
            }
        };
        start(authModule, new AsyncServlet(asyncCompleted));

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """, 10, TimeUnit.SECONDS);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("async=done"));
        awaitRequestCompleted();
        assertThat(authModule.getSecureResponseCount(), equalTo(1));
        assertTrue(asyncCompletedWhenSecured.get(),
            "secureResponse must not be called until the async dispatch has finished with the response");
    }

    /**
     * An {@link AsyncContext#dispatch()} re-enters the container after the dispatch that started
     * it has returned, so the response is only finished with once the dispatch target has run.
     */
    @Test
    public void testSecureResponseWaitsForTheAsyncRedispatchToComplete() throws Exception
    {
        AtomicBoolean dispatched = new AtomicBoolean();
        AtomicBoolean dispatchedWhenSecured = new AtomicBoolean();
        TestBaseAuthModule authModule = new TestBaseAuthModule()
        {
            @Override
            public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
            {
                dispatchedWhenSecured.set(dispatched.get());
                return super.secureResponse(messageInfo, serviceSubject);
            }
        };
        _context.addServlet(new ServletHolder(new AsyncDispatchedServlet(dispatched)), "/dispatched");
        start(authModule, new AsyncDispatchServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """, 10, TimeUnit.SECONDS);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("dispatched=done"));
        awaitRequestCompleted();
        assertThat(authModule.getSecureResponseCount(), equalTo(1));
        assertTrue(dispatchedWhenSecured.get(),
            "secureResponse must not be called until the async dispatch target has run");
    }

    @Test
    public void testTheWrappedRequestSurvivesAnAsyncDispatch() throws Exception
    {
        WrappingAuthModule authModule = new WrappingAuthModule();
        _context.addServlet(new ServletHolder(new DispatchedServlet()), "/dispatched");
        start(authModule, new AsyncDispatchServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            \r
            """, 10, TimeUnit.SECONDS);

        // The ASYNC dispatch takes the messages from the request's authentication results, so the module's wrapper is carried on without it being asked again.
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("dispatcherType=ASYNC"));
        assertThat(response, containsString("dispatchedRequestWrapper=" + authModule.getWrappedRequest().getMarker()));
        assertThat(authModule.getValidateRequestCount(), equalTo(1));
    }

    @Test
    public void testTheModuleDecidesEveryRequestRatherThanAnEarlierProgrammaticLogin() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        _loginService.putUser("admin", new Password("secret"), new String[]{"users"});
        start(authModule, new AuthInfoServlet());

        // The application logs a caller in for itself, which puts an authentication in the session.
        String response = _connector.getResponse("""
            GET /ctx/open/resource?action=login HTTP/1.0\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("loginUserPrincipal=user"));
        String session = sessionCookie(response);
        assertNotNull(session, "the programmatic login must have made a session");

        // The profile has validateRequest decide every request, so the caller the module names on the next one stands.
        response = _connector.getResponse(
            "GET /ctx/open/resource HTTP/1.0\r\n" +
            "Cookie: " + session + "\r\n" +
            "X-Test-User: admin\r\n" +
            "\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("userPrincipal=admin"));
    }

    private static Set<String> principalNames(Subject subject)
    {
        return subject.getPrincipals().stream().map(Principal::getName).collect(Collectors.toSet());
    }

    private static String sessionCookie(String response)
    {
        for (String line : response.split("\r\n"))
        {
            if (line.startsWith("Set-Cookie: JSESSIONID="))
                return line.substring("Set-Cookie: ".length()).split(";")[0];
        }
        return null;
    }

    public static class PathRecordingAuthModule extends TestBaseAuthModule
    {
        private final AtomicReference<String> _contextPath = new AtomicReference<>();
        private final AtomicReference<String> _servletPath = new AtomicReference<>();
        private final AtomicReference<DispatcherType> _dispatcherType = new AtomicReference<>();

        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
        {
            HttpServletRequest request = (HttpServletRequest)messageInfo.getRequestMessage();
            _contextPath.set(request.getContextPath());
            _servletPath.set(request.getServletPath());
            _dispatcherType.set(request.getDispatcherType());
            return super.secureResponse(messageInfo, serviceSubject);
        }

        public String getSecureResponseContextPath()
        {
            return _contextPath.get();
        }

        public String getSecureResponseServletPath()
        {
            return _servletPath.get();
        }

        public DispatcherType getSecureResponseDispatcherType()
        {
            return _dispatcherType.get();
        }
    }

    public static class TestBaseAuthModule implements ServerAuthModule
    {
        private CallbackHandler _handler;
        private final AtomicReference<String> _isMandatory = new AtomicReference<>();
        private final AtomicReference<String> _isAuthenticationRequest = new AtomicReference<>();
        private final AtomicBoolean _secureResponseCalled = new AtomicBoolean();
        private final AtomicInteger _secureResponseCount = new AtomicInteger();
        private final AtomicReference<Subject> _clientSubject = new AtomicReference<>();
        private final AtomicReference<Subject> _serviceSubject = new AtomicReference<>();
        private final AtomicReference<Subject> _cleanSubjectSubject = new AtomicReference<>();
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
            _isAuthenticationRequest.set((String)messageInfo.getMap().get(JaspiMessageInfo.AUTH_REQUEST_KEY));

            HttpServletRequest request = (HttpServletRequest)messageInfo.getRequestMessage();
            String userName = request.getHeader("X-Test-User");

            if (userName == null)
            {
                // No credentials, so fail if authentication is mandatory, and otherwise name the unauthenticated caller as a SUCCESS requires.
                if ("true".equals(mandatory))
                    return AuthStatus.FAILURE;
                try
                {
                    _handler.handle(new Callback[]{new CallerPrincipalCallback(clientSubject, (Principal)null)});
                }
                catch (Exception e)
                {
                    throw new AuthException(e.getMessage());
                }
                return AuthStatus.SUCCESS;
            }

            String group = request.getHeader("X-Test-Group");

            try
            {
                _handler.handle(new Callback[]{
                    new CallerPrincipalCallback(clientSubject, userName),
                    new GroupPrincipalCallback(clientSubject, new String[]{group == null ? "users" : group})
                });

                return AuthStatus.SUCCESS;
            }
            catch (Exception e)
            {
                throw new AuthException(e.getMessage());
            }
        }

        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
        {
            _secureResponseCalled.set(true);
            _secureResponseCount.incrementAndGet();
            return AuthStatus.SEND_SUCCESS;
        }

        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject)
        {
            _cleanSubjectSubject.set(subject);
        }

        public CallbackHandler getHandler()
        {
            return _handler;
        }

        public String getIsMandatory()
        {
            return _isMandatory.get();
        }

        public String isAuthenticationRequest()
        {
            return _isAuthenticationRequest.get();
        }

        public int getValidateRequestCount()
        {
            return _validateRequestCount.get();
        }

        public boolean getSecureResponseCalled()
        {
            return _secureResponseCalled.get();
        }

        public int getSecureResponseCount()
        {
            return _secureResponseCount.get();
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
    }

    /**
     * Names a caller and then wraps both messages, which the Servlet Container Profile allows a
     * module to do so that the resource is invoked with the wrappers.
     */
    public static class WrappingAuthModule implements ServerAuthModule
    {
        private final String _callerName;
        private CallbackHandler _handler;
        private final AtomicInteger _validateRequestCount = new AtomicInteger();
        private final AtomicReference<HttpServletRequest> _originalRequest = new AtomicReference<>();
        private final AtomicReference<HttpServletResponse> _originalResponse = new AtomicReference<>();
        private final AtomicReference<WrappedRequest> _wrappedRequest = new AtomicReference<>();
        private final AtomicReference<WrappedResponse> _wrappedResponse = new AtomicReference<>();
        private final AtomicReference<Object> _secureResponseRequest = new AtomicReference<>();
        private final AtomicReference<Object> _secureResponseResponse = new AtomicReference<>();
        private final AtomicReference<Object> _unwrappedRequest = new AtomicReference<>();
        private final AtomicReference<Object> _unwrappedResponse = new AtomicReference<>();

        public WrappingAuthModule()
        {
            this("user");
        }

        /**
         * @param callerName the caller to name, or null to name no caller at all.
         */
        public WrappingAuthModule(String callerName)
        {
            _callerName = callerName;
        }

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
        }

        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject,
                                          Subject serviceSubject) throws AuthException
        {
            int count = _validateRequestCount.incrementAndGet();
            try
            {
                if (_callerName == null)
                    _handler.handle(new Callback[]{new CallerPrincipalCallback(clientSubject, (Principal)null)});
                else
                {
                    _handler.handle(new Callback[]{
                        new CallerPrincipalCallback(clientSubject, _callerName),
                        new GroupPrincipalCallback(clientSubject, new String[]{"users"})
                    });
                }
            }
            catch (Exception e)
            {
                throw new AuthException(e.getMessage());
            }

            HttpServletRequest request = (HttpServletRequest)messageInfo.getRequestMessage();
            HttpServletResponse response = (HttpServletResponse)messageInfo.getResponseMessage();
            _originalRequest.set(request);
            _originalResponse.set(response);

            WrappedRequest wrappedRequest = new WrappedRequest(request, "request-wrapper-" + count);
            WrappedResponse wrappedResponse = new WrappedResponse(response);
            _wrappedRequest.set(wrappedRequest);
            _wrappedResponse.set(wrappedResponse);
            messageInfo.setRequestMessage(wrappedRequest);
            messageInfo.setResponseMessage(wrappedResponse);
            return AuthStatus.SUCCESS;
        }

        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject)
        {
            _secureResponseRequest.set(messageInfo.getRequestMessage());
            _secureResponseResponse.set(messageInfo.getResponseMessage());

            // Put the container's own messages back, as a module that wrapped them should.
            if (messageInfo.getRequestMessage() instanceof WrappedRequest wrapped)
                messageInfo.setRequestMessage(wrapped.getRequest());
            if (messageInfo.getResponseMessage() instanceof WrappedResponse wrapped)
                messageInfo.setResponseMessage(wrapped.getResponse());

            _unwrappedRequest.set(messageInfo.getRequestMessage());
            _unwrappedResponse.set(messageInfo.getResponseMessage());
            return AuthStatus.SEND_SUCCESS;
        }

        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject)
        {
        }

        public int getValidateRequestCount()
        {
            return _validateRequestCount.get();
        }

        public HttpServletRequest getOriginalRequest()
        {
            return _originalRequest.get();
        }

        public HttpServletResponse getOriginalResponse()
        {
            return _originalResponse.get();
        }

        public WrappedRequest getWrappedRequest()
        {
            return _wrappedRequest.get();
        }

        public WrappedResponse getWrappedResponse()
        {
            return _wrappedResponse.get();
        }

        public Object getSecureResponseRequest()
        {
            return _secureResponseRequest.get();
        }

        public Object getSecureResponseResponse()
        {
            return _secureResponseResponse.get();
        }

        public Object getUnwrappedRequest()
        {
            return _unwrappedRequest.get();
        }

        public Object getUnwrappedResponse()
        {
            return _unwrappedResponse.get();
        }
    }

    /**
     * Answers {@link #WRAPPED_REQUEST_HEADER} with a marker of its own, so a servlet can tell this
     * wrapper from the container's request and from any other wrapper of it.
     */
    public static class WrappedRequest extends HttpServletRequestWrapper
    {
        private final String _marker;

        public WrappedRequest(HttpServletRequest request, String marker)
        {
            super(request);
            _marker = marker;
        }

        public String getMarker()
        {
            return _marker;
        }

        @Override
        public String getHeader(String name)
        {
            if (WRAPPED_REQUEST_HEADER.equalsIgnoreCase(name))
                return _marker;
            return super.getHeader(name);
        }
    }

    /**
     * Records what is written through it, as well as writing it on.
     */
    public static class WrappedResponse extends HttpServletResponseWrapper
    {
        private final StringBuffer _written = new StringBuffer();
        private PrintWriter _writer;

        public WrappedResponse(HttpServletResponse response)
        {
            super(response);
        }

        public String getWritten()
        {
            return _written.toString();
        }

        @Override
        public PrintWriter getWriter() throws IOException
        {
            if (_writer == null)
            {
                PrintWriter writer = super.getWriter();
                _writer = new PrintWriter(new Writer()
                {
                    @Override
                    public void write(char[] buffer, int offset, int length)
                    {
                        _written.append(buffer, offset, length);
                        writer.write(buffer, offset, length);
                    }

                    @Override
                    public void flush()
                    {
                        writer.flush();
                    }

                    @Override
                    public void close()
                    {
                        writer.close();
                    }
                });
            }
            return _writer;
        }
    }

    /**
     * Prints the marker of the request wrapper it was given, and forwards to {@code /dispatched}
     * for {@code ?action=forward}.
     */
    public static class WrappingInfoServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
        {
            if ("forward".equals(req.getParameter("action")))
            {
                req.getRequestDispatcher("/dispatched").forward(req, resp);
                return;
            }

            resp.setContentType("text/plain");
            resp.getWriter().println("requestWrapper=" + req.getHeader(WRAPPED_REQUEST_HEADER));
        }
    }

    public static class DispatchedServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setContentType("text/plain");
            resp.getWriter().println("dispatcherType=" + req.getDispatcherType());
            resp.getWriter().println("dispatchedRequestWrapper=" + req.getHeader(WRAPPED_REQUEST_HEADER));
        }
    }

    /**
     * Records what the container passed to the module, and otherwise behaves exactly as the module
     * it extends: the {@code cleanSubject} that runs is the one in {@code BaseAuthModule}.
     */
    public static class RecordingBasicAuthModule extends BasicAuthenticationAuthModule
    {
        private final AtomicReference<Subject> _clientSubject = new AtomicReference<>();
        private final AtomicReference<Set<String>> _validatedPrincipalNames = new AtomicReference<>();
        private final AtomicReference<Subject> _cleanSubjectSubject = new AtomicReference<>();
        private final AtomicReference<String> _cleanSubjectMandatory = new AtomicReference<>();
        private final AtomicInteger _cleanSubjectCount = new AtomicInteger();

        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
        {
            AuthStatus authStatus = super.validateRequest(messageInfo, clientSubject, serviceSubject);
            _clientSubject.set(clientSubject);
            _validatedPrincipalNames.set(principalNames(clientSubject));
            return authStatus;
        }

        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject) throws AuthException
        {
            _cleanSubjectCount.incrementAndGet();
            _cleanSubjectSubject.set(subject);
            _cleanSubjectMandatory.set((String)messageInfo.getMap().get(JaspiMessageInfo.MANDATORY_KEY));
            super.cleanSubject(messageInfo, subject);
        }

        public Subject getClientSubject()
        {
            return _clientSubject.get();
        }

        /**
         * @return the names of the principals of the client subject as validateRequest left them.
         */
        public Set<String> getValidatedPrincipalNames()
        {
            return _validatedPrincipalNames.get();
        }

        public Subject getCleanSubjectSubject()
        {
            return _cleanSubjectSubject.get();
        }

        public String getCleanSubjectMandatory()
        {
            return _cleanSubjectMandatory.get();
        }

        public int getCleanSubjectCount()
        {
            return _cleanSubjectCount.get();
        }
    }

    /**
     * Fails to clean the subject with a RuntimeException, which is undeclared and so is the one
     * way a module can fail that {@code cleanSubject} does not report with an AuthException.
     */
    public static class ThrowingCleanSubjectAuthModule extends RecordingBasicAuthModule
    {
        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject) throws AuthException
        {
            super.cleanSubject(messageInfo, subject);
            throw new IllegalStateException("cleanSubject failed");
        }
    }

    /**
     * Logs the caller out from within its own {@code cleanSubject}, which the Servlet Container
     * Profile allows a module to do. The nested logout is only attempted once, so a re-entered
     * {@code cleanSubject} is counted rather than recursed into.
     */
    public static class LogoutInCleanSubjectAuthModule extends RecordingBasicAuthModule
    {
        private final AtomicInteger _nestedLogoutCount = new AtomicInteger();

        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject) throws AuthException
        {
            if (_nestedLogoutCount.getAndIncrement() == 0)
            {
                try
                {
                    ((HttpServletRequest)messageInfo.getRequestMessage()).logout();
                }
                catch (ServletException e)
                {
                    throw new AuthException(e.getMessage());
                }
            }
            super.cleanSubject(messageInfo, subject);
        }

        public int getNestedLogoutCount()
        {
            return _nestedLogoutCount.get();
        }
    }

    /**
     * Returns from its dispatch immediately and continues the request asynchronously.
     */
    public static class AsyncDispatchServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp)
        {
            req.startAsync().dispatch("/dispatched");
        }
    }

    /**
     * Writes the response of an {@link AsyncContext#dispatch()} and records that it has done so.
     */
    public static class AsyncDispatchedServlet extends HttpServlet
    {
        private final AtomicBoolean _dispatched;

        public AsyncDispatchedServlet(AtomicBoolean dispatched)
        {
            _dispatched = dispatched;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setContentType("text/plain");
            resp.getWriter().println("dispatched=done");
            _dispatched.set(true);
        }
    }

    /**
     * Returns from its dispatch immediately and writes the response from another thread, so that
     * the response is only finished with once {@link AsyncContext#complete()} is called.
     */
    public static class AsyncServlet extends HttpServlet
    {
        private final AtomicBoolean _completed;

        public AsyncServlet(AtomicBoolean completed)
        {
            _completed = completed;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp)
        {
            AsyncContext async = req.startAsync();
            new Thread(() ->
            {
                try
                {
                    Thread.sleep(250);
                    resp.setContentType("text/plain");
                    resp.getWriter().println("async=done");
                    _completed.set(true);
                }
                catch (Throwable ignored)
                {
                    // The assertions are made on the response.
                }
                finally
                {
                    async.complete();
                }
            }).start();
        }
    }

    /**
     * Asks the container who the caller is from inside its own {@code validateRequest}, as the
     * reference module of the TCK does.
     */
    public static class GetUserPrincipalAuthModule extends TestBaseAuthModule
    {
        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
        {
            ((HttpServletRequest)messageInfo.getRequestMessage()).getUserPrincipal();
            return super.validateRequest(messageInfo, clientSubject, serviceSubject);
        }
    }

    /**
     * Names nobody the first time it is asked about a request and the user the second time, so
     * that {@code HttpServletRequest.authenticate} reaches it rather than being answered from
     * what the call before the dispatch established.
     */
    public static class TwoCallAuthModule extends TestBaseAuthModule
    {
        private final List<MessageInfo> _validateRequestMessageInfos = new CopyOnWriteArrayList<>();
        private final AtomicReference<MessageInfo> _secureResponseMessageInfo = new AtomicReference<>();

        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
        {
            _validateRequestMessageInfos.add(messageInfo);
            String name = _validateRequestMessageInfos.size() == 1 ? null : "user";
            try
            {
                getHandler().handle(new Callback[]{new CallerPrincipalCallback(clientSubject, name)});
            }
            catch (Exception e)
            {
                throw new AuthException(e.getMessage());
            }
            return AuthStatus.SUCCESS;
        }

        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
        {
            _secureResponseMessageInfo.set(messageInfo);
            return super.secureResponse(messageInfo, serviceSubject);
        }

        public List<MessageInfo> getValidateRequestMessageInfos()
        {
            return _validateRequestMessageInfos;
        }

        public MessageInfo getSecureResponseMessageInfo()
        {
            return _secureResponseMessageInfo.get();
        }
    }

    /**
     * Returns {@code SUCCESS} without ever handling a {@code CallerPrincipalCallback}, which the
     * Servlet Container Profile requires of a module but some modules written against other
     * containers omit.
     */
    public static class NoCallbackAuthModule extends TestBaseAuthModule
    {
        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject)
        {
            return AuthStatus.SUCCESS;
        }
    }

    /**
     * Servlet that prints authentication state and supports {@code ?action=logout},
     * {@code ?action=authenticate} and {@code ?action=login} query parameters.
     */
    public static class AuthInfoServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
        {
            String action = req.getParameter("action");

            if ("login".equals(action))
            {
                req.getSession(true);
                req.login("user", "password");
                resp.setStatus(200);
                resp.setContentType("text/plain");
                resp.getWriter().println("loginUserPrincipal=" + (req.getUserPrincipal() == null ? "null" : req.getUserPrincipal().getName()));
                resp.getWriter().println("loginIsUserInRole=" + req.isUserInRole("users"));
                resp.getWriter().println("loginIsUserInOtherRole=" + req.isUserInRole("others"));
                resp.getWriter().println("loginAuthType=" + req.getAuthType());
                req.logout();
                resp.getWriter().println("remoteUserAfterLogout=" + req.getRemoteUser());
                return;
            }

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

    /**
     * Counts the dispatches it is given, so a test can tell that the resource was not invoked.
     */
    public static class CountingServlet extends HttpServlet
    {
        private final AtomicInteger _dispatches;

        public CountingServlet(AtomicInteger dispatches)
        {
            _dispatches = dispatches;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp)
        {
            _dispatches.incrementAndGet();
        }
    }

    public static class SendErrorServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.sendError(599);
        }
    }

    public static class ThrowingServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException
        {
            throw new QuietServletException("test");
        }
    }

    /**
     * Records that it was dispatched to, so that a test can assert when it ran relative to the
     * calls made on the module.
     */
    public static class RecordingServlet extends HttpServlet
    {
        private final List<String> _order;
        private final String _name;

        public RecordingServlet(List<String> order, String name)
        {
            _order = order;
            _name = name;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp)
        {
            _order.add(_name);
        }
    }

    public static class UserPrincipalServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setContentType("text/plain");
            resp.getWriter().println("errorUserPrincipal=" + (req.getUserPrincipal() == null ? "null" : req.getUserPrincipal().getName()));
            resp.getWriter().println("errorAuthType=" + req.getAuthType());
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

    /**
     * Remembers the identity it last logged in, so a test can see the subject the LoginService
     * built for it and what is left of that afterwards.
     */
    public static class RecordingLoginService extends TestLoginService
    {
        private final AtomicReference<UserIdentity> _lastIdentity = new AtomicReference<>();

        public RecordingLoginService(String name)
        {
            super(name);
        }

        @Override
        public UserIdentity login(String username, Object credentials, Request request, Function<Boolean, Session> getOrCreateSession)
        {
            UserIdentity identity = super.login(username, credentials, request, getOrCreateSession);
            _lastIdentity.set(identity);
            return identity;
        }

        public UserIdentity getLastIdentity()
        {
            return _lastIdentity.get();
        }
    }
}
