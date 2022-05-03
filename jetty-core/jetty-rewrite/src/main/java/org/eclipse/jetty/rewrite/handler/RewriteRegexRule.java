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
 * <p>The replacement String follows standard {@link Matcher#replaceAll(String)} behavior, including named groups</p>
 * <p></p>
 */
public class RewriteRegexRule extends RegexRule
{
    private String replacement;

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
        this.replacement = replacement;
    }

    @Override
    public Request.WrapperProcessor apply(Request.WrapperProcessor input, Matcher matcher) throws IOException
    {
        HttpURI httpURI = input.getHttpURI();
        String replacedPath = matcher.replaceAll(replacement);

        HttpURI newURI = HttpURI.build(httpURI, replacedPath);
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
        return "%s[rewrite:%s]".formatted(super.toString(), replacement);
    }
}
