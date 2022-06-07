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

package org.eclipse.jetty.server;

import java.util.Objects;
import javax.servlet.http.HttpServletMapping;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.MappingMatch;

import org.eclipse.jetty.http.pathmap.MatchedPath;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;

/**
 * Implementation of HttpServletMapping.
 *
 * Represents the application of a {@link ServletPathSpec} to a specific path
 * that resulted in a mapping to a {@link javax.servlet.Servlet}.
 * As well as supporting the standard {@link HttpServletMapping} methods, this
 * class also carries fields, which can be precomputed for the implementation
 * of {@link HttpServletRequest#getServletPath()} and
 * {@link HttpServletRequest#getPathInfo()}
 */
public class ServletPathMapping implements HttpServletMapping
{
    private final MappingMatch _mappingMatch;
    private final String _matchValue;
    private final String _pattern;
    private final String _servletName;
    private final String _servletPath;
    private final String _pathInfo;

    public ServletPathMapping(PathSpec pathSpec, String servletName, String pathInContext, MatchedPath matchedPath)
    {
        Objects.requireNonNull(pathSpec);
        _servletName = (servletName == null ? "" : servletName);
        _pattern = pathSpec.getDeclaration();
        _servletPath = matchedPath.getPathMatch();
        _pathInfo = matchedPath.getPathInfo();

        switch (pathSpec.getGroup())
        {
            case ROOT:
                _mappingMatch = MappingMatch.CONTEXT_ROOT;
                _matchValue = "";
                break;

            case DEFAULT:
                _mappingMatch = MappingMatch.DEFAULT;
                _matchValue = "";
                break;

            case EXACT:
                _mappingMatch = MappingMatch.EXACT;
                _matchValue = _pattern.startsWith("/") ? _pattern.substring(1) : _pattern;
                break;

            case PREFIX_GLOB:
                _mappingMatch = MappingMatch.PATH;
                // TODO avoid the substring on the known servletPath!
                _matchValue = _servletPath.startsWith("/") ? _servletPath.substring(1) : _servletPath;
                break;

            case SUFFIX_GLOB:
                _mappingMatch = MappingMatch.EXTENSION;
                int dot = pathInContext.lastIndexOf('.');
                _matchValue = pathInContext.substring(pathInContext.startsWith("/") ? 1 : 0, dot);
                break;

            case MIDDLE_GLOB:
            default:
                _mappingMatch = null;
                _matchValue = "";
                break;
        }
    }

    /**
     * @deprecated use {@link ServletPathMapping(PathSpec, String, String, MatchedPath)} instead.
     */
    @Deprecated
    public ServletPathMapping(PathSpec pathSpec, String servletName, String pathInContext)
    {
        _servletName = (servletName == null ? "" : servletName);
        _pattern = pathSpec == null ? null : pathSpec.getDeclaration();

        if (pathSpec instanceof ServletPathSpec && pathInContext != null)
        {
            switch (pathSpec.getGroup())
            {
                case ROOT:
                    _mappingMatch = MappingMatch.CONTEXT_ROOT;
                    _matchValue = "";
                    _servletPath = "";
                    _pathInfo = "/";
                    break;

                case DEFAULT:
                    _mappingMatch = MappingMatch.DEFAULT;
                    _matchValue = "";
                    _servletPath = pathInContext;
                    _pathInfo = null;
                    break;

                case EXACT:
                    _mappingMatch = MappingMatch.EXACT;
                    _matchValue = _pattern.startsWith("/") ? _pattern.substring(1) : _pattern;
                    _servletPath = _pattern;
                    _pathInfo = null;
                    break;

                case PREFIX_GLOB:
                    _mappingMatch = MappingMatch.PATH;
                    _servletPath = pathSpec.getPrefix();
                    // TODO avoid the substring on the known servletPath!
                    _matchValue = _servletPath.startsWith("/") ? _servletPath.substring(1) : _servletPath;
                    _pathInfo = pathSpec.getPathInfo(pathInContext);
                    break;

                case SUFFIX_GLOB:
                    _mappingMatch = MappingMatch.EXTENSION;
                    int dot = pathInContext.lastIndexOf('.');
                    _matchValue = pathInContext.substring(pathInContext.startsWith("/") ? 1 : 0, dot);
                    _servletPath = pathInContext;
                    _pathInfo = null;
                    break;

                case MIDDLE_GLOB:
                default:
                    throw new IllegalStateException();
            }
        }
        else if (pathSpec != null)
        {
            _mappingMatch = null;
            _servletPath = pathSpec.getPathMatch(pathInContext);
            _matchValue = _servletPath.startsWith("/") ? _servletPath.substring(1) : _servletPath;
            _pathInfo = pathSpec.getPathInfo(pathInContext);
        }
        else
        {
            _mappingMatch = null;
            _matchValue = "";
            _servletPath = pathInContext;
            _pathInfo = null;
        }
    }

    @Override
    public String getMatchValue()
    {
        return _matchValue;
    }

    @Override
    public String getPattern()
    {
        return _pattern;
    }

    @Override
    public String getServletName()
    {
        return _servletName;
    }

    @Override
    public MappingMatch getMappingMatch()
    {
        return _mappingMatch;
    }

    public String getServletPath()
    {
        return _servletPath;
    }

    public String getPathInfo()
    {
        return _pathInfo;
    }

    @Override
    public String toString()
    {
        return "ServletPathMapping{" +
            "matchValue=" + _matchValue +
            ", pattern=" + _pattern +
            ", servletName=" + _servletName +
            ", mappingMatch=" + _mappingMatch +
            ", servletPath=" + _servletPath +
            ", pathInfo=" + _pathInfo +
            "}";
    }
}
