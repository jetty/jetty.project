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

package org.eclipse.jetty.security.openid;

import java.security.Principal;
import javax.security.auth.Subject;

import org.eclipse.jetty.server.UserIdentity;

public class OpenIdUserIdentity implements UserIdentity
{
    private final Subject subject;
    private final Principal userPrincipal;
    private final UserIdentity userIdentity;

    public OpenIdUserIdentity(Subject subject, Principal userPrincipal, UserIdentity userIdentity)
    {
        this.subject = subject;
        this.userPrincipal = userPrincipal;
        this.userIdentity = userIdentity;
    }

    @Override
    public Subject getSubject()
    {
        return subject;
    }

    @Override
    public Principal getUserPrincipal()
    {
        return userPrincipal;
    }

    @Override
    public boolean isUserInRole(String role, Scope scope)
    {
        return userIdentity != null && userIdentity.isUserInRole(role, scope);
    }
}
