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

package org.eclipse.jetty.ee10.security.openid;

import org.eclipse.jetty.ee10.servlet.security.Authenticator;
import org.eclipse.jetty.ee10.servlet.security.LoginService;
import org.eclipse.jetty.ee10.servlet.security.WrappedAuthConfiguration;

/**
 * <p>This class is used to wrap the {@link Authenticator.AuthConfiguration} given to the {@link OpenIdAuthenticator}.</p>
 * <p>When {@link #getLoginService()} method is called, this implementation will always return an instance of
 * {@link OpenIdLoginService}. This allows you to configure an {@link OpenIdAuthenticator} using a {@code null}
 * LoginService or any alternative LoginService implementation which will be wrapped by the OpenIdLoginService</p>
 */
public class OpenIdAuthConfiguration extends WrappedAuthConfiguration
{
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
        }
    }

    @Override
    public LoginService getLoginService()
    {
        return _openIdLoginService;
    }
}
