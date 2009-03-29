// ========================================================================
// $Id$
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
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.PathMap;
import org.eclipse.jetty.util.URIUtil;

/**
 * Rule implementing the legacy API of RewriteHandler
 * 
 *
 */
public class LegacyRule extends Rule
{
    private PathMap _rewrite = new PathMap(true);
    
    public LegacyRule()
    {
        _handling = false;
        _terminating = false;
    }

    /* ------------------------------------------------------------ */
    public String matchAndApply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        Map.Entry<?,?> rewrite =_rewrite.getMatch(target);
        
        if (rewrite!=null && rewrite.getValue()!=null)
        {
            target=URIUtil.addPaths(rewrite.getValue().toString(),
                    PathMap.pathInfo(rewrite.getKey().toString(),target));

            return target;
        }
        
        return null;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Returns the map of rewriting rules.
     * @return A {@link PathMap} of the rewriting rules.
     */
    public PathMap getRewrite()
    {
        return _rewrite;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets the map of rewriting rules.
     * @param rewrite A {@link PathMap} of the rewriting rules. Only 
     * prefix paths should be included.
     */
    public void setRewrite(PathMap rewrite)
    {
        _rewrite=rewrite;
    }
    
    
    /* ------------------------------------------------------------ */
    /** Add a path rewriting rule
     * @param pattern The path pattern to match. The pattern must start with / and may use
     * a trailing /* as a wildcard.
     * @param prefix The path prefix which will replace the matching part of the path.
     */
    public void addRewriteRule(String pattern, String prefix)
    {
        if (pattern==null || pattern.length()==0 || !pattern.startsWith("/"))
            throw new IllegalArgumentException();
        if (_rewrite==null)
            _rewrite=new PathMap(true);
        _rewrite.put(pattern,prefix);
    }


}
