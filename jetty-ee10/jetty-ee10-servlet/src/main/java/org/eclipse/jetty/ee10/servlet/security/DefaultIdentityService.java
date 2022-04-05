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
 * Default Identity Service implementation.
 * This service handles only role reference maps passed in an
 * associated {@link UserIdentity.Scope}.  If there are roles
 * refs present, then associate will wrap the UserIdentity with one
 * that uses the role references in the
 * {@link UserIdentity#isUserInRole(String, UserIdentity.Scope)}
 * implementation. All other operations are effectively noops.
 */
public class DefaultIdentityService implements IdentityService
{

    public DefaultIdentityService()
    {
    }

    /**
     * If there are roles refs present in the scope, then wrap the UserIdentity
     * with one that uses the role references in the {@link UserIdentity#isUserInRole(String, UserIdentity.Scope)}
     */
    @Override
    public Object associate(UserIdentity user)
    {
        return null;
    }

    @Override
    public void disassociate(Object previous)
    {
    }

    @Override
    public Object setRunAs(UserIdentity user, RunAsToken token)
    {
        return token;
    }

    @Override
    public void unsetRunAs(Object lastToken)
    {
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
}
