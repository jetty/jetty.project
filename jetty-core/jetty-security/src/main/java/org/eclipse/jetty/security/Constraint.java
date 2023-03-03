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
        REQUIRE_NONE,
        REQUIRE_INTEGRAL,
        REQUIRE_CONFIDENTIAL;

        static Transport combine(Transport a, Transport b)
        {
            if (a == null)
                return b == null ? REQUIRE_NONE : b;
            if (b == null)
                return a;

            return switch (b)
            {
                case REQUIRE_NONE -> a;
                case REQUIRE_INTEGRAL -> a == REQUIRE_CONFIDENTIAL ? REQUIRE_CONFIDENTIAL : REQUIRE_INTEGRAL;
                case REQUIRE_CONFIDENTIAL -> REQUIRE_CONFIDENTIAL;
            };
        }
    }

    enum Authentication
    {
        REQUIRE_NONE,
        REQUIRE,
        REQUIRE_KNOWN_ROLE,
        REQUIRE_SPECIFIC_ROLE;

        static Authentication combine(Authentication a, Authentication b)
        {
            if (a == null)
                return b == null ? REQUIRE_NONE : b;
            if (b == null)
                return a;

            return switch (b)
            {
                case REQUIRE_NONE -> a;
                case REQUIRE -> a == REQUIRE_NONE ? REQUIRE : a;
                case REQUIRE_KNOWN_ROLE -> a == REQUIRE_SPECIFIC_ROLE ? REQUIRE_SPECIFIC_ROLE : REQUIRE_KNOWN_ROLE;
                case REQUIRE_SPECIFIC_ROLE -> REQUIRE_SPECIFIC_ROLE;
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
    Transport getTransport();

    /**
     * @return The {@link Authentication} criteria applied by this {@code Constraint}.
     */
    Authentication getAuthentication();

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
        private Authentication _authentication;
        private Set<String> _roles;

        public Builder()
        {}

        Builder(Constraint constraint)
        {
            _forbidden = constraint.isForbidden();
            _transport = constraint.getTransport();
            _authentication = constraint.getAuthentication();
            _roles = constraint.getRoles();
        }

        public Builder name(String name)
        {
            _name = name;
            return this;
        }

        public String getName()
        {
            return _name;
        }

        public Builder forbidden(boolean forbidden)
        {
            _forbidden = forbidden;
            return this;
        }

        public boolean isForbidden()
        {
            return _forbidden;
        }

        public Builder transport(Transport transport)
        {
            _transport = transport;
            return this;
        }

        public Transport getTransport()
        {
            return _transport;
        }

        public Builder authentication(Authentication authentication)
        {
            _authentication = authentication;
            return this;
        }

        public Authentication getAuthentication()
        {
            return _authentication;
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

        public Set<String> getRoles()
        {
            return _roles == null ? Collections.emptySet() : _roles;
        }

        public Constraint build()
        {
            return from(_name, _forbidden, _transport, _authentication, _roles);
        }
    }

    Constraint NONE = from(false, Transport.REQUIRE_NONE, Authentication.REQUIRE_NONE);
    Constraint FORBIDDEN = from(true, null, null);
    Constraint INTEGRAL = from(false, Transport.REQUIRE_INTEGRAL, null);
    Constraint CONFIDENTIAL = from(false, Transport.REQUIRE_CONFIDENTIAL, null);
    Constraint AUTHENTICATED = from(false, null, Authentication.REQUIRE);
    Constraint AUTHENTICATED_KNOWN_ROLE = from(false, null, Authentication.REQUIRE_KNOWN_ROLE);

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
            Transport.combine(a.getTransport(), b.getTransport()),
            Authentication.combine(a.getAuthentication(), b.getAuthentication()),
            roles);
    }

    static Constraint from(String... roles)
    {
        return from(false, null, Authentication.REQUIRE_SPECIFIC_ROLE, roles);
    }

    static Constraint from(boolean forbidden, Transport transport, Authentication authentication, String... roles)
    {
        return from(forbidden, transport, authentication, (roles == null || roles.length == 0)
            ? Collections.emptySet()
            : new HashSet<>(Arrays.stream(roles).toList()));
    }

    static Constraint from(boolean forbidden, Transport transport, Authentication authentication, Set<String> roles)
    {
        return from(null, forbidden, transport, authentication, roles);
    }

    static Constraint from(String name, boolean forbidden, Transport transport, Authentication authentication, Set<String> roles)
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
            public Transport getTransport()
            {
                return transport == null ? Transport.REQUIRE_NONE : transport;
            }

            @Override
            public Authentication getAuthentication()
            {
                return authentication == null ? Authentication.REQUIRE_NONE : authentication;
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

