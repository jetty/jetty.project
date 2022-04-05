//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.servlet.security.authentication;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.ee10.handler.UserIdentity;
import org.eclipse.jetty.ee10.security.LoginService;

/**
 * <p>A service to query for user roles.</p>
 */
@FunctionalInterface
public interface AuthorizationService
{
    /**
     * @param request the current HTTP request
     * @param name the user name
     * @return a {@link UserIdentity} to query for roles of the given user
     */
    UserIdentity getUserIdentity(HttpServletRequest request, String name);

    /**
     * <p>Wraps a {@link LoginService} as an AuthorizationService</p>
     *
     * @param loginService the {@link LoginService} to wrap
     * @return an AuthorizationService that delegates the query for roles to the given {@link LoginService}
     */
    public static AuthorizationService from(LoginService loginService, Object credentials)
    {
        return (request, name) -> loginService.login(name, credentials, request);
    }
}
