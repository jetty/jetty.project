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

package org.eclipse.jetty.client;

import java.net.URI;

/**
 * <p>Abstract base class for authentication implementations.
 * Provides common functionality for URI and realm matching.</p>
 */
public abstract class AbstractAuthentication implements Authentication
{
    private final URI uri;
    private final String realm;

    /**
     * Creates an authentication for the given URI and realm.
     *
     * @param uri the URI this authentication applies to
     * @param realm the authentication realm
     */
    public AbstractAuthentication(URI uri, String realm)
    {
        this.uri = uri;
        this.realm = realm;
    }

    public abstract String getType();

    /**
     * @return the URI this authentication applies to
     */
    public URI getURI()
    {
        return uri;
    }

    /**
     * @return the authentication realm
     */
    public String getRealm()
    {
        return realm;
    }

    @Override
    public boolean matches(String type, URI uri, String realm)
    {
        if (!getType().equalsIgnoreCase(type))
            return false;

        if (!this.realm.equals(ANY_REALM) && !this.realm.equals(realm))
            return false;

        return matchesURI(this.uri, uri);
    }

    public static boolean matchesURI(URI uri1, URI uri2)
    {
        String scheme = uri1.getScheme();
        if (scheme.equalsIgnoreCase(uri2.getScheme()))
        {
            if (uri1.getHost().equalsIgnoreCase(uri2.getHost()))
            {
                // Handle default HTTP ports.
                int thisPort = HttpClient.normalizePort(scheme, uri1.getPort());
                int thatPort = HttpClient.normalizePort(scheme, uri2.getPort());
                if (thisPort == thatPort)
                {
                    // Use decoded URI paths.
                    return uri2.getPath().startsWith(uri1.getPath());
                }
            }
        }
        return false;
    }
}
