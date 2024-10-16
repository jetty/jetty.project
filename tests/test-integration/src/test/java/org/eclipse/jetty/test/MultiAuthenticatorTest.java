package org.eclipse.jetty.test;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.security.AnyUserLoginService;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.MultiAuthenticator;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.security.openid.OpenIdAuthenticator;
import org.eclipse.jetty.security.openid.OpenIdConfiguration;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.tests.OpenIdProvider;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MultiAuthenticatorTest
{
    private Server _server;
    private ServerConnector _connector;
    private HttpClient _client;
    private OpenIdProvider _provider;

    @BeforeEach
    public void before() throws Exception
    {
        // Set up a local OIDC provider and add its configuration to the Server.
        _provider = new OpenIdProvider();
        _provider.start();

        _server = new Server();
        _connector = new ServerConnector(_server);
        _connector.setPort(8080); // TODO: remove.
        _server.addConnector(_connector);

        OpenIdConfiguration config = new OpenIdConfiguration(_provider.getProvider(), _provider.getClientId(), _provider.getClientSecret());
        _server.addBean(config);

        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        securityHandler.put("", Constraint.ALLOWED);
        securityHandler.put("/logout", Constraint.ALLOWED);
        securityHandler.put("/", Constraint.ANY_USER);
        securityHandler.setHandler(new AuthTestHandler());

        MultiAuthenticator multiAuthenticator = new MultiAuthenticator();

        OpenIdAuthenticator openIdAuthenticator = new OpenIdAuthenticator(config, "/error");
        openIdAuthenticator.setRedirectPath("/redirect_path");
        openIdAuthenticator.setLogoutRedirectPath("/");
        multiAuthenticator.addAuthenticator("/login/openid", openIdAuthenticator);

        Path fooPropsFile = MavenTestingUtils.getTestResourcePathFile("user.properties");
        Resource fooResource = ResourceFactory.root().newResource(fooPropsFile);
        HashLoginService loginService = new HashLoginService("users", fooResource);
        _server.addBean(loginService);
        FormAuthenticator formAuthenticator = new FormAuthenticator("/login/form", "/error", false);
        formAuthenticator.setLoginService(loginService);
        multiAuthenticator.addAuthenticator("/login/form", formAuthenticator);

        securityHandler.setAuthenticator(multiAuthenticator);
        securityHandler.setLoginService(new AnyUserLoginService(_provider.getProvider(), null));
        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setHandler(securityHandler);
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/");
        contextHandler.setHandler(sessionHandler);

        _server.setHandler(contextHandler);
        _server.start();
        String redirectUri = "http://localhost:" + _connector.getLocalPort() + "/redirect_path";
        _provider.addRedirectUri(redirectUri);

        _client = new HttpClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void test() throws Exception
    {
        _server.join();
    }

    @Test
    public void test2() throws Exception
    {
        _server.join();
    }

    private static class AuthTestHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            String pathInContext = Request.getPathInContext(request);
            if (pathInContext.startsWith("/error"))
                return onError(request, response, callback);
            else if (pathInContext.startsWith("/logout"))
                return onLogout(request, response, callback);
            else if (pathInContext.startsWith("/login/form"))
                return onFormLogin(request, response, callback);

            try (PrintWriter writer = new PrintWriter(Content.Sink.asOutputStream(response)))
            {
                AuthenticationState authenticationState = AuthenticationState.getAuthenticationState(request);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/html");
                writer.println("<b>authState: " + authenticationState + "</b><br>");
                if (authenticationState instanceof AuthenticationState.Deferred deferred)
                {
                    AuthenticationState.Succeeded succeeded = deferred.authenticate(request);
                    if (succeeded != null)
                        writer.println("<b>userPrincipal: " + succeeded.getUserPrincipal() + "</b><br>");
                    else
                        writer.println("<b>userPrincipal: null</b><br>");
                }
                else if (authenticationState != null)
                {
                    writer.println("<b>userPrincipal: " + authenticationState.getUserPrincipal() + "</b><br>");
                }

                Session session = request.getSession(true);
                @SuppressWarnings("unchecked")
                Map<String, Object> claims = (Map<String, Object>)session.getAttribute(OpenIdAuthenticator.CLAIMS);
                if (claims != null)
                {
                    writer.printf("""
                        <br><b>Authenticated with OpenID</b><br>
                        userId: %s<br>
                        name: %s<br>
                        email: %s<br>
                        """, claims.get("sub"), claims.get("name"), claims.get("email"));
                }

                writer.println("""
                    <a href="/login/openid">OpenID Login</a><br>
                    <a href="/login/form">Form Login</a><br>
                    <a href="/logout">Logout</a><br>
                    """);
            }

            callback.succeeded();
            return true;
        }

        private boolean onFormLogin(Request request, Response response, Callback callback) throws Exception
        {
            String content = """
                    <h2>Login</h2>
                    <form action="j_security_check" method="POST">
                        <div>
                            <label for="username">Username:</label>
                            <input type="text" id="username" name="j_username" required>
                        </div>
                        <div>
                            <label for="password">Password:</label>
                            <input type="password" id="password" name="j_password" required>
                        </div>
                        <div>
                            <button type="submit">Login</button>
                        </div>
                    </form>
                    """;
            response.write(true, BufferUtil.toBuffer(content), callback);
            return true;
        }

        private boolean onLogout(Request request, Response response, Callback callback) throws Exception
        {
            Request.AuthenticationState authState = Request.getAuthenticationState(request);
            if (authState instanceof AuthenticationState.Succeeded succeeded)
                succeeded.logout(request, response);
            else if (authState instanceof AuthenticationState.Deferred deferred)
                deferred.logout(request, response);
            else
                request.getSession(true).invalidate();

            if (!response.isCommitted())
                Response.sendRedirect(request, response, callback, "/");
            else
                callback.succeeded();
            return true;
        }

        private boolean onError(Request request, Response response, Callback callback) throws Exception
        {
            Fields parameters = Request.getParameters(request);
            String errorDescription = parameters.getValue("error_description_jetty");
            response.write(true, BufferUtil.toBuffer("error: " + errorDescription), callback);
            return true;
        }
    }
}
