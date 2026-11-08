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

package org.eclipse.jetty.ee11.servlet.security;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.awaitility.Awaitility;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.security.Credential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class AuthenticateTest
{
    private Server _server;
    private LocalConnector _connector;

    public void configureServer(Authenticator authenticator, HttpServlet servlet) throws Exception
    {
        configureServer(authenticator, contextHandler -> contextHandler.addServlet(servlet, "/"));
    }

    public void configureServer(Authenticator authenticator, Consumer<ServletContextHandler> configuration) throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler contextHandler = new ServletContextHandler("/ctx", ServletContextHandler.SESSIONS);
        _server.setHandler(contextHandler);
        configuration.accept(contextHandler);

        UserStore userStore = new UserStore();
        userStore.addUser("admin", Credential.getCredential("password"), new String[]{"admin"});
        HashLoginService hashLoginService = new HashLoginService("test");
        hashLoginService.setUserStore(userStore);

        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        contextHandler.insertHandler(securityHandler);
        securityHandler.setLoginService(hashLoginService);
        securityHandler.setAuthenticator(authenticator);

        _server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (_server != null && _server.isRunning())
        {
            _server.stop();
            _server.join();
            _server = null;
            _connector = null;
        }
    }

    @Test
    public void testAuthenticate() throws Exception
    {
        configureServer(new BasicAuthenticator(), new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
            {
                boolean authenticate = req.authenticate(resp);
                if (!authenticate)
                    return;
                resp.getWriter().println("UserPrincipal: " + req.getUserPrincipal());
            }
        });
        String response;

        // No credentials result in a 401 response.
        response = _connector.getResponse("GET /ctx/ HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 401 Unauthorized"));

        // Incorrect credentials also result in a 401 response.
        response = _connector.getResponse("GET /ctx/ HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("admin", "bad_password")));
        assertThat(response, containsString("HTTP/1.1 401 Unauthorized"));

        // If we have correct credentials we will be able to get a valid user principal.
        response = _connector.getResponse("GET /ctx/ HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("admin", "password")));
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("UserPrincipal: admin"));
    }

    @Test
    public void testCustomAuthenticator() throws Exception
    {
        configureServer(new LoginAuthenticator()
        {
            @Override
            public String getAuthenticationType()
            {
                return "CUSTOM";
            }

            @Override
            public AuthenticationState validateRequest(Request request, Response response, Callback callback) throws ServerAuthException
            {
                Fields parameters = Request.extractQueryParameters(request);
                String username = parameters.getValue("username");
                String password = parameters.getValue("password");
                if (username != null && password != null)
                {
                    UserIdentity user = login(username, Credential.getCredential(password), request, response);
                    if (user == null)
                    {
                        if (response.isCommitted())
                            return null;
                        return AuthenticationState.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
                    }
                    return new UserAuthenticationSucceeded(getAuthenticationType(), user);
                }

                if (response.isCommitted())
                    return null;

                Response.sendRedirect(request, response, callback, "/login");
                return AuthenticationState.CHALLENGE;
            }
        }, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
            {
                boolean authenticate = req.authenticate(resp);
                if (!authenticate)
                    return;
                resp.getWriter().println("UserPrincipal: " + req.getUserPrincipal());
            }
        });
        String response;

        // No credentials result in a 302 redirect to the login page.
        response = _connector.getResponse("GET /ctx/ HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 302 Found"));
        assertThat(response, containsString("Location: /login"));

        // Incorrect credentials also result in a 403 response.
        response = _connector.getResponse("GET /ctx/?username=admin&password=wrong HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));

        // If we have correct credentials we will be able to get a valid user principal.
        response = _connector.getResponse("GET /ctx/?username=admin&password=password HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("UserPrincipal: admin"));
    }

    private static class TestHeaderAuthenticator extends LoginAuthenticator
    {
        @Override
        public String getAuthenticationType()
        {
            return "CUSTOM";
        }

        @Override
        public AuthenticationState validateRequest(Request request, Response response, Callback callback) throws ServerAuthException
        {
            String username = request.getHeaders().get("X-Username");
            String password = request.getHeaders().get("X-Password");
            if (username != null && password != null)
            {
                UserIdentity user = login(username, Credential.getCredential(password), request, response);
                if (user == null)
                {
                    if (response.isCommitted())
                        return null;
                    return AuthenticationState.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
                }
                return new UserAuthenticationSucceeded(getAuthenticationType(), user);
            }

            if (response.isCommitted())
                return null;

            return AuthenticationState.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
        }
    }

    @Test
    public void testWrappedRequest() throws Exception
    {
        configureServer(new TestHeaderAuthenticator(), servletContextHandler ->
        {
            servletContextHandler.addFilter(new HttpFilter()
            {
                @Override
                protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException
                {
                    String pathInContext = URIUtil.addPaths(req.getServletPath(), req.getPathInfo());
                    if (pathInContext.startsWith("/authenticate"))
                    {
                        // The request is wrapped to override some headers.
                        req = new HttpServletRequestWrapper(req)
                        {
                            @Override
                            public String getHeader(String name)
                            {
                                if ("X-Username".equalsIgnoreCase(name))
                                    return "admin";
                                if ("X-Password".equalsIgnoreCase(name))
                                    return "password";
                                return super.getHeader(name);
                            }
                        };
                    }
                    chain.doFilter(req, res);
                }
            }, "/*", EnumSet.allOf(DispatcherType.class));
            servletContextHandler.addServlet(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
                {
                    boolean authenticate = req.authenticate(resp);
                    if (!authenticate)
                        return;
                    resp.getWriter().println("UserPrincipal: " + req.getUserPrincipal());
                }
            }, "/");
        });

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("GET /ctx/authenticate HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("UserPrincipal: admin"));
    }

    @Test
    public void testAsyncRunnableWrappedRequest() throws Exception
    {
        configureServer(new TestHeaderAuthenticator(), servletContextHandler ->
        {
            servletContextHandler.addServlet(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
                {
                    // The request is wrapped to override some headers.
                    HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(req)
                    {
                        @Override
                        public String getHeader(String name)
                        {
                            if ("X-Username".equalsIgnoreCase(name))
                                return "admin";
                            if ("X-Password".equalsIgnoreCase(name))
                                return "password";
                            return super.getHeader(name);
                        }
                    };

                    AsyncContext asyncContext = req.startAsync(wrappedRequest, resp);
                    asyncContext.start(() ->
                    {
                        try
                        {
                            boolean authenticate = wrappedRequest.authenticate(resp);
                            if (authenticate)
                                resp.getWriter().println("UserPrincipal: " + wrappedRequest.getUserPrincipal());
                            asyncContext.complete();
                        }
                        catch (Throwable t)
                        {
                            throw new RuntimeException(t);
                        }
                    });
                }
            }, "/");
        });

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("GET /ctx/authenticate HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("UserPrincipal: admin"));
    }

    @Test
    public void testAsyncDispatchWrappedRequest() throws Exception
    {
        configureServer(new TestHeaderAuthenticator(), servletContextHandler ->
        {
            servletContextHandler.addServlet(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
                {
                    if (req.getDispatcherType() ==  DispatcherType.ASYNC)
                    {
                        try
                        {
                            boolean authenticate = req.authenticate(resp);
                            if (authenticate)
                                resp.getWriter().println("UserPrincipal: " + req.getUserPrincipal());
                        }
                        catch (Throwable t)
                        {
                            throw new RuntimeException(t);
                        }
                        return;
                    }

                    // The request is wrapped to override some headers then async dispatched.
                    req = new HttpServletRequestWrapper(req)
                    {
                        @Override
                        public String getHeader(String name)
                        {
                            if ("X-Username".equalsIgnoreCase(name))
                                return "admin";
                            if ("X-Password".equalsIgnoreCase(name))
                                return "password";
                            return super.getHeader(name);
                        }
                    };
                    AsyncContext asyncContext = req.startAsync(req, resp);
                    asyncContext.dispatch();
                }
            }, "/");
        });

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("GET /ctx/authenticate HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("UserPrincipal: admin"));
    }

    @Test
    public void testAsyncNewThreadWrappedRequest() throws Exception
    {
        configureServer(new TestHeaderAuthenticator(), servletContextHandler ->
        {
            AtomicInteger scopeCount = new AtomicInteger();
            servletContextHandler.addEventListener(new ContextHandler.ContextScopeListener()
            {
                @Override
                public void enterScope(Context context, Request request)
                {
                    scopeCount.incrementAndGet();
                }

                @Override
                public void exitScope(Context context, Request request)
                {
                    scopeCount.decrementAndGet();
                }
            });
            servletContextHandler.addServlet(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
                {
                    // The request is wrapped to override some headers then async dispatched.
                    HttpServletRequest wrappedRequest = new HttpServletRequestWrapper(req)
                    {
                        @Override
                        public String getHeader(String name)
                        {
                            if ("X-Username".equalsIgnoreCase(name))
                                return "admin";
                            if ("X-Password".equalsIgnoreCase(name))
                                return "password";
                            return super.getHeader(name);
                        }
                    };

                    AsyncContext asyncContext = req.startAsync(wrappedRequest, resp);
                    new Thread(() ->
                    {
                        // Await exiting the scope.
                        Awaitility.await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).until(() -> scopeCount.get() == 0);
                        try
                        {
                            boolean authenticate = wrappedRequest.authenticate(resp);
                            if (authenticate)
                                resp.getWriter().println("UserPrincipal: " + wrappedRequest.getUserPrincipal());
                        }
                        catch (Throwable t)
                        {
                            throw new RuntimeException(t);
                        }
                        finally
                        {
                            asyncContext.complete();
                        }
                    }).start();
                }
            }, "/");
        });

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse("GET /ctx/authenticate HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("UserPrincipal: admin"));
    }

    @Test
    public void testGetUserPrincipal() throws Exception
    {
        configureServer(new BasicAuthenticator(), new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.getWriter().println("UserPrincipal: " + req.getUserPrincipal());
            }
        });
        String response;

        // No credentials results in null user principal.
        response = _connector.getResponse("GET /ctx/getUserPrincipal HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("UserPrincipal: null"));

        // Incorrect credentials also results in null user principal, but a 200 response.
        response = _connector.getResponse("GET /ctx/getUserPrincipal HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("admin", "bad_password")));
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("UserPrincipal: null"));

        // If we have correct credentials we will be able to get a valid user principal.
        response = _connector.getResponse("GET /ctx/getUserPrincipal HTTP/1.0\r\nAuthorization: %s\r\n\r\n".formatted(BasicAuthenticator.authorization("admin", "password")));
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("UserPrincipal: admin"));
    }

    @Test
    public void testDispatch() throws Exception
    {
        configureServer(new LoginAuthenticator()
        {
            @Override
            public String getAuthenticationType()
            {
                return "CUSTOM";
            }

            @Override
            public AuthenticationState validateRequest(Request request, Response response, Callback callback) throws ServerAuthException
            {
                Fields parameters = Request.extractQueryParameters(request);
                String username = parameters.getValue("username");
                String password = parameters.getValue("password");
                if (username != null && password != null)
                {
                    UserIdentity user = login(username, Credential.getCredential(password), request, response);
                    if (user == null)
                    {
                        if (response.isCommitted())
                            return null;
                        return AuthenticationState.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
                    }
                    return new UserAuthenticationSucceeded(getAuthenticationType(), user);
                }

                if (response.isCommitted())
                    return null;

                return new AuthenticationState.ServeAs(HttpURI.build(request.getHttpURI()).pathQuery("/login"));
            }
        }, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
            {
                String pathInContext = URIUtil.addPaths(req.getServletPath(), req.getPathInfo());
                if ("/login".equals(pathInContext))
                {
                    resp.setStatus(HttpStatus.OK_200);
                    resp.getWriter().println("this is the login page");
                    return;
                }

                boolean authenticate = req.authenticate(resp);
                if (!authenticate)
                    return;
                resp.getWriter().println("UserPrincipal: " + req.getUserPrincipal());
            }
        });
        String response;

        // No credentials result in a 302 redirect to the login page.
        response = _connector.getResponse("GET /ctx/ HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("this is the login page"));

        // Incorrect credentials also result in a 403 response.
        response = _connector.getResponse("GET /ctx/?username=admin&password=wrong HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 403 Forbidden"));

        // If we have correct credentials we will be able to get a valid user principal.
        response = _connector.getResponse("GET /ctx/?username=admin&password=password HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("UserPrincipal: admin"));
    }

    @Test
    public void testResponseCommitted() throws Exception
    {
        configureServer(new LoginAuthenticator()
        {
            @Override
            public String getAuthenticationType()
            {
                return "CUSTOM";
            }

            @Override
            public AuthenticationState validateRequest(Request request, Response response, Callback callback)
            {
                return null;
            }
        }, servletContextHandler ->
        {
            servletContextHandler.addServlet(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
                {
                    resp.getWriter().println("commiting response");
                    resp.getWriter().flush();

                    try
                    {
                        req.authenticate(resp);
                    }
                    catch (Throwable t)
                    {
                        t.printStackTrace(resp.getWriter());
                    }
                }
            }, "/");
        });
        String response;

        // ISE should be thrown because the response was already committed before calling authenticate.
        response = _connector.getResponse("GET /ctx/ HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("java.lang.IllegalStateException: Response committed"));
    }

    @Test
    public void testNoAuthenticationState() throws Exception
    {
        configureServer(new LoginAuthenticator()
        {
            @Override
            public String getAuthenticationType()
            {
                return "CUSTOM";
            }

            @Override
            public AuthenticationState validateRequest(Request request, Response response, Callback callback)
            {
                return null;
            }
        }, servletContextHandler ->
        {
            servletContextHandler.addServlet(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
                {
                    try
                    {
                        req.authenticate(resp);
                    }
                    catch (Throwable t)
                    {
                        t.printStackTrace(resp.getWriter());
                    }
                }
            }, "/");
        });
        String response;

        // ServletException is thrown if the authentication failed and the caller is responsible for handling the error.
        response = _connector.getResponse("GET /ctx/ HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("jakarta.servlet.ServletException: Authentication failed"));
    }
}
