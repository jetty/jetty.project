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

package org.eclipse.jetty.http.content;

import java.io.IOException;

import org.eclipse.jetty.util.resource.Resource;

public class StaticHttpContentFactory implements HttpContent.Factory
{
    private final HttpContent.Factory _factory;
    private final Resource _styleSheet;

    public StaticHttpContentFactory(HttpContent.Factory factory, Resource styleSheet)
    {
        _factory = factory;
        _styleSheet = styleSheet;
    }

    /**
     * @return Returns the stylesheet as a Resource.
     */
    public Resource getStyleSheet()
    {
        return _styleSheet;
    }

    @Override
    public HttpContent getContent(String path) throws IOException
    {
        HttpContent content = _factory.getContent(path);
        if (content != null)
            return content;

        if ((_styleSheet != null) && (path != null) && path.endsWith("/jetty-dir.css"))
            return new ResourceHttpContent(_styleSheet, "text/css");

        return null;
    }
}