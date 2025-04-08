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
import java.util.regex.Matcher;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.Name;

/**
 * <p>A rule to rewrite the path and query that match a regular expression pattern with a fixed string.</p>
 * <p>The replacement String follows standard {@link Matcher#replaceAll(String)} behavior, including named groups</p>
 */
public class RewriteRegexRule extends RegexRule
{
    private String replacement;
    private boolean addQueries = false;

    public RewriteRegexRule()
    {
    }

    public RewriteRegexRule(@Name("regex") String regex, @Name("replacement") String replacement)
    {
        super(regex);
        setReplacement(replacement);
    }

    /**
     * <p>Is the input URI query added with replacement URI query</p>
     *
     * @return true to add input query with replacement query.
     */
    public boolean isAddQueries()
    {
        return addQueries;
    }

    /**
     * <p>Set if input query should be preserved, and added together with replacement query</p>
     *
     * <p>
     *     This is especially useful when used in combination with a disabled {@link #setMatchQuery(boolean)}
     * </p>
     *
     * @param flag true to have input query added with replacement query, false (default) to have query
     *    from input or output just be treated as a string, and not merged.
     */
    public void setAddQueries(boolean flag)
    {
        this.addQueries = flag;
    }

    /**
     * Whenever a match is found, it replaces with this value.
     *
     * @param replacement the replacement string.
     */
    public void setReplacement(String replacement)
    {
        this.replacement = replacement;
    }

    @Override
    public Handler apply(Handler input, Matcher matcher) throws IOException
    {
        HttpURI httpURI = input.getHttpURI();
        String replacedPath = matcher.replaceAll(replacement);

        HttpURI newURI = HttpURI.build(httpURI, replacedPath);
        if (isAddQueries())
        {
            String inputQuery = input.getHttpURI().getQuery();
            String targetQuery = newURI.getQuery();
            String resultingQuery = URIUtil.addQueries(inputQuery, targetQuery);
            newURI = HttpURI.build(newURI).query(resultingQuery);
        }
        return new HttpURIHandler(input, newURI);
    }

    @Override
    public String toString()
    {
        return "%s[rewrite:%s]".formatted(super.toString(), replacement);
    }
}
