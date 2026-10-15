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

package org.eclipse.jetty.ee.servlet.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.security.auth.Subject;

import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee.servlet.ErrorHandler;
import org.eclipse.jetty.ee.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.ee.servlet.ServletApiResponse;
import org.eclipse.jetty.ee.servlet.ServletChannel;
import org.eclipse.jetty.ee.servlet.ServletContextHandler;
import org.eclipse.jetty.ee.servlet.ServletContextRequest;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.logging.JettyLevel;
import org.eclipse.jetty.logging.JettyLogger;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.logging.StdErrAppender;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.ServletAuthenticator;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the servlet layer half of the {@link ServletAuthenticator} gate, where the request is
 * authenticated and authorized on behalf of the {@link SecurityHandler} that deferred it.
 */
public class ServletAuthenticatorTest
{
    private Server _server;
    private LocalConnector _connector;
    private ServletContextHandler _contextHandler;
    private SecurityHandler.PathMapped _securityHandler;
    private final List<String> _events = new CopyOnWriteArrayList<>();
    private final Semaphore _requestsCompleted = new Semaphore(0);

    @BeforeEach
    public void before()
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        _contextHandler = new ServletContextHandler();
        _contextHandler.setContextPath("/");
        _contextHandler.addServlet(new TestServlet(), "/");

        _securityHandler = new SecurityHandler.PathMapped();
        _securityHandler.put("/private/*", Constraint.ANY_USER);
        _securityHandler.put("/admin/*", Constraint.from("admin"));
        _securityHandler.put("/public/*", Constraint.ALLOWED);
        _securityHandler.setAuthenticator(new TestServletAuthenticator());
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    private void startServer() throws Exception
    {
        _contextHandler.setSecurityHandler(_securityHandler);
        _server.setHandler(countCompletions(_contextHandler));
        _server.start();
    }

    /**
     * Wraps the handler so that a permit is released for every request the server has finished with.
     */
    private Handler.Wrapper countCompletions(Handler handler)
    {
        return new Handler.Wrapper(handler)
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                Request.addCompletionListener(request, failure -> _requestsCompleted.release());
                return super.handle(request, response, callback);
            }
        };
    }

    /**
     * <p>Wait for the server to finish with a request.</p>
     * <p>{@code secureResponse} is called as the request completes, which on an error path is after the
     * response has been written, so the events can only be asserted once the request has completed on
     * the server. Completion is also the point after which no further event can be recorded.</p>
     */
    private void awaitRequestCompleted() throws Exception
    {
        assertTrue(_requestsCompleted.tryAcquire(10, TimeUnit.SECONDS), "the server did not complete the request");
    }

    @Test
    public void testAuthenticatedRequestReachesTheServlet() throws Exception
    {
        startServer();

        String response = _connector.getResponse("GET /private/info HTTP/1.0\r\nX-User: alice\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("principal=alice"));

        awaitRequestCompleted();

        // The servlet layer validated the request before the dispatch, and secured the response after it.
        assertThat(_events, contains("validate:mandatory", "servlet", "secureResponse"));

        // The attributes the SecurityHandler passed the decision in are consumed by the security check.
        assertThat(response, containsString("mustValidate=null"));
        assertThat(response, containsString("constraint=null"));
    }

    @Test
    public void testUnauthenticatedMandatoryRequestIsForbidden() throws Exception
    {
        startServer();

        String response = _connector.getResponse("GET /private/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));

        awaitRequestCompleted();

        // The request was validated and the response secured, but the servlet was never dispatched.
        assertThat(_events, contains("validate:mandatory", "secureResponse"));
    }

    @Test
    public void testTheRequestListenersRunBeforeTheCheck() throws Exception
    {
        recordRequestListenerEvents();
        startServer();

        String response = _connector.getResponse("GET /private/info HTTP/1.0\r\nX-User: alice\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));

        awaitRequestCompleted();

        // The request is initialized before the authenticator sees it, so whatever a listener scopes to the request is active for the check.
        assertThat(_events, contains("requestInitialized", "validate:mandatory", "servlet", "requestDestroyed", "secureResponse"));
    }

    @Test
    public void testTheRequestIsDestroyedOnceWhenTheCheckForbidsIt() throws Exception
    {
        recordRequestListenerEvents();
        startServer();

        String response = _connector.getResponse("GET /private/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));

        awaitRequestCompleted();

        // A request the check rejects was still initialized, so it must be destroyed once, or what a listener scoped to it is never released.
        assertThat(_events, contains("requestInitialized", "validate:mandatory", "requestDestroyed", "secureResponse"));
    }

    /**
     * Record the {@link ServletRequestListener} callbacks alongside the other events, so their order
     * relative to the security check can be asserted.
     */
    private void recordRequestListenerEvents()
    {
        _contextHandler.addEventListener(new ServletRequestListener()
        {
            @Override
            public void requestInitialized(ServletRequestEvent sre)
            {
                _events.add("requestInitialized");
            }

            @Override
            public void requestDestroyed(ServletRequestEvent sre)
            {
                _events.add("requestDestroyed");
            }
        });
    }

    @Test
    public void testAuthenticatedRequestWithoutTheRoleIsForbidden() throws Exception
    {
        startServer();

        String response = _connector.getResponse("GET /admin/info HTTP/1.0\r\nX-User: alice\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));

        awaitRequestCompleted();

        // The request was validated and the response secured, but the servlet was never dispatched.
        assertThat(_events, contains("validate:mandatory", "secureResponse"));
    }

    @Test
    public void testAuthenticatedRequestWithTheRoleIsAllowed() throws Exception
    {
        startServer();

        String response = _connector.getResponse("GET /admin/info HTTP/1.0\r\nX-User: alice\r\nX-Roles: admin\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("principal=alice"));
    }

    @Test
    public void testOptionalRequestProceedsUnauthenticated() throws Exception
    {
        startServer();

        String response = _connector.getResponse("GET /public/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("principal=null"));
        awaitRequestCompleted();
        assertThat(_events, contains("validate:optional", "servlet", "secureResponse"));
    }

    @Test
    public void testResponseSentStateStopsTheDispatch() throws Exception
    {
        startServer();

        // With no callback to complete, an authenticator answers the request with the Servlet API and says so with a ResponseSent state.
        String response = _connector.getResponse("GET /private/info HTTP/1.0\r\nX-Challenge: yes\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 401 Unauthorized"));
        assertThat(response, containsString("WWW-Authenticate: Test"));

        awaitRequestCompleted();
        assertThat(_events, contains("validate:mandatory", "secureResponse"));
    }

    @Test
    public void testServeAsStateIsRejected() throws Exception
    {
        startServer();

        // The servlet layer cannot serve the request as another URI, so failing says so rather than the 403 an unrecognised state would get.
        String response = _connector.getResponse("GET /private/info HTTP/1.0\r\nX-ServeAs: /public/info\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 500"));

        awaitRequestCompleted();
        assertThat(_events, not(hasItem("servlet")));
    }

    @Test
    public void testAnErrorPageDoesNotSeeTheDeferredDecision() throws Exception
    {
        delayAnErrorBeforeTheServletLayer();
        startServer();

        String response = _connector.getResponse("GET /private/info HTTP/1.0\r\nX-User: alice\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 500"));

        // The attributes the SecurityHandler passed its decision in are taken before the error is answered, so the error page cannot see them.
        assertThat(response, containsString("mustValidate=null"));
        assertThat(response, containsString("constraint=null"));

        awaitRequestCompleted();

        // The delayed error does not skip the deferred check, so the error response is secured like any other.
        assertThat(_events, contains("validate:mandatory", "servlet", "secureResponse"));
    }

    @Test
    public void testADelayedErrorOnAnUnauthenticatedRequestIsForbidden() throws Exception
    {
        delayAnErrorBeforeTheServletLayer();
        startServer();

        // The request was never authorized, so the application error page must not be served for it.
        String response = _connector.getResponse("GET /private/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));

        awaitRequestCompleted();
        assertThat(_events, contains("validate:mandatory", "secureResponse"));
    }

    @Test
    public void testADelayedErrorIsDroppedWhenTheCheckAnswersTheRequest() throws Exception
    {
        delayAnErrorBeforeTheServletLayer();
        startServer();

        // The authenticator answered the request, so its challenge is sent and the delayed error is dropped.
        String response = _connector.getResponse("GET /private/info HTTP/1.0\r\nX-Challenge: yes\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 401 Unauthorized"));
        assertThat(response, containsString("WWW-Authenticate: Test"));

        awaitRequestCompleted();
        assertThat(_events, contains("validate:mandatory", "secureResponse"));
    }

    @Test
    public void testAProtectedTargetIsValidatedAndTheErrorSecured() throws Exception
    {
        // The ServletContextHandler answers a protected target with a 404 that the servlet ErrorHandler delays until the channel is handling the request.
        _contextHandler.setProtectedTargets(new String[]{"/WEB-INF"});
        _contextHandler.setErrorHandler(new ErrorPageErrorHandler());
        startServer();

        String response = _connector.getResponse("GET /WEB-INF/web.xml HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 404"));

        awaitRequestCompleted();
        assertThat(_events, contains("validate:optional", "secureResponse"));
    }

    /**
     * Answer with an error the way a handler between the {@link SecurityHandler} and the servlet
     * layer does, which the servlet {@link ErrorHandler} delays until the channel is handling the
     * request, and map an error page for it.
     */
    private void delayAnErrorBeforeTheServletLayer()
    {
        _securityHandler.setHandler(new Handler.Wrapper()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                request.setAttribute(ErrorHandler.ERROR_STATUS, HttpStatus.INTERNAL_SERVER_ERROR_500);
                return super.handle(request, response, callback);
            }
        });
        ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
        errorHandler.addErrorPage(HttpStatus.INTERNAL_SERVER_ERROR_500, "/error");
        _contextHandler.setErrorHandler(errorHandler);
        _contextHandler.addServlet(new TestServlet(), "/error");
    }

    @Test
    public void testMustValidateAttributeThatIsNotTheCachedBoolean() throws Exception
    {
        // The SecurityHandler sets the attribute by autoboxing, but the key is public, so any equal Boolean must be read as true.
        _securityHandler.setHandler(new Handler.Wrapper()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                request.setAttribute(ServletAuthenticator.MUST_VALIDATE_KEY, notTheCachedTrue());
                return super.handle(request, response, callback);
            }
        });
        startServer();

        String response = _connector.getResponse("GET /private/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));

        awaitRequestCompleted();
        assertThat(_events, contains("validate:mandatory", "secureResponse"));
    }

    /**
     * @return a {@link Boolean} equal to {@link Boolean#TRUE} but not the same instance.
     */
    private static Boolean notTheCachedTrue() throws Exception
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes))
        {
            out.writeObject(Boolean.TRUE);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())))
        {
            return (Boolean)in.readObject();
        }
    }

    @Test
    public void testAResourceThrowingToTheContainerDoesNotSecureTheResponse() throws Exception
    {
        startServer();

        // a418 says the runtime should complete a request whose resource threw without calling secureResponse.
        try (StacklessLogging ignored = new StacklessLogging(ServletChannel.class))
        {
            String response = _connector.getResponse("GET /private/info HTTP/1.0\r\nX-User: alice\r\nX-Throw: yes\r\n\r\n");
            assertThat(response, containsString("HTTP/1.1 500"));
        }

        awaitRequestCompleted();
        assertThat(_events, contains("validate:mandatory", "servlet"));
    }

    @Test
    public void testSecureResponseFailureOnACommittedResponseIsLogged() throws Exception
    {
        startServer();

        // The servlet has flushed, so the response can only be aborted, and this is the last point at which the failure can be reported.
        String log = captureLogging(() ->
        {
            _connector.getResponse("GET /private/flushed HTTP/1.0\r\nX-User: alice\r\nX-Flush: yes\r\nX-Fail-Secure: yes\r\n\r\n");
            awaitRequestCompleted();
        });

        assertThat(log, containsString("Could not secure the committed response of /private/flushed"));
        assertThat(log, containsString(ServerAuthException.class.getName() + ": cannot secure"));
    }

    @Test
    public void testSecureResponseFailureOnAnUncommittedResponseIsReportedOnlyByTheErrorResponse() throws Exception
    {
        startServer();

        // An uncommitted response is replaced by a 500, which reports the failure itself, so the channel must not report it again.
        String log = captureLogging(() ->
        {
            String response = _connector.getResponse("GET /private/buffered HTTP/1.0\r\nX-User: alice\r\nX-Fail-Secure: yes\r\n\r\n");
            assertThat(response, containsString("HTTP/1.1 500"));
            awaitRequestCompleted();
        });

        assertThat(log, containsString("writeError: status=500"));
        assertThat(log, containsString(ServerAuthException.class.getName() + ": cannot secure"));
        assertThat(log, not(containsString("Could not secure the committed response of /private/buffered")));
    }

    /**
     * <p>Runs the action with the {@link ServletChannel} and {@link Response} loggers capturing to
     * a string at WARN, so that what an operator would see can be asserted.</p>
     * <p>The appender is shared, so only what this test provokes can be asserted on: the messages
     * below are unique to it and name the request they are about.</p>
     */
    private String captureLogging(ThrowingRunnable action) throws Exception
    {
        JettyLogger channelLogger = (JettyLogger)LoggerFactory.getLogger(ServletChannel.class);
        JettyLogger responseLogger = (JettyLogger)LoggerFactory.getLogger(Response.class);
        StdErrAppender appender = (StdErrAppender)channelLogger.getAppender();
        PrintStream oldStream = appender.getStream();
        JettyLevel oldChannelLevel = channelLogger.getLevel();
        JettyLevel oldResponseLevel = responseLogger.getLevel();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try
        {
            appender.setStream(new PrintStream(bytes, true));
            channelLogger.setLevel(JettyLevel.WARN);
            responseLogger.setLevel(JettyLevel.WARN);
            action.run();
        }
        finally
        {
            appender.setStream(oldStream);
            channelLogger.setLevel(oldChannelLevel);
            responseLogger.setLevel(oldResponseLevel);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private interface ThrowingRunnable
    {
        void run() throws Exception;
    }

    @Test
    public void testAConstrainedRequestWithNoServletIsNotServedByTheNextHandler() throws Exception
    {
        aHandlerAfterTheServletHandler();

        // The error handler a WebAppContext installs, which must answer a request that never became a ServletContextRequest.
        _contextHandler.setErrorHandler(new ErrorPageErrorHandler());
        startServer();

        // No servlet matches, so there is no ServletChannel to make the check the SecurityHandler deferred.
        String response = _connector.getResponse("GET /private/info HTTP/1.0\r\nX-User: alice\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));
        assertThat(response, containsString("HTTP ERROR 403 Forbidden"));

        awaitRequestCompleted();
        assertThat(_events, is(List.of()));
    }

    @Test
    public void testAnUnconstrainedRequestWithNoServletReachesTheNextHandler() throws Exception
    {
        aHandlerAfterTheServletHandler();
        startServer();

        // The request carries a constraint that requires no authentication, so the handler after the ServletHandler still serves it.
        String response = _connector.getResponse("GET /public/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("mustValidate=null constraint=null"));

        awaitRequestCompleted();
        assertThat(_events, contains("next handler"));
    }

    /**
     * Configure the context to fall through to a handler after the {@link ServletHandler} for a
     * request no servlet mapping matches, which the default servlet otherwise matches.
     */
    private void aHandlerAfterTheServletHandler()
    {
        _contextHandler = new ServletContextHandler();
        _contextHandler.setContextPath("/");
        _contextHandler.getServletHandler().setEnsureDefaultServlet(false);
        _contextHandler.getServletHandler().setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                _events.add("next handler");
                Content.Sink.write(response, true, "mustValidate=%s constraint=%s".formatted(
                    request.getAttribute(ServletAuthenticator.MUST_VALIDATE_KEY),
                    request.getAttribute(ServletAuthenticator.CONSTRAINT_KEY)), callback);
                return true;
            }
        });
    }

    @Test
    public void testSecurityHandlerOutsideTheContextDoesNotStart()
    {
        // A SecurityHandler wrapping the context rather than installed in it would defer to a servlet layer that does not know to validate the request.
        _securityHandler.setHandler(_contextHandler);
        _server.setHandler(countCompletions(_securityHandler));

        Throwable failure = assertThrows(Throwable.class, () -> _server.start());
        assertThat(failure.getMessage(), containsString("it is not starting within a context"));
        assertThat(_events, is(List.of()));
    }

    private class TestServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            _events.add("servlet");
            if (request.getHeader("X-Throw") != null)
                throw new RuntimeException("thrown by the resource");
            Principal principal = request.getUserPrincipal();
            response.setContentType("text/plain");
            response.getWriter().printf("principal=%s%nmustValidate=%s%nconstraint=%s%n",
                principal == null ? null : principal.getName(),
                request.getAttribute(ServletAuthenticator.MUST_VALIDATE_KEY),
                request.getAttribute(ServletAuthenticator.CONSTRAINT_KEY));
            if (request.getHeader("X-Flush") != null)
                response.flushBuffer();
        }
    }

    private class TestServletAuthenticator implements Authenticator, ServletAuthenticator
    {
        @Override
        public void setConfiguration(Configuration configuration)
        {
        }

        @Override
        public String getAuthenticationType()
        {
            return "TEST";
        }

        @Override
        public AuthenticationState validateRequest(Request request, Response response, Callback callback)
        {
            throw new IllegalStateException("not called by the SecurityHandler");
        }

        @Override
        public AuthenticationState validateServletRequest(Request request, Response response, boolean mandatory)
        {
            _events.add(mandatory ? "validate:mandatory" : "validate:optional");
            String serveAs = request.getHeaders().get("X-ServeAs");
            if (serveAs != null)
                return new AuthenticationState.ServeAs(HttpURI.build(request.getHttpURI()).path(serveAs).asImmutable());

            if (request.getHeaders().contains("X-Challenge"))
            {
                ServletApiResponse servletResponse = ServletContextRequest.getServletContextRequest(request).getServletApiResponse();
                servletResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                servletResponse.setHeader("WWW-Authenticate", "Test");
                return AuthenticationState.CHALLENGE;
            }

            String user = request.getHeaders().get("X-User");
            if (user == null)
                return null;
            String roles = request.getHeaders().get("X-Roles");
            return new LoginAuthenticator.UserAuthenticationSucceeded(getAuthenticationType(),
                new TestUserIdentity(user, roles == null ? Set.of() : Set.of(roles.split(","))));
        }

        @Override
        public void secureResponse(Request request, Response response) throws ServerAuthException
        {
            _events.add("secureResponse");
            if (request.getHeaders().contains("X-Fail-Secure"))
                throw new ServerAuthException("cannot secure");
        }
    }

    private record TestUserIdentity(String name, Set<String> roles) implements UserIdentity
    {
        @Override
        public Subject getSubject()
        {
            return new Subject();
        }

        @Override
        public Principal getUserPrincipal()
        {
            return () -> name;
        }

        @Override
        public boolean isUserInRole(String role)
        {
            return roles.contains(role);
        }
    }
}
