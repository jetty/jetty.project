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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A Security Constraint interface.
 */
public interface Constraint
{
    boolean isForbidden();

    UserData getUserData();

    Authorization getAuthorization();

    Set<String> getRoles();

    enum UserData
    {
        NONE,
        INTEGRAL,
        CONFIDENTIAL;

        static UserData combine(UserData a, UserData b)
        {
            if (a == null)
                return b == null ? NONE : b;
            if (b == null)
                return a;

            return switch (b)
            {
                case NONE -> a;
                case INTEGRAL -> a == CONFIDENTIAL ? CONFIDENTIAL : INTEGRAL;
                case CONFIDENTIAL -> CONFIDENTIAL;
            };
        }
    }

    enum Authorization
    {
        NONE,
        AUTHENTICATED,
        AUTHENTICATED_IN_KNOWN_ROLE,
        AUTHENTICATED_IN_ROLE;

        static Authorization combine(Authorization a, Authorization b)
        {
            if (a == null)
                return b == null ? NONE : b;
            if (b == null)
                return a;

            return switch (b)
            {
                case NONE -> a;
                case AUTHENTICATED -> a == NONE ? AUTHENTICATED : a;
                case AUTHENTICATED_IN_KNOWN_ROLE -> a == AUTHENTICATED_IN_ROLE ? AUTHENTICATED_IN_ROLE : AUTHENTICATED_IN_KNOWN_ROLE;
                case AUTHENTICATED_IN_ROLE -> AUTHENTICATED_IN_ROLE;
            };
        }
    }

    Constraint NONE = from(false, UserData.NONE, Authorization.NONE);
    Constraint FORBIDDEN = from(true, null, null);
    Constraint INTEGRAL = from(false, UserData.INTEGRAL, null);
    Constraint CONFIDENTIAL = from(false, UserData.CONFIDENTIAL, null);
    Constraint AUTHENTICATED = from(false, null, Authorization.AUTHENTICATED);
    Constraint AUTHENTICATED_IN_KNOWN_ROLE = from(false, null, Authorization.AUTHENTICATED_IN_KNOWN_ROLE);

    static Constraint combine(Constraint a, Constraint b)
    {
        if (a == null)
            return b == null ? NONE : b;
        if (b == null)
            return a;

        Set<String> roles = a.getRoles();
        if (roles == null)
            roles = b.getRoles();
        else if (b.getRoles() != null || b.getRoles() != null)
            roles = Stream.concat(roles.stream(), b.getRoles().stream()).collect(Collectors.toSet());

        return from(
            a.isForbidden() || b.isForbidden(),
            UserData.combine(a.getUserData(), b.getUserData()),
            Authorization.combine(a.getAuthorization(), b.getAuthorization()),
            roles);
    }

    static Constraint roles(String... roles)
    {
        return from(false, null, Authorization.AUTHENTICATED_IN_ROLE, roles);
    }

    static Constraint from(boolean forbidden, UserData userData, Authorization authorization, String... roles)
    {
        return from(forbidden, userData, authorization, (roles == null || roles.length == 0)
            ? Collections.emptySet()
            : new HashSet<>(Arrays.stream(roles).toList()));
    }

    static Constraint from(boolean forbidden, UserData userData, Authorization authorization, Set<String> roles)
    {
        Set<String> roleSet = roles == null
            ? Collections.emptySet()
            : Collections.unmodifiableSet(roles);

        return new Constraint()
        {
            @Override
            public boolean isForbidden()
            {
                return forbidden;
            }

            @Override
            public UserData getUserData()
            {
                return userData == null ? UserData.NONE : userData;
            }

            @Override
            public Authorization getAuthorization()
            {
                return authorization == null ? Authorization.NONE : authorization;
            }

            @Override
            public Set<String> getRoles()
            {
                return roleSet;
            }
        };
    }
}

