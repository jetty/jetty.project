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
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.ee.security.jaspi.modules.BasicAuthenticationAuthModule;
import org.eclipse.jetty.ee.security.jaspi.provider.JaspiAuthConfigProvider;
import org.eclipse.jetty.ee.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.ee.servlet.ServletContextHandler;
import org.eclipse.jetty.ee.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.RolePrincipal;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.NanoTime;
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
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
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
 * <p>The auth modules record every call made to them so the tests can assert what the
 * container did without having to read the module's source.
 */
@Isolated("Sets the global AuthConfigFactory")
public class JaspiServletContainerProfileTest
{
    /**
     * Answered by the request wrapper the module installs with a marker of its own, in place of whatever the client sent.
     */
    private static final String WRAPPED_REQUEST_HEADER = "X-Wrapped-Request";

    /**
     * Set on the response by a module as it secures it, and by nothing else.
     */
    private static final String SECURED_RESPONSE_HEADER = "X-Secured-Response";

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
        // Install a fresh factory with our test auth module.
        _factory = new DefaultAuthConfigFactory();
        AuthConfigFactory.setFactory(_factory);

        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        // Login service so the CallerPrincipalCallback can resolve users.
        _loginService = new RecordingLoginService("TestRealm");
        _loginService.putUser("user", new Password("password"), new String[]{"users"});
        _server.addBean(_loginService);

        _context = new ServletContextHandler();
        _server.setHandler(new Handler.Wrapper(_context)
        {
            @Override
            public boolean handle(Request request, Response response, org.eclipse.jetty.util.Callback callback) throws Exception
            {
                Request.addCompletionListener(request, failure -> _requestsCompleted.release());
                return super.handle(request, response, callback);
            }
        });
        _context.setContextPath("/ctx");

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        _context.setSecurityHandler(security);
        security.setAuthenticatorFactory(new JaspiAuthenticatorFactory());

        // Only /secured/* requires authentication.
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/secured/*");
        mapping.setConstraint(new Constraint.Builder().roles("users").build());
        security.addConstraintMapping(mapping);

        // Nothing may reach /forbidden/*, whoever asks.
        ConstraintMapping forbidden = new ConstraintMapping();
        forbidden.setPathSpec("/forbidden/*");
        forbidden.setConstraint(Constraint.FORBIDDEN);
        security.addConstraintMapping(forbidden);
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
            "HttpServlet", "null /ctx", "test provider");
        _context.addServlet(servlet, "/");
        _server.start();
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

        String response = _connector.getResponse("""
            GET /ctx/open/resource?action=authenticate HTTP/1.0\r
            \r
            """);

        // Nobody was named before the dispatch, so authenticate() does ask the module, and the profile requires both keys of that call.
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(authModule.getValidateRequestCount(), equalTo(2));
        assertThat(authModule.getIsMandatory(), equalTo("true"));
        assertThat(authModule.isAuthenticationRequest(), equalTo("true"));
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

        _factory.registerConfigProvider(
            new JaspiAuthConfigProvider(authModule),
            "HttpServlet", "null /ctx", "test provider");
        _context.addServlet(new AuthInfoServlet(), "/");
        _context.addServlet(new UserPrincipalServlet(), "/error403");
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

        // A FORBIDDEN constraint is rejected by the SecurityHandler itself, so the request is never deferred and the module is never asked.
        String response = _connector.getResponse("""
            GET /ctx/forbidden/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(authModule.getValidateRequestCount(), equalTo(0));
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
        awaitRequestCompleted();
        assertTrue(authModule.getSecureResponseCalled(),
            "secureResponse must be called for a request that was not constrained");
    }

    @Test
    public void testDeferredGetUserPrincipalDoesNotAskTheModuleAgain() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        // The module was asked about the request before the dispatch, so the servlet's getUserPrincipal is answered from that call and not from a second one.
        String response = _connector.getResponse("GET /ctx/open/resource HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("userPrincipal=null"));
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
    public void testAModuleCallingAuthenticateInsideValidateRequestIsNotReentered() throws Exception
    {
        AuthenticateAuthModule authModule = new AuthenticateAuthModule();
        start(authModule, new AuthInfoServlet());

        // An authenticate() is an authentication request, which the validated marker does not answer, so only the re-entry guard keeps it from recursing.
        String response = _connector.getResponse("GET /ctx/open/resource HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(authModule.getValidateRequestCount(), equalTo(1));

        // The container has no answer to give while the module is still deciding, so authenticate() fails rather than re-entering it.
        assertThat(authModule.getAuthenticateFailure(), instanceOf(ServletException.class));
    }

    @Test
    public void testSuccessWithoutACallerPrincipalCallbackIsNotAuthenticated() throws Exception
    {
        NoCallbackAuthModule authModule = new NoCallbackAuthModule();
        start(authModule, new AuthInfoServlet());

        // SUCCESS obliges the module to name the caller, but naming none leaves no caller rather than an error, so a constrained path is forbidden.
        String response = _connector.getResponse("GET /ctx/secured/resource HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 403"));

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
        _context.addServlet(new AuthInfoServlet(), "/");
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
    public void testSendError() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule()
        {
            @Override
            public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
            {
                try
                {
                    ((HttpServletResponse)messageInfo.getResponseMessage()).sendError(321);
                    return AuthStatus.SEND_FAILURE;
                }
                catch (IOException e)
                {
                    throw new AuthException(e.getMessage());
                }
            }
        };
        start(authModule, new AuthInfoServlet());

        // SEND_FAILURE with a response the module established: the runtime sends that rather than replacing it with one of its own.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 321"));
        awaitRequestCompleted();
        assertFalse(authModule.getSecureResponseCalled());
    }

    @Test
    public void testErrorDispatchDoesNotAskTheModuleAgain() throws Exception
    {
        CountingAuthModule authModule = new CountingAuthModule();
        ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
        errorHandler.addErrorPage(599, "/error599");
        _context.setErrorHandler(errorHandler);

        _factory.registerConfigProvider(
            new JaspiAuthConfigProvider(authModule),
            "HttpServlet", "null /ctx", "test provider");
        _context.addServlet(new SendErrorServlet(), "/open/*");
        _context.addServlet(new UserPrincipalServlet(), "/error599");
        _server.start();

        String response = _connector.getResponse("""
            GET /ctx/open/resource HTTP/1.0\r
            \r
            """);

        // The module has already answered for this request, so the deferred getUserPrincipal from the error page must not ask it again.
        assertThat(response, startsWith("HTTP/1.1 599"));
        assertThat(response, containsString("errorUserPrincipal=null"));
        assertThat(authModule.getValidateRequestCount(), equalTo(1));
    }

    @Test
    public void testErrorDispatchOfAFailedValidationDoesNotAskTheModuleAgain() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
        errorHandler.addErrorPage(403, "/error403");
        _context.setErrorHandler(errorHandler);

        _factory.registerConfigProvider(
            new JaspiAuthConfigProvider(authModule),
            "HttpServlet", "null /ctx", "test provider");
        _context.addServlet(new AuthInfoServlet(), "/");
        _context.addServlet(new UserPrincipalServlet(), "/error403");
        _server.start();

        // FAILURE is still an answer about this request, so the error page's deferred reads must not ask the module again or take the error page away.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString("errorUserPrincipal=null"));
        assertThat(response, containsString("errorAuthType=null"));
        assertThat(authModule.getValidateRequestCount(), equalTo(1));
    }

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

        // The servlet returns from its dispatch at once and writes from another thread, so point (3) is only reached when that thread completes.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """, 10, TimeUnit.SECONDS);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("async=done"));
        awaitRequestCompleted();
        assertTrue(authModule.getSecureResponseCalled(), "secureResponse must be called");
        assertTrue(asyncCompletedWhenSecured.get(),
            "secureResponse must not be called until the async dispatch has finished with the response");
    }

    @Test
    public void testSecureResponseCanActOnTheResponseOfAnUnauthorizedRequest() throws Exception
    {
        HeaderSecureResponseAuthModule authModule = new HeaderSecureResponseAuthModule();
        start(authModule, new AuthInfoServlet());

        // The 403 is generated by the runtime rather than by a resource, so it must not be sent before the module has secured it.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            X-Test-Group: others\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 403"));
        assertThat(response, containsString(SECURED_RESPONSE_HEADER + ": true"));
        awaitRequestCompleted();
        assertTrue(authModule.getSecureResponseCalled());
    }

    @Test
    public void testSecureResponseCanActOnAnErrorResponseWithNoErrorPageMapped() throws Exception
    {
        HeaderSecureResponseAuthModule authModule = new HeaderSecureResponseAuthModule();
        // An ErrorPageErrorHandler with no page mapped for the status, as a web application that declares no error page has.
        _context.setErrorHandler(new ErrorPageErrorHandler());
        start(authModule, new SendErrorServlet(404));

        // No error page is mapped, so the runtime generates the error response itself, which it must not send before the module has secured it.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 404"));
        assertThat(response, containsString(SECURED_RESPONSE_HEADER + ": true"));
        awaitRequestCompleted();
        assertTrue(authModule.getSecureResponseCalled());
    }

    @Test
    public void testSecureResponseSendFailureReplacesAnErrorResponseWithNoErrorPageMapped() throws Exception
    {
        SendFailureSecureResponseAuthModule authModule = new SendFailureSecureResponseAuthModule();
        _context.setErrorHandler(new ErrorPageErrorHandler());
        start(authModule, new SendErrorServlet(404));

        // The error response has not been sent, so the failure to secure it replaces it with the 500 the profile allows the runtime to describe.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """, 5, TimeUnit.SECONDS);

        assertThat(response, startsWith("HTTP/1.1 500"));
        awaitRequestCompleted();
        assertTrue(authModule.getSecureResponseCalled());
    }

    @Test
    public void testSecureResponseSendFailureReplacesAContainerGeneratedErrorResponse() throws Exception
    {
        SendFailureSecureResponseAuthModule authModule = new SendFailureSecureResponseAuthModule();
        start(authModule, new SendErrorServlet(404));

        // The error response has not been sent, so the failure to secure it replaces it with the 500 the profile allows the runtime to describe.
        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """, 5, TimeUnit.SECONDS);

        assertThat(response, startsWith("HTTP/1.1 500"));
        awaitRequestCompleted();
        assertTrue(authModule.getSecureResponseCalled());
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

        _factory.registerConfigProvider(
            new JaspiAuthConfigProvider(authModule),
            "HttpServlet", "null /ctx", "test provider");
        _context.addServlet(new SendErrorServlet(), "/");
        _context.addServlet(new RecordingServlet(order, "errorPage"), "/error599");
        _server.start();

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        // An error page is the response the module is given to secure, so point (3) is only reached once that page has finished with the response.
        assertThat(response, startsWith("HTTP/1.1 599"));
        awaitRequestCompleted();
        assertThat(order, contains("errorPage", "secureResponse"));
    }

    @Test
    public void testIdentityIsAssociatedForTheDispatch() throws Exception
    {
        RecordingIdentityService identityService = new RecordingIdentityService();
        _loginService.setIdentityService(identityService);
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AssociatedIdentityServlet(identityService));

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("associated=user"));
        assertThat("the association must be closed when the dispatch returns",
            identityService.getOpenAssociations(), equalTo(0));
    }

    @Test
    public void testNoIdentityIsAssociatedForAnUnauthenticatedDispatch() throws Exception
    {
        RecordingIdentityService identityService = new RecordingIdentityService();
        _loginService.setIdentityService(identityService);
        // This module validates the request but leaves the caller unauthenticated.
        start(new CountingAuthModule(), new AssociatedIdentityServlet(identityService));

        String response = _connector.getResponse("""
            GET /ctx/open/resource HTTP/1.0\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("associated=null"));
        assertThat(identityService.getOpenAssociations(), equalTo(0));
    }

    // MessagePolicy Requirements (a412): the request policy the module is initialized with.

    @Test
    public void testRequestPolicyOfAConstrainedPath() throws Exception
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

        assertNotNull(authModule.getRequestPolicy(),
            "the request policy must not be null");
        assertTrue(authModule.getRequestPolicyMandatory(),
            "the request policy must be mandatory for a constrained path");
        assertThat(authModule.getRequestPolicy().getTargetPolicies()[0].getProtectionPolicy().getID(),
            equalTo(MessagePolicy.ProtectionPolicy.AUTHENTICATE_SENDER));
    }

    @Test
    public void testRequestPolicyOfAnUnconstrainedPath() throws Exception
    {
        TestBaseAuthModule authModule = new TestBaseAuthModule();
        start(authModule, new AuthInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/open/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(authModule.getValidateRequestCount(), greaterThan(0));

        assertNotNull(authModule.getRequestPolicy(),
            "the request policy must not be null");
        assertFalse(authModule.getRequestPolicyMandatory(),
            "the request policy must not be mandatory for an unconstrained path");
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
        _context.addServlet(new DispatchedServlet(), "/dispatched");
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

    /**
     * The error page mechanism operates on the request and the response the container created, so
     * the wrappers a module installed for the servlet dispatch do not reach the error page.
     */
    @Test
    public void testTheErrorPageIsGivenTheContainerRequestAndResponse() throws Exception
    {
        WrappingAuthModule authModule = new WrappingAuthModule();
        ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
        errorHandler.addErrorPage(599, "/error599");
        _context.setErrorHandler(errorHandler);
        _context.addServlet(new DispatchedServlet(), "/error599");
        start(authModule, new SendErrorServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Wrapped-Request: from-the-client\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 599"));
        assertThat(response, containsString("dispatcherType=ERROR"));

        // Only the container's own request reports what the client sent, as the module's wrapper answers the header with its marker.
        assertThat(response, containsString("dispatchedRequestWrapper=from-the-client"));

        // The module validated once, so the response wrapper asserted on is the one the servlet dispatch was given.
        assertThat(authModule.getValidateRequestCount(), equalTo(1));
        assertThat("the error page reached the client without being written through the module's wrapper",
            authModule.getWrappedResponse().getWritten(), equalTo(""));
    }

    /**
     * A no-argument {@code startAsync()} initializes the AsyncContext with the container's own
     * request, so the module's wrapper reaches the servlet that starts the asynchronous cycle but
     * not the servlet the cycle is dispatched to.
     */
    @Test
    public void testAnAsyncDispatchStartedWithNoArgumentsIsGivenTheContainerRequest() throws Exception
    {
        WrappingAuthModule authModule = new WrappingAuthModule();
        _context.addServlet(new DispatchedServlet(), "/dispatched");
        start(authModule, new WrappingInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource?action=async HTTP/1.0\r
            \r
            """, 10, TimeUnit.SECONDS);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("requestWrapper=" + authModule.getWrappedRequest().getMarker()));
        assertThat(response, containsString("dispatcherType=ASYNC"));
        assertThat("startAsync() supplies the original, unwrapped request",
            response, containsString("dispatchedRequestWrapper=null"));
        assertThat(authModule.getValidateRequestCount(), equalTo(1));
    }

    /**
     * {@code startAsync(request, response)} is how an application keeps the wrappers it was given
     * for the asynchronous cycle, and a module's wrapper is kept the same way.
     */
    @Test
    public void testTheWrappedRequestSurvivesAnAsyncDispatchStartedWithIt() throws Exception
    {
        WrappingAuthModule authModule = new WrappingAuthModule();
        _context.addServlet(new DispatchedServlet(), "/dispatched");
        start(authModule, new WrappingInfoServlet());

        String response = _connector.getResponse("""
            GET /ctx/secured/resource?action=async-with-request HTTP/1.0\r
            \r
            """, 10, TimeUnit.SECONDS);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("dispatcherType=ASYNC"));
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
    public void testTheModuleReadsTheRequestMessageAndNotTheContainerRequest() throws Exception
    {
        start(new ReplacingBasicAuthModule(), new AuthInfoServlet());

        // The credentials are only on the request message the module put back, so reading the container's own request would not find them.
        String response = _connector.getResponse("GET /ctx/secured/resource HTTP/1.0\r\n\r\n");
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("userPrincipal=user"));
    }

    // Logout: the subject the module is asked to clean

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
        assertThat(clientSubject.getPrivateCredentials(), empty());
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
        assertThat(cleaned.getPrivateCredentials(), empty());
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

    // A module that fails, and the responses a module makes for itself

    @Test
    public void testSecureResponseThrowingReplacesAResponseNotYetSent() throws Exception
    {
        ThrowingSecureResponseAuthModule authModule = new ThrowingSecureResponseAuthModule();
        start(authModule, new AuthInfoServlet());

        // The response has not been sent, so the failure to secure it replaces it with the 500 the profile allows the runtime to describe.
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
    public void testSecureResponseThrowingAbortsAResponseAlreadySent() throws Exception
    {
        ThrowingSecureResponseAuthModule authModule = new ThrowingSecureResponseAuthModule();
        // Long enough that a request with nothing left to complete it would hang, so the assertion below is the abort and not an idle connection.
        _connector.setIdleTimeout(30000);
        start(authModule, new FlushingServlet());

        LocalConnector.LocalEndPoint endPoint = _connector.executeRequest("""
            GET /ctx/secured/resource HTTP/1.1\r
            Host: local\r
            X-Test-User: user\r
            \r
            """);

        // The response is committed, so it can only be failed, and the client is told by the connection closing on an unterminated response.
        awaitRequestCompleted();
        assertTrue(authModule.getSecureResponseCalled());
        assertTrue(awaitClosed(endPoint), "the aborted response must close the connection");
        String response = endPoint.takeOutputString();
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat("the chunked response must be left unterminated", response, not(containsString("\r\n0\r\n\r\n")));
    }

    @Test
    public void testSecureResponseSendFailureReplacesAResponseNotYetSent() throws Exception
    {
        SendFailureSecureResponseAuthModule authModule = new SendFailureSecureResponseAuthModule();
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
        SuccessSecureResponseAuthModule authModule = new SuccessSecureResponseAuthModule();
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
        FailureSecureResponseAuthModule authModule = new FailureSecureResponseAuthModule();
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
        SendFailureSecureResponseAuthModule authModule = new SendFailureSecureResponseAuthModule();
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
    public void testSecureResponseSendFailureAbortsAnAsyncResponseAlreadySent() throws Exception
    {
        AtomicBoolean asyncCompleted = new AtomicBoolean();
        SendFailureSecureResponseAuthModule authModule = new SendFailureSecureResponseAuthModule();
        _connector.setIdleTimeout(30000);
        start(authModule, new AsyncServlet(asyncCompleted, true));

        LocalConnector.LocalEndPoint endPoint = _connector.executeRequest("""
            GET /ctx/secured/resource HTTP/1.1\r
            Host: local\r
            X-Test-User: user\r
            \r
            """);

        // The async thread committed the response, so it can only be failed, as on a response committed by a dispatch.
        awaitRequestCompleted();
        assertTrue(asyncCompleted.get(), "the async dispatch must have written the response");
        assertTrue(authModule.getSecureResponseCalled());
        assertTrue(awaitClosed(endPoint), "the aborted response must close the connection");
        String response = endPoint.takeOutputString();
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat("the chunked response must be left unterminated", response, not(containsString("\r\n0\r\n\r\n")));
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
    public void testAuthenticateCanEstablishTheCallerAfterTheDispatchHasStarted() throws Exception
    {
        CountingAuthModule authModule = new CountingAuthModule();
        start(authModule, new AuthInfoServlet());

        // The module names nobody on the first call and a caller on any later one, so the only caller is the one authenticate() established.
        String response = _connector.getResponse("""
            GET /ctx/open/resource?action=authenticate HTTP/1.0\r
            \r
            """);

        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("authenticated=true"));
        assertThat(response, containsString("remoteUser=user"));
        assertThat(authModule.getValidateRequestCount(), equalTo(2));
        awaitRequestCompleted();

        // The profile pairs secureResponse with the point (2) validateRequest made before the dispatch, not the one authenticate() made during it.
        assertThat(authModule.getSecureResponseMessageInfo(),
            sameInstance(authModule.getValidateRequestMessageInfos().get(0)));
        assertThat(authModule.getSecureResponseMessageInfo(),
            not(sameInstance(authModule.getValidateRequestMessageInfos().get(1))));
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
        _factory.registerConfigProvider(
            new JaspiAuthConfigProvider(second),
            "HttpServlet", "null /ctx", "replacement provider");

        response = _connector.getResponse("""
            GET /ctx/secured/resource HTTP/1.0\r
            X-Test-User: user\r
            \r
            """);
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(second.getValidateRequestCount(), equalTo(1));
        assertThat("the replaced module must not be asked again", first.getValidateRequestCount(), equalTo(1));
    }

    /**
     * @param endPoint the endpoint of a request.
     * @return whether the connection was closed within a bound well under the idle timeout.
     */
    private static boolean awaitClosed(LocalConnector.LocalEndPoint endPoint) throws Exception
    {
        long end = NanoTime.now() + TimeUnit.SECONDS.toNanos(5);
        while (endPoint.isOpen() && NanoTime.isBefore(NanoTime.now(), end))
        {
            Thread.sleep(10);
        }
        return !endPoint.isOpen();
    }

    private static Set<String> principalNames(Subject subject)
    {
        return subject.getPrincipals().stream().map(Principal::getName).collect(Collectors.toSet());
    }

    public static class CountingAuthModule implements ServerAuthModule
    {
        private final AtomicInteger _validateRequestCount = new AtomicInteger();
        private final List<MessageInfo> _validateRequestMessageInfos = new CopyOnWriteArrayList<>();
        private final AtomicReference<MessageInfo> _secureResponseMessageInfo = new AtomicReference<>();
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
        }

        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject,
                                          Subject serviceSubject) throws AuthException
        {
            String name = _validateRequestCount.getAndIncrement() == 0 ? null : "user";
            _validateRequestMessageInfos.add(messageInfo);
            try
            {
                _handler.handle(new Callback[]{new CallerPrincipalCallback(clientSubject, name)});
            }
            catch (Exception e)
            {
                throw new AuthException(e.getMessage());
            }
            return AuthStatus.SUCCESS;
        }

        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject)
        {
            _secureResponseMessageInfo.set(messageInfo);
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

        /**
         * @return the MessageInfo of each validateRequest, in the order the calls were made.
         */
        public List<MessageInfo> getValidateRequestMessageInfos()
        {
            return _validateRequestMessageInfos;
        }

        public MessageInfo getSecureResponseMessageInfo()
        {
            return _secureResponseMessageInfo.get();
        }
    }

    public static class TestBaseAuthModule  implements ServerAuthModule
    {
        private CallbackHandler _handler;
        private final AtomicReference<String> _isMandatory = new AtomicReference<>();
        private final AtomicBoolean _secureResponseCalled = new AtomicBoolean();
        private final AtomicReference<Subject> _clientSubject = new AtomicReference<>();
        private final AtomicReference<Subject> _serviceSubject = new AtomicReference<>();
        private final AtomicReference<Subject> _cleanSubjectSubject = new AtomicReference<>();
        private final AtomicReference<String> _isAuthenticationRequest = new AtomicReference<>();
        private final AtomicReference<MessagePolicy> _requestPolicy = new AtomicReference<>();
        private final AtomicReference<Boolean> _requestPolicyMandatory = new AtomicReference<>();
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
        }

        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject,
                                          Subject serviceSubject) throws AuthException
        {
            _validateRequestCount.incrementAndGet();
            _clientSubject.set(clientSubject);
            _serviceSubject.set(serviceSubject);
            MessagePolicy requestPolicy = _requestPolicy.get();
            _requestPolicyMandatory.set(requestPolicy == null ? null : requestPolicy.isMandatory());

            String mandatory = (String)messageInfo.getMap().get(JaspiMessageInfo.MANDATORY_KEY);
            _isMandatory.set(mandatory);
            String isAuthRequest = (String)messageInfo.getMap().get(JaspiMessageInfo.AUTH_REQUEST_KEY);
            _isAuthenticationRequest.set(isAuthRequest);

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
            return AuthStatus.SEND_SUCCESS;
        }

        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject)
        {
            _cleanSubjectSubject.set(subject);
        }

        public String getIsMandatory()
        {
            return _isMandatory.get();
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

        public String isAuthenticationRequest()
        {
            return _isAuthenticationRequest.get();
        }

        public MessagePolicy getRequestPolicy()
        {
            return _requestPolicy.get();
        }

        /**
         * @return what the request policy said about the last message processed, read while it was
         * being processed, which is when the policy describes it.
         */
        public Boolean getRequestPolicyMandatory()
        {
            return _requestPolicyMandatory.get();
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
     * Calls {@code authenticate} from inside its own {@code validateRequest}, as a module that
     * wants the container's answer before deciding does.
     */
    public static class AuthenticateAuthModule extends TestBaseAuthModule
    {
        private final AtomicReference<Exception> _authenticateFailure = new AtomicReference<>();

        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
        {
            try
            {
                ((HttpServletRequest)messageInfo.getRequestMessage()).authenticate((HttpServletResponse)messageInfo.getResponseMessage());
            }
            catch (IOException | ServletException e)
            {
                _authenticateFailure.set(e);
            }
            return super.validateRequest(messageInfo, clientSubject, serviceSubject);
        }

        public Exception getAuthenticateFailure()
        {
            return _authenticateFailure.get();
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
     * Replaces the request message with one carrying the credentials, which the module then reads
     * from the message as the profile has it do.
     */
    public static class ReplacingBasicAuthModule extends BasicAuthenticationAuthModule
    {
        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
        {
            messageInfo.setRequestMessage(new HttpServletRequestWrapper((HttpServletRequest)messageInfo.getRequestMessage())
            {
                @Override
                public String getHeader(String name)
                {
                    return HttpHeader.AUTHORIZATION.is(name) ? BASIC_CREDENTIALS : super.getHeader(name);
                }
            });
            return super.validateRequest(messageInfo, clientSubject, serviceSubject);
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
     * Prints the marker of the request wrapper it was given, and dispatches to
     * {@code /dispatched} for {@code ?action=forward}, {@code ?action=async} and
     * {@code ?action=async-with-request}.
     */
    public static class WrappingInfoServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
        {
            String action = req.getParameter("action");

            if ("forward".equals(action))
            {
                req.getRequestDispatcher("/dispatched").forward(req, resp);
                return;
            }

            if ("async".equals(action))
            {
                resp.setContentType("text/plain");
                resp.getWriter().println("requestWrapper=" + req.getHeader(WRAPPED_REQUEST_HEADER));
                req.startAsync().dispatch("/dispatched");
                return;
            }

            if ("async-with-request".equals(action))
            {
                req.startAsync(req, resp).dispatch("/dispatched");
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
     * Fails to secure the response, which the Servlet Container Profile allows a module to report
     * by throwing an {@link AuthException}.
     */
    public static class ThrowingSecureResponseAuthModule extends TestBaseAuthModule
    {
        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
        {
            super.secureResponse(messageInfo, serviceSubject);
            throw new AuthException("secureResponse failed");
        }
    }

    /**
     * Adds a header to the response it secures, which only reaches the client if the response had
     * not already been sent when the module was given it.
     */
    public static class HeaderSecureResponseAuthModule extends TestBaseAuthModule
    {
        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
        {
            ((HttpServletResponse)messageInfo.getResponseMessage()).setHeader(SECURED_RESPONSE_HEADER, "true");
            return super.secureResponse(messageInfo, serviceSubject);
        }
    }

    /**
     * Fails to secure the response, which the Servlet Container Profile allows a module to report
     * by returning {@link AuthStatus#SEND_FAILURE}.
     */
    public static class SendFailureSecureResponseAuthModule extends TestBaseAuthModule
    {
        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
        {
            super.secureResponse(messageInfo, serviceSubject);
            return AuthStatus.SEND_FAILURE;
        }
    }

    /**
     * Secures the response and reports it with {@link AuthStatus#SUCCESS}, which the Servlet
     * Container Profile does not define for {@code secureResponse}.
     */
    public static class SuccessSecureResponseAuthModule extends TestBaseAuthModule
    {
        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
        {
            super.secureResponse(messageInfo, serviceSubject);
            return AuthStatus.SUCCESS;
        }
    }

    /**
     * Reports {@link AuthStatus#FAILURE}, which the Servlet Container Profile does not define for
     * {@code secureResponse} and which reports no success.
     */
    public static class FailureSecureResponseAuthModule extends TestBaseAuthModule
    {
        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject) throws AuthException
        {
            super.secureResponse(messageInfo, serviceSubject);
            return AuthStatus.FAILURE;
        }
    }

    /**
     * Commits the response before it returns, so that nothing after the dispatch can replace it.
     */
    public static class FlushingServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.setContentType("text/plain");
            resp.getWriter().println("userPrincipal=" + req.getUserPrincipal().getName());
            resp.flushBuffer();
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
        private final int _status;

        public SendErrorServlet()
        {
            this(599);
        }

        public SendErrorServlet(int status)
        {
            _status = status;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            resp.sendError(_status);
        }
    }

    /**
     * Records that it was dispatched, so a test can tell in what order it ran.
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

    /**
     * Returns from its dispatch immediately and writes the response from another thread, so that
     * the response is only finished with once {@link AsyncContext#complete()} is called.
     */
    public static class AsyncServlet extends HttpServlet
    {
        private final AtomicBoolean _completed;
        private final boolean _flush;

        public AsyncServlet(AtomicBoolean completed)
        {
            this(completed, false);
        }

        /**
         * @param completed set once the response has been written
         * @param flush true to commit the response from the async thread
         */
        public AsyncServlet(AtomicBoolean completed, boolean flush)
        {
            _completed = completed;
            _flush = flush;
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
                    if (_flush)
                        resp.flushBuffer();
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
     * An {@link IdentityService} that remembers which {@link UserIdentity} is associated with the
     * calling thread, so a test can see what the application would run as.
     */
    public static class RecordingIdentityService extends DefaultIdentityService
    {
        private final ThreadLocal<UserIdentity> _associated = new ThreadLocal<>();
        private final AtomicInteger _open = new AtomicInteger();

        @Override
        public Association associate(UserIdentity user, RunAsToken runAsToken)
        {
            UserIdentity previous = _associated.get();
            Association association = super.associate(user, runAsToken);
            _associated.set(user);
            _open.incrementAndGet();
            return () ->
            {
                _associated.set(previous);
                _open.decrementAndGet();
                association.close();
            };
        }

        public UserIdentity getAssociated()
        {
            return _associated.get();
        }

        public int getOpenAssociations()
        {
            return _open.get();
        }
    }

    public static class AssociatedIdentityServlet extends HttpServlet
    {
        private final RecordingIdentityService _identityService;

        public AssociatedIdentityServlet(RecordingIdentityService identityService)
        {
            _identityService = identityService;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
        {
            UserIdentity associated = _identityService.getAssociated();
            resp.setContentType("text/plain");
            resp.getWriter().println("associated=" + (associated == null ? "null" : associated.getUserPrincipal().getName()));
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
