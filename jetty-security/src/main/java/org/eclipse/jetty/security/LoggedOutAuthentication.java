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

package org.eclipse.jetty.security;

import javax.servlet.ServletRequest;

import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.UserIdentity;

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
