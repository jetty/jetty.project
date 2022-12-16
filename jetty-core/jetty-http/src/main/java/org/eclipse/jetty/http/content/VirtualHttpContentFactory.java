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

/**
 * An {@link HttpContent.Factory} implementation which takes a Resource and fakes this resource as
 * an entry in every directory. If any request is made for this resources file name, and it is not
 * already present in that directory then the resource contained in this factory will be served instead.
 */
public class VirtualHttpContentFactory implements HttpContent.Factory
{
    private final HttpContent.Factory _factory;
    private final Resource _resource;
    private final String _contentType;
    private final String _matchSuffix;

    public VirtualHttpContentFactory(HttpContent.Factory factory, Resource resource, String contentType)
    {
        _factory = factory;
        _resource = resource;
        _matchSuffix = "/" + _resource.getFileName();
        _contentType = contentType;
    }

    /**
     * @return Returns the stylesheet as a Resource.
     */
    public Resource getResource()
    {
        return _resource;
    }

    @Override
    public HttpContent getContent(String path) throws IOException
    {
        HttpContent content = _factory.getContent(path);
        if (content != null)
            return content;
        if (matchResource(path))
            return new ResourceHttpContent(_resource, _contentType);
        return null;
    }

    protected boolean matchResource(String path)
    {
        return (_resource != null) && (path != null) && path.endsWith(_matchSuffix);
    }
}
