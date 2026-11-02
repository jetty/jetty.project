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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.URIUtil;

/**
 * <p>Rewrites the URI by applying <a href="https://jakarta.ee/specifications/servlet/6.0/jakarta-servlet-spec-6.0.html#uri-path-canonicalization">Servlet 3.5.2. URI Path Canonicalization</a></p>
 *
 * @see URIUtil#canonicalServletPath(String)
 */
public class ServletCanonicalizedPathRule extends Rule
{
    private boolean preserveViolations = false;

    /**
     * Flag to preserve original HTTP UriCompliance Violations on any rewritten HTTP URI from this rule.
     *
     * @return true to preserve, false to not.
     */
    public boolean isPreserveViolations()
    {
        return preserveViolations;
    }

    /**
     * Flag to preserve original HTTP UriCompliance Violations on any rewritten HTTP URI from this rule.
     *
     * @param preserveViolations true to preserve, false to not.
     */
    public void setPreserveViolations(boolean preserveViolations)
    {
        this.preserveViolations = preserveViolations;
    }

    @Override
    public Handler matchAndApply(Handler input) throws IOException
    {
        // Get the path without extra things like path parameters
        String path = input.getHttpURI().getPath();
        path = URIUtil.canonicalServletPath(path);

        if (path == null)
        {
            // Attempted to navigate to above root URL
            path = "/";
        }

        HttpURI.Mutable uriBuilder = HttpURI.build(input.getHttpURI()).path(path);
        if (isPreserveViolations())
            uriBuilder.addViolations(input.getHttpURI().getViolations());
        HttpURI uri = uriBuilder.asImmutable();

        return new HttpURIHandler(input, uri);
    }
}
