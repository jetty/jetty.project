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

package org.eclipse.jetty.http.pathmap;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServletPathSpec extends AbstractPathSpec
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletPathSpec.class);

    private final String _declaration;
    private final PathSpecGroup _group;
    private final int _pathDepth;
    private final int _specLength;
    private final int _matchLength;
    private final String _prefix;
    private final String _suffix;
    private final MatchedPath _preMatchedPath;

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

    /**
     * @param pathSpec the path spec
     * @param path the path
     * @return true if match.
     */
    public static boolean match(String pathSpec, String path)
    {
        return match(pathSpec, path, false);
    }

    /**
     * @param pathSpec the path spec
     * @param path the path
     * @param noDefault true to not handle the default path "/" special, false to allow matcher rules to run
     * @return true if match.
     */
    public static boolean match(String pathSpec, String path, boolean noDefault)
    {
        if (pathSpec.length() == 0)
            return "/".equals(path);

        char c = pathSpec.charAt(0);
        if (c == '/')
        {
            if (!noDefault && pathSpec.length() == 1 || pathSpec.equals(path))
                return true;

            if (isPathWildcardMatch(pathSpec, path))
                return true;
        }
        else if (c == '*')
            return path.regionMatches(path.length() - pathSpec.length() + 1,
                pathSpec, 1, pathSpec.length() - 1);
        return false;
    }

    private static boolean isPathWildcardMatch(String pathSpec, String path)
    {
        // For a spec of "/foo/*" match "/foo" , "/foo/..." but not "/foobar"
        int cpl = pathSpec.length() - 2;
        if (pathSpec.endsWith("/*") && path.regionMatches(0, pathSpec, 0, cpl))
        {
            if (path.length() == cpl || '/' == path.charAt(cpl))
                return true;
        }
        return false;
    }

    /**
     * Return the portion of a path that matches a path spec.
     *
     * @param pathSpec the path spec
     * @param path the path
     * @return null if no match at all.
     */
    public static String pathMatch(String pathSpec, String path)
    {
        char c = pathSpec.charAt(0);

        if (c == '/')
        {
            if (pathSpec.length() == 1)
                return path;

            if (pathSpec.equals(path))
                return path;

            if (isPathWildcardMatch(pathSpec, path))
                return path.substring(0, pathSpec.length() - 2);
        }
        else if (c == '*')
        {
            if (path.regionMatches(path.length() - (pathSpec.length() - 1),
                pathSpec, 1, pathSpec.length() - 1))
                return path;
        }
        return null;
    }

    /**
     * Return the portion of a path that is after a path spec.
     *
     * @param pathSpec the path spec
     * @param path the path
     * @return The path info string
     */
    public static String pathInfo(String pathSpec, String path)
    {
        if ("".equals(pathSpec))
            return path; //servlet 3 spec sec 12.2 will be '/'

        char c = pathSpec.charAt(0);

        if (c == '/')
        {
            if (pathSpec.length() == 1)
                return null;

            boolean wildcard = isPathWildcardMatch(pathSpec, path);

            // handle the case where pathSpec uses a wildcard and path info is "/*"
            if (pathSpec.equals(path) && !wildcard)
                return null;

            if (wildcard)
            {
                if (path.length() == pathSpec.length() - 2)
                    return null;
                return path.substring(pathSpec.length() - 2);
            }
        }
        return null;
    }

    /**
     * Relative path.
     *
     * @param base The base the path is relative to.
     * @param pathSpec The spec of the path segment to ignore.
     * @param path the additional path
     * @return base plus path with pathspec removed
     */
    public static String relativePath(String base,
                                      String pathSpec,
                                      String path)
    {
        String info = pathInfo(pathSpec, path);
        if (info == null)
            info = path;

        if (info.startsWith("./"))
            info = info.substring(2);
        if (base.endsWith(URIUtil.SLASH))
            if (info.startsWith(URIUtil.SLASH))
                path = base + info.substring(1);
            else
                path = base + info;
        else if (info.startsWith(URIUtil.SLASH))
            path = base + info;
        else
            path = base + URIUtil.SLASH + info;
        return path;
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
            _matchLength = 0;
            _prefix = null;
            _suffix = null;
            _preMatchedPath = MatchedPath.from("", "/");
            return;
        }

        // The Default Path Spec
        if ("/".equals(servletPathSpec))
        {
            _declaration = "/";
            _group = PathSpecGroup.DEFAULT;
            _pathDepth = -1; // Set pathDepth to -1 to force this to be at the end of the sort order.
            _specLength = 1;
            _matchLength = 0;
            _prefix = null;
            _suffix = null;
            _preMatchedPath = null;
            return;
        }

        int specLength = servletPathSpec.length();
        PathSpecGroup group;
        String prefix;
        String suffix;
        MatchedPath preMatchedPath;

        // prefix based
        if (servletPathSpec.charAt(0) == '/' && servletPathSpec.endsWith("/*"))
        {
            group = PathSpecGroup.PREFIX_GLOB;
            prefix = servletPathSpec.substring(0, specLength - 2);
            suffix = null;
            preMatchedPath = MatchedPath.from(prefix, null);
        }
        // suffix based
        else if (servletPathSpec.charAt(0) == '*' && servletPathSpec.length() > 1)
        {
            group = PathSpecGroup.SUFFIX_GLOB;
            prefix = null;
            suffix = servletPathSpec.substring(2, specLength);
            preMatchedPath = null;
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
            preMatchedPath = MatchedPath.from(servletPathSpec, null);
        }

        int pathDepth = 0;
        for (int i = 0; i < specLength; i++)
        {
            char c = servletPathSpec.charAt(i);
            if (c < 128)
            {
                if (c == '/')
                    pathDepth++;
            }
        }

        _declaration = servletPathSpec;
        _group = group;
        _pathDepth = pathDepth;
        _specLength = specLength;
        _matchLength = prefix == null ? 0 : prefix.length();
        _prefix = prefix;
        _suffix = suffix;
        _preMatchedPath = preMatchedPath;

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Creating {}[{}] (group: {}, prefix: \"{}\", suffix: \"{}\")",
                getClass().getSimpleName(),
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
                if (path.length() == _matchLength)
                    return null;
                return path.substring(_matchLength);

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
                    return path.substring(0, _matchLength);
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
        if (_group == PathSpecGroup.PREFIX_GLOB && path.length() >= _matchLength && path.regionMatches(0, _declaration, 0, _matchLength))
            return path.length() == _matchLength || path.charAt(_matchLength) == '/';
        return false;
    }

    @Override
    public MatchedPath matched(String path)
    {
        switch (_group)
        {
            case EXACT:
                if (_declaration.equals(path))
                    return _preMatchedPath;
                break;
            case PREFIX_GLOB:
                if (isWildcardMatch(path))
                {
                    if (path.length() == _matchLength)
                        return _preMatchedPath;
                    return MatchedPath.from(path.substring(0, _matchLength), path.substring(_matchLength));
                }
                break;
            case SUFFIX_GLOB:
                if (path.regionMatches((path.length() - _specLength) + 1, _declaration, 1, _specLength - 1))
                    return MatchedPath.from(path, null);
                break;
            case ROOT:
                // Only "/" matches
                if ("/".equals(path))
                    return _preMatchedPath;
                break;
            case DEFAULT:
                // If we reached this point, then everything matches
                return MatchedPath.from(path, null);
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
}
