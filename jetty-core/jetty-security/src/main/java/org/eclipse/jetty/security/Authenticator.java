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

import java.util.Set;
import java.util.function.Function;

import org.eclipse.jetty.security.Authentication.User;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.Callback;

/**
 * Authenticator Interface
 * <p>
 * An Authenticator is responsible for checking requests and sending
 * response challenges in order to authenticate a request.
 * Various types of {@link Authentication} are returned in order to
 * signal the next step in authentication.
 *
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public interface Authenticator
{
    String BASIC_AUTH = "BASIC";
    String FORM_AUTH = "FORM";
    String DIGEST_AUTH = "DIGEST";
    String CERT_AUTH = "CLIENT_CERT";
    String CERT_AUTH2 = "CLIENT-CERT";
    String SPNEGO_AUTH = "SPNEGO";
    String NEGOTIATE_AUTH = "NEGOTIATE";
    String OPENID_AUTH = "OPENID";

    /**
     * Configure the Authenticator
     *
     * @param configuration the configuration
     */
    void setConfiguration(AuthConfiguration configuration);

    /**
     * @return The name of the authentication method
     */
    String getAuthMethod();

    /**
     * TODO: fix javadoc and rename method.
     * Called after to validateRequest.
     * This may be restore method or content from a previous request
     * that was challenged.
     *
     * @param request the request to prepare for handling
     * @param authentication The authentication for the request
     */
    default Request prepareRequest(Request request, Authentication authentication)
    {
        return request;
    }

    /**
     * Get an {@link Constraint.Authorization} applicable to the path for
     * this authenticator.  This is typically used to vary protection on special URIs known to a
     * specific {@link Authenticator} (e.g. /j_security_check for
     * the {@link org.eclipse.jetty.security.authentication.FormAuthenticator}.
     *
     * @param pathInContext The pathInContext to potentially constrain.
     * @param existing The existing authentication constraint for the pathInContext determined independently of {@link Authenticator}
     * @param getSession Function to get or create a {@link Session}.
     * @return The {@link Constraint.Authorization} to apply.
     */
    default Constraint.Authorization getConstraintAuthentication(String pathInContext, Constraint.Authorization existing, Function<Boolean, Session> getSession)
    {
        return existing == null ? Constraint.Authorization.NONE : existing;
    }

    /**
     * Validate a request
     *
     * @param request The request
     * @param response The response
     * @param callback the callback to use for writing a response
     * @return An Authentication.  If Authentication is successful, this will be a {@link User}. If a response has
     * been sent by the Authenticator (which can be done for both successful and unsuccessful authentications), then the result will
     * implement {@link Authentication.ResponseSent}.
     * @throws ServerAuthException if unable to validate request
     */
    Authentication validateRequest(Request request, Response response, Callback callback) throws ServerAuthException;

    /**
     * Authenticator Configuration
     */
    interface AuthConfiguration
    {
        String getAuthMethod();

        String getRealmName();

        /**
         * Get a SecurityHandler init parameter
         *
         * @param param parameter name
         * @return Parameter value or null
         */
        String getParameter(String param);

        /**
         * Get a SecurityHandler init parameter names
         *
         * @return Set of parameter names
         */
        Set<String> getParameterNames();

        LoginService getLoginService();

        IdentityService getIdentityService();

        boolean isSessionRenewedOnAuthentication();

        class Wrapper implements AuthConfiguration
        {
            private final AuthConfiguration _configuration;

            public Wrapper(AuthConfiguration configuration)
            {
                _configuration = configuration;
            }

            @Override
            public String getAuthMethod()
            {
                return _configuration.getAuthMethod();
            }

            @Override
            public String getRealmName()
            {
                return _configuration.getRealmName();
            }

            @Override
            public String getParameter(String param)
            {
                return _configuration.getParameter(param);
            }

            @Override
            public Set<String> getParameterNames()
            {
                return _configuration.getParameterNames();
            }

            @Override
            public LoginService getLoginService()
            {
                return _configuration.getLoginService();
            }

            @Override
            public IdentityService getIdentityService()
            {
                return _configuration.getIdentityService();
            }

            @Override
            public boolean isSessionRenewedOnAuthentication()
            {
                return _configuration.isSessionRenewedOnAuthentication();
            }
        }
    }

    /**
     * Authenticator Factory
     */
    interface Factory
    {
        Authenticator getAuthenticator(Server server, Context context, AuthConfiguration configuration);
    }

    class Null implements Authenticator
    {
        @Override
        public void setConfiguration(AuthConfiguration configuration)
        {
        }

        @Override
        public String getAuthMethod()
        {
            return null;
        }

        @Override
        public Authentication validateRequest(Request request, Response response, Callback callback) throws ServerAuthException
        {
            return null;
        }
    }
}
