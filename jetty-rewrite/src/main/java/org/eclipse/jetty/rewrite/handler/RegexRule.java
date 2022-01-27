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
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Abstract rule to use as a base class for rules that match with a regular expression.
 */
public abstract class RegexRule extends Rule
{
    protected Pattern _regex;

    protected RegexRule()
    {
    }

    protected RegexRule(String pattern)
    {
        setRegex(pattern);
    }

    /**
     * Sets the regular expression string used to match with string URI.
     *
     * @param regex the regular expression.
     */
    public void setRegex(String regex)
    {
        _regex = regex == null ? null : Pattern.compile(regex);
    }

    /**
     * @return get the regular expression
     */
    public String getRegex()
    {
        return _regex == null ? null : _regex.pattern();
    }

    @Override
    public String matchAndApply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        Matcher matcher = _regex.matcher(target);
        boolean matches = matcher.matches();
        if (matches)
            return apply(target, request, response, matcher);
        return null;
    }

    /**
     * Apply this rule to the request/response pair.
     * Called by {@link #matchAndApply(String, HttpServletRequest, HttpServletResponse)} if the regex matches.
     *
     * @param target field to attempt match
     * @param request request object
     * @param response response object
     * @param matcher The Regex matcher that matched the request (with capture groups available for replacement).
     * @return The target (possible updated).
     * @throws IOException exceptions dealing with operating on request or response objects
     */
    protected abstract String apply(String target, HttpServletRequest request, HttpServletResponse response, Matcher matcher) throws IOException;

    /**
     * Returns the regular expression string.
     */
    @Override
    public String toString()
    {
        return super.toString() + "[" + _regex + "]";
    }
}
