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

package org.eclipse.jetty.client;

import java.net.URI;

/**
 * A store for {@link Authentication}s and {@link Authentication.Result}s.
 */
public interface AuthenticationStore
{
    /**
     * @param authentication the {@link Authentication} to add
     */
    public void addAuthentication(Authentication authentication);

    /**
     * @param authentication the {@link Authentication} to remove
     */
    public void removeAuthentication(Authentication authentication);

    /**
     * Removes all {@link Authentication}s stored
     */
    public void clearAuthentications();

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
    public Authentication findAuthentication(String type, URI uri, String realm);

    /**
     * @param result the {@link Authentication.Result} to add
     */
    public void addAuthenticationResult(Authentication.Result result);

    /**
     * @param result the {@link Authentication.Result} to remove
     */
    public void removeAuthenticationResult(Authentication.Result result);

    /**
     * Removes all authentication results stored
     */
    public void clearAuthenticationResults();

    /**
     * Returns an {@link Authentication.Result} that matches the given URI, or null if no
     * {@link Authentication.Result}s match the given URI.
     *
     * @param uri the request URI
     * @return the {@link Authentication.Result} that matches the given URI, or null
     */
    public Authentication.Result findAuthenticationResult(URI uri);

    /**
     * @return false if there are no stored authentication results, true if there may be some.
     */
    public default boolean hasAuthenticationResults()
    {
        return true;
    }
}
