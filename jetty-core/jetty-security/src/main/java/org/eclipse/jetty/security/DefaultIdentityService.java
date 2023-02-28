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

import java.security.Principal;
import javax.security.auth.Subject;

import org.eclipse.jetty.security.internal.DefaultUserIdentity;
import org.eclipse.jetty.security.internal.RoleRunAsToken;
import org.eclipse.jetty.security.internal.RunAsToken;

/**
 *
 */
public class DefaultIdentityService implements IdentityService
{
    private static final ThreadLocal<String> runAsRole = new ThreadLocal<>();

    public static boolean isRoleAssociated(String role)
    {
        return role != null && role.equals(runAsRole.get());
    }

    @Override
    public Association associate(UserIdentity user)
    {
        return NOOP;
    }

    @Override
    public Association associate(UserIdentity user, Object token)
    {
        if (token instanceof RoleRunAsToken roleRunAsToken)
        {
            String oldAssociate = runAsRole.get();
            runAsRole.set(roleRunAsToken.getRunAsRole());
            if (oldAssociate == null)
                return CLEAR_RUN_AS;
            return () -> runAsRole.set(oldAssociate);
        }
        return NOOP;
    }

    @Override
    public void logout(UserIdentity user)
    {
        runAsRole.set(null);
    }

    @Override
    public RunAsToken newRunAsToken(String runAsName)
    {
        return new RoleRunAsToken(runAsName);
    }

    @Override
    public UserIdentity getSystemUserIdentity()
    {
        return null;
    }

    @Override
    public UserIdentity newUserIdentity(final Subject subject, final Principal userPrincipal, final String[] roles)
    {
        return new DefaultUserIdentity(subject, userPrincipal, roles);
    }

    private static final Association NOOP = () -> {};
    private static final Association CLEAR_RUN_AS = () -> runAsRole.set(null);
}
