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

/**
 * A Security constraint that is applied to a request, which contain:
 * <ul>
 *     <li>A name</li>
 *     <li>Authorization to specify if authentication is needed and what roles are applicable</li>
 *     <li>An optional list of role names used for {@link Authorization#KNOWN_ROLE}</li>
 *     <li>A Transport constraint, indicating if it must be secure or not.</li>
 * </ul>
 * <p>
 * The core constraint is not the same as the servlet specification {@code AuthConstraint}, but it is
 * sufficiently capable to represent servlet constraints.
 * </p>
 */
public interface Constraint
{
    /**
     * The Authorization applied to any authentication of the request/
     */
    enum Authorization
    {
        /**
         * Access not allowed. Equivalent to Servlet AuthConstraint with no roles.
         */
        FORBIDDEN,
        /**
         * Access allowed. Equivalent to Servlet AuthConstraint without any Authorization.
         */
        ALLOWED,
        /**
         * Access allowed for any authenticated user regardless of role. Equivalent to Servlet role "**".
         * For example, a web application that defines only an "admin" can use this {@code Authorization} to
         * allow any authenticated user known to the configured {@link LoginService}, even if their roles are
         * not know to the web application.
         */
        ANY_USER,
        /**
         * Access allowed for authenticated user with any known role. Equivalent to Servlet role "*".
         * For example, a web application that defines roles "admin" and "user" might be deployed to a server
         * with a configured {@link LoginService} that also has users with "operator" role. This constraint would
         * not allow an "operator" user, as that role is not known to the web application.
         */
        KNOWN_ROLE,
        /**
         * Access allowed only for authenticated user with specific role(s).
         */
        SPECIFIC_ROLE,
        /**
         * Inherit the authorization from a less specific constraint when passed to {@link #combine(Constraint, Constraint)},
         * otherwise act as {@link #ALLOWED}.
         */
        INHERIT;
    }

    /**
     * The constraints requirement for the transport
     */
    enum Transport
    {
        /**
         * The transport must be secure (e.g. TLS)
         */
        SECURE,
        /**
         * The transport can be either secure or not secure.
         */
        ANY,
        /**
         * Inherit the transport constraint from a less specific constraint when passed to {@link #combine(Constraint, Constraint)},
         * otherwise act as {@link #ANY}.
         */
        INHERIT
    }

    /**
     * @return The name for the {@code Constraint} or "unnamed@hashcode" if not named
     */
    String getName();

    /**
     * @return The required {@link Transport} or null if the transport can be either.
     */
    Transport getTransport();
    
    /**
     * @return The {@link Authorization} criteria applied by this {@code Constraint}
     * or null if this constraint does not have any authorization requirements.
     */
    Authorization getAuthorization();

    /**
     * @return The set of roles applied by this {@code Constraint} or the empty set.
     */
    Set<String> getRoles();

    /**
     * Builder for Constraint.
     */
    class Builder
    {
        private String _name;
        private Authorization _authorization;
        private Set<String> _roles;
        private Transport _transport;

        public Builder()
        {}

        public Builder(Constraint constraint)
        {
            _transport = constraint.getTransport();
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

        public Builder transport(Transport transport)
        {
            _transport = transport;
            return this;
        }

        public Transport getTransport()
        {
            return _transport;
        }

        public Builder authorization(Authorization authorization)
        {
            _authorization = authorization;
            return this;
        }

        public Authorization getAuthorization()
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
            return from(_name, _transport, _authorization, _roles);
        }
    }

    /**
     * A static Constraint that has {@link Authorization#ALLOWED} and {@link Transport#INHERIT}.
     */
    Constraint ALLOWED = from("ALLOWED", Authorization.ALLOWED);

    /**
     * A static Constraint that has {@link Authorization#FORBIDDEN} and {@link Transport#INHERIT}.
     */
    Constraint FORBIDDEN = from("FORBIDDEN", Authorization.FORBIDDEN);

    /**
     * A static Constraint that has {@link Authorization#ANY_USER} and {@link Transport#INHERIT}.
     */
    Constraint ANY_USER = from("ANY_USER", Authorization.ANY_USER);

    /**
     * A static Constraint that has {@link Authorization#KNOWN_ROLE} and {@link Transport#INHERIT}.
     */
    Constraint KNOWN_ROLE = from("KNOWN_ROLE", Authorization.KNOWN_ROLE);

    /**
     * A static Constraint that has {@link Transport#SECURE} and {@link Authorization#INHERIT}
     */
    Constraint SECURE_TRANSPORT = from("SECURE", Transport.SECURE);

    /**
     * A static Constraint that has {@link Transport#ANY} and {@link Authorization#INHERIT}
     */
    Constraint ANY_TRANSPORT = from("ANY", Transport.ANY);

    /**
     * A static Constraint that has {@link Authorization#ALLOWED} and {@link Transport#ANY}.
     */
    Constraint ALLOWED_ANY_TRANSPORT = combine("ALLOWED_ANY_TRANSPORT", ALLOWED, ANY_TRANSPORT);

    /**
     * Combine two Constraints by using {@link #combine(String, Constraint, Constraint)} with
     * a generated name.
     * @see #combine(String, Constraint, Constraint)
     * @param leastSpecific Constraint to combine
     * @param mostSpecific Constraint to combine
     * @return the combined constraint.
     */
    static Constraint combine(Constraint leastSpecific, Constraint mostSpecific)
    {
        return combine(null, leastSpecific, mostSpecific);
    }

    /**
     * <p>Combine two Constraints by:</p>
     * <ul>
     *     <li>if both constraints are {@code Null}, then {@link Constraint#ALLOWED} is returned.</li>
     *     <li>if either constraint is {@code Null} the other is returned.</li>
     *     <li>only if the {@code mostSpecific} constraint has {@link Authorization#INHERIT} is the
     *         {@code leastSpecific} constraint's {@link Authorization} used,
     *         otherwise the {@code mostSpecific}'s is used.</li>
     *     <li>if the combined constraint has {@link Authorization#SPECIFIC_ROLE}, then the role set from
     *     the constraint that specified the {@link Authorization#SPECIFIC_ROLE} is used.</li>
     *     <li>only if the {@code mostSpecific} constraint has {@link Transport#INHERIT} is the
     *         {@code leastSpecific} constraint's {@link Transport} used,
     *         otherwise the {@code mostSpecific}'s is used.</li>
     * </ul>
     * <p>
     * Typically the path of the constraint is used to determine which constraint is most specific.  For example
     * if the following paths mapped to Constraints as:
     * </p>
     * <pre>
     *     /*         -> Authorization.FORBIDDEN,roles=[],Transport.SECURE
     *     /admin/*   -> Authorization.SPECIFIC_ROLE,roles=["admin"],Transport.INHERIT
     * </pre>
     * <p>
     * The the {@code /admin/*} constraint would be consider most specific and a request to {@code /admin/file} would
     * have {@link Authorization#SPECIFIC_ROLE} from the {@code /admin/*} constraint and
     * {@link Transport#SECURE} inherited from the {@code /*} constraint.  For more examples see
     * {@link SecurityHandler.PathMapped}.
     * </p>
     * <p>Note that this combination is not equivalent to the combination done by the EE servlet specification.</p>
     * @param name The name to use for the combined constraint
     * @param leastSpecific Constraint to combine
     * @param mostSpecific Constraint to combine
     * @return the combined constraint.
     */
    static Constraint combine(String name, Constraint leastSpecific, Constraint mostSpecific)
    {
        if (leastSpecific == null)
            return mostSpecific == null ? ALLOWED : mostSpecific;
        if (mostSpecific == null)
            return leastSpecific;

        return from(
            name,
            mostSpecific.getTransport() == Transport.INHERIT ? leastSpecific.getTransport() : mostSpecific.getTransport(),
            mostSpecific.getAuthorization() == Authorization.INHERIT ? leastSpecific.getAuthorization() : mostSpecific.getAuthorization(),
            mostSpecific.getAuthorization() == Authorization.INHERIT ? leastSpecific.getRoles() : mostSpecific.getRoles());
    }

    static Constraint from(String... roles)
    {
        return from(null, Authorization.SPECIFIC_ROLE, roles);
    }

    static Constraint from(String name, Transport transport)
    {
        return from(name, transport, null, null);
    }

    static Constraint from(String name, Authorization authorization, String... roles)
    {
        return from(name, Transport.INHERIT, authorization, (roles == null || roles.length == 0)
            ? Collections.emptySet()
            : new HashSet<>(Arrays.stream(roles).toList()));
    }

    static Constraint from(Transport transport, Authorization authorization, Set<String> roles)
    {
        return from(null, transport, authorization, roles);
    }

    static Constraint from(String name, Transport transport, Authorization authorization, Set<String> roles)
    {
        return new Constraint()
        {
            private final String _name = name == null ? "unnamed@%x".formatted(hashCode()) : name;
            private final Transport _transport = transport == null ? Transport.INHERIT : transport;
            private final Set<String> _roles = roles == null || roles.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(roles);
            private final Authorization _authorization = authorization == null
                ? (_roles.isEmpty() ? Authorization.INHERIT : Authorization.SPECIFIC_ROLE)
                : authorization;
            {
                if (!_roles.isEmpty() && _authorization != Authorization.SPECIFIC_ROLE)
                    throw new IllegalStateException("Constraint with roles must be SPECIFIC_ROLE, not " + _authorization);
            }

            @Override
            public String getName()
            {
                return _name;
            }

            @Override
            public Transport getTransport()
            {
                return _transport;
            }

            @Override
            public Authorization getAuthorization()
            {
                return _authorization;
            }

            @Override
            public Set<String> getRoles()
            {
                return _roles;
            }

            @Override
            public String toString()
            {
                return "Constraint@%x{%s,%s,%s,%s}".formatted(
                    hashCode(),
                    getName(),
                    getTransport(),
                    getAuthorization(),
                    getRoles());
            }
        };
    }
}

