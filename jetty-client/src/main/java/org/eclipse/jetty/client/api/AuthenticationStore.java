//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

/**
 * A store for {@link Authentication}s and {@link Authentication.Result}s.
 */
public interface AuthenticationStore
{
    /**
     * @param authentication the {@link Authentication} to add
     */
    void addAuthentication(Authentication authentication);

    /**
     * @param authentication the {@link Authentication} to remove
     */
    void removeAuthentication(Authentication authentication);

    /**
     * Removes all {@link Authentication}s stored
     */
    void clearAuthentications();

    /**
     * Returns the authentication that matches the given type (for example, "Basic" or "Digest"),
     * the given request URI and the given realm.
     * If no such authentication can be found, returns null.
     *
     * @param type the {@link Authentication} type such as "Basic" or "Digest"
     * @param uri the request URI
     * @param realm the authentication realm
     * @return the authentication that matches the given parameters, or null
     */
    Authentication findAuthentication(String type, URI uri, String realm);

    /**
     * @param result the {@link Authentication.Result} to add
     */
    void addAuthenticationResult(Authentication.Result result);

    /**
     * @param result the {@link Authentication.Result} to remove
     */
    void removeAuthenticationResult(Authentication.Result result);

    /**
     * Removes all authentication results stored
     */
    void clearAuthenticationResults();

    /**
     * Returns an {@link Authentication.Result} that matches the given URI, or null if no
     * {@link Authentication.Result}s match the given URI.
     *
     * @param uri the request URI
     * @return the {@link Authentication.Result} that matches the given URI, or null
     */
    Authentication.Result findAuthenticationResult(URI uri);

    /**
     * @return false if there are no stored authentication results, true if there may be some.
     */
    default boolean hasAuthenticationResults()
    {
        return true;
    }
}
