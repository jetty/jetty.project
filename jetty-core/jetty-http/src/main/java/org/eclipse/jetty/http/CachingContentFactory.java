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

package org.eclipse.jetty.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HttpContent.ContentFactory implementation that wraps any other HttpContent.ContentFactory instance
 * using it as a caching authority.
 * Only HttpContent instances whose path is not a directory are cached.
 * HttpContent instances returned by getContent() implement HttpContent.InMemory when a cached instance is found.
 *
 * TODO cache config (sizing) is missing
 */
public class CachingContentFactory implements HttpContent.ContentFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(CachingContentFactory.class);

    private final HttpContent.ContentFactory _authority;
    private final ConcurrentMap<String, CachingHttpContent> _cache = new ConcurrentHashMap<>();

    public CachingContentFactory(HttpContent.ContentFactory _authority)
    {
        this._authority = _authority;
    }

    public void flushCache()
    {
        for (String path : _cache.keySet())
        {
            CachingHttpContent content = _cache.remove(path);
            if (content != null)
                content.invalidate();
        }
    }

    @Override
    public HttpContent getContent(String path, int maxBuffer) throws IOException
    {
        CachingHttpContent cachingHttpContent = _cache.get(path);
        if (cachingHttpContent != null && cachingHttpContent.isValid())
            return cachingHttpContent;
        HttpContent httpContent = _authority.getContent(path, maxBuffer);
        // Do not cache directories
        if (httpContent != null && !Files.isDirectory(httpContent.getPath()))
        {
            httpContent = cachingHttpContent = new CachingHttpContent(httpContent);
            _cache.put(path, cachingHttpContent);
        }
        return httpContent;
    }

    private static class CachingHttpContent implements HttpContent.InMemory
    {
        private final HttpContent _delegate;
        private final ByteBuffer _buffer;
        private final FileTime _lastModifiedValue;

        private CachingHttpContent(HttpContent httpContent) throws IOException
        {
            // load the content into memory
            long contentLengthValue = httpContent.getContentLengthValue();
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect((int)contentLengthValue); // TODO use pool & check length limit

            SeekableByteChannel channel = Files.newByteChannel(httpContent.getPath());
            // fill buffer
            int read = 0;
            while (read != contentLengthValue)
                read += channel.read(byteBuffer);
            byteBuffer.flip();

            _buffer = byteBuffer;
            _lastModifiedValue = Files.getLastModifiedTime(httpContent.getPath());
            _delegate = httpContent;
        }

        @Override
        public ByteBuffer getBuffer()
        {
            return _buffer.slice();
        }

        public boolean isValid()
        {
            try
            {
                FileTime lastModifiedTime = Files.getLastModifiedTime(_delegate.getPath());
                if (lastModifiedTime.equals(_lastModifiedValue))
                    return true;
            }
            catch (IOException e)
            {
                LOG.debug("unable to get delegate path' LastModifiedTime", e);
            }
            invalidate();
            return false;
        }

        public void invalidate()
        {
            // TODO re-pool buffer
        }

        @Override
        public HttpField getContentType()
        {
            return _delegate.getContentType();
        }

        @Override
        public String getContentTypeValue()
        {
            return _delegate.getContentTypeValue();
        }

        @Override
        public String getCharacterEncoding()
        {
            return _delegate.getCharacterEncoding();
        }

        @Override
        public MimeTypes.Type getMimeType()
        {
            return _delegate.getMimeType();
        }

        @Override
        public HttpField getContentEncoding()
        {
            return _delegate.getContentEncoding();
        }

        @Override
        public String getContentEncodingValue()
        {
            return _delegate.getContentEncodingValue();
        }

        @Override
        public HttpField getContentLength()
        {
            return _delegate.getContentLength();
        }

        @Override
        public long getContentLengthValue()
        {
            return _delegate.getContentLengthValue();
        }

        @Override
        public HttpField getLastModified()
        {
            return _delegate.getLastModified();
        }

        @Override
        public String getLastModifiedValue()
        {
            return _delegate.getLastModifiedValue();
        }

        @Override
        public HttpField getETag()
        {
            return _delegate.getETag();
        }

        @Override
        public String getETagValue()
        {
            return _delegate.getETagValue();
        }

        @Override
        public Path getPath()
        {
            return _delegate.getPath();
        }

        @Override
        public Resource getResource()
        {
            return _delegate.getResource();
        }

        @Override
        public Map<CompressedContentFormat, ? extends HttpContent> getPrecompressedContents()
        {
            return _delegate.getPrecompressedContents();
        }
    }
}
