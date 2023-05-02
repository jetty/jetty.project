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

import java.io.Serial;
import java.io.Serializable;
import java.util.function.Function;

import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.Callback;
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
     * If the UserIdentity returned from
     * {@link LoginService#login(String, Object, Request, Function)} is not null, it
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
        UserIdentity user = _loginService.login(username, password, request, request::getSession);
        if (LOG.isDebugEnabled())
            LOG.debug("{}.login {}", this, user);
        if (user != null)
        {
            renewSession(request, response);
            return user;
        }
        return null;
    }

    public void logout(Request request, Response response)
    {
        Session session = request.getSession(false);
        if (LOG.isDebugEnabled())
            LOG.debug("{}.logout {}", this, session);

        if (session == null)
            return;
        session.removeAttribute(SecurityHandler.SESSION_AUTHENTICATED_ATTRIBUTE);
    }

    @Override
    public void setConfiguration(Configuration configuration)
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
     * <li>The {@link Configuration#isSessionRenewedOnAuthentication()} returns true.
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

    /**
     * Base class for representing a successful authentication state.
     */
    public static class UserAuthenticationSucceeded implements AuthenticationState.Succeeded, Serializable
    {
        @Serial
        private static final long serialVersionUID = -6290411814232723403L;
        protected String _authenticationType;
        protected transient UserIdentity _userIdentity;

        public UserAuthenticationSucceeded(String authenticationType, UserIdentity userIdentity)
        {
            _authenticationType = authenticationType;
            _userIdentity = userIdentity;
        }

        @Override
        public String getAuthenticationType()
        {
            return _authenticationType;
        }

        @Override
        public UserIdentity getUserIdentity()
        {
            return _userIdentity;
        }

        @Override
        public boolean isUserInRole(String role)
        {
            return _userIdentity.isUserInRole(role);
        }

        @Override
        public void logout(Request request, Response response)
        {
            SecurityHandler security = SecurityHandler.getCurrentSecurityHandler();
            if (security != null)
            {
                LoginService loginService = security.getLoginService();
                if (loginService != null)
                    loginService.logout(((Succeeded)this).getUserIdentity());
                IdentityService identityService = security.getIdentityService();
                if (identityService != null)
                    identityService.onLogout(((Succeeded)this).getUserIdentity());

                Authenticator authenticator = security.getAuthenticator();

                AuthenticationState authenticationState = null;
                if (authenticator instanceof LoginAuthenticator loginAuthenticator)
                {
                    ((LoginAuthenticator)authenticator).logout(request, response);
                    authenticationState = new LoginAuthenticator.LoggedOutAuthentication(loginAuthenticator);
                }
                AuthenticationState.setAuthenticationState(request, authenticationState);
            }
        }

        @Override
        public String toString()
        {
            return "%s@%x{%s,%s}".formatted(getClass().getSimpleName(), hashCode(), getAuthenticationType(), getUserIdentity());
        }
    }

    /**
     * This Authentication represents a just completed authentication, that has sent a response, typically to
     * redirect the client to the original request target..
     */
    public static class UserAuthenticationSent extends UserAuthenticationSucceeded implements AuthenticationState.ResponseSent
    {
        public UserAuthenticationSent(String method, UserIdentity userIdentity)
        {
            super(method, userIdentity);
        }
    }

    public static class LoggedOutAuthentication implements AuthenticationState.Deferred
    {
        @Override
        public Succeeded login(String username, Object password, Request request, Response response)
        {
            return _delegate.login(username, password, request, response);
        }

        @Override
        public void logout(Request request, Response response)
        {
            _delegate.logout(request, response);
        }

        @Override
        public IdentityService.Association getAssociation()
        {
            return _delegate.getAssociation();
        }

        private final Deferred _delegate;

        public LoggedOutAuthentication(LoginAuthenticator authenticator)
        {
            _delegate = AuthenticationState.defer(authenticator);
        }

        @Override
        public Succeeded authenticate(Request request)
        {
            return null;
        }

        @Override
        public AuthenticationState authenticate(Request request, Response response, Callback callback)
        {
            return null;
        }
    }
}
