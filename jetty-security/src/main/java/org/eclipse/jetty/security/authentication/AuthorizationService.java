//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.security.authentication;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.UserIdentity;

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
    static AuthorizationService from(LoginService loginService, Object credentials)
    {
        return (request, name) -> loginService.login(name, credentials, request);
    }
}
