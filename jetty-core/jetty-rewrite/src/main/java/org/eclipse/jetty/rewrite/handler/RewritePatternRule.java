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

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A rule to rewrite the path and query that match a Servlet pattern with a fixed string.</p>
 */
public class RewritePatternRule extends PatternRule
{
    private static final Logger LOG = LoggerFactory.getLogger(RewritePatternRule.class);

    private String _path;
    private String _query;

    public RewritePatternRule()
    {
    }

    public RewritePatternRule(@Name("pattern") String pattern, @Name("replacement") String replacement)
    {
        super(pattern);
        setReplacement(replacement);
    }

    /**
     * <p>The replacement for the path and query matched by this rule.</p>
     *
     * @param replacement the replacement path
     */
    public void setReplacement(String replacement)
    {
        if (replacement == null)
        {
            _path = null;
            _query = null;
        }
        else
        {
            String[] split = replacement.split("\\?", 2);
            _path = split[0];
            _query = split.length == 2 ? split[1] : null;
        }
    }

    @Override
    public Request.WrapperProcessor apply(Request.WrapperProcessor input) throws IOException
    {
        HttpURI httpURI = input.getHttpURI();
        String newQuery = URIUtil.addQueries(httpURI.getQuery(), _query);
        String newPath = URIUtil.addPaths(_path, ServletPathSpec.pathInfo(getPattern(), httpURI.getPath()));
        HttpURI newURI = HttpURI.build(httpURI, newPath, httpURI.getParam(), newQuery);
        if (LOG.isDebugEnabled())
            LOG.debug("rewriting {} to {}", httpURI, newURI);
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
