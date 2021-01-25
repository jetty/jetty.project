//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

public class RegexPathSpec extends AbstractPathSpec
{
    private final String _declaration;
    private final PathSpecGroup _group;
    private final int _pathDepth;
    private final int _specLength;
    private final Pattern _pattern;

    public RegexPathSpec(String regex)
    {
        String declaration;
        if (regex.startsWith("regex|"))
            declaration = regex.substring("regex|".length());
        else
            declaration = regex;
        int specLength = declaration.length();
        // build up a simple signature we can use to identify the grouping
        boolean inGrouping = false;
        StringBuilder signature = new StringBuilder();

        int pathDepth = 0;
        for (int i = 0; i < declaration.length(); i++)
        {
            char c = declaration.charAt(i);
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
                        pathDepth++;
                    break;
                default:
                    if (!inGrouping && Character.isLetterOrDigit(c))
                        signature.append('l'); // literal (exact)
                    break;
            }
        }
        Pattern pattern = Pattern.compile(declaration);

        // Figure out the grouping based on the signature
        String sig = signature.toString();

        PathSpecGroup group;
        if (Pattern.matches("^l*$", sig))
            group = PathSpecGroup.EXACT;
        else if (Pattern.matches("^l*g+", sig))
            group = PathSpecGroup.PREFIX_GLOB;
        else if (Pattern.matches("^g+l+$", sig))
            group = PathSpecGroup.SUFFIX_GLOB;
        else
            group = PathSpecGroup.MIDDLE_GLOB;

        _declaration = declaration;
        _group = group;
        _pathDepth = pathDepth;
        _specLength = specLength;
        _pattern = pattern;
    }

    protected Matcher getMatcher(String path)
    {
        return _pattern.matcher(path);
    }

    @Override
    public int getSpecLength()
    {
        return _specLength;
    }

    @Override
    public PathSpecGroup getGroup()
    {
        return _group;
    }

    @Override
    public int getPathDepth()
    {
        return _pathDepth;
    }

    @Override
    public String getPathInfo(String path)
    {
        // Path Info only valid for PREFIX_GLOB types
        if (_group == PathSpecGroup.PREFIX_GLOB)
        {
            Matcher matcher = getMatcher(path);
            if (matcher.matches())
            {
                if (matcher.groupCount() >= 1)
                {
                    String pathInfo = matcher.group(1);
                    if ("".equals(pathInfo))
                        return "/";
                    else
                        return pathInfo;
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
                        idx--;
                    return path.substring(0, idx);
                }
            }
            return path;
        }
        return null;
    }

    @Override
    public String getDeclaration()
    {
        return _declaration;
    }

    @Override
    public String getPrefix()
    {
        return null;
    }

    @Override
    public String getSuffix()
    {
        return null;
    }

    public Pattern getPattern()
    {
        return _pattern;
    }

    @Override
    public boolean matches(final String path)
    {
        int idx = path.indexOf('?');
        if (idx >= 0)
        {
            // match only non-query part
            return getMatcher(path.substring(0, idx)).matches();
        }
        else
        {
            // match entire path
            return getMatcher(path).matches();
        }
    }
}
