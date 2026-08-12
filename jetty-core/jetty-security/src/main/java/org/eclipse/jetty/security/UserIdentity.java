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
import java.util.Collection;
import java.util.HashSet;
import javax.security.auth.Subject;

import org.eclipse.jetty.security.internal.DefaultUserIdentity;
import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * User object that encapsulates user identity and operations such as run-as-role actions,
 * checking isUserInRole and getUserPrincipal.
 * <p>
 * Implementations of UserIdentity should be immutable so that they may be
 * cached by Authenticators and LoginServices.
 */
public interface UserIdentity
{
    /**
     * @return The user subject
     */
    Subject getSubject();

    /**
     * @return The user principal
     */
    Principal getUserPrincipal();

    /**
     * Check if the user is in a role.
     * This call is used to satisfy authorization calls from
     * container code which will be using translated role names.
     *
     * @param role A role name.
     * @return True if the user can act in that role.
     */
    boolean isUserInRole(String role);

    default String[] getRoles()
    {
        HashSet<String> roles = new HashSet<>();

        // Extract any RolePrincipal names from the Subject.
        for (Principal principal : getSubject().getPrincipals())
        {
            if (principal instanceof RolePrincipal && isUserInRole(principal.getName()))
                roles.add(principal.getName());
        }

        // Run through the list of known roles and verify with isUserInRole.
        SecurityHandler securityHandler = SecurityHandler.getCurrentSecurityHandler();
        if (securityHandler != null)
        {
            // Check any roles known by the SecurityHandler.
            for (String role : securityHandler.getKnownRoles())
            {
                if (isUserInRole(role))
                    roles.add(role);
            }
        }
        else
        {
            // This may be EE8/EE9 which does not have a jetty-core SecurityHandler so check this ContextHandler attribute.
            ContextHandler contextHandler = ContextHandler.getCurrentContextHandler();
            if (contextHandler != null)
            {
                Object attribute = contextHandler.getAttribute(SecurityHandler.KNOWN_ROLES_ATTRIBUTE);
                if (attribute instanceof String[] knownRoles)
                {
                    for (String role : knownRoles)
                    {
                        if (isUserInRole(role))
                            roles.add(role);
                    }
                }
                else if (attribute instanceof Collection<?> knownRoles)
                {
                    for (Object role : knownRoles)
                    {
                        if (isUserInRole(role.toString()))
                            roles.add(role.toString());
                    }
                }
            }
        }

        return roles.toArray(new String[0]);
    }

    static UserIdentity from(Subject subject, Principal userPrincipal, String... roles)
    {
        return new DefaultUserIdentity(subject, userPrincipal, roles);
    }
}
