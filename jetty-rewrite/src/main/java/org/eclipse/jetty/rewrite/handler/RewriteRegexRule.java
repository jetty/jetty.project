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
import java.util.regex.Matcher;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.annotation.Name;

/**
 * Rewrite the URI by matching with a regular expression. 
 * The replacement string may use $n" to replace the nth capture group.
 * If the replacement string contains ? character, then it is split into a path
 * and query string component.  The replacement query string may also contain $Q, which 
 * is replaced with the original query string. 
 * The returned target contains only the path.
 */
public class RewriteRegexRule extends RegexRule  implements Rule.ApplyURI
{
    private String _replacement;
    private String _query;
    private boolean _queryGroup;

    /* ------------------------------------------------------------ */
    public RewriteRegexRule()
    {
        this(null,null);
    }
    
    /* ------------------------------------------------------------ */
    public RewriteRegexRule(@Name("regex") String regex, @Name("replacement") String replacement)
    {
        super(regex);
        setHandling(false);
        setTerminating(false);
        setReplacement(replacement);
    }

    /* ------------------------------------------------------------ */
    /**
     * Whenever a match is found, it replaces with this value.
     * 
     * @param replacement the replacement string.
     */
    public void setReplacement(String replacement)
    {
        if (replacement==null)
        {
            _replacement=null;
            _query=null;
            _queryGroup=false;
        }
        else
        {
            String[] split=replacement.split("\\?",2);
            _replacement = split[0];
            _query=split.length==2?split[1]:null;
            _queryGroup=_query!=null && _query.contains("$Q");
        }
    }


    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.eclipse.jetty.server.handler.rules.RegexRule#apply(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.util.regex.Matcher)
     */
    @Override
    public String apply(String target, HttpServletRequest request, HttpServletResponse response, Matcher matcher) throws IOException
    {
        target=_replacement;
        String query=_query;
        for (int g=1;g<=matcher.groupCount();g++)
        {
            String group=matcher.group(g);
            if (group==null)
                group="";
            else
                group = Matcher.quoteReplacement(group);
            target=target.replaceAll("\\$"+g,group);
            if (query!=null)
                query=query.replaceAll("\\$"+g,group);
        }

        if (query!=null)
        {
            if (_queryGroup)
                query=query.replace("$Q",request.getQueryString()==null?"":request.getQueryString());
            request.setAttribute("org.eclipse.jetty.rewrite.handler.RewriteRegexRule.Q",query);
        }
        
        return target;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void applyURI(Request request, String oldURI, String newURI) throws IOException
    {
        if (_query==null)
        {
            request.setURIPathQuery(newURI);
        }
        else
        {
            String query=(String)request.getAttribute("org.eclipse.jetty.rewrite.handler.RewriteRegexRule.Q");
            
            if (!_queryGroup && request.getQueryString()!=null)
                query=request.getQueryString()+"&"+query;
            request.setURIPathQuery(newURI);
            request.setQueryString(query);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the replacement string.
     */
    @Override
    public String toString()
    {
        return super.toString()+"["+_replacement+"]";
    }
}
