//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.security.openid;

import java.util.Set;

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;

class OpenIdAuthConfiguration implements Authenticator.AuthConfiguration
{
    public static final String AUTHENTICATE_NEW_USERS_INIT_PARAM = "jetty.openid.authenticateNewUsers";

    private final Authenticator.AuthConfiguration _configuration;
    private final OpenIdLoginService _openIdLoginService;

    public OpenIdAuthConfiguration(OpenIdConfiguration openIdConfiguration, Authenticator.AuthConfiguration authConfiguration)
    {
        _configuration = authConfiguration;

        LoginService loginService = authConfiguration.getLoginService();
        if (loginService instanceof OpenIdLoginService)
            _openIdLoginService = (OpenIdLoginService)loginService;
        else
        {
            _openIdLoginService = new OpenIdLoginService(openIdConfiguration, loginService);
            if (loginService == null)
                _openIdLoginService.setIdentityService(authConfiguration.getIdentityService());

            String authNewUsers = authConfiguration.getInitParameter(AUTHENTICATE_NEW_USERS_INIT_PARAM);
            if (authNewUsers != null)
                _openIdLoginService.setAuthenticateNewUsers(Boolean.parseBoolean(authNewUsers));
        }
    }

    @Override
    public String getAuthMethod()
    {
        return _configuration.getAuthMethod();
    }

    @Override
    public String getRealmName()
    {
        return _configuration.getRealmName();
    }

    @Override
    public String getInitParameter(String param)
    {
        return _configuration.getInitParameter(param);
    }

    @Override
    public Set<String> getInitParameterNames()
    {
        return _configuration.getInitParameterNames();
    }

    @Override
    public LoginService getLoginService()
    {
        return _openIdLoginService;
    }

    @Override
    public IdentityService getIdentityService()
    {
        return _configuration.getIdentityService();
    }

    @Override
    public boolean isSessionRenewedOnAuthentication()
    {
        return _configuration.isSessionRenewedOnAuthentication();
    }
}
