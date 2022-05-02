//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ServletPathSpec extends AbstractPathSpec
{
    private static final Logger LOG = Log.getLogger(ServletPathSpec.class);

    private final String _declaration;
    private final PathSpecGroup _group;
    private final int _pathDepth;
    private final int _specLength;
    private final String _prefix;
    private final String _suffix;

    /**
     * If a servlet or filter path mapping isn't a suffix mapping, ensure
     * it starts with '/'
     *
     * @param pathSpec the servlet or filter mapping pattern
     * @return the pathSpec prefixed by '/' if appropriate
     */
    public static String normalize(String pathSpec)
    {
        if (StringUtil.isNotBlank(pathSpec) && !pathSpec.startsWith("/") && !pathSpec.startsWith("*"))
            return "/" + pathSpec;
        return pathSpec;
    }

    public ServletPathSpec(String servletPathSpec)
    {
        if (servletPathSpec == null)
            servletPathSpec = "";
        if (servletPathSpec.startsWith("servlet|"))
            servletPathSpec = servletPathSpec.substring("servlet|".length());
        assertValidServletPathSpec(servletPathSpec);

        // The Root Path Spec
        if (servletPathSpec.isEmpty())
        {
            _declaration = "";
            _group = PathSpecGroup.ROOT;
            _pathDepth = -1; // Set pathDepth to -1 to force this to be at the end of the sort order.
            _specLength = 1;
            _prefix = null;
            _suffix = null;
            return;
        }

        // The Default Path Spec
        if ("/".equals(servletPathSpec))
        {
            _declaration = "/";
            _group = PathSpecGroup.DEFAULT;
            _pathDepth = -1; // Set pathDepth to -1 to force this to be at the end of the sort order.
            _specLength = 1;
            _prefix = null;
            _suffix = null;
            return;
        }

        int specLength = servletPathSpec.length();
        PathSpecGroup group;
        String prefix;
        String suffix;

        // prefix based
        if (servletPathSpec.charAt(0) == '/' && servletPathSpec.endsWith("/*"))
        {
            group = PathSpecGroup.PREFIX_GLOB;
            prefix = servletPathSpec.substring(0, specLength - 2);
            suffix = null;
        }
        // suffix based
        else if (servletPathSpec.charAt(0) == '*' && servletPathSpec.length() > 1)
        {
            group = PathSpecGroup.SUFFIX_GLOB;
            prefix = null;
            suffix = servletPathSpec.substring(2, specLength);
        }
        else
        {
            group = PathSpecGroup.EXACT;
            prefix = servletPathSpec;
            suffix = null;
            if (servletPathSpec.endsWith("*"))
            {
                LOG.warn("Suspicious URL pattern: '{}'; see sections 12.1 and 12.2 of the Servlet specification",
                        servletPathSpec);
            }
        }

        int pathDepth = 0;
        for (int i = 0; i < specLength; i++)
        {
            int cp = servletPathSpec.codePointAt(i);
            if (cp < 128)
            {
                char c = (char)cp;
                if (c == '/')
                    pathDepth++;
            }
        }

        _declaration = servletPathSpec;
        _group = group;
        _pathDepth = pathDepth;
        _specLength = specLength;
        _prefix = prefix;
        _suffix = suffix;

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Creating ServletPathSpec[{}] (group: {}, prefix: \"{}\", suffix: \"{}\")",
                _declaration, _group, _prefix, _suffix);
        }
    }

    private static void assertValidServletPathSpec(String servletPathSpec)
    {
        if ((servletPathSpec == null) || servletPathSpec.equals(""))
        {
            return; // empty path spec
        }

        int len = servletPathSpec.length();
        // path spec must either start with '/' or '*.'
        if (servletPathSpec.charAt(0) == '/')
        {
            // Prefix Based
            if (len == 1)
            {
                return; // simple '/' path spec
            }
            int idx = servletPathSpec.indexOf('*');
            if (idx < 0)
            {
                return; // no hit on glob '*'
            }
            // only allowed to have '*' at the end of the path spec
            if (idx != (len - 1))
            {
                throw new IllegalArgumentException("Servlet Spec 12.2 violation: glob '*' can only exist at end of prefix based matches: bad spec \"" + servletPathSpec + "\"");
            }
        }
        else if (servletPathSpec.startsWith("*."))
        {
            // Suffix Based
            int idx = servletPathSpec.indexOf('/');
            // cannot have path separator
            if (idx >= 0)
                throw new IllegalArgumentException("Servlet Spec 12.2 violation: suffix based path spec cannot have path separators: bad spec \"" + servletPathSpec + "\"");

            idx = servletPathSpec.indexOf('*', 2);
            // only allowed to have 1 glob '*', at the start of the path spec
            if (idx >= 1)
                throw new IllegalArgumentException("Servlet Spec 12.2 violation: suffix based path spec cannot have multiple glob '*': bad spec \"" + servletPathSpec + "\"");
        }
        else
        {
            throw new IllegalArgumentException("Servlet Spec 12.2 violation: path spec must start with \"/\" or \"*.\": bad spec \"" + servletPathSpec + "\"");
        }
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

    /**
     * @deprecated use {@link #matched(String)}#{@link MatchedPath#getPathInfo()} instead.
     */
    @Deprecated
    @Override
    public String getPathInfo(String path)
    {
        switch (_group)
        {
            case ROOT:
                return path;

            case PREFIX_GLOB:
                if (path.length() == (_specLength - 2))
                    return null;
                return path.substring(_specLength - 2);

            default:
                return null;
        }
    }

    /**
     * @deprecated use {@link #matched(String)}#{@link MatchedPath#getPathMatch()} instead.
     */
    @Deprecated
    @Override
    public String getPathMatch(String path)
    {
        switch (_group)
        {
            case ROOT:
                return "";

            case EXACT:
                if (_declaration.equals(path))
                    return path;
                return null;

            case PREFIX_GLOB:
                if (isWildcardMatch(path))
                    return path.substring(0, _specLength - 2);
                return null;

            case SUFFIX_GLOB:
                if (path.regionMatches(path.length() - (_specLength - 1), _declaration, 1, _specLength - 1))
                    return path;
                return null;

            case DEFAULT:
                return path;

            default:
                return null;
        }
    }

    @Override
    public String getDeclaration()
    {
        return _declaration;
    }

    @Override
    public String getPrefix()
    {
        return _prefix;
    }

    @Override
    public String getSuffix()
    {
        return _suffix;
    }

    private boolean isWildcardMatch(String path)
    {
        // For a spec of "/foo/*" match "/foo" , "/foo/..." but not "/foobar"
        int cpl = _specLength - 2;
        if ((_group == PathSpecGroup.PREFIX_GLOB) && (path.regionMatches(0, _declaration, 0, cpl)))
            return (path.length() == cpl) || ('/' == path.charAt(cpl));
        return false;
    }

    @Override
    public MatchedPath matched(String path)
    {
        switch (_group)
        {
            case EXACT:
                if (_declaration.equals(path))
                    return new ServletMatchedPath(path, null); // TODO: return final matchedpath
                break;
            case PREFIX_GLOB:
                if (isWildcardMatch(path))
                {
                    String pathMatch = path;
                    String pathInfo = null;
                    if (path.length() != (_specLength - 2))
                    {
                        pathMatch = path.substring(0, _specLength - 2);
                        pathInfo = path.substring(_specLength - 2);
                    }
                    return new ServletMatchedPath(pathMatch, pathInfo);
                }
                break;
            case SUFFIX_GLOB:
                if (path.regionMatches((path.length() - _specLength) + 1, _declaration, 1, _specLength - 1))
                    return new ServletMatchedPath(path, null);
                break;
            case ROOT:
                // Only "/" matches
                if ("/".equals(path))
                    return new ServletMatchedPath("", path); // TODO: review this
                break;
            case DEFAULT:
                // If we reached this point, then everything matches
                return new ServletMatchedPath(path, null);
        }
        return null;
    }

    @Override
    public boolean matches(String path)
    {
        switch (_group)
        {
            case EXACT:
                return _declaration.equals(path);
            case PREFIX_GLOB:
                return isWildcardMatch(path);
            case SUFFIX_GLOB:
                return path.regionMatches((path.length() - _specLength) + 1, _declaration, 1, _specLength - 1);
            case ROOT:
                // Only "/" matches
                return ("/".equals(path));
            case DEFAULT:
                // If we reached this point, then everything matches
                return true;
            default:
                return false;
        }
    }

    public static class ServletMatchedPath implements MatchedPath
    {
        private final String servletName;
        private final String pathInfo;

        public ServletMatchedPath(String servletName, String pathInfo)
        {
            this.servletName = servletName;
            this.pathInfo = pathInfo;
        }

        @Override
        public String getPathMatch()
        {
            return this.servletName;
        }

        @Override
        public String getPathInfo()
        {
            return this.pathInfo;
        }
    }
}
