//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpContent.ContentFactory;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.ResourceHttpContent;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;

/**
 * An HttpContent.Factory for transient content (not cached).  The HttpContent's created by
 * this factory are not intended to be cached, so memory limits for individual
 * HttpOutput streams are enforced.
 */
public class ResourceContentFactory implements ContentFactory
{
    private final ResourceFactory _factory;
    private final MimeTypes _mimeTypes;
    private final CompressedContentFormat[] _precompressedFormats;

    public ResourceContentFactory(ResourceFactory factory, MimeTypes mimeTypes, CompressedContentFormat[] precompressedFormats)
    {
        _factory = factory;
        _mimeTypes = mimeTypes;
        _precompressedFormats = precompressedFormats;
    }

    @Override
    public HttpContent getContent(String pathInContext, int maxBufferSize)
        throws IOException
    {
        try
        {
            // try loading the content from our factory.
            Resource resource = _factory.getResource(pathInContext);
            HttpContent loaded = load(pathInContext, resource, maxBufferSize);
            return loaded;
        }
        catch (Throwable t)
        {
            // Any error has potential to reveal fully qualified path
            throw (InvalidPathException)new InvalidPathException(pathInContext, "Invalid PathInContext").initCause(t);
        }
    }

    private HttpContent load(String pathInContext, Resource resource, int maxBufferSize)
        throws IOException
    {
        if (resource == null || !resource.exists())
            return null;

        if (resource.isDirectory())
            return new ResourceHttpContent(resource, _mimeTypes.getMimeByExtension(resource.toString()), maxBufferSize);

        // Look for a precompressed resource or content
        String mt = _mimeTypes.getMimeByExtension(pathInContext);
        if (_precompressedFormats.length > 0)
        {
            // Is there a compressed resource?
            Map<CompressedContentFormat, HttpContent> compressedContents = new HashMap<>(_precompressedFormats.length);
            for (CompressedContentFormat format : _precompressedFormats)
            {
                String compressedPathInContext = pathInContext + format._extension;
                Resource compressedResource = _factory.getResource(compressedPathInContext);
                if (compressedResource != null && compressedResource.exists() && compressedResource.lastModified() >= resource.lastModified() &&
                    compressedResource.length() < resource.length())
                    compressedContents.put(format,
                        new ResourceHttpContent(compressedResource, _mimeTypes.getMimeByExtension(compressedPathInContext), maxBufferSize));
            }
            if (!compressedContents.isEmpty())
                return new ResourceHttpContent(resource, mt, maxBufferSize, compressedContents);
        }
        return new ResourceHttpContent(resource, mt, maxBufferSize);
    }

    @Override
    public String toString()
    {
        return "ResourceContentFactory[" + _factory + "]@" + hashCode();
    }
}
