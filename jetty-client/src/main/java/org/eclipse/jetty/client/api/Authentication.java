//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client.api;

import java.net.URI;

import org.eclipse.jetty.util.Attributes;

/**
 * {@link Authentication} represents a mechanism to authenticate requests for protected resources.
 * <p />
 * {@link Authentication}s are added to an {@link AuthenticationStore}, which is then
 * {@link #matches(String, String, String) queried} to find the right
 * {@link Authentication} mechanism to use based on its type, URI and realm, as returned by
 * {@code WWW-Authenticate} response headers.
 * <p />
 * If an {@link Authentication} mechanism is found, it is then
 * {@link #authenticate(Request, ContentResponse, String, Attributes) executed} for the given request,
 * returning an {@link Authentication.Result}, which is then stored in the {@link AuthenticationStore}
 * so that subsequent requests can be preemptively authenticated.
 */
public interface Authentication
{
    /**
     * Matches {@link Authentication}s based on the given parameters
     * @param type the {@link Authentication} type such as "Basic" or "Digest"
     * @param uri the request URI
     * @param realm the authentication realm as provided in the {@code WWW-Authenticate} response header
     * @return true if this authentication matches, false otherwise
     */
    boolean matches(String type, URI uri, String realm);

    /**
     * Executes the authentication mechanism for the given request, returning a {@link Result} that can be
     * used to actually authenticate the request via {@link Result#apply(Request)}.
     * <p />
     * If a request for {@code "/secure"} returns a {@link Result}, then the result may be used for other
     * requests such as {@code "/secure/foo"} or {@code "/secure/bar"}, unless those resources are protected
     * by other realms.
     *
     * @param request the request to execute the authentication mechanism for
     * @param response the 401 response obtained in the previous attempt to request the protected resource
     * @param wwwAuthenticate the {@code WWW-Authenticate} header chosen for this authentication
     *                        (among the many that the response may contain)
     * @param context the conversation context in case the authentication needs multiple exchanges
     *                to be completed and information needs to be stored across exchanges
     * @return the authentication result, or null if the authentication could not be performed
     */
    Result authenticate(Request request, ContentResponse response, String wwwAuthenticate, Attributes context);

    /**
     * {@link Result} holds the information needed to authenticate a {@link Request} via {@link #apply(Request)}.
     */
    public static interface Result
    {
        /**
         * @return the URI of the request that has been used to generate this {@link Result}
         */
        URI getURI();

        /**
         * Applies the authentication result to the given request.
         * Typically, a {@code Authorization} header is added to the request, with the right information to
         * successfully authenticate at the server.
         *
         * @param request the request to authenticate
         */
        void apply(Request request);
    }
}
