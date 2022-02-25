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
import java.util.regex.Matcher;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.Name;

/**
 * Rewrite the URI by matching with a regular expression.
 * The replacement string may use $n" to replace the nth capture group.
 * If the replacement string contains ? character, then it is split into a path
 * and query string component.  The replacement query string may also contain $Q, which
 * is replaced with the original query string.
 * The returned target contains only the path.
 */
public class RewriteRegexRule extends RegexRule implements Rule.ApplyURI
{
    private String _replacement;
    private String _query;
    private boolean _queryGroup;

    public RewriteRegexRule()
    {
        this(null, null);
    }

    public RewriteRegexRule(@Name("regex") String regex, @Name("replacement") String replacement)
    {
        super(regex);
        setHandling(false);
        setTerminating(false);
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
            _queryGroup = false;
        }
        else
        {
            String[] split = replacement.split("\\?", 2);
            _replacement = split[0];
            _query = split.length == 2 ? split[1] : null;
            _queryGroup = _query != null && _query.contains("$Q");
        }
    }

    @Override
    public String apply(String target, HttpServletRequest request, HttpServletResponse response, Matcher matcher) throws IOException
    {
        target = _replacement;
        String query = _query;
        for (int g = 1; g <= matcher.groupCount(); g++)
        {
            String group = matcher.group(g);
            if (group == null)
                group = "";
            else
                group = Matcher.quoteReplacement(group);
            target = target.replaceAll("\\$" + g, group);
            if (query != null)
                query = query.replaceAll("\\$" + g, group);
        }

        if (query != null)
        {
            if (_queryGroup)
                query = query.replace("$Q", request.getQueryString() == null ? "" : request.getQueryString());
            request.setAttribute("org.eclipse.jetty.rewrite.handler.RewriteRegexRule.Q", query);
        }

        return target;
    }

    @Override
    public void applyURI(Request request, String oldURI, String newURI) throws IOException
    {
        HttpURI baseURI = request.getHttpURI();
        if (_query == null)
        {
            request.setHttpURI(HttpURI.build(baseURI, newURI, baseURI.getParam(), baseURI.getQuery()));
        }
        else
        {
            // TODO why isn't _query used?
            String query = (String)request.getAttribute("org.eclipse.jetty.rewrite.handler.RewriteRegexRule.Q");
            if (!_queryGroup)
                query = URIUtil.addQueries(baseURI.getQuery(), query);

            request.setHttpURI(HttpURI.build(baseURI, newURI, baseURI.getParam(), query));
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
