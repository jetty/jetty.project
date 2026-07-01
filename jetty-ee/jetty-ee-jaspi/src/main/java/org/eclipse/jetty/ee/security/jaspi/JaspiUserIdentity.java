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

package org.eclipse.jetty.ee.security.jaspi;

import java.security.Principal;
import java.util.Collections;
import java.util.HashSet;
import javax.security.auth.Subject;

import org.eclipse.jetty.security.UserIdentity;

public class JaspiUserIdentity implements UserIdentity
{
    private final Subject _subject;
    private final HashSet<String> _roles = new HashSet<>();
    private Principal _userPrincipal;
    private UserIdentity _userIdentity;

    public JaspiUserIdentity(Subject subject)
    {
        _subject = subject;
    }

    @Override
    public Subject getSubject()
    {
        return _subject;
    }

    public void setWrapped(UserIdentity userIdentity)
    {
        _userIdentity = userIdentity;
    }

    public UserIdentity getWrapped()
    {
        return _userIdentity;
    }

    public void setUserPrincipal(Principal userPrincipal)
    {
        _userPrincipal = userPrincipal;
    }

    @Override
    public Principal getUserPrincipal()
    {
        return _userPrincipal;
    }

    public void addRoles(String[] roles)
    {
        if (roles == null)
            return;
        Collections.addAll(_roles, roles);
    }

    @Override
    public boolean isUserInRole(String role)
    {
        return _roles.contains(role) || (_userIdentity != null && _userIdentity.isUserInRole(role));
    }

    @Override
    public String toString()
    {
        return JaspiUserIdentity.class.getSimpleName() + "('" + _userPrincipal + "')";
    }
}
