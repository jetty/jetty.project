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

import org.eclipse.jetty.util.URIUtil;

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
        if (scheme == null || !scheme.equalsIgnoreCase(uri2.getScheme()))
            return false;

        String host = uri1.getHost();
        if (host == null || !host.equalsIgnoreCase(uri2.getHost()))
            return false;

        // Handle default HTTP ports.
        if (HttpClient.normalizePort(scheme, uri1.getPort()) != HttpClient.normalizePort(scheme, uri2.getPort()))
            return false;

        // Compare canonical paths.
        String path1 = URIUtil.canonicalPath(uri1.getRawPath());
        String path2 = URIUtil.canonicalPath(uri2.getRawPath());
        if (path1 == null || path2 == null)
            return false;

        if (path1.endsWith("/"))
            path1 = path1.substring(0, path1.length() - 1);

        if (path1.isEmpty())
            return true;

        if (!path2.startsWith(path1))
            return false;

        if (path2.length() == path1.length())
            return true;

        // Must match at a segment boundary.
        return path2.charAt(path1.length()) == '/';
    }
}
