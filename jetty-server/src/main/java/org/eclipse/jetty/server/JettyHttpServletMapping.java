//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import javax.servlet.http.HttpServletMapping;
import javax.servlet.http.MappingMatch;

public class JettyHttpServletMapping implements HttpServletMapping
{
    private final String _matchValue;
    private final String _pattern;
    private final String _servletName;
    private final MappingMatch _mappingMatch;

    public JettyHttpServletMapping(String matchValue, String pattern, String servletName, MappingMatch mappingMatch)
    {
        _matchValue = matchValue;
        _pattern = pattern;
        _servletName = servletName;
        _mappingMatch = mappingMatch;
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

    @Override
    public String toString()
    {
        return "HttpServletMapping{matchValue=" + _matchValue +
            ", pattern=" + _pattern + ", servletName=" + _servletName +
            ", mappingMatch=" + _mappingMatch + "}";
    }
}
