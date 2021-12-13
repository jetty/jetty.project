//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.security.Principal;
import java.util.Map;
import javax.security.auth.Subject;

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
     * @param scope the scope
     * @return True if the user can act in that role.
     */
    boolean isUserInRole(String role, Scope scope);

    /**
     * A UserIdentity Scope.
     * A scope is the environment in which a User Identity is to
     * be interpreted. Typically it is set by the target servlet of
     * a request.
     */
    interface Scope
    {

        /**
         * @return The context handler that the identity is being considered within
         */
        ContextHandler getContextHandler();

        /**
         * @return The context path that the identity is being considered within
         */
        String getContextPath();

        /**
         * @return The name of the identity context. Typically this is the servlet name.
         */
        String getName();

        /**
         * @return A map of role reference names that converts from names used by application code
         * to names used by the context deployment.
         */
        Map<String, String> getRoleRefMap();
    }

    public interface UnauthenticatedUserIdentity extends UserIdentity
    {
    }

    public static final UserIdentity UNAUTHENTICATED_IDENTITY = new UnauthenticatedUserIdentity()
    {
        @Override
        public Subject getSubject()
        {
            return null;
        }

        @Override
        public Principal getUserPrincipal()
        {
            return null;
        }

        @Override
        public boolean isUserInRole(String role, Scope scope)
        {
            return false;
        }

        @Override
        public String toString()
        {
            return "UNAUTHENTICATED";
        }
    };
}
