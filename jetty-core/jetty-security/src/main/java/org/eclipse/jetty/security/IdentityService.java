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

/**
 * Associates UserIdentities from with threads and UserIdentity.Contexts.
 */
public interface IdentityService
{
    /**
     * Associate a runas Token with the current user and thread.
     *
     * @param user The UserIdentity
     * @param runAsToken The runAsToken to associate, obtained from {@link #newRunAsToken(String)}, or null.
     * @return A {@link Closeable} that, when closed, will disassociate the token and restore any prior associations.
     */
    Association associate(UserIdentity user, RunAsToken runAsToken);

    /**
     * Called to notify that a user has been logged out.
     * The service may, among other actions, close any {@link Association} for the calling thread.
     * @param user The user that has logged out
     */
    void onLogout(UserIdentity user);

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
     * @param roleName a role name
     * @return A token that can be passed to {@link #associate(UserIdentity, RunAsToken)}.
     */
    RunAsToken newRunAsToken(String roleName);

    UserIdentity getSystemUserIdentity();

    /**
     * An association between an identity and the current thread that can be terminated by {@link #close()}.
     * @see #associate(UserIdentity, RunAsToken)
     */
    interface Association extends AutoCloseable
    {
        @Override
        void close();
    }

    /**
     * An opaque token created by {@link #newRunAsToken(String)} and used by {@link #associate(UserIdentity, RunAsToken)}
     */
    interface RunAsToken
    {
    }
}
