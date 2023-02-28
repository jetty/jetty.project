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

import java.io.Closeable;
import java.security.Principal;
import javax.security.auth.Subject;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;

/**
 * Associates UserIdentities from with threads and UserIdentity.Contexts.
 */
public interface IdentityService
{
    /**
     * Associate a user identity with the current thread.
     * This is called with as a thread enters the
     * {@link Handler#handle(Request, org.eclipse.jetty.server.Response, org.eclipse.jetty.util.Callback)}
     * method and then again with a null argument as that call exits.
     *
     * @param user The current user or null for no user to associate.
     * @return A {@link Closeable} that, when closed, will disassociate the user and restore any prior associations.
     */
    Association associate(UserIdentity user);

    /**
     * Associate a runas Token with the current user and thread.
     *
     * @param user The UserIdentity
     * @param token The runAsToken to associate, obtained from {@link #newRunAsToken(String)}.
     * @return A {@link Closeable} that, when closed, will disassociate the token and restore any prior associations.
     */
    Association associate(UserIdentity user, Object token);

    void logout(UserIdentity user);

    /**
     * Create a new UserIdentity for use with this identity service.
     * The UserIdentity should be immutable and able to be cached.
     *
     * @param subject Subject to include in UserIdentity
     * @param userPrincipal Principal to include in UserIdentity.  This will be returned from getUserPrincipal calls
     * @param roles set of roles to include in UserIdentity.
     * @return A new immutable UserIdententity
     */
    UserIdentity newUserIdentity(Subject subject, Principal userPrincipal, String[] roles);

    /**
     * Create a new RunAsToken from a runAsName (normally a role).
     *
     * @param runAsName Normally a role name
     * @return A token that can be passed to {@link #associate(UserIdentity, Object)}.
     */
    Object newRunAsToken(String runAsName);

    UserIdentity getSystemUserIdentity();

    interface Association extends AutoCloseable
    {

    }
}
