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
import java.util.Set;
import javax.security.auth.Subject;

import org.eclipse.jetty.security.UserIdentity;

/**
 * <p>The {@link UserIdentity} of a caller a module establishes, built by {@link ServletCallbackHandler}
 * from the callbacks the module issued during {@code validateRequest} and not changed after that.</p>
 * <p>The {@link Subject} it is built for is not part of the identity and remains mutable, as the
 * generic {@code validateRequest} contract requires the {@code clientSubject} not to be
 * read-only.</p>
 */
public class JaspiUserIdentity implements UserIdentity
{
    private final Subject _subject;
    private final Principal _userPrincipal;
    private final Set<String> _roles;
    private final boolean _groupsEstablished;

    JaspiUserIdentity(Subject subject, Principal userPrincipal, Set<String> roles, boolean groupsEstablished)
    {
        _subject = subject;
        _userPrincipal = userPrincipal;
        _roles = Set.copyOf(roles);
        _groupsEstablished = groupsEstablished;
    }

    /**
     * @return whether the group principals of this caller were established, which a
     * {@code GroupPrincipalCallback} does even when the groups it establishes are none.
     */
    boolean hasEstablishedGroups()
    {
        return _groupsEstablished;
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
        return _roles.contains(role);
    }

    @Override
    public String[] getRoles()
    {
        return _roles.toArray(new String[0]);
    }

    @Override
    public String toString()
    {
        return JaspiUserIdentity.class.getSimpleName() + "('" + _userPrincipal + "')";
    }
}
