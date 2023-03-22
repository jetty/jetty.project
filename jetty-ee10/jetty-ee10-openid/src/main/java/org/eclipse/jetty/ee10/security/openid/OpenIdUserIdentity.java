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

package org.eclipse.jetty.ee10.security.openid;

import java.security.Principal;
import javax.security.auth.Subject;

import org.eclipse.jetty.ee10.servlet.security.UserIdentity;

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
    public boolean isUserInRole(String role)
    {
        return userIdentity != null && userIdentity.isUserInRole(role);
    }
}
