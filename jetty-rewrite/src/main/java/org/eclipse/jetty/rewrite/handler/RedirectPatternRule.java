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

/**
 * Redirects the response whenever the rule finds a match.
 */
public class RedirectPatternRule extends PatternRule
{
    private String _location;

    /* ------------------------------------------------------------ */
    public RedirectPatternRule()
    {
        _handling = true;
        _terminating = true;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the redirect location.
     * 
     * @param value the location to redirect.
     */
    public void setLocation(String value)
    {
        _location = value;
    }

    /* ------------------------------------------------------------ */
    /*
     * (non-Javadoc)
     * @see org.eclipse.jetty.server.server.handler.rules.RuleBase#apply(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    public String apply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        response.sendRedirect(_location);
        return target;
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the redirect location.
     */
    public String toString()
    {
        return super.toString()+"["+_location+"]";
    }
}
