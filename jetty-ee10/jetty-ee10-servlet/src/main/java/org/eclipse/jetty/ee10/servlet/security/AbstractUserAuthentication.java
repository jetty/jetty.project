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

import java.io.Serializable;
import java.util.Set;

import jakarta.servlet.ServletRequest;
import org.eclipse.jetty.ee10.servlet.security.Authentication.User;
import org.eclipse.jetty.ee10.servlet.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Request;

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
        //Servlet Spec 3.1 pg 125 if testing special role **
        if ("**".equals(role.trim()))
        {
            //if ** is NOT a declared role name, the we return true 
            //as the user is authenticated. If ** HAS been declared as a
            //role name, then we have to check if the user has that role
            if (!declaredRolesContains("**"))
                return true;
            else
                return _userIdentity.isUserInRole(role);
        }

        return _userIdentity.isUserInRole(role);
    }

    public boolean declaredRolesContains(String roleName)
    {
        SecurityHandler security = SecurityHandler.getCurrentSecurityHandler();
        if (security == null)
            return false;

        if (security instanceof ConstraintAware)
        {
            Set<String> declaredRoles = ((ConstraintAware)security).getRoles();
            return (declaredRoles != null) && declaredRoles.contains(roleName);
        }

        return false;
    }

    @Override
    public Authentication logout(Request request)
    {
        SecurityHandler security = SecurityHandler.getCurrentSecurityHandler();
        if (security != null)
        {
            security.logout(this);
            Authenticator authenticator = security.getAuthenticator();
            if (authenticator instanceof LoginAuthenticator)
            {
                ((LoginAuthenticator)authenticator).logout(request);
                return new LoggedOutAuthentication((LoginAuthenticator)authenticator);
            }
        }

        return Authentication.UNAUTHENTICATED;
    }
}
