//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
     * @param request
     *            the request the redirect should be based on (needed when relative locations are provided, so that
     *            server name, scheme, port can be built out properly)
     * @param location
     *            the location URL to redirect to (can be a relative path)
     * @return the full redirect "Location" URL (including scheme, host, port, path, etc...)
     */
    public static String toRedirectURL(final HttpServletRequest request, String location)
    {
        if (!URIUtil.hasScheme(location))
        {
            StringBuilder url = new StringBuilder(128);
            URIUtil.appendSchemeHostPort(url,request.getScheme(),request.getServerName(),request.getServerPort());

            if (location.startsWith("/"))
            {
                // absolute in context
                location = URIUtil.canonicalPath(location);
            }
            else
            {
                // relative to request
                String path = request.getRequestURI();
                String parent = (path.endsWith("/")) ? path : URIUtil.parentPath(path);
                location = URIUtil.canonicalPath(URIUtil.addPaths(parent,location));
                if (!location.startsWith("/"))
                    url.append('/');
            }

            if (location == null)
                throw new IllegalStateException("path cannot be above root");
            url.append(location);

            location = url.toString();
        }

        return location;
    }
}
