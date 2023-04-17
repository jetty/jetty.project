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
    enum Authentication
    {
        REQUIRE_NONE,
        REQUIRE_ANY_ROLE,
        REQUIRE_KNOWN_ROLE,
        REQUIRE_SPECIFIC_ROLE;

        public static Authentication combine(Authentication a, Authentication b)
        {
            if (a == null)
                return b == null ? REQUIRE_NONE : b;
            if (b == null)
                return a;

            return switch (b)
            {
                case REQUIRE_NONE -> a;
                case REQUIRE_ANY_ROLE -> a == REQUIRE_NONE ? REQUIRE_ANY_ROLE : a;
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
     * TODO this should just be another Authentication mode.
     */
    boolean isForbidden();

    /**
     * @return {@code True} if the transport must be secure.
     */
    boolean isSecure();

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
        private boolean _secure;
        private Authentication _authentication;
        private Set<String> _roles;

        public Builder()
        {}

        public Builder(Constraint constraint)
        {
            _forbidden = constraint.isForbidden();
            _secure = constraint.isSecure();
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
            _authentication = Authentication.REQUIRE_NONE;
            return this;
        }

        public boolean isForbidden()
        {
            return _forbidden;
        }

        public Builder secure(boolean secure)
        {
            _secure = secure;
            return this;
        }

        public boolean isSecure()
        {
            return _secure;
        }

        public Builder authentication(Authentication authentication)
        {
            _authentication = authentication;
            _forbidden = false;
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
            return from(_name, _forbidden, _secure, _authentication, _roles);
        }
    }

    Constraint NONE = from(false, false, Authentication.REQUIRE_NONE);
    Constraint FORBIDDEN = from(true, false, null);
    Constraint CONFIDENTIAL = from(false, true, null);
    Constraint AUTHENTICATED = from(false, false, Authentication.REQUIRE_ANY_ROLE);
    Constraint AUTHENTICATED_KNOWN_ROLE = from(false, false, Authentication.REQUIRE_KNOWN_ROLE);

    /**
     * <p>Combine two Constraints by:</p>
     * <ul>
     *     <li>{@code Null} values are ignored.</li>
     *     <li>Union of role sets.</li>
     *     <li>Combine {@link Constraint.Authentication} with {@link Constraint.Authentication#combine(Constraint, Constraint)}</li>
     *     <li>Forbidden is OR'd</li>
     *     <li>Secure is OR'd</li>
     * </ul>
     * <p>Note that this combination is not equivalent to the combination done by the EE servlet specification.</p>
     * @param a Constraint to combine
     * @param b Constraint to combine
     * @return the combined constraint.
     */
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
            a.isSecure() || b.isSecure(),
            Authentication.combine(a.getAuthentication(), b.getAuthentication()),
            roles);
    }

    static Constraint from(String... roles)
    {
        return from(false, false, Authentication.REQUIRE_SPECIFIC_ROLE, roles);
    }

    static Constraint from(boolean forbidden, boolean secure, Authentication authentication, String... roles)
    {
        return from(forbidden, secure, authentication, (roles == null || roles.length == 0)
            ? Collections.emptySet()
            : new HashSet<>(Arrays.stream(roles).toList()));
    }

    static Constraint from(boolean forbidden, boolean secure, Authentication authentication, Set<String> roles)
    {
        return from(null, forbidden, secure, authentication, roles);
    }

    static Constraint from(String name, boolean forbidden, boolean secure, Authentication authentication, Set<String> roles)
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
            public boolean isSecure()
            {
                return secure;
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

            @Override
            public String toString()
            {
                return "Constraint@%x{%s,f=%b,c=%b,%s,%s}".formatted(
                    hashCode(),
                    name,
                    forbidden,
                    secure,
                    authentication,
                    roleSet);
            }
        };
    }
}

