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

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;

/**
 * <p>An {@link Authenticator} whose {@link #validateServletRequest} needs the servlet environment,
 * so the {@link SecurityHandler} cannot call it itself.</p>
 * <p>For a request it defers, the {@link SecurityHandler} instead passes its decision in the
 * {@link #MUST_VALIDATE_KEY} and {@link #CONSTRAINT_KEY} request attributes, and the servlet layer
 * authenticates the request.</p>
 */
public interface ServletAuthenticator extends Authenticator
{
    String MUST_VALIDATE_KEY = ServletAuthenticator.class.getName() + ".MUST_VALIDATE_KEY";
    String CONSTRAINT_KEY = ServletAuthenticator.class.getName() + ".CONSTRAINT_KEY";

    /**
     * <p>Implemented by the servlet layer that calls a {@link ServletAuthenticator} for the requests
     * a {@link SecurityHandler} passes to it.</p>
     * <p>A {@link SecurityHandler} deferring to a {@link ServletAuthenticator} does not validate or
     * authorize the request itself, so it does not start unless the servlet layer says it will.</p>
     * <p>The only Validator asked is the {@link ContextHandler#getCurrentContextHandler() context
     * handler the SecurityHandler starts within}, and it is asked once as that handler starts, so a
     * context must hold the security handler it will use before it starts.</p>
     */
    interface Validator
    {
        /**
         * @param securityHandler a {@link SecurityHandler} whose {@link Authenticator} is a {@link ServletAuthenticator}.
         * @return whether the requests that handler passes on are validated by this servlet layer.
         */
        boolean validatesRequestsFor(SecurityHandler securityHandler);
    }

    /**
     * <p>Whether the {@link SecurityHandler} leaves this request to the servlet layer.</p>
     * <p>An implementation that stands in for another {@link Authenticator} answers false for a
     * request that other Authenticator is to serve, and the {@link SecurityHandler} then validates
     * and authorizes the request itself by calling
     * {@link Authenticator#validateRequest(Request, Response, Callback)} as it does for any other
     * Authenticator.</p>
     *
     * @param request the request.
     * @return true if the servlet layer validates this request.
     */
    default boolean deferValidation(Request request)
    {
        return true;
    }

    /**
     * <p>Validate a request for the {@link SecurityHandler}, before it is dispatched.</p>
     * <p>This is called for every request the {@link SecurityHandler} defers, mandatory or not,
     * unlike {@link Authenticator#validateRequest(Request, Response, Callback)}, which it calls
     * only for a mandatory request.</p>
     *
     * <p>There is no {@link Callback}: an implementation that answers the request writes the
     * response through the Servlet API and returns an {@link AuthenticationState.ResponseSent}
     * state, which stops the dispatch.</p>
     *
     * @param request the request.
     * @param response the response.
     * @param mandatory whether the constraints require this request to be authenticated.
     * @return the state the request was authenticated with, or null if it was not. Any state other
     * than {@link AuthenticationState.Succeeded} or {@link AuthenticationState.ResponseSent} is
     * treated as not authenticated. The servlet layer cannot serve an
     * {@link AuthenticationState.ServeAs}, and fails the request if one is returned.
     * @throws ServerAuthException if the authentication could not be performed.
     */
    AuthenticationState validateServletRequest(Request request, Response response, boolean mandatory) throws ServerAuthException;

    /**
     * <p>Secure the response, after the servlet has finished with it and before it is
     * completed.</p>
     * <p>Returning means the response may be sent as it stands. An implementation that could not
     * secure the response must throw, as the unsecured response must not reach the client: the
     * servlet layer replaces an uncommitted response with a 500, and aborts a committed one.</p>
     *
     * @param request the request.
     * @param response the response.
     * @throws ServerAuthException if the response could not be secured.
     */
    void secureResponse(Request request, Response response) throws ServerAuthException;
}
