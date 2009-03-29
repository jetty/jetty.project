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
import java.util.regex.Matcher;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Rewrite the URI by matching with a regular expression. 
 * The replacement string may use $n" to replace the nth capture group.
 */
public class RewriteRegexRule extends RegexRule
{
    private String _replacement;

    /* ------------------------------------------------------------ */
    public RewriteRegexRule()
    {
        _handling = false;
        _terminating = false;
    }

    /* ------------------------------------------------------------ */
    /**
     * Whenever a match is found, it replaces with this value.
     * 
     * @param replacement the replacement string.
     */
    public void setReplacement(String replacement)
    {
        _replacement = replacement;
    }


    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.eclipse.jetty.server.handler.rules.RegexRule#apply(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.util.regex.Matcher)
     */
    public String apply(String target, HttpServletRequest request, HttpServletResponse response, Matcher matcher) throws IOException
    {
        target=_replacement;
        for (int g=1;g<=matcher.groupCount();g++)
        {
            String group = matcher.group(g);
            target=target.replaceAll("\\$"+g,group);
        }

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
