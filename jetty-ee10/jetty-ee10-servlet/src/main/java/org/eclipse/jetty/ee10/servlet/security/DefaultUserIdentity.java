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

import java.security.Principal;
import javax.security.auth.Subject;

import org.eclipse.jetty.ee10.handler.UserIdentity;

/**
 * The default implementation of UserIdentity.
 */
public class DefaultUserIdentity implements UserIdentity
{
    private final Subject _subject;
    private final Principal _userPrincipal;
    private final String[] _roles;

    public DefaultUserIdentity(Subject subject, Principal userPrincipal, String[] roles)
    {
        _subject = subject;
        _userPrincipal = userPrincipal;
        _roles = roles;
    }

    @Override
    public Subject getSubject()
    {
        return _subject;
    }

    @Override
    public Principal getUserPrincipal()
    {
        return _userPrincipal;
    }

    @Override
    public boolean isUserInRole(String role, Scope scope)
    {
        //Servlet Spec 3.1, pg 125
        if ("*".equals(role))
            return false;

        String roleToTest = null;
        if (scope != null && scope.getRoleRefMap() != null)
            roleToTest = scope.getRoleRefMap().get(role);

        //Servlet Spec 3.1, pg 125
        if (roleToTest == null)
            roleToTest = role;

        for (String r : _roles)
        {
            if (r.equals(roleToTest))
                return true;
        }
        return false;
    }

    @Override
    public String toString()
    {
        return DefaultUserIdentity.class.getSimpleName() + "('" + _userPrincipal + "')";
    }
}
