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

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.annotation.Name;

/**
 * <p>A rule to rewrite the path and query that match a regular expression pattern with a fixed string.</p>
 * <p>The replacement string may use {@code $n} to replace the nth capture group.</p>
 * <p>If the replacement string contains the {@code ?} character,
 * then it is split into a path and query string component.</p>
 * <p>The replacement query string may also contain {@code $Q},
 * which is replaced with the original query string.</p>
 */
public class RewriteRegexRule extends RegexRule
{
    private String _path;
    private String _query;
    private boolean _queryGroup;

    public RewriteRegexRule(@Name("regex") String regex, @Name("replacement") String replacement)
    {
        super(regex);
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
            _path = null;
            _query = null;
            _queryGroup = false;
        }
        else
        {
            String[] split = replacement.split("\\?", 2);
            _path = split[0];
            _query = split.length == 2 ? split[1] : null;
            _queryGroup = _query != null && _query.contains("$Q");
        }
    }

    @Override
    public Request.WrapperProcessor apply(Request.WrapperProcessor input, Matcher matcher) throws IOException
    {
        String path = _path;
        String query = _query;
        for (int g = 1; g <= matcher.groupCount(); ++g)
        {
            String group = matcher.group(g);
            if (group == null)
                group = "";
            else
                group = Matcher.quoteReplacement(group);
            path = path.replaceAll("\\$" + g, group);
            if (query != null)
                query = query.replaceAll("\\$" + g, group);
        }

        HttpURI httpURI = input.getHttpURI();

        if (query != null)
        {
            if (_queryGroup)
            {
                String origQuery = httpURI.getQuery();
                query = query.replace("$Q", origQuery == null ? "" : origQuery);
            }
        }

        HttpURI newURI = HttpURI.build(httpURI, path, httpURI.getParam(), query);
        return new Request.WrapperProcessor(input)
        {
            @Override
            public HttpURI getHttpURI()
            {
                return newURI;
            }
        };
    }

    @Override
    public String toString()
    {
        return "%s[rewrite:%s%s]".formatted(super.toString(), _path, _query == null ? "" : "?" + _query);
    }
}
