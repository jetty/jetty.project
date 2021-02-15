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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.Name;

/**
 * Rewrite the URI by replacing the matched {@link PathMap} path with a fixed string.
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
        target = URIUtil.addPaths(_replacement, PathMap.pathInfo(_pattern, target));
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
        if (_query == null)
        {
            request.setURIPathQuery(newURI);
        }
        else
        {
            String queryString = request.getQueryString();
            if (queryString != null)
                queryString = queryString + "&" + _query;
            else
                queryString = _query;
            request.setURIPathQuery(newURI);
            request.setQueryString(queryString);
        }
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
