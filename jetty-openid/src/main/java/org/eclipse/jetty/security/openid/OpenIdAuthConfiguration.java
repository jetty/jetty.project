//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.security.openid;

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.WrappedAuthConfiguration;

public class OpenIdAuthConfiguration extends WrappedAuthConfiguration
{
    public static final String AUTHENTICATE_NEW_USERS_INIT_PARAM = "jetty.openid.authenticateNewUsers";

    private final OpenIdLoginService _openIdLoginService;

    public OpenIdAuthConfiguration(OpenIdConfiguration openIdConfiguration, Authenticator.AuthConfiguration authConfiguration)
    {
        super(authConfiguration);

        LoginService loginService = authConfiguration.getLoginService();
        if (loginService instanceof OpenIdLoginService)
        {
            _openIdLoginService = (OpenIdLoginService)loginService;
        }
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
    public LoginService getLoginService()
    {
        return _openIdLoginService;
    }
}
