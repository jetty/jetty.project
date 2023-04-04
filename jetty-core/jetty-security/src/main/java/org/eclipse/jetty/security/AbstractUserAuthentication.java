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

package org.eclipse.jetty.security;

import java.io.Serializable;

import org.eclipse.jetty.security.Authentication.User;
import org.eclipse.jetty.security.authentication.DeferredAuthentication;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * AbstractUserAuthentication
 *
 *
 * Base class for representing an authenticated user.
 */
public abstract class AbstractUserAuthentication implements User, Serializable
{
    private static final long serialVersionUID = -6290411814232723403L;
    protected String _method;
    protected transient UserIdentity _userIdentity;

    public AbstractUserAuthentication(String method, UserIdentity userIdentity)
    {
        _method = method;
        _userIdentity = userIdentity;
    }

    @Override
    public String getAuthMethod()
    {
        return _method;
    }

    @Override
    public UserIdentity getUserIdentity()
    {
        return _userIdentity;
    }

    @Override
    public boolean isUserInRole(String role)
    {
        return _userIdentity.isUserInRole(role);
    }

    @Override
    public void logout(Request request, Response response)
    {
        SecurityHandler security = SecurityHandler.getCurrentSecurityHandler();
        if (security != null)
        {
            security.logout(this);
            Authenticator authenticator = security.getAuthenticator();

            Authentication authentication = null;
            if (authenticator instanceof LoginAuthenticator loginAuthenticator)
            {
                ((LoginAuthenticator)authenticator).logout(request, response);
                authentication = new LoggedOutAuthentication(loginAuthenticator);
            }
            Authentication.setAuthentication(request, authentication);
        }
    }

    private static class LoggedOutAuthentication extends DeferredAuthentication
    {
        public LoggedOutAuthentication(LoginAuthenticator authenticator)
        {
            super(authenticator);
        }

        @Override
        public Authentication.User authenticate(Request request)
        {
            return null;
        }

        @Override
        public Authentication authenticate(Request request, Response response, Callback callback)
        {
            return null;
        }
    }
}
