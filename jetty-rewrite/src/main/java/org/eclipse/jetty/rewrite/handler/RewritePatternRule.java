//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.URIUtil;

/**
 * Rewrite the URI by replacing the matched {@link PathMap} path with a fixed string.
 */
public class RewritePatternRule extends PatternRule implements Rule.ApplyURI
{
    private String _replacement;
    private String _query;

    /* ------------------------------------------------------------ */
    public RewritePatternRule()
    {
        _handling = false;
        _terminating = false;
    }

    /* ------------------------------------------------------------ */
    /**
     * Whenever a match is found, it replaces with this value.
     *
     * @param replacement the replacement string.
     */
    public void setReplacement(String replacement)
    {
        String[] split = replacement.split("\\?", 2);
        _replacement = split[0];
        _query = split.length == 2 ? split[1] : null;
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     *
     * @see org.eclipse.jetty.server.handler.rules.RuleBase#apply(javax.servlet.http.HttpServletRequest,
     * javax.servlet.http.HttpServletResponse)
     */
    @Override
    public String apply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        target = URIUtil.addPaths(_replacement, PathMap.pathInfo(_pattern, target));
        return target;
    }

    /* ------------------------------------------------------------ */
    /**
     * This method will add _query to the requests's queryString and also combine it with existing queryStrings in
     * the request. However it won't take care for duplicate. E.g. if request.getQueryString contains a parameter
     * "param1 = true" and _query will contain "param1=false" the result will be param1=true&param1=false.
     * To cover this use case some more complex pattern matching is necessary. We can implement this if there's use
     * cases.
     *
     * @param request
     * @param oldURI
     * @param newURI
     * @throws IOException
     */
    @Override
    public void applyURI(Request request, String oldURI, String newURI) throws IOException
    {
        if (_query == null)
        {
            request.setRequestURI(newURI);
        }
        else
        {
            String queryString = request.getQueryString();
            if (queryString != null)
                queryString = queryString + "&" + _query;
            else
                queryString = _query;
            HttpURI uri = new HttpURI(newURI + "?" + queryString);
            request.setUri(uri);
            request.setRequestURI(newURI);
            request.setQueryString(queryString);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the replacement string.
     */
    @Override
    public String toString()
    {
        return super.toString()+"["+_replacement+"]";
    }
}
