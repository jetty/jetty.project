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
 * A Security Constraint.
 */
public interface Constraint
{
    enum Transport
    {
        CLEAR,
        INTEGRAL,
        CONFIDENTIAL;

        static Transport combine(Transport a, Transport b)
        {
            if (a == null)
                return b == null ? CLEAR : b;
            if (b == null)
                return a;

            return switch (b)
            {
                case CLEAR -> a;
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

    /**
     * @return The name for the {@code Constraint} or "unnamed@hashcode" if not named
     */
    String getName();

    /**
     * @return true if the {@code Constraint} forbids all access.
     */
    boolean isForbidden();

    /**
     * @return The {@link Transport} criteria applied by this {@code Constraint}.
     */
    Transport getUserData();

    /**
     * @return The {@link Authorization} criteria applied by this {@code Constraint}.
     */
    Authorization getAuthorization();

    /**
     * @return The set of roles applied by this {@code Constraint} or the empty set.
     */
    Set<String> getRoles();

    default Builder builder()
    {
        return new Builder(this);
    }

    class Builder
    {
        private String _name;
        private boolean _forbidden;
        private Transport _transport;
        private Authorization _authorization;
        private Set<String> _roles;

        public Builder()
        {}

        Builder(Constraint constraint)
        {
            _forbidden = constraint.isForbidden();
            _transport = constraint.getUserData();
            _authorization = constraint.getAuthorization();
            _roles = constraint.getRoles();
        }

        public Builder name(String name)
        {
            _name = name;
            return this;
        }

        public Builder forbidden(boolean forbidden)
        {
            _forbidden = forbidden;
            return this;
        }

        public Builder transport(Transport transport)
        {
            _transport = transport;
            return this;
        }

        public Builder authorization(Authorization authorization)
        {
            _authorization = authorization;
            return this;
        }

        public Builder roles(String... roles)
        {
            if (roles != null && roles.length > 0)
            {
                if (_roles == null)
                    _roles = new HashSet<>();
                else if (!(_roles instanceof HashSet<String>))
                    _roles = new HashSet<>(_roles);
                _roles.addAll(Arrays.asList(roles));
            }
            return this;
        }

        public Constraint build()
        {
            return from(_name, _forbidden, _transport, _authorization, _roles);
        }
    }

    Constraint NONE = from(false, Transport.CLEAR, Authorization.NONE);
    Constraint FORBIDDEN = from(true, null, null);
    Constraint INTEGRAL = from(false, Transport.INTEGRAL, null);
    Constraint CONFIDENTIAL = from(false, Transport.CONFIDENTIAL, null);
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
            Transport.combine(a.getUserData(), b.getUserData()),
            Authorization.combine(a.getAuthorization(), b.getAuthorization()),
            roles);
    }

    static Constraint from(String... roles)
    {
        return from(false, null, Authorization.AUTHENTICATED_IN_ROLE, roles);
    }

    static Constraint from(boolean forbidden, Transport transport, Authorization authorization, String... roles)
    {
        return from(forbidden, transport, authorization, (roles == null || roles.length == 0)
            ? Collections.emptySet()
            : new HashSet<>(Arrays.stream(roles).toList()));
    }

    static Constraint from(boolean forbidden, Transport transport, Authorization authorization, Set<String> roles)
    {
        return from(null, forbidden, transport, authorization, roles);
    }

    static Constraint from(String name, boolean forbidden, Transport transport, Authorization authorization, Set<String> roles)
    {
        Set<String> roleSet = roles == null
            ? Collections.emptySet()
            : Collections.unmodifiableSet(roles);

        return new Constraint()
        {
            @Override
            public String getName()
            {
                return name;
            }

            @Override
            public boolean isForbidden()
            {
                return forbidden;
            }

            @Override
            public Transport getUserData()
            {
                return transport == null ? Transport.CLEAR : transport;
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

            @Override
            public Builder builder()
            {
                return null;
            }
        };
    }
}

