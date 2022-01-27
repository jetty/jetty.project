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

package org.eclipse.jetty.rewrite.handler;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.URIUtil;

/**
 * Utility for managing redirect based rules
 */
public final class RedirectUtil
{
    /**
     * Common point to generate a proper "Location" header for redirects.
     *
     * @param request the request the redirect should be based on (needed when relative locations are provided, so that
     * server name, scheme, port can be built out properly)
     * @param location the location URL to redirect to (can be a relative path)
     * @return the full redirect "Location" URL (including scheme, host, port, path, etc...)
     */
    public static String toRedirectURL(final HttpServletRequest request, String location)
    {
        if (!URIUtil.hasScheme(location))
        {
            StringBuilder url = new StringBuilder(128);
            URIUtil.appendSchemeHostPort(url, request.getScheme(), request.getServerName(), request.getServerPort());

            if (location.startsWith("/"))
            {
                // absolute in context
                location = URIUtil.canonicalURI(location);
            }
            else
            {
                // relative to request
                String path = request.getRequestURI();
                String parent = (path.endsWith("/")) ? path : URIUtil.parentPath(path);
                location = URIUtil.canonicalURI(URIUtil.addEncodedPaths(parent, location));
                if (location != null && !location.startsWith("/"))
                    url.append('/');
            }

            if (location == null)
                throw new IllegalStateException("redirect path cannot be above root");
            url.append(location);

            location = url.toString();
        }

        return location;
    }
}
