//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http.pathmap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegexPathSpec extends PathSpec
{
    protected Pattern pattern;

    protected RegexPathSpec()
    {
        super();
    }

    public RegexPathSpec(String regex)
    {
        super.pathSpec = regex;
        boolean inGrouping = false;
        this.pathDepth = 0;
        this.specLength = pathSpec.length();
        // build up a simple signature we can use to identify the grouping
        StringBuilder signature = new StringBuilder();
        for (char c : pathSpec.toCharArray())
        {
            switch (c)
            {
                case '[':
                    inGrouping = true;
                    break;
                case ']':
                    inGrouping = false;
                    signature.append('g'); // glob
                    break;
                case '*':
                    signature.append('g'); // glob
                    break;
                case '/':
                    if (!inGrouping)
                    {
                        this.pathDepth++;
                    }
                    break;
                default:
                    if (!inGrouping)
                    {
                        if (Character.isLetterOrDigit(c))
                        {
                            signature.append('l'); // literal (exact)
                        }
                    }
                    break;
            }
        }
        this.pattern = Pattern.compile(pathSpec);

        // Figure out the grouping based on the signature
        String sig = signature.toString();

        if (Pattern.matches("^l*$",sig))
        {
            this.group = PathSpecGroup.EXACT;
        }
        else if (Pattern.matches("^l*g+",sig))
        {
            this.group = PathSpecGroup.PREFIX_GLOB;
        }
        else if (Pattern.matches("^g+l+$",sig))
        {
            this.group = PathSpecGroup.SUFFIX_GLOB;
        }
        else
        {
            this.group = PathSpecGroup.MIDDLE_GLOB;
        }
    }

    public Matcher getMatcher(String path)
    {
        return this.pattern.matcher(path);
    }

    @Override
    public String getPathInfo(String path)
    {
        // Path Info only valid for PREFIX_GLOB types
        if (group == PathSpecGroup.PREFIX_GLOB)
        {
            Matcher matcher = getMatcher(path);
            if (matcher.matches())
            {
                if (matcher.groupCount() >= 1)
                {
                    String pathInfo = matcher.group(1);
                    if ("".equals(pathInfo))
                    {
                        return "/";
                    }
                    else
                    {
                        return pathInfo;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String getPathMatch(String path)
    {
        Matcher matcher = getMatcher(path);
        if (matcher.matches())
        {
            if (matcher.groupCount() >= 1)
            {
                int idx = matcher.start(1);
                if (idx > 0)
                {
                    if (path.charAt(idx - 1) == '/')
                    {
                        idx--;
                    }
                    return path.substring(0,idx);
                }
            }
            return path;
        }
        return null;
    }

    public Pattern getPattern()
    {
        return this.pattern;
    }

    @Override
    public String getRelativePath(String base, String path)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean matches(final String path)
    {
        int idx = path.indexOf('?');
        if (idx >= 0)
        {
            // match only non-query part
            return getMatcher(path.substring(0,idx)).matches();
        }
        else
        {
            // match entire path
            return getMatcher(path).matches();
        }
    }
}
