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

package org.eclipse.jetty.ee9.nested;

import java.util.Map;

/**
 * User object that encapsulates user identity and operations such as run-as-role actions,
 * checking isUserInRole and getUserPrincipal.
 * <p>
 * Implementations of UserIdentityScope should be immutable so that they may be
 * cached by Authenticators and LoginServices.
 */
public interface UserIdentityScope
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

    static String deRefRole(UserIdentityScope scope, String role)
    {
        if (scope == null)
            return role;

        Map<String, String> roleRefMap = scope.getRoleRefMap();
        if (roleRefMap == null || roleRefMap.isEmpty())
            return role;

        String ref = roleRefMap.get(role);
        while (ref != null)
        {
            role = ref;
            ref = roleRefMap.get(role);
        }
        return role;
    }
}
