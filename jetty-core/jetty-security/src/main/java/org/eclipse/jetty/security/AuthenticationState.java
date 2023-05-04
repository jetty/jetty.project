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

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.IdentityService.RunAsToken;
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
    /**
     * Get the authentication state of a request
     * @param request The request to query
     * @return The authentication state of the request or null if none.
     */
    static AuthenticationState getAuthenticationState(Request request)
    {
        Object auth = request.getAttribute(AuthenticationState.class.getName());
        return auth instanceof AuthenticationState authenticationState ? authenticationState : null;
    }

    /**
     * Set the authentication state of a request.
     * @param request The request to update
     * @param authenticationState the state to set on the request.
     */
    static void setAuthenticationState(Request request, AuthenticationState authenticationState)
    {
        request.setAttribute(AuthenticationState.class.getName(), authenticationState);
    }

    /**
     * Get the {@link UserPrincipal} of an authenticated request.  If the {@link AuthenticationState}
     * is {@link Deferred}, then an attempt to validate is made and the {@link AuthenticationState}
     * of the request is updated.
     * @see #authenticate(Request)
     * @param request The request to query
     * @return The {@link UserPrincipal} of any {@link Succeeded} authentication state, potential
     * after validating a {@link Deferred} state.
     */
    static Principal getUserPrincipal(Request request)
    {
        Succeeded succeeded = authenticate(request);
        if (succeeded == null)
            return null;

        return succeeded.getUserIdentity().getUserPrincipal();
    }

    /**
     * Get successful authentication for a request.  If the {@link AuthenticationState}
     * is {@link Deferred}, then an attempt to authenticate, but without sending a challenge.
     * @see Deferred#authenticate(Request)
     * @see #authenticate(Request, Response, Callback) if an authentication challenge should be sent.
     * @param request The request to query.
     * @return A {@link Succeeded} authentiction or null
     */
    static Succeeded authenticate(Request request)
    {
        AuthenticationState authenticationState = getAuthenticationState(request);

        // resolve any Deferred authentication
        if (authenticationState instanceof Deferred deferred)
            authenticationState = deferred.authenticate(request);

        //if authenticated, return the state
        if (authenticationState instanceof Succeeded succeeded)
            return succeeded;

        // else null
        return null;
    }

    /**
     * Get successful authentication for a request.  If the {@link AuthenticationState}
     * is {@link Deferred}, then an attempt to authenticate, possibly sending a challenge.
     * If authentication is not successful, then a {@link HttpStatus#FORBIDDEN_403} response is sent.
     * @see Deferred#authenticate(Request, Response, Callback)
     * @see #authenticate(Request) if an authentication challenge should not be sent.
     * @param request The request to query.
     * @param response The response to use for a challenge or error
     * @param callback The collback to complete if a challenge or error is sent.
     * @return A {@link Succeeded} authentication or null.  If null is returned, then the callback
     * will be completed.
     */
    static Succeeded authenticate(Request request, Response response, Callback callback)
    {
        AuthenticationState authenticationState = getAuthenticationState(request);

        // resolve any Deferred authentication
        if (authenticationState instanceof Deferred deferred)
        {
            authenticationState = deferred.authenticate(request, response, callback);
            if (authenticationState instanceof AuthenticationState.ResponseSent)
                return null;
        }

        // if already authenticated, return the state
        if (authenticationState instanceof Succeeded succeeded)
            return succeeded;

        Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
        return null;
    }

    /**
     * Attempt to login a request using the passed credentials.
     * The current {@link AuthenticationState} must be {@link Deferred}.
     * @see Deferred#login(String, String, Request, Response)
     * @param request The request to query.
     * @return A {@link Succeeded} authentiction or null
     */
    static Succeeded login(String username, String password, Request request, Response response)
    {
        AuthenticationState authenticationState = getAuthenticationState(request);

        // if already authenticated, throw
        if (authenticationState instanceof Succeeded)
            throw new HttpException.RuntimeException(HttpStatus.INTERNAL_SERVER_ERROR_500, "Already authenticated");

        // Use Deferred authentication to login
        if (authenticationState instanceof Deferred deferred)
        {
            Succeeded undeferred =  deferred.login(username, password, request, response);
            if (undeferred != null)
            {
                setAuthenticationState(request, undeferred);
                return undeferred;
            }
        }

        return null;
    }

    static boolean logout(Request request, Response response)
    {
        AuthenticationState authenticationState = getAuthenticationState(request);

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

    /**
     * A successful Authentication with User information.
     */
    interface Succeeded extends AuthenticationState
    {
        /**
         * @return The method used to authenticate the user.
         */
        String getAuthenticationType();

        /**
         * @return The {@link UserIdentity} of the authenticated user.
         */
        UserIdentity getUserIdentity();

        /**
         * @param role The role to check.
         * @return True if the user is in the passed role
         */
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

    /**
     * Authentication is Deferred, either so that credentials can later be passed
     * with {@link #login(String, String, Request, Response)}; or that existing
     * credentials on the request may be validated with {@link #authenticate(Request)};
     * or an authentication dialog can be advanced with {@link #authenticate(Request, Response, Callback)}.
     */
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

        /**
         * Authenticate the request using any credentials already associated with the request.
         * No challenge can be sent.  If the login is successful, then the
         * {@link IdentityService#associate(UserIdentity, RunAsToken)} method is used and the returned
         * {@link org.eclipse.jetty.security.IdentityService.Association} is made available via
         * {@link #getAssociation()}.
         * @see #getAssociation()
         * @param request The request to authenticate
         * @return A {@link Succeeded} authentication or null
         */
        Succeeded authenticate(Request request);

        /**
         * Authenticate the request using any credentials already associated with the request or
         * challenging if necessary.  If the login is successful, then the
         * {@link IdentityService#associate(UserIdentity, RunAsToken)} method is used and the returned
         * {@link org.eclipse.jetty.security.IdentityService.Association} is made available via
         * {@link #getAssociation()}.
         * @see #getAssociation()
         * @param request The request to authenticate
         * @param response The response to use for a challenge.
         * @param callback The callback to complete if a challenge is sent
         * @return The next {@link AuthenticationState}, if it is {@link ResponseSent}, then the
         * callback will be completed.
         */
        AuthenticationState authenticate(Request request, Response response, Callback callback);

        /**
         * Authenticate the request with the passed credentials
         * @param username The username to validate
         * @param password The credential to validate
         * @param request The request to authenticate
         * @param response The response, which may be updated if the session ID is changed.
         * @return A {@link Succeeded} authentication or null
         */
        Succeeded login(String username, Object password, Request request, Response response);

        /**
         * Logout the authenticated user.
         * @param request The authenticated request
         * @param response The associated response, which may be updated to clear a session ID.
         */
        void logout(Request request, Response response);

        /**
         * @return Any {@link org.eclipse.jetty.security.IdentityService.Association} created during
         * deferred authentication.
         * @see #authenticate(Request, Response, Callback)
         * @see #authenticate(Request)
         */
        IdentityService.Association getAssociation();

        /**
         * A tag interface used to identify a {@link Response} that might be passed to
         * {@link Authenticator#validateRequest(Request, Response, Callback)} while
         * doing deferred authentication when a challenge cannot be sent.
         * @see #authenticate(Request)
         */
        interface DeferredResponse extends Response
        {
        }
    }
}
