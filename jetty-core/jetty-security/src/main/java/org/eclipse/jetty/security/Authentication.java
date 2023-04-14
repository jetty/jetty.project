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
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.security.authentication.DeferredAuthentication;
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
public interface Authentication
{
    static Authentication getAuthentication(Request request)
    {
        Object auth = request.getAttribute(Authentication.class.getName());
        return auth instanceof Authentication authentication ? authentication : null;
    }

    static Principal getUserPrincipal(Request request)
    {
        Authentication authentication = Authentication.getAuthentication(request);
        if (authentication instanceof UserAuthentication userAuthentication)
        {
            return userAuthentication.getUserIdentity().getUserPrincipal();
        }
        if (authentication instanceof DeferredAuthentication deferredAuthentication)
        {
            User user = deferredAuthentication.authenticate(request);
            if (user == null)
                return null;
            return user.getUserIdentity().getUserPrincipal();
        }
        return null;
    }

    static void setAuthentication(Request request, Authentication authentication)
    {
        request.setAttribute(Authentication.class.getName(), authentication);
    }

    static Authentication.User authenticate(Request request)
    {
        Authentication authentication = getAuthentication(request);

        //if already authenticated, return true
        if (authentication instanceof Authentication.User user)
            return user;

        //do the authentication
        if (authentication instanceof DeferredAuthentication deferred)
        {
            Authentication.User undeferred = deferred.authenticate(request);
            if (undeferred != null)
            {
                setAuthentication(request, undeferred);
                return undeferred;
            }
        }
        return null;
    }

    static Authentication.User authenticate(Request request, Response response, Callback callback)
    {
        Authentication authentication = getAuthentication(request);

        //if already authenticated, return true
        if (authentication instanceof Authentication.User user)
            return user;

        //do the authentication
        if (authentication instanceof DeferredAuthentication deferred)
        {
            Authentication undeferred = deferred.authenticate(request, response, callback);
            if (undeferred instanceof Authentication.ResponseSent)
                return null;

            if (undeferred instanceof Authentication.User user)
            {
                setAuthentication(request, undeferred);
                return user;
            }
        }
        Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
        return null;
    }

    static Authentication.User login(String username, String password, Request request, Response response)
    {
        Authentication authentication = getAuthentication(request);

        //if already authenticated, return true
        if (authentication instanceof Authentication.User)
            throw new HttpException.RuntimeException(HttpStatus.INTERNAL_SERVER_ERROR_500, "Already authenticated");

        //do the authentication
        if (authentication instanceof DeferredAuthentication deferred)
        {
            Authentication.User undeferred =  deferred.login(username, password, request, response);
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
        Authentication authentication = getAuthentication(request);

        //if already authenticated, return true
        if (authentication instanceof Authentication.User userAuthentication)
        {
            userAuthentication.logout(request, response);
            return true;
        }

        if (authentication instanceof DeferredAuthentication deferredAuthentication)
        {
            deferredAuthentication.logout(request, response);
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
    interface User extends Authentication
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
    interface ResponseSent extends Authentication
    {
    }

    /**
     * Authentication challenge sent.
     * <p>
     * This convenience instance is for when an authentication challenge has been sent.
     */
    Authentication CHALLENGE = new ResponseSent()
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
    Authentication SEND_FAILURE = new ResponseSent()
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
    Authentication SEND_SUCCESS = new ResponseSent()
    {
        @Override
        public String toString()
        {
            return "SEND_SUCCESS";
        }
    };
}
