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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StringUtil;

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
                long delta = NanoTime.elapsed(c2._lastAccessed, c1._lastAccessed);
                if (delta != 0)
                    return delta < 0 ? -1 : 1;

                delta = c1._contentLengthValue - c2._contentLengthValue;
                if (delta != 0)
                    return delta < 0 ? -1 : 1;

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
            content.release();
            _cachedSize.addAndGet(-content.calculateSize());
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
    public HttpContent getContent(String path) throws IOException
    {
        // TODO load precompressed otherwise it is never served from cache
        // TODO: Consider _cache.computeIfAbsent()?
        CachingHttpContent cachingHttpContent = _cache.get(path);
        if (cachingHttpContent != null)
        {
            if (cachingHttpContent.isValid())
                return cachingHttpContent;
            else
                removeFromCache(cachingHttpContent);
        }

        HttpContent httpContent = _authority.getContent(path);
        // Do not cache directories or files that are too big
        if (httpContent != null && !httpContent.getResource().isDirectory() && httpContent.getContentLengthValue() <= _maxCachedFileSize)
        {
            httpContent = cachingHttpContent = new CachingHttpContent(path, httpContent);
            _cache.put(path, cachingHttpContent);
            _cachedSize.addAndGet(cachingHttpContent.calculateSize());
            shrinkCache();
        }
        return httpContent;
    }

    private class CachingHttpContent extends HttpContentWrapper
    {
        private final ByteBuffer _buffer;
        private final Instant _lastModifiedValue;
        private final String _cacheKey;
        private final HttpField _etagField;
        private final long _contentLengthValue;
        private final Map<CompressedContentFormat, CachingHttpContent> _precompressedContents;
        private volatile long _lastAccessed;

        private CachingHttpContent(String key, HttpContent httpContent) throws IOException
        {
            this(key, httpContent, httpContent.getETagValue());
        }

        private CachingHttpContent(String key, HttpContent httpContent, String etagValue) throws IOException
        {
            super(httpContent);

            if (_delegate.getResource() == null)
                throw new IllegalArgumentException("Null Resource");
            if (!_delegate.getResource().exists())
                throw new IllegalArgumentException("Resource doesn't exist: " + _delegate.getResource());
            if (_delegate.getResource().isDirectory())
                throw new IllegalArgumentException("Directory Resources not supported: " + _delegate.getResource());
            if (_delegate.getResource().getPath() == null) // only required because we need the Path to access the mapped ByteBuffer or SeekableByteChannel.
                throw new IllegalArgumentException("Resource not backed by Path not supported: " + _delegate.getResource());

            // Resources with negative length cannot be cached.
            // But allow resources with zero length.
            long resourceSize = _delegate.getResource().length();
            if (resourceSize < 0)
                throw new IllegalArgumentException("Resource with negative size: " + _delegate.getResource());

            HttpField etagField = _delegate.getETag();
            if (StringUtil.isNotBlank(etagValue))
            {
                etagField = new PreEncodedHttpField(HttpHeader.ETAG, etagValue);
            }
            _etagField = etagField;
            _contentLengthValue = resourceSize;

            // map the content into memory if possible
            ByteBuffer byteBuffer = _useFileMappedBuffer ? BufferUtil.toMappedBuffer(_delegate.getResource(), 0, _contentLengthValue) : null;

            if (byteBuffer == null)
            {
                // TODO use pool?
                // load the content into memory
                byteBuffer = ByteBuffer.allocateDirect((int)_contentLengthValue);
                try (SeekableByteChannel channel = Files.newByteChannel(_delegate.getResource().getPath()))
                {
                    // fill buffer
                    int read = 0;
                    while (read != _contentLengthValue)
                        read += channel.read(byteBuffer);
                }
                byteBuffer.flip();
            }

            // Load precompressed contents into memory.
            Map<CompressedContentFormat, ? extends HttpContent> precompressedContents = _delegate.getPrecompressedContents();
            if (precompressedContents != null)
            {
                _precompressedContents = new HashMap<>();
                for (Map.Entry<CompressedContentFormat, ? extends HttpContent> entry : precompressedContents.entrySet())
                {
                    CompressedContentFormat format = entry.getKey();
                    String precompressedEtag = EtagUtils.rewriteWithSuffix(_delegate.getETagValue(), format.getEtagSuffix());
                    // The etag of the precompressed content must be the one of the non-compressed content, with the etag suffix appended.
                    _precompressedContents.put(format, new CachingHttpContent(key, entry.getValue(), precompressedEtag));
                }
            }
            else
            {
                _precompressedContents = null;
            }

            _cacheKey = key;
            _buffer = byteBuffer;
            _lastModifiedValue = _delegate.getResource().lastModified();
            _lastAccessed = NanoTime.now();
        }

        long calculateSize()
        {
            long totalSize = _contentLengthValue;
            if (_precompressedContents != null)
            {
                for (CachingHttpContent cachingHttpContent : _precompressedContents.values())
                {
                    totalSize += cachingHttpContent.calculateSize();
                }
            }
            return totalSize;
        }

        @Override
        public long getContentLengthValue()
        {
            return _contentLengthValue;
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
            Instant lastModifiedTime = _delegate.getResource().lastModified();
            if (lastModifiedTime.equals(_lastModifiedValue))
            {
                _lastAccessed = NanoTime.now();
                return true;
            }
            release();
            return false;
        }

        @Override
        public void release()
        {
            // TODO re-pool buffer and release precompressed contents
        }

        @Override
        public HttpField getETag()
        {
            return _etagField;
        }

        @Override
        public String getETagValue()
        {
            HttpField etag = getETag();
            if (etag == null)
                return null;
            return etag.getValue();
        }

        @Override
        public Map<CompressedContentFormat, ? extends HttpContent> getPrecompressedContents()
        {
            return _precompressedContents;
        }
    }
}
