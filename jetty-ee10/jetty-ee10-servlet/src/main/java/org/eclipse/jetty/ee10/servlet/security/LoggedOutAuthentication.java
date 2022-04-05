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

import jakarta.servlet.ServletRequest;
import org.eclipse.jetty.ee10.handler.Authentication;
import org.eclipse.jetty.ee10.handler.UserIdentity;
import org.eclipse.jetty.ee10.security.authentication.LoginAuthenticator;

/**
 * LoggedOutAuthentication
 *
 * An Authentication indicating that a user has been previously, but is not currently logged in,
 * but may be capable of logging in after a call to Request.login(String,String)
 */
public class LoggedOutAuthentication implements Authentication.NonAuthenticated
{
    private LoginAuthenticator _authenticator;

    public LoggedOutAuthentication(LoginAuthenticator authenticator)
    {
        _authenticator = authenticator;
    }

    @Override
    public Authentication login(String username, Object password, ServletRequest request)
    {
        if (username == null)
            return null;

        UserIdentity identity = _authenticator.login(username, password, request);
        if (identity != null)
        {
            IdentityService identityService = _authenticator.getLoginService().getIdentityService();
            UserAuthentication authentication = new UserAuthentication("API", identity);
            if (identityService != null)
                identityService.associate(identity);
            return authentication;
        }
        return null;
    }
}
