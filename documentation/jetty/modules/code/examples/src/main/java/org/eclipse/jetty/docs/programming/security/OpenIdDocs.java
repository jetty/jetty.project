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

package org.eclipse.jetty.docs.programming.security;

import java.io.PrintStream;
import java.security.Principal;
import java.util.Map;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.openid.OpenIdAuthenticator;
import org.eclipse.jetty.security.openid.OpenIdConfiguration;
import org.eclipse.jetty.security.openid.OpenIdLoginService;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;

@SuppressWarnings("unused")
public class OpenIdDocs
{
    public void combinedExample()
    {
        Server server = new Server();
        // tag::openIdConfigExample[]
        // To create an OpenIdConfiguration you can rely on discovery of the OIDC metadata.
        OpenIdConfiguration openIdConfig = new OpenIdConfiguration(
            "https://example.com/issuer",           // ISSUER
            "my-client-id",                         // CLIENT_ID
            "my-client-secret"                     // CLIENT_SECRET
        );

        // Or you can specify the full OpenID configuration manually.
        openIdConfig = new OpenIdConfiguration(
            "https://example.com/issuer",           // ISSUER
            "https://example.com/token",            // TOKEN_ENDPOINT
            "https://example.com/auth",             // AUTH_ENDPOINT
            "https://example.com/logout",           // END_SESSION_ENDPOINT
            "my-client-id",                         // CLIENT_ID
            "my-client-secret",                     // CLIENT_SECRET
            "client_secret_post",                   // AUTH_METHOD (e.g., client_secret_post, client_secret_basic)
            new HttpClient()                        // HttpClient instance
        );

        // The specific security handler implementation will change depending on whether you are using EE8/EE9/EE10/EE11 or Jetty Core API.
        SecurityHandler.PathMapped securityHandler = new SecurityHandler.PathMapped();
        server.insertHandler(securityHandler);
        securityHandler.put("/auth/*", Constraint.ANY_USER);

        // A nested LoginService is optional and used to specify known users with defined roles.
        // This can be any instance of LoginService and is not restricted to be a HashLoginService.
        HashLoginService nestedLoginService = new HashLoginService();
        UserStore userStore = new UserStore();
        userStore.addUser("<admin-user-subject-identifier>", null, new String[]{"admin"});
        nestedLoginService.setUserStore(userStore);

        // Optional configuration to allow new users not listed in the nested LoginService to be authenticated.
        openIdConfig.setAuthenticateNewUsers(true);

        // An OpenIdLoginService should be used which can optionally wrap the nestedLoginService to support roles.
        LoginService loginService = new OpenIdLoginService(openIdConfig, nestedLoginService);
        securityHandler.setLoginService(loginService);

        // Configure an OpenIdAuthenticator.
        securityHandler.setAuthenticator(new OpenIdAuthenticator(openIdConfig,
            "/j_security_check", // The path where the OIDC provider redirects back to Jetty.
            "/error", // Optional page where authentication errors are redirected.
            "/logoutRedirect" // Optional page where the user is redirected to this page after logout.
        ));

        // Session handler is required for OpenID authentication.
        server.insertHandler(new SessionHandler());
        // end::openIdConfigExample[]

        // tag::openIdUsageExample[]
        class OpenIdExampleHandler extends Handler.Abstract
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                PrintStream writer = new PrintStream(Content.Sink.asOutputStream(response));

                String pathInContext = Request.getPathInContext(request);
                if (pathInContext.startsWith("/error"))
                {
                    // Handle requests to the error page which may have an error description parameter.
                    Fields parameters = Request.getParameters(request);
                    writer.println("error_description: " + parameters.get("error_description_jetty") + "<br>");
                }
                else
                {
                    Principal userPrincipal = AuthenticationState.getUserPrincipal(request);
                    writer.println("userPrincipal: " + userPrincipal);
                    if (userPrincipal != null)
                    {
                        // You can access the full openid claims for an authenticated session.
                        Session session = request.getSession(false);
                        @SuppressWarnings("unchecked")
                        Map<String, String> claims = (Map<String, String>)session.getAttribute("org.eclipse.jetty.security.openid.claims");
                        writer.println("claims: " + claims);
                        writer.println("name: " + claims.get("name"));
                        writer.println("sub: " + claims.get("sub"));
                    }
                }

                writer.close();
                callback.succeeded();
                return true;
            }
        }
        // end::openIdUsageExample[]
    }
}
