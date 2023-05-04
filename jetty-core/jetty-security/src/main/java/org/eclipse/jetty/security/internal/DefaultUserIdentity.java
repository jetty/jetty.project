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

package org.eclipse.jetty.security.internal;

import java.security.Principal;
import javax.security.auth.Subject;

import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.UserIdentity;

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
    public boolean isUserInRole(String role)
    {
        if (DefaultIdentityService.isRoleAssociated(role))
            return true;

        for (String r : _roles)
        {
            if (r.equals(role))
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
