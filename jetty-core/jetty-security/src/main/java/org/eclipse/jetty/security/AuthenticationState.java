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

import java.io.Serializable;
import java.security.Principal;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.security.internal.DeferredAuthenticationState;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * The Authentication state of a request.
 * <p>
 * The Authentication state can be one of several sub-types that
 * reflects where the request is in the many different authentication
 * cycles. Authentication might not yet be checked or it might be checked
 * and failed, checked and deferred or succeeded.
 */
public interface AuthenticationState
{
    static AuthenticationState getAuthentication(Request request)
    {
        Object auth = request.getAttribute(AuthenticationState.class.getName());
        return auth instanceof AuthenticationState authenticationState ? authenticationState : null;
    }

    static Principal getUserPrincipal(Request request)
    {
        AuthenticationState authenticationState = AuthenticationState.getAuthentication(request);
        if (authenticationState instanceof SucceededAuthenticationState userAuthentication)
        {
            return userAuthentication.getUserIdentity().getUserPrincipal();
        }
        if (authenticationState instanceof Deferred deferred)
        {
            Succeeded succeeded = deferred.authenticate(request);
            if (succeeded == null)
                return null;
            return succeeded.getUserIdentity().getUserPrincipal();
        }
        return null;
    }

    static void setAuthentication(Request request, AuthenticationState authenticationState)
    {
        request.setAttribute(AuthenticationState.class.getName(), authenticationState);
    }

    static Succeeded authenticate(Request request)
    {
        AuthenticationState authenticationState = getAuthentication(request);

        //if already authenticated, return true
        if (authenticationState instanceof Succeeded succeeded)
            return succeeded;

        //do the authentication
        if (authenticationState instanceof Deferred deferred)
        {
            Succeeded undeferred = deferred.authenticate(request);
            if (undeferred != null)
            {
                setAuthentication(request, undeferred);
                return undeferred;
            }
        }
        return null;
    }

    static Succeeded authenticate(Request request, Response response, Callback callback)
    {
        AuthenticationState authenticationState = getAuthentication(request);

        //if already authenticated, return true
        if (authenticationState instanceof Succeeded succeeded)
            return succeeded;

        //do the authentication
        if (authenticationState instanceof Deferred deferred)
        {
            AuthenticationState undeferred = deferred.authenticate(request, response, callback);
            if (undeferred instanceof AuthenticationState.ResponseSent)
                return null;

            if (undeferred instanceof Succeeded succeeded)
            {
                setAuthentication(request, undeferred);
                return succeeded;
            }
        }
        Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
        return null;
    }

    static Succeeded login(String username, String password, Request request, Response response)
    {
        AuthenticationState authenticationState = getAuthentication(request);

        //if already authenticated, return true
        if (authenticationState instanceof Succeeded)
            throw new HttpException.RuntimeException(HttpStatus.INTERNAL_SERVER_ERROR_500, "Already authenticated");

        //do the authentication
        if (authenticationState instanceof Deferred deferred)
        {
            Succeeded undeferred =  deferred.login(username, password, request, response);
            if (undeferred != null)
            {
                setAuthentication(request, undeferred);
                return undeferred;
            }
        }

        return null;
    }

    static boolean logout(Request request, Response response)
    {
        AuthenticationState authenticationState = getAuthentication(request);

        //if already authenticated, return true
        if (authenticationState instanceof Succeeded succeededAuthentication)
        {
            succeededAuthentication.logout(request, response);
            return true;
        }

        if (authenticationState instanceof Deferred deferred)
        {
            deferred.logout(request, response);
            return true;
        }

        return false;
    }

    class Failed extends QuietException.Exception
    {
        public Failed(String message)
        {
            super(message);
        }
    }

    /**
     * A successful Authentication with User information.
     */
    interface Succeeded extends AuthenticationState
    {
        String getAuthMethod();

        UserIdentity getUserIdentity();

        boolean isUserInRole(String role);

        /**
         * Remove any user information that may be present in the request
         * such that a call to getUserPrincipal/getRemoteUser will return null.
         *
         * @param request the request
         * @param response the response
         */
        void logout(Request request, Response response);
    }

    /**
     * Authentication Response sent state.
     * Responses are sent by authenticators either to issue an
     * authentication challenge or on successful authentication in
     * order to redirect the user to the original URL.
     */
    interface ResponseSent extends AuthenticationState
    {
    }

    /**
     * Authentication challenge sent.
     * <p>
     * This convenience instance is for when an authentication challenge has been sent.
     */
    AuthenticationState CHALLENGE = new ResponseSent()
    {
        @Override
        public String toString()
        {
            return "CHALLENGE";
        }
    };

    /**
     * Authentication failure sent.
     * <p>
     * This convenience instance is for when an authentication failure has been sent.
     */
    AuthenticationState SEND_FAILURE = new ResponseSent()
    {
        @Override
        public String toString()
        {
            return "FAILURE";
        }
    };

    /**
     * Authentication success sent.
     * <p>
     * This convenience instance is for when an authentication success has been sent.
     */
    AuthenticationState SEND_SUCCESS = new ResponseSent()
    {
        @Override
        public String toString()
        {
            return "SEND_SUCCESS";
        }
    };

    static Deferred defer(LoginAuthenticator loginAuthenticator)
    {
        return new DeferredAuthenticationState(loginAuthenticator);
    }

    interface Deferred extends AuthenticationState
    {
        /**
         * @param response the response
         * @return true if this response is from a deferred call to {@link #authenticate(Request)}
         */
        static boolean isDeferred(Response response)
        {
            return response instanceof DeferredResponse;
        }

        Succeeded authenticate(Request request);

        AuthenticationState authenticate(Request request, Response response, Callback callback);

        Succeeded login(String username, Object password, Request request, Response response);

        void logout(Request request, Response response);

        IdentityService.Association getAssociation();

        interface DeferredResponse extends Response
        {
        }
    }

    /**
     * Base class for representing a successful authentication state.
     */
    abstract class AbstractSucceeded implements Succeeded, Serializable
    {
        private static final long serialVersionUID = -6290411814232723403L;
        protected String _method;
        protected transient UserIdentity _userIdentity;

        public AbstractSucceeded(String method, UserIdentity userIdentity)
        {
            _method = method;
            _userIdentity = userIdentity;
        }

        @Override
        public String getAuthMethod()
        {
            return _method;
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
                security.logout(this);
                Authenticator authenticator = security.getAuthenticator();

                AuthenticationState authenticationState = null;
                if (authenticator instanceof LoginAuthenticator loginAuthenticator)
                {
                    ((LoginAuthenticator)authenticator).logout(request, response);
                    authenticationState = new LoggedOutAuthentication(loginAuthenticator);
                }
                AuthenticationState.setAuthentication(request, authenticationState);
            }
        }

        private static class LoggedOutAuthentication implements Deferred
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
                _delegate = defer(authenticator);
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
}
