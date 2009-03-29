// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.util.URIUtil;

/**
 * Rewrite the URI by replacing the matched {@link PathMap} path with a fixed string. 
 */
public class RewritePatternRule extends PatternRule
{
    private String _replacement;

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
     * @param value the replacement string.
     */
    public void setReplacement(String value)
    {
        _replacement = value;
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     * @see org.eclipse.jetty.server.handler.rules.RuleBase#apply(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    public String apply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        target = URIUtil.addPaths(_replacement, PathMap.pathInfo(_pattern,target));   
        return target;
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the replacement string.
     */
    public String toString()
    {
        return super.toString()+"["+_replacement+"]";
    }
}
