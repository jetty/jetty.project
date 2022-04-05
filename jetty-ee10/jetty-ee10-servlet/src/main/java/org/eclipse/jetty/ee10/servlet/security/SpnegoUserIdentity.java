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
