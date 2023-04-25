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

package org.eclipse.jetty.ee9.security.authentication;

import java.util.function.Function;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee9.nested.HttpChannel;
import org.eclipse.jetty.ee9.nested.Request;
import org.eclipse.jetty.ee9.security.Authenticator;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.session.ManagedSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.ee9.nested.SessionHandler.ServletSessionApi.getOrCreateSession;

public abstract class LoginAuthenticator implements Authenticator
{
    private static final Logger LOG = LoggerFactory.getLogger(LoginAuthenticator.class);

    protected LoginService _loginService;
    protected IdentityService _identityService;
    private boolean _renewSession;

    protected LoginAuthenticator()
    {
    }

    @Override
    public void prepareRequest(ServletRequest request)
    {
        //empty implementation as the default
    }

    /**
     * If the UserIdentity returned from
     * {@link LoginService#login(String, Object, org.eclipse.jetty.server.Request, Function)} is not null, it
     * is assumed that the user is fully authenticated and we need to change the session id to prevent
     * session fixation vulnerability. If the UserIdentity is not necessarily fully
     * authenticated, then subclasses must override this method and
     * determine when the UserIdentity IS fully authenticated and renew the session id.
     *
     * @param username the username of the client to be authenticated
     * @param password the user's credential
     * @param servletRequest the inbound request that needs authentication
     */
    public UserIdentity login(String username, Object password, ServletRequest servletRequest)
    {
        Request baseRequest = Request.getBaseRequest(servletRequest);
        if (baseRequest == null)
            return null;
        UserIdentity user = _loginService.login(username, password, baseRequest.getCoreRequest(), getOrCreateSession(servletRequest));
        if (user != null)
        {
            Request request = Request.getBaseRequest(servletRequest);
            if (request != null)
                renewSession(request, request.getResponse());
            return user;
        }
        return null;
    }

    public void logout(ServletRequest request)
    {
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpSession session = httpRequest.getSession(false);
        if (session == null)
            return;

        session.removeAttribute(ManagedSession.SESSION_CREATED_SECURE);
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
     * @param request the request
     * @param response the response
     * @return The new session.
     */
    protected HttpSession renewSession(HttpServletRequest request, HttpServletResponse response)
    {
        HttpSession httpSession = request.getSession(false);

        if (_renewSession && httpSession != null)
        {
            synchronized (httpSession)
            {
                //if we should renew sessions, and there is an existing session that may have been seen by non-authenticated users
                //(indicated by SESSION_SECURED not being set on the session) then we should change id
                if (httpSession.getAttribute(ManagedSession.SESSION_CREATED_SECURE) != Boolean.TRUE)
                {
                    if (httpSession instanceof Session.API api)
                    {
                        Request baseRequest = Request.getBaseRequest(request);
                        if (baseRequest != null)
                        {
                            httpSession.setAttribute(ManagedSession.SESSION_CREATED_SECURE, Boolean.TRUE);
                            HttpChannel httpChannel = baseRequest.getHttpChannel();
                            api.getSession().renewId(httpChannel.getCoreRequest(), httpChannel.getCoreResponse());
                            return httpSession;
                        }
                    }
                    LOG.warn("Unable to renew session {}", httpSession);
                    return httpSession;
                }
            }
        }
        return httpSession;
    }
}
