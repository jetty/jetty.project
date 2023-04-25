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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Security constraint that is applied to a request, which can optionally contain:
 * <ul>
 *     <li>A name</li>
 *     <li>Authorization to specify if authentication is needed and what roles are applicable</li>
 *     <li>A list of role names used for {@link Authorization#KNOWN_ROLE}</li>
 *     <li>If the request must be secure or not.</li>
 * </ul>
 * <p>
 * If a constraint does not contain one of these elements, it is interpreted as "don't care". For example
 * if {@link #isSecure()} returns null, this signifies that the transport may either be secure or insecure.
 * Such "don't care" values are important when combining constraints.
 * <p>
 * The core constraint is not the same as the servlet specification {@code AuthConstraint}, but it is
 * sufficiently capable to represent servlet constraints.
 * </p>
 */
public interface Constraint
{
    Logger LOG = LoggerFactory.getLogger(Constraint.class);

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
        SPECIFIC_ROLE;
    }

    /**
     * @return The name for the {@code Constraint} or "unnamed@hashcode" if not named
     */
    String getName();

    /**
     * @return {@code True} if the transport must be secure, {@link Boolean#FALSE} if the
     * transport does not need to be secure; {@code NULL} if the transport can be either.
     */
    Boolean isSecure();

    /**
     * @return The {@link Authorization} criteria applied by this {@code Constraint}
     * or null if this constraint does not have any authorization requirements.
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
            return from(_name, _secure, _authorization, _roles);
        }
    }

    /**
     * A static Constraint with {@link Authorization#ALLOWED} and not secure.
     */
    Constraint ALLOWED = from("ALLOWED", Authorization.ALLOWED);

    /**
     * A static Constraint with {@link Authorization#FORBIDDEN} and not secure.
     */
    Constraint FORBIDDEN = from("FORBIDDEN", Authorization.FORBIDDEN);

    /**
     * A static Constraint with {@link Authorization#ANY_USER} and not secure.
     */
    Constraint ANY_USER = from("ANY_USER", Authorization.ANY_USER);

    /**
     * A static Constraint with {@link Authorization#KNOWN_ROLE} and not secure.
     */
    Constraint KNOWN_ROLE = from("KNOWN_ROLE", Authorization.KNOWN_ROLE);

    /**
     * A static Constraint that is secure.
     */
    Constraint SECURE = from("SECURE", true);

    /**
     * A static Constraint that is insecure.
     */
    Constraint INSECURE = from("INSECURE", false);

    /**
     * A static Constraint with {@link Authorization#ALLOWED} and not secure.
     */
    Constraint ALLOWED_INSECURE = combine(ALLOWED, INSECURE);

    /**
     * <p>Combine two Constraints by:</p>
     * <ul>
     *     <li>if both constraints are {@code Null}, then {@link Constraint#ALLOWED} is returned.</li>
     *     <li>if either constraint is {@code Null} the other is returned.</li>
     *     <li>if the {@code mostSpecific} constraints {@link Authorization} is not null, it is used in the
     *     combined constraint, otherwise the {@code leastSpecific}'s is used.</li>
     *     <li>if the {@code mostSpecific} constraints {@link Authorization} is not null, it's
     *     {@link Constraint#getRoles()} are used in the combined constraint, otherwise
     *     the {@code leastSpecific}'s are used.</li>
     *     <li>if the {@code mostSpecific} constraint's {@link #isSecure()} is not null, then that is used
     *     in the combined constraint, otherwise the {@code leastSpecific}'s is used.</li>
     * </ul>
     * <p>Note that this combination is not equivalent to the combination done by the EE servlet specification.</p>
     * @param leastSpecific Constraint to combine
     * @param mostSpecific Constraint to combine
     * @return the combined constraint.
     */
    static Constraint combine(Constraint leastSpecific, Constraint mostSpecific)
    {
        if (leastSpecific == null)
            return mostSpecific == null ? ALLOWED : mostSpecific;
        if (mostSpecific == null)
            return leastSpecific;

        String name = LOG.isDebugEnabled()
            ? leastSpecific.getName() + ">" + mostSpecific : null;

        return from(
            name,
            mostSpecific.isSecure() == null ? leastSpecific.isSecure() : mostSpecific.isSecure(),
            mostSpecific.getAuthorization() == null ? leastSpecific.getAuthorization() : mostSpecific.getAuthorization(),
            mostSpecific.getAuthorization() == null ? leastSpecific.getRoles() : mostSpecific.getRoles());
    }

    static Constraint from(String... roles)
    {
        return from(null, Authorization.SPECIFIC_ROLE, roles);
    }

    static Constraint from(String name, boolean secure)
    {
        return from(name, secure, null, null);
    }

    static Constraint from(String name, Authorization authorization, String... roles)
    {
        return from(name, null, authorization, (roles == null || roles.length == 0)
            ? Collections.emptySet()
            : new HashSet<>(Arrays.stream(roles).toList()));
    }

    static Constraint from(boolean secure, Authorization authorization, Set<String> roles)
    {
        return from(null, secure, authorization, roles);
    }

    static Constraint from(String name, Boolean secure, Authorization authorization, Set<String> roles)
    {
        Set<String> roleSet = roles == null || roles.isEmpty()
            ? Collections.emptySet()
            : Collections.unmodifiableSet(roles);

        Authorization auth = authorization == null
            ? (roleSet.isEmpty() ? null : Authorization.SPECIFIC_ROLE)
            : authorization;

        return new Constraint()
        {
            @Override
            public String getName()
            {
                return name;
            }

            @Override
            public Boolean isSecure()
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

