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
import java.security.Principal;
import java.util.Map;
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
import jakarta.security.auth.message.module.ServerAuthModule;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee.security.jaspi.provider.JaspiAuthConfigProvider;
import org.eclipse.jetty.ee.servlet.ServletContextHandler;
import org.eclipse.jetty.ee.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.security.Password;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A context that declares an auth-method is served by it while no {@code AuthConfigProvider} is
 * registered for the context, which is what the Servlet Container Profile leaves a request to when
 * {@code getConfigProvider} returns null. The whole of that auth-method applies, not only its
 * {@code validateRequest}, which is what a FORM login needs to complete.
 */
@Isolated("Sets the global AuthConfigFactory")
public class JaspiDeclaredAuthMethodTest
{
    private Server _server;
    private LocalConnector _connector;
    private DefaultAuthConfigFactory _authConfigFactory;
    private ServletContextHandler _context;

    @BeforeEach
    public void before() throws Exception
    {
        _authConfigFactory = new DefaultAuthConfigFactory();
        AuthConfigFactory.setFactory(_authConfigFactory);

        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        _context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        _context.setContextPath("/ctx");
        _context.addServlet(new EchoServlet(), "/");
        _context.addServlet(new LoginServlet(), "/programmatic-login");
        _context.addServlet(new LogoutServlet(), "/programmatic-logout");
        _server.setHandler(_context);

        ConstraintSecurityHandler security = new ConstraintSecurityHandler();
        _context.setSecurityHandler(security);
        security.setAuthenticatorFactory(new JaspiAuthenticatorFactory());
        security.setAuthenticationType(Authenticator.FORM_AUTH);
        security.setParameter(FormAuthenticator.__FORM_LOGIN_PAGE, "/login");
        security.setParameter(FormAuthenticator.__FORM_ERROR_PAGE, "/error");
        security.setLoginService(loginService());
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setPathSpec("/secured/*");
        mapping.setConstraint(new Constraint.Builder().roles("users").build());
        security.addConstraintMapping(mapping);

        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
        AuthConfigFactory.setFactory(null);
    }

    /**
     * The login page and {@code j_security_check} are left reachable by the Authenticator rather
     * than by the constraints, and the parameters of the request the login interrupted are restored
     * to it afterwards.
     */
    @Test
    public void testTheDeclaredFormLoginCompletes() throws Exception
    {
        HttpTester.Response response = request("POST", "/ctx/secured/resource", "x=1", null);
        assertThat(response.getStatus(), is(303));
        assertThat(response.get(HttpHeader.LOCATION), containsString("/ctx/login"));
        String session = sessionCookie(response);
        assertThat(session, notNullValue());

        assertThat(request("GET", "/ctx/login", null, session).getStatus(), is(200));

        response = request("POST", "/ctx/j_security_check", "j_username=user&j_password=password", session);
        assertThat(response.getStatus(), is(303));
        assertThat(response.get(HttpHeader.LOCATION), containsString("/ctx/secured/resource"));

        // The login renews the session id.
        session = sessionCookie(response);
        assertThat(session, notNullValue());

        response = request("GET", "/ctx/secured/resource", null, session);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), containsString("principal=user"));
        assertThat(response.getContent(), containsString("x=1"));
    }

    @Test
    public void testAProgrammaticLoginIsRememberedByTheDeclaredAuthMethod() throws Exception
    {
        HttpTester.Response response = request("GET", "/ctx/programmatic-login", null, null);
        assertThat(response.getStatus(), is(200));
        String session = sessionCookie(response);
        assertThat(session, notNullValue());

        response = request("GET", "/ctx/secured/resource", null, session);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), containsString("principal=user"));
    }

    @Test
    public void testAProgrammaticLogoutIsHonouredByTheDeclaredAuthMethod() throws Exception
    {
        String session = sessionCookie(request("GET", "/ctx/programmatic-login", null, null));
        assertThat(request("GET", "/ctx/programmatic-logout", null, session).getStatus(), is(200));

        assertThat(request("GET", "/ctx/secured/resource", null, session).getStatus(), is(302));
    }

    /**
     * A registered module takes the context back from the declared auth-method whole, including the
     * login page the Authenticator had left unconstrained.
     */
    @Test
    public void testARegisteredModuleTakesTheContextFromTheDeclaredAuthMethod() throws Exception
    {
        assertThat(request("GET", "/ctx/login", null, null).getStatus(), is(200));

        _authConfigFactory.registerServerAuthModule(new HttpHeaderAuthModule(), _context.getServletContext());

        HttpTester.Response response = request("GET", "/ctx/secured/resource", null, null);
        assertThat(response.getStatus(), is(403));

        response = requestAs("user", "GET", "/ctx/secured/resource");
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), containsString("principal=user"));

        assertThat(requestAs(null, "GET", "/ctx/login").getStatus(), is(403));
    }

    /**
     * The container authentication session a module registered is held in the session of the
     * request, so a logout served by the declared auth-method discards it too: a provider
     * registered again would otherwise restore the caller the application had logged out.
     */
    @Test
    public void testAProgrammaticLogoutDiscardsTheRegisteredSessionOfARemovedModule() throws Exception
    {
        String registration = registerSessionModule();

        HttpTester.Response response = requestAs("user", "GET", "/ctx/secured/resource");
        assertThat(response.getStatus(), is(200));
        String session = sessionCookie(response);
        assertThat(session, notNullValue());

        // The module continues the registered session for a request that names nobody.
        response = request("GET", "/ctx/secured/resource", null, session);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), containsString("principal=user"));

        // With the module gone the declared auth-method serves the context, including the logout.
        assertTrue(_authConfigFactory.removeRegistration(registration));
        assertThat(request("GET", "/ctx/programmatic-logout", null, session).getStatus(), is(200));

        registerSessionModule();
        assertThat(request("GET", "/ctx/secured/resource", null, session).getStatus(), is(403));
    }

    private String registerSessionModule()
    {
        return _authConfigFactory.registerConfigProvider(new JaspiAuthConfigProvider(new RegisterSessionAuthModule()),
            JaspiAuthenticatorFactory.MESSAGE_LAYER, "null /ctx", null);
    }

    private static LoginService loginService()
    {
        HashLoginService loginService = new HashLoginService("realm");
        UserStore userStore = new UserStore();
        userStore.addUser("user", new Password("password"), new String[]{"users"});
        loginService.setUserStore(userStore);
        return loginService;
    }

    private HttpTester.Response request(String method, String target, String body, String sessionCookie) throws Exception
    {
        StringBuilder request = new StringBuilder(method).append(" ").append(target).append(" HTTP/1.1\r\nHost: local\r\n");
        if (sessionCookie != null)
            request.append("Cookie: ").append(sessionCookie).append("\r\n");
        if (body != null)
            request.append("Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ").append(body.length()).append("\r\n");
        request.append("Connection: close\r\n\r\n");
        if (body != null)
            request.append(body);
        return HttpTester.parseResponse(_connector.getResponse(request.toString()));
    }

    private HttpTester.Response requestAs(String user, String method, String target) throws Exception
    {
        String header = user == null ? "" : "X-Forwarded-User: " + user + "\r\n";
        return HttpTester.parseResponse(_connector.getResponse(
            method + " " + target + " HTTP/1.1\r\nHost: local\r\n" + header + "Connection: close\r\n\r\n"));
    }

    private static String sessionCookie(HttpTester.Response response)
    {
        String setCookie = response.get(HttpHeader.SET_COOKIE);
        if (setCookie == null)
            return null;
        int semi = setCookie.indexOf(';');
        return semi < 0 ? setCookie : setCookie.substring(0, semi);
    }

    public static class EchoServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            response.setContentType("text/plain");
            PrintWriter out = response.getWriter();
            out.println("principal=" + (request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName()));
            out.println("x=" + request.getParameter("x"));
        }
    }

    public static class LoginServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            request.login("user", "password");
            response.getWriter().println("principal=" + request.getUserPrincipal().getName());
        }
    }

    public static class LogoutServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            request.logout();
            response.getWriter().println("principal=" + request.getUserPrincipal());
        }
    }

    /**
     * A module that registers an authentication session for the caller named in the header, and
     * on a later request continues it with the caller the container restores.
     */
    public static class RegisterSessionAuthModule implements ServerAuthModule
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
        public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy, CallbackHandler handler, Map options)
        {
            _handler = handler;
        }

        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject, Subject serviceSubject) throws AuthException
        {
            HttpServletRequest request = (HttpServletRequest)messageInfo.getRequestMessage();
            String named = request.getHeader("X-Forwarded-User");
            Principal restored = request.getUserPrincipal();
            if (named == null && restored == null)
                return AuthStatus.FAILURE;

            try
            {
                _handler.handle(new Callback[]{
                    new CallerPrincipalCallback(clientSubject, named == null ? restored.getName() : named),
                    new GroupPrincipalCallback(clientSubject, new String[]{"users"})});
            }
            catch (Exception e)
            {
                throw new AuthException(e.getMessage());
            }

            if (named != null)
                messageInfo.getMap().put(JaspiMessageInfo.REGISTER_SESSION_KEY, "true");
            return AuthStatus.SUCCESS;
        }

        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject)
        {
            return AuthStatus.SEND_SUCCESS;
        }

        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject)
        {
        }
    }
}
