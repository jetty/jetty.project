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

import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class LoginAuthenticator implements Authenticator
{
    private static final Logger LOG = LoggerFactory.getLogger(LoginAuthenticator.class);

    protected LoginService _loginService;
    protected IdentityService _identityService;
    private boolean _renewSession;

    protected LoginAuthenticator()
    {
    }

    /**
     * If the UserIdentity is not null after this method calls {@link LoginService#login(String, Object, Request)}, it
     * is assumed that the user is fully authenticated and we need to change the session id to prevent
     * session fixation vulnerability. If the UserIdentity is not necessarily fully
     * authenticated, then subclasses must override this method and
     * determine when the UserIdentity IS fully authenticated and renew the session id.
     *
     * @param username the username of the client to be authenticated
     * @param password the user's credential
     * @param request the inbound request that needs authentication
     */
    public UserIdentity login(String username, Object password, Request request, Response response)
    {
        UserIdentity user = _loginService.login(username, password, request);
        if (user != null)
        {
            renewSession(request, response);
            return user;
        }
        return null;
    }

    public void logout(Request request)
    {
        Session session = request.getSession(false);
        if (session == null)
            return;
        session.removeAttribute(SecurityHandler.SESSION_AUTHENTICATED_ATTRIBUTE);
    }

    @Override
    public void setConfiguration(AuthConfiguration configuration)
    {
        _loginService = configuration.getLoginService();
        if (_loginService == null)
            throw new IllegalStateException("No LoginService for " + this + " in " + configuration);
        _identityService = configuration.getIdentityService();
        if (_identityService == null)
            throw new IllegalStateException("No IdentityService for " + this + " in " + configuration);
        _renewSession = configuration.isSessionRenewedOnAuthentication();
    }

    public LoginService getLoginService()
    {
        return _loginService;
    }

    /**
     * Change the session id.
     * The session is changed to a new instance with a new ID if and only if:<ul>
     * <li>A session exists.
     * <li>The {@link Authenticator.AuthConfiguration#isSessionRenewedOnAuthentication()} returns true.
     * <li>The session ID has been given to unauthenticated responses
     * </ul>
     *
     * @param httpRequest the request
     * @param httpResponse the response
     * @return The new session.
     */
    protected Session renewSession(Request httpRequest, Response httpResponse)
    {
        Session session = httpRequest.getSession(false);
        if (_renewSession && session != null)
        {
            synchronized (session)
            {
                //if we should renew sessions, and there is an existing session that may have been seen by non-authenticated users
                //(indicated by SESSION_SECURED not being set on the session) then we should change id
                if (session.getAttribute(SecurityHandler.SESSION_AUTHENTICATED_ATTRIBUTE) != Boolean.TRUE)
                {
                    session.setAttribute(SecurityHandler.SESSION_AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
                    session.renewId(httpRequest, httpResponse);
                    return session;
                }
            }
        }
        return session;
    }
}
