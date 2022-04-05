//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.servlet.security;

import java.util.Set;

import org.eclipse.jetty.ee10.security.Authenticator.AuthConfiguration;

/**
 * A wrapper for {@link AuthConfiguration}. This allows you create a new AuthConfiguration which can
 * override a method to change a value from an another instance of AuthConfiguration.
 */
public class WrappedAuthConfiguration implements AuthConfiguration
{
    private final AuthConfiguration _configuration;

    public WrappedAuthConfiguration(AuthConfiguration configuration)
    {
        _configuration = configuration;
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
        return _configuration.getLoginService();
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
