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
