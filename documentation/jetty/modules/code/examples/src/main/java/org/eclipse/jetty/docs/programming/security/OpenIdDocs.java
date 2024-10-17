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

import java.util.Map;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.openid.OpenIdAuthenticator;
import org.eclipse.jetty.security.openid.OpenIdConfiguration;
import org.eclipse.jetty.security.openid.OpenIdLoginService;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.security.Credential;

public class OpenIdDocs
{
    private static final String ISSUER = "";
    private static final String CLIENT_ID = "";
    private static final String CLIENT_SECRET = "";
    private static final String TOKEN_ENDPOINT = "";
    private static final String AUTH_ENDPOINT = "";
    private static final String END_SESSION_ENDPOINT = "";
    private static final String AUTH_METHOD = "";
    private static final HttpClient httpClient = new HttpClient();

    private OpenIdConfiguration openIdConfig;
    private SecurityHandler securityHandler;

    public void createConfigurationWithDiscovery()
    {
        // tag::createConfigurationWithDiscovery[]
        OpenIdConfiguration openIdConfig = new OpenIdConfiguration(ISSUER, CLIENT_ID, CLIENT_SECRET);
        // end::createConfigurationWithDiscovery[]
    }

    public void createConfiguration()
    {
        // tag::createConfiguration[]
        OpenIdConfiguration openIdConfig = new OpenIdConfiguration(ISSUER, TOKEN_ENDPOINT, AUTH_ENDPOINT, END_SESSION_ENDPOINT,
            CLIENT_ID, CLIENT_SECRET, AUTH_METHOD, httpClient);
        // end::createConfiguration[]
    }

    public void configureLoginService()
    {
        // tag::configureLoginService[]
        LoginService loginService = new OpenIdLoginService(openIdConfig);
        securityHandler.setLoginService(loginService);
        // end::configureLoginService[]
    }

    public void configureAuthenticator()
    {
        // tag::configureAuthenticator[]
        Authenticator authenticator = new OpenIdAuthenticator(openIdConfig, "/error");
        securityHandler.setAuthenticator(authenticator);
        // end::configureAuthenticator[]
    }

    @SuppressWarnings("unchecked")
    public void accessClaims()
    {
        Request request = new Request.Wrapper(null);

        // tag::accessClaims[]
        Map<String, Object> claims = (Map<String, Object>)request.getSession(true).getAttribute("org.eclipse.jetty.security.openid.claims");
        String userId = (String)claims.get("sub");

        Map<String, Object> response = (Map<String, Object>)request.getSession(true).getAttribute("org.eclipse.jetty.security.openid.response");
        String accessToken = (String)response.get("access_token");
        // tag::accessClaims[]
    }

    public void wrappedLoginService()
    {
        // tag::wrappedLoginService[]
        // Use the optional LoginService for Roles.
        LoginService wrappedLoginService = createWrappedLoginService();
        LoginService loginService = new OpenIdLoginService(openIdConfig, wrappedLoginService);
        // end::wrappedLoginService[]
    }

    private LoginService createWrappedLoginService()
    {
        HashLoginService loginService = new HashLoginService();
        UserStore userStore = new UserStore();
        userStore.addUser("admin", Credential.getCredential("password"), new String[]{"admin"});
        loginService.setUserStore(userStore);
        loginService.setName(ISSUER);
        return loginService;
    }
}



