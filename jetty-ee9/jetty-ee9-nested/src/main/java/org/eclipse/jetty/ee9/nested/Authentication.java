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

package org.eclipse.jetty.ee9.nested;

import java.security.Principal;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.server.Request;

/**
 * The Authentication state of a request.
 * <p>
 * The Authentication state can be one of several sub-types that
 * reflects where the request is in the many different authentication
 * cycles. Authentication might not yet be checked or it might be checked
 * and failed, checked and deferred or succeeded.
 */
public interface Authentication extends Request.AuthenticationState
{
    class Failed extends QuietServletException
    {
        public Failed(String message)
        {
            super(message);
        }
    }

    /**
     * A successful Authentication with User information.
     */
    interface User extends LogoutAuthentication
    {
        String getAuthMethod();

        UserIdentity getUserIdentity();

        boolean isUserInRole(UserIdentityScope scope, String role);

        @Override
        default Principal getUserPrincipal()
        {
            UserIdentity user = getUserIdentity();
            return user == null ? null : user.getUserPrincipal();
        }
    }

    /**
     * A wrapped authentication with methods provide the
     * wrapped request/response for use by the application
     */
    interface Wrapped extends Authentication
    {
        HttpServletRequest getHttpServletRequest();

        HttpServletResponse getHttpServletResponse();
    }

    /**
     * An authentication that is capable of performing a programmatic login
     * operation.
     */
    interface LoginAuthentication extends Authentication
    {

        /**
         * Login with the LOGIN authenticator
         *
         * @param username the username
         * @param password the password
         * @param request the request
         * @return The new Authentication state
         */
        Authentication login(String username, Object password, ServletRequest request);
    }

    /**
     * An authentication that is capable of performing a programmatic
     * logout operation.
     */
    interface LogoutAuthentication extends Authentication
    {

        /**
         * Remove any user information that may be present in the request
         * such that a call to getUserPrincipal/getRemoteUser will return null.
         *
         * @param request the request
         * @return NoAuthentication if we successfully logged out
         */
        Authentication logout(ServletRequest request);
    }

    /**
     * A deferred authentication with methods to progress
     * the authentication process.
     */
    interface Deferred extends LoginAuthentication, LogoutAuthentication
    {

        /**
         * Authenticate if possible without sending a challenge.
         * This is used to check credentials that have been sent for
         * non-mandatory authentication.
         *
         * @param request the request
         * @return The new Authentication state.
         */
        Authentication authenticate(ServletRequest request);

        /**
         * Authenticate and possibly send a challenge.
         * This is used to initiate authentication for previously
         * non-mandatory authentication.
         *
         * @param request the request
         * @param response the response
         * @return The new Authentication state.
         */
        Authentication authenticate(ServletRequest request, ServletResponse response);
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
     * An Authentication Challenge has been sent.
     */
    interface Challenge extends ResponseSent
    {
    }

    /**
     * An Authentication Failure has been sent.
     */
    interface Failure extends ResponseSent
    {
    }

    interface SendSuccess extends ResponseSent
    {
    }

    /**
     * After a logout, the authentication reverts to a state
     * where it is possible to programmatically log in again.
     */
    interface NonAuthenticated extends LoginAuthentication
    {
    }

    /**
     * Unauthenticated state.
     * <p>
     * This convenience instance is for non mandatory authentication where credentials
     * have been presented and checked, but failed authentication.
     */
    Authentication UNAUTHENTICATED =
        new Authentication()
        {
            @Override
            public String toString()
            {
                return "UNAUTHENTICATED";
            }
        };

    /**
     * Authentication not checked
     * <p>
     * This convenience instance us for non mandatory authentication when no
     * credentials are present to be checked.
     */
    Authentication NOT_CHECKED = new Authentication()
    {
        @Override
        public String toString()
        {
            return "NOT CHECKED";
        }
    };

    /**
     * Authentication challenge sent.
     * <p>
     * This convenience instance is for when an authentication challenge has been sent.
     */
    Authentication SEND_CONTINUE = new Authentication.Challenge()
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
    Authentication SEND_FAILURE = new Authentication.Failure()
    {
        @Override
        public String toString()
        {
            return "FAILURE";
        }
    };
    Authentication SEND_SUCCESS = new SendSuccess()
    {
        @Override
        public String toString()
        {
            return "SEND_SUCCESS";
        }
    };
}
