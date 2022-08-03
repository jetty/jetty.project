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

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
//TODO remove
public class ResourceContentFactory implements ContentFactory
{
    private final ResourceFactory _factory;
    private final MimeTypes _mimeTypes;
    private final List<CompressedContentFormat> _precompressedFormats;

    public ResourceContentFactory(ResourceFactory factory, MimeTypes mimeTypes, List<CompressedContentFormat> precompressedFormats)
    {
        Objects.requireNonNull(mimeTypes, "MimeTypes cannot be null");
        _factory = factory;
        _mimeTypes = mimeTypes;
        _precompressedFormats = precompressedFormats;
    }

    @Override
    public HttpContent getContent(String pathInContext, int maxBufferSize) throws IOException
    {
        try
        {
            // try loading the content from our factory.
            Resource resource = this._factory.newResource(pathInContext);
            return load(pathInContext, resource, maxBufferSize);
        }
        catch (Throwable t)
        {
            // There are many potential Exceptions that can reveal a fully qualified path.
            // See Issue #2560 - Always wrap a Throwable here in an InvalidPathException
            // that is limited to only the provided pathInContext.
            // The cause (which might reveal a fully qualified path) is still available,
            // on the Exception and the logging, but is not reported in normal error page situations.
            // This specific exception also allows WebApps to specifically hook into a known / reliable
            // Exception type for ErrorPageErrorHandling logic.
            InvalidPathException saferException = new InvalidPathException(pathInContext, "Invalid PathInContext");
            saferException.initCause(t);
            throw saferException;
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
        if (_precompressedFormats.size() > 0)
        {
            // Is there a compressed resource?
            Map<CompressedContentFormat, HttpContent> compressedContents = new HashMap<>(_precompressedFormats.size());
            for (CompressedContentFormat format : _precompressedFormats)
            {
                String compressedPathInContext = pathInContext + format.getExtension();
                Resource compressedResource = this._factory.newResource(compressedPathInContext);
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
