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

/**
 * The default {@link IdentityService}, which creates and uses {@link DefaultUserIdentity}s.
 * The {@link #associate(UserIdentity, RunAsToken)} method ignores the
 * {@code user}, but will associate the {@link RunAsToken} with the current thread
 * until {@link Association#close()} is called.
 */
public class DefaultIdentityService implements IdentityService
{
    private static final ThreadLocal<String> runAsRole = new ThreadLocal<>();
    private static final Association NOOP = () -> {};
    private static final Association CLEAR_RUN_AS = () -> runAsRole.set(null);

    public static boolean isRoleAssociated(String role)
    {
        return role != null && role.equals(runAsRole.get());
    }

    @Override
    public Association associate(UserIdentity user, RunAsToken runAsToken)
    {
        if (runAsToken instanceof RoleRunAsToken roleRunAsToken)
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
    public void onLogout(UserIdentity user)
    {
        runAsRole.set(null);
    }

    @Override
    public RunAsToken newRunAsToken(String roleName)
    {
        return new RoleRunAsToken(roleName);
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
}
