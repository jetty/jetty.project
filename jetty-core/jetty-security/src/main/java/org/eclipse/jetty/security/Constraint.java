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
    enum Authorization
    {
        /**
         * Access not allowed. Equivalent to Servlet AuthConstraint with no roles.
         */
        FORBIDDEN,
        /**
         * Access allowed. Equivalent to Servlet AuthConstraint without any Authorization.
         */
        NONE,
        /**
         * Access allowed for any authenticated user. Equivalent to Servlet role "**".
         */
        ANY_USER,
        /**
         * Access allowed for authenticated user with any known role. Equivalent to Servlet role "*".
         */
        KNOWN_ROLE,
        /**
         * Access allowed for authenticated user with specific role(s).
         */
        SPECIFIC_ROLE;

        /**
         * <p>Combine Authorization Constraints, with the strictest constraint
         * always given precedence. Note that this is not servlet specification compliant</p>
         * @param a A constraint
         * @param b A constraint
         * @return The combination of the two constraints.
         */
        public static Authorization combine(Authorization a, Authorization b)
        {
            if (a == null)
                return b == null ? NONE : b;
            if (b == null)
                return a;

            return switch (b)
            {
                case FORBIDDEN -> b;
                case NONE -> a;
                case ANY_USER -> a == NONE ? ANY_USER : a;
                case KNOWN_ROLE -> a == SPECIFIC_ROLE ? SPECIFIC_ROLE : KNOWN_ROLE;
                case SPECIFIC_ROLE -> SPECIFIC_ROLE;
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
    default boolean isForbidden()
    {
        return getAuthorization() == Authorization.FORBIDDEN;
    }

    /**
     * @return {@code True} if the transport must be secure.
     */
    boolean isSecure();

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

    /**
     * Builder for Constraint.
     */
    class Builder
    {
        private String _name;
        private boolean _secure;
        private Authorization _authorization;
        private Set<String> _roles;

        public Builder()
        {}

        public Builder(Constraint constraint)
        {
            _secure = constraint.isSecure();
            _authorization = constraint.getAuthorization();
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

        public Builder secure(boolean secure)
        {
            _secure = secure;
            return this;
        }

        public boolean isSecure()
        {
            return _secure;
        }

        public Builder authentication(Authorization authorization)
        {
            _authorization = authorization;
            return this;
        }

        public Authorization getAuthentication()
        {
            return _authorization;
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
            return from(_name, _secure, _authorization, _roles);
        }
    }

    /**
     * A static Constraint with {@link Authorization#NONE} and not secure.
     */
    Constraint NONE = from(false, Authorization.NONE);

    /**
     * A static Constraint with {@link Authorization#FORBIDDEN} and not secure.
     */
    Constraint FORBIDDEN = from(false, Authorization.FORBIDDEN);

    /**
     * A static Constraint with {@link Authorization#ANY_USER} and not secure.
     */
    Constraint ANY_USER = from(false, Authorization.ANY_USER);

    /**
     * A static Constraint with {@link Authorization#KNOWN_ROLE} and not secure.
     */
    Constraint KNOWN_ROLE = from(false, Authorization.KNOWN_ROLE);

    /**
     * A static Constraint with {@link Authorization#NONE} that is secure.
     */
    Constraint SECURE = from(true, Authorization.NONE);

    /**
     * <p>Combine two Constraints by:</p>
     * <ul>
     *     <li>{@code Null} values are ignored.</li>
     *     <li>Union of role sets.</li>
     *     <li>Combine {@link Authorization}s with {@link Authorization#combine(Authorization, Authorization)}</li>
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
            a.isSecure() || b.isSecure(),
            Authorization.combine(a.getAuthorization(), b.getAuthorization()),
            roles);
    }

    static Constraint from(String... roles)
    {
        return from(false, Authorization.SPECIFIC_ROLE, roles);
    }

    static Constraint from(boolean secure, Authorization authorization, String... roles)
    {
        return from(secure, authorization, (roles == null || roles.length == 0)
            ? Collections.emptySet()
            : new HashSet<>(Arrays.stream(roles).toList()));
    }

    static Constraint from(boolean secure, Authorization authorization, Set<String> roles)
    {
        return from(null, secure, authorization, roles);
    }

    static Constraint from(String name, boolean secure, Authorization authorization, Set<String> roles)
    {
        Set<String> roleSet = roles == null || roles.isEmpty()
            ? Collections.emptySet()
            : Collections.unmodifiableSet(roles);

        Authorization auth = authorization == null
            ? (roleSet.isEmpty() ? Authorization.NONE : Authorization.SPECIFIC_ROLE)
            : authorization;

        return new Constraint()
        {
            @Override
            public String getName()
            {
                return name;
            }

            @Override
            public boolean isSecure()
            {
                return secure;
            }

            @Override
            public Authorization getAuthorization()
            {
                return auth;
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
                return "Constraint@%x{%s,c=%b,%s,%s}".formatted(
                    hashCode(),
                    name,
                    secure,
                    authorization,
                    roleSet);
            }
        };
    }
}

