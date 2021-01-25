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

import java.security.Principal;
import javax.security.auth.Subject;

import org.eclipse.jetty.server.UserIdentity;

public class SpnegoUserIdentity implements UserIdentity
{
    private final Subject _subject;
    private final Principal _principal;
    private final UserIdentity _roleDelegate;

    public SpnegoUserIdentity(Subject subject, Principal principal, UserIdentity roleDelegate)
    {
        _subject = subject;
        _principal = principal;
        _roleDelegate = roleDelegate;
    }

    @Override
    public Subject getSubject()
    {
        return _subject;
    }

    @Override
    public Principal getUserPrincipal()
    {
        return _principal;
    }

    @Override
    public boolean isUserInRole(String role, Scope scope)
    {
        return _roleDelegate != null && _roleDelegate.isUserInRole(role, scope);
    }

    public boolean isEstablished()
    {
        return _roleDelegate != null;
    }
}
