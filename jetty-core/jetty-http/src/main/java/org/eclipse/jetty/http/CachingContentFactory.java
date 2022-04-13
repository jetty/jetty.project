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
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HttpContent.ContentFactory implementation that wraps any other HttpContent.ContentFactory instance
 * using it as a caching authority.
 * Only HttpContent instances whose path is not a directory are cached.
 * HttpContent instances returned by getContent() implement HttpContent.InMemory when a cached instance is found.
 *
 * TODO should directories (generated HTML) and not-found contents be cached?
 * TODO this form of caching is done at a layer below the request processor (i.e.: done in the guts of the ResourceHandler)
 *  Consider if caching should rather be done at a layer above using a CachingHandler that would intercept Response.write()
 *  of a configured URI set, save that in a cache and serve that again.
 */
public class CachingContentFactory implements HttpContent.ContentFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(CachingContentFactory.class);

    private final HttpContent.ContentFactory _authority;
    private final boolean _useFileMappedBuffer;
    private final ConcurrentMap<String, CachingHttpContent> _cache = new ConcurrentHashMap<>();
    private final AtomicLong _cachedSize = new AtomicLong();
    private int _maxCachedFileSize = 128 * 1024 * 1024;
    private int _maxCachedFiles = 2048;
    private int _maxCacheSize = 256 * 1024 * 1024;

    public CachingContentFactory(HttpContent.ContentFactory authority)
    {
        this(authority, false);
    }

    public CachingContentFactory(HttpContent.ContentFactory authority, boolean useFileMappedBuffer)
    {
        _authority = authority;
        _useFileMappedBuffer = useFileMappedBuffer;
    }

    public long getCachedSize()
    {
        return _cachedSize.get();
    }

    public int getCachedFiles()
    {
        return _cache.size();
    }

    public int getMaxCachedFileSize()
    {
        return _maxCachedFileSize;
    }

    public void setMaxCachedFileSize(int maxCachedFileSize)
    {
        _maxCachedFileSize = maxCachedFileSize;
        shrinkCache();
    }

    public int getMaxCacheSize()
    {
        return _maxCacheSize;
    }

    public void setMaxCacheSize(int maxCacheSize)
    {
        _maxCacheSize = maxCacheSize;
        shrinkCache();
    }

    /**
     * @return the max number of cached files.
     */
    public int getMaxCachedFiles()
    {
        return _maxCachedFiles;
    }

    /**
     * @param maxCachedFiles the max number of cached files.
     */
    public void setMaxCachedFiles(int maxCachedFiles)
    {
        _maxCachedFiles = maxCachedFiles;
        shrinkCache();
    }

    public boolean isUseFileMappedBuffer()
    {
        return _useFileMappedBuffer;
    }

    private void shrinkCache()
    {
        // While we need to shrink
        while (_cache.size() > 0 && (_cache.size() > _maxCachedFiles || _cachedSize.get() > _maxCacheSize))
        {
            // Scan the entire cache and generate an ordered list by last accessed time.
            SortedSet<CachingHttpContent> sorted = new TreeSet<>((c1, c2) ->
            {
                if (c1._lastAccessed != c2._lastAccessed)
                    return Long.compare(c1._lastAccessed, c2._lastAccessed);

                if (c1._contentLengthValue < c2._contentLengthValue)
                    return -1;

                return c1._cacheKey.compareTo(c2._cacheKey);
            });
            sorted.addAll(_cache.values());

            // Invalidate least recently used first
            for (CachingHttpContent content : sorted)
            {
                if (_cache.size() <= _maxCachedFiles && _cachedSize.get() <= _maxCacheSize)
                    break;
                removeFromCache(content);
            }
        }
    }

    private void removeFromCache(CachingHttpContent content)
    {
        if (content == _cache.remove(content._cacheKey))
        {
            content.invalidate();
            _cachedSize.addAndGet(-content._contentLengthValue);
        }
    }

    public void flushCache()
    {
        for (CachingHttpContent content : _cache.values())
        {
            removeFromCache(content);
        }
    }

    @Override
    public HttpContent getContent(String path, int maxBuffer) throws IOException
    {
        CachingHttpContent cachingHttpContent = _cache.get(path);
        if (cachingHttpContent != null)
        {
            if (cachingHttpContent.isValid())
                return cachingHttpContent;
            else
                removeFromCache(cachingHttpContent);
        }
        HttpContent httpContent = _authority.getContent(path, maxBuffer);
        // Do not cache directories or files that are too big
        if (httpContent != null && !Files.isDirectory(httpContent.getPath()) && httpContent.getContentLengthValue() <= _maxCachedFileSize)
        {
            httpContent = cachingHttpContent = new CachingHttpContent(path, httpContent);
            _cache.put(path, cachingHttpContent);
            _cachedSize.addAndGet(cachingHttpContent._contentLengthValue);
            shrinkCache();
        }
        return httpContent;
    }

    private class CachingHttpContent implements HttpContent.InMemory
    {
        private final HttpContent _delegate;
        private final ByteBuffer _buffer;
        private final FileTime _lastModifiedValue;
        private final String _cacheKey;
        private final long _contentLengthValue;
        private volatile long _lastAccessed;

        private CachingHttpContent(String key, HttpContent httpContent) throws IOException
        {
            _contentLengthValue = httpContent.getContentLengthValue(); // TODO getContentLengthValue() could return -1
            ByteBuffer byteBuffer;

            if (_useFileMappedBuffer)
            {
                // mmap the content into memory
                byteBuffer = BufferUtil.toMappedBuffer(httpContent.getPath(), 0, _contentLengthValue);
            }
            else
            {
                // TODO use pool & check length limit
                // load the content into memory
                byteBuffer = ByteBuffer.allocateDirect((int)_contentLengthValue);
                try (SeekableByteChannel channel = Files.newByteChannel(httpContent.getPath()))
                {
                    // fill buffer
                    int read = 0;
                    while (read != _contentLengthValue)
                        read += channel.read(byteBuffer);
                }
                byteBuffer.flip();
            }

            _cacheKey = key;
            _buffer = byteBuffer;
            _lastModifiedValue = Files.getLastModifiedTime(httpContent.getPath());
            _delegate = httpContent;
            _lastAccessed = System.nanoTime();
        }

        @Override
        public ByteBuffer getBuffer()
        {
            // TODO this should return a RetainableByteBuffer otherwise there is a race between
            //  threads serving the buffer while another thread invalidates it. That's going to
            //  be a lot of fun since RetainableByteBuffer is only meant to be acquired from a pool
            //  but the byte buffer here could be coming from a mmap'ed file.
            return _buffer.slice();
        }

        public boolean isValid()
        {
            try
            {
                FileTime lastModifiedTime = Files.getLastModifiedTime(_delegate.getPath());
                if (lastModifiedTime.equals(_lastModifiedValue))
                {
                    _lastAccessed = System.nanoTime();
                    return true;
                }
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
