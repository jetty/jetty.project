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

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.ee10.servlet.ServletContextResponse;
import org.eclipse.jetty.ee10.servlet.security.Authenticator;
import org.eclipse.jetty.ee10.servlet.security.IdentityService;
import org.eclipse.jetty.ee10.servlet.security.LoginService;
import org.eclipse.jetty.ee10.servlet.security.UserIdentity;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.session.Session;
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

    @Override
    public void prepareRequest(Request request)
    {
        //empty implementation as the default
    }

    /**
     * If the UserIdentity is not null after this method calls {@link LoginService#login(String, Object, ServletRequest)}, it
     * is assumed that the user is fully authenticated and we need to change the session id to prevent
     * session fixation vulnerability. If the UserIdentity is not necessarily fully
     * authenticated, then subclasses must override this method and
     * determine when the UserIdentity IS fully authenticated and renew the session id.
     *
     * @param username the username of the client to be authenticated
     * @param password the user's credential
     * @param servletRequest the inbound request that needs authentication
     */
    public UserIdentity login(String username, Object password, Request request)
    {
        //TODO do we need to operate on a Response passed in, rather than the Response obtained from the Request
        ServletContextRequest servletContextRequest = Request.as(request, ServletContextRequest.class);
        ServletContextRequest.ServletApiRequest servletApiRequest = servletContextRequest.getServletApiRequest();
        ServletContextResponse.ServletApiResponse servletApiResponse = servletContextRequest.getResponse().getServletApiResponse();
        UserIdentity user = _loginService.login(username, password, servletApiRequest);
        if (user != null)
        {
            renewSession(servletApiRequest, servletApiResponse);
            return user;
        }
        return null;
    }

    public void logout(Request request)
    {
        ServletContextRequest servletContextRequest = Request.as(request, ServletContextRequest.class);
        ServletContextRequest.ServletApiRequest servletApiRequest = servletContextRequest.getServletApiRequest();
        HttpSession session = servletApiRequest.getSession(false);
        if (session == null)
            return;

        session.removeAttribute(Session.SESSION_CREATED_SECURE);
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
    protected HttpSession renewSession(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
    {
        HttpSession httpSession = httpRequest.getSession(false);
        Session session = Session.getSession(httpSession);
        if (_renewSession && session != null)
        {
            synchronized (session)
            {
                //if we should renew sessions, and there is an existing session that may have been seen by non-authenticated users
                //(indicated by SESSION_SECURED not being set on the session) then we should change id
                if (session.getAttribute(Session.SESSION_CREATED_SECURE) != Boolean.TRUE)
                {
                    ServletContextRequest servletContextRequest = ServletContextRequest.getBaseRequest(httpRequest);
                    Response response = servletContextRequest.getResponse().getWrapped();
                    String oldId = session.getId();
                    session.renewId(servletContextRequest);
                    session.setAttribute(Session.SESSION_CREATED_SECURE, Boolean.TRUE);
                    if (session.isSetCookieNeeded())
                        Response.replaceCookie(response, session.getSessionManager().getSessionCookie(session, httpRequest.getContextPath(), httpRequest.isSecure()));
                    if (LOG.isDebugEnabled())
                        LOG.debug("renew {}->{}", oldId, session.getId());
                    return httpSession;
                }
            }
        }
        return httpSession;
    }
}
