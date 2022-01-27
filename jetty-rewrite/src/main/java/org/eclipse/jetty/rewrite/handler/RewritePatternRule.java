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

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.Name;

/**
 * Rewrite the URI by replacing the matched {@link ServletPathSpec} path with a fixed string.
 */
public class RewritePatternRule extends PatternRule implements Rule.ApplyURI
{
    private String _replacement;
    private String _query;

    public RewritePatternRule()
    {
        this(null, null);
    }

    public RewritePatternRule(@Name("pattern") String pattern, @Name("replacement") String replacement)
    {
        super(pattern);
        _handling = false;
        _terminating = false;
        setReplacement(replacement);
    }

    /**
     * Whenever a match is found, it replaces with this value.
     *
     * @param replacement the replacement string.
     */
    public void setReplacement(String replacement)
    {
        if (replacement == null)
        {
            _replacement = null;
            _query = null;
        }
        else
        {
            String[] split = replacement.split("\\?", 2);
            _replacement = split[0];
            _query = split.length == 2 ? split[1] : null;
        }
    }

    @Override
    public String apply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        target = URIUtil.addPaths(_replacement, ServletPathSpec.pathInfo(_pattern, target));
        return target;
    }

    /**
     * This method will add _query to the requests's queryString and also combine it with existing queryStrings in
     * the request. However it won't take care for duplicate. E.g. if request.getQueryString contains a parameter
     * <code>param1 = true</code> and _query will contain <code>param1=false</code> the result will be <code>param1=true&amp;param1=false</code>.
     * To cover this use case some more complex pattern matching is necessary. We can implement this if there's use
     * cases.
     *
     * @param request the request
     * @param oldURI the old URI
     * @param newURI the new URI
     * @throws IOException if unable to apply the URI
     */
    @Override
    public void applyURI(Request request, String oldURI, String newURI) throws IOException
    {
        HttpURI baseURI = request.getHttpURI();
        String query = URIUtil.addQueries(baseURI.getQuery(), _query);
        request.setHttpURI(HttpURI.build(baseURI, newURI, baseURI.getParam(), query));
    }

    /**
     * Returns the replacement string.
     */
    @Override
    public String toString()
    {
        return super.toString() + "[" + _replacement + "]";
    }
}
