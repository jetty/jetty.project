//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

/**
 * Abstract rule that use a {@link PathMap} for pattern matching. It uses the 
 * servlet pattern syntax.
 */
public abstract class PatternRule extends Rule
{
    protected String _pattern;

    /* ------------------------------------------------------------ */
    protected PatternRule()
    {
    }

    /* ------------------------------------------------------------ */
    protected PatternRule(String pattern)
    {
        this();
        setPattern(pattern);
    }
    
    /* ------------------------------------------------------------ */
    public String getPattern()
    {
        return _pattern;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the rule pattern.
     * 
     * @param pattern the pattern
     */
    public void setPattern(String pattern)
    {
        _pattern = pattern;
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.eclipse.jetty.server.server.handler.rules.RuleBase#matchAndApply(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    public String matchAndApply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        if (PathMap.match(_pattern, target))
        {
            return apply(target,request, response);
        }
        return null;
    }

    /* ------------------------------------------------------------ */
    /** Apply the rule to the request
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
        return super.toString()+"["+_pattern+"]";                
    }
}
