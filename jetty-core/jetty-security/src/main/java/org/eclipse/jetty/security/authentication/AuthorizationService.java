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

package org.eclipse.jetty.security.authentication;

import java.util.function.Function;

import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.server.Session;

/**
 * <p>A service to query for user roles.</p>
 * TODO do we need this interface? Can it be moved somewhere more private?
 */
@FunctionalInterface
public interface AuthorizationService
{
    /**
     * @param name the user name
     * @param getSession Function to get or create a {@link Session}
     * @return a {@link UserIdentity} to query for roles of the given user
     */
    UserIdentity getUserIdentity(String name, Function<Boolean, Session> getSession);

    /**
     * <p>Wraps a {@link LoginService} as an AuthorizationService</p>
     *
     * @param loginService the {@link LoginService} to wrap
     * @return an AuthorizationService that delegates the query for roles to the given {@link LoginService}
     */
    static AuthorizationService from(LoginService loginService, Object credentials)
    {
        return (name, getSession) -> loginService.login(name, credentials, getSession);
    }
}
