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
import org.eclipse.jetty.http.pathmap.ServletPathSpec;

/**
 * Abstract rule that use a {@link ServletPathSpec} for pattern matching. It uses the
 * servlet pattern syntax.
 */
public abstract class PatternRule extends Rule
{
    protected String _pattern;

    protected PatternRule()
    {
    }

    protected PatternRule(String pattern)
    {
        this();
        setPattern(pattern);
    }

    public String getPattern()
    {
        return _pattern;
    }

    /**
     * Sets the rule pattern.
     *
     * @param pattern the pattern
     */
    public void setPattern(String pattern)
    {
        _pattern = pattern;
    }

    @Override
    public String matchAndApply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        if (ServletPathSpec.match(_pattern, target))
        {
            return apply(target, request, response);
        }
        return null;
    }

    /**
     * Apply the rule to the request
     *
     * @param target field to attempt match
     * @param request request object
     * @param response response object
     * @return The target (possible updated)
     * @throws IOException exceptions dealing with operating on request or response objects
     */
    protected abstract String apply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException;

    /**
     * Returns the rule pattern.
     */
    @Override
    public String toString()
    {
        return super.toString() + "[" + _pattern + "]";
    }
}
