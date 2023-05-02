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
import java.util.function.Function;
import javax.security.auth.Subject;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Session;

/**
 * Login Service Interface.
 * <p>
 * The Login service provides an abstract mechanism for an {@link Authenticator}
 * to check credentials and to create a {@link UserIdentity} using the
 * set {@link IdentityService}.
 */
public interface LoginService
{
    /**
     * @return Get the name of the login service (aka Realm name)
     */
    String getName();

    /**
     * Login a user.
     *
     * @param username The username.
     * @param credentials The users credentials.
     * @param request The request or null
     * @param getOrCreateSession function to retrieve or create a session.
     * @return A UserIdentity if the credentials matched, otherwise null
     */
    UserIdentity login(String username, Object credentials, Request request, Function<Boolean, Session> getOrCreateSession);

    /**
     * Get or create a {@link UserIdentity} that is not authenticated by the {@link LoginService}.
     * Typically, this method is used when a user is separately authenticated, but the roles
     * of this service are needed for authorization.
     *
     * @param subject The subject
     * @param userPrincipal the userPrincipal
     * @param create If true, the {@link #getIdentityService()} may be used to create a new {@link UserIdentity}.
     * @return A {@link UserIdentity} or null.
     */
    default UserIdentity getUserIdentity(Subject subject, Principal userPrincipal, boolean create)
    {
        UserIdentity userIdentity = login(userPrincipal.getName(), "", null, b -> null);
        if (userIdentity != null)
            return new RoleDelegateUserIdentity(subject, userPrincipal, userIdentity);
        if (create && getIdentityService() != null)
            return getIdentityService().newUserIdentity(subject, userPrincipal, new String[0]);
        return null;
    }

    /**
     * Validate a user identity.
     * Validate that a UserIdentity previously created by a call
     * to {@link #login(String, Object, Request, Function)} is still valid.
     *
     * @param user The user to validate
     * @return true if authentication has not been revoked for the user.
     */
    boolean validate(UserIdentity user);

    /**
     * Get the IdentityService associated with this Login Service.
     *
     * @return the IdentityService associated with this Login Service.
     */
    IdentityService getIdentityService();

    /**
     * Set the IdentityService associated with this Login Service.
     *
     * @param service the IdentityService associated with this Login Service.
     */
    void setIdentityService(IdentityService service);

    void logout(UserIdentity user);
}
