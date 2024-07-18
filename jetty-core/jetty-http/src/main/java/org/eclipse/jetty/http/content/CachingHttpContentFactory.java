//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.time.Instant;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.IOResources;
import org.eclipse.jetty.io.Retainable;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * {@link HttpContent.Factory} implementation that wraps any other {@link HttpContent.Factory} instance
 * using it as a caching authority. Only HttpContent instances whose path is not a directory are cached.
 * </p>
 * <p>
 * No eviction is done by this {@link HttpContent.Factory}, once an entry is in the cache it is always
 * assumed to be valid. This class can be extended to implement the validation behaviours on
 * {@link CachingHttpContent} which allow entries to be evicted once they become invalid.
 * </p>
 * <br>
 * The default values for the cache are:
 * <ul>
 *     <li>maxCachedFileSize: {@value #DEFAULT_MAX_CACHE_SIZE}</li>
 *     <li>maxCachedFiles: {@value #DEFAULT_MAX_CACHED_FILES}</li>
 *     <li>maxCacheSize: {@value #DEFAULT_MAX_CACHE_SIZE}</li>
 * </ul>
 * @see ValidatingCachingHttpContentFactory
 */
public class CachingHttpContentFactory implements HttpContent.Factory
{
    private static final Logger LOG = LoggerFactory.getLogger(CachingHttpContentFactory.class);
    private static final int DEFAULT_MAX_CACHED_FILE_SIZE = 128 * 1024 * 1024;
    private static final int DEFAULT_MAX_CACHED_FILES = 2048;
    private static final long DEFAULT_MAX_CACHE_SIZE = 256 * 1024 * 1024;

    private final HttpContent.Factory _authority;
    private final ConcurrentHashMap<String, CachingHttpContent> _cache = new ConcurrentHashMap<>();
    private final AtomicLong _cachedSize = new AtomicLong();
    private final ByteBufferPool _bufferPool;
    private int _maxCachedFileSize = DEFAULT_MAX_CACHED_FILE_SIZE;
    private int _maxCachedFiles = DEFAULT_MAX_CACHED_FILES;
    private long _maxCacheSize = DEFAULT_MAX_CACHE_SIZE;
    private boolean _useDirectByteBuffers = true;

    public CachingHttpContentFactory(HttpContent.Factory authority, ByteBufferPool bufferPool)
    {
        _authority = authority;
        _bufferPool = bufferPool != null ? bufferPool : ByteBufferPool.NON_POOLING;
    }

    protected ConcurrentMap<String, CachingHttpContent> getCache()
    {
        return _cache;
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

    public long getMaxCacheSize()
    {
        return _maxCacheSize;
    }

    public void setMaxCacheSize(long maxCacheSize)
    {
        _maxCacheSize = maxCacheSize;
        shrinkCache();
    }

    /**
     * Get the max number of cached files..
     * @return the max number of cached files.
     */
    public int getMaxCachedFiles()
    {
        return _maxCachedFiles;
    }

    /**
     * Set the max number of cached files..
     * @param maxCachedFiles the max number of cached files.
     */
    public void setMaxCachedFiles(int maxCachedFiles)
    {
        _maxCachedFiles = maxCachedFiles;
        shrinkCache();
    }

    public boolean isUseDirectByteBuffers()
    {
        return _useDirectByteBuffers;
    }

    public void setUseDirectByteBuffers(boolean useDirectByteBuffers)
    {
        _useDirectByteBuffers = useDirectByteBuffers;
    }

    private void shrinkCache()
    {
        // While we need to shrink
        int numCacheEntries = _cache.size();
        while (numCacheEntries > 0 && (numCacheEntries > _maxCachedFiles || _cachedSize.get() > _maxCacheSize))
        {
            // Scan the entire cache and generate an ordered list by last accessed time.
            SortedSet<CachingHttpContent> sorted = new TreeSet<>((c1, c2) ->
            {
                long delta = NanoTime.elapsed(c2.getLastAccessedNanos(), c1.getLastAccessedNanos());
                if (delta != 0)
                    return delta < 0 ? -1 : 1;

                delta = c1.getContentLengthValue() - c2.getContentLengthValue();
                if (delta != 0)
                    return delta < 0 ? -1 : 1;

                return c1.getKey().compareTo(c2.getKey());
            });
            sorted.addAll(_cache.values());

            // TODO: Can we remove the buffers from the content before evicting.
            // Invalidate least recently used first
            for (CachingHttpContent content : sorted)
            {
                if (_cache.size() <= _maxCachedFiles && _cachedSize.get() <= _maxCacheSize)
                    break;
                removeFromCache(content);
            }

            numCacheEntries = _cache.size();
        }
    }

    protected void removeFromCache(CachingHttpContent content)
    {
        CachingHttpContent removed = _cache.remove(content.getKey());
        if (removed != null)
        {
            removed.release();
            _cachedSize.addAndGet(-removed.getBytesOccupied());
        }
    }

    public void flushCache()
    {
        for (CachingHttpContent content : _cache.values())
        {
            removeFromCache(content);
        }
    }

    /**
     * Tests whether the given HttpContent is cacheable, and if there is enough room to fit it in the cache.
     *
     * @param httpContent the HttpContent to test.
     * @return whether the HttpContent is cacheable.
     */
    protected boolean isCacheable(HttpContent httpContent)
    {
        if (httpContent == null)
            return true;

        if (httpContent.getResource().isDirectory())
            return false;

        if (_maxCachedFiles <= 0)
            return false;

        // Will it fit in the cache?
        long len = httpContent.getBytesOccupied();
        return (len <= _maxCachedFileSize && len <= _maxCacheSize);
    }

    @Override
    public HttpContent getContent(String path) throws IOException
    {
        CachingHttpContent cachingHttpContent = _cache.get(path);
        if (cachingHttpContent != null)
        {
            cachingHttpContent.setLastAccessedNanos(NanoTime.now());
            if (cachingHttpContent.isValid())
            {
                // If retain fails the CachingHttpContent was already evicted.
                if (cachingHttpContent.retain())
                    return (cachingHttpContent instanceof NotFoundHttpContent) ? null : cachingHttpContent;
            }
            else
                removeFromCache(cachingHttpContent);
        }

        HttpContent httpContent = _authority.getContent(path);
        if (!isCacheable(httpContent))
            return httpContent;

        // The re-mapping function may be run multiple times by compute.
        AtomicBoolean added = new AtomicBoolean();
        cachingHttpContent = _cache.computeIfAbsent(path, key ->
        {
            CachingHttpContent cachingContent = (httpContent == null) ? newNotFoundContent(key) : newCachedContent(key, httpContent);
            added.set(true);
            _cachedSize.addAndGet(cachingContent.getBytesOccupied());
            return cachingContent;
        });

        // If retain fails the CachingHttpContent was already evicted.
        if (!cachingHttpContent.retain())
            return httpContent;

        if (added.get())
        {
            // We want to shrink cache only if we have just added an entry.
            shrinkCache();
        }
        else if (httpContent != null)
        {
            // If we did not add an entry we are using a cached version added by someone else,
            // so we should release the local content taken from the authority.
            httpContent.release();
        }

        return (cachingHttpContent instanceof NotFoundHttpContent) ? null : cachingHttpContent;
    }

    protected CachingHttpContent newCachedContent(String p, HttpContent httpContent)
    {
        return new CachedHttpContent(p, httpContent);
    }

    protected CachingHttpContent newNotFoundContent(String p)
    {
        return new NotFoundHttpContent(p);
    }

    protected interface CachingHttpContent extends HttpContent
    {
        long getLastAccessedNanos();

        void setLastAccessedNanos(long nanosTime);

        String getKey();

        boolean isValid();

        boolean retain();
    }

    protected class CachedHttpContent extends HttpContent.Wrapper implements CachingHttpContent
    {
        private final RetainableByteBuffer _buffer;
        private final String _cacheKey;
        private final HttpField _etagField;
        private final long _contentLengthValue;
        private volatile long _lastAccessed;
        private final Set<CompressedContentFormat> _compressedFormats;
        private final String _lastModifiedValue;
        private final String _characterEncoding;
        private final MimeTypes.Type _mimeType;
        private final HttpField _contentLength;
        private final Instant _lastModifiedInstant;
        private final HttpField _lastModified;
        private final long _bytesOccupied;
        private final boolean _isValid;
        private final Retainable.ReferenceCounter _referenceCount = new Retainable.ReferenceCounter();

        public CachedHttpContent(String key, HttpContent httpContent)
        {
            super(httpContent);
            _cacheKey = key;

            // TODO: do all the following lazily and asynchronously.
            HttpField etagField = httpContent.getETag();
            String eTagValue = httpContent.getETagValue();
            if (StringUtil.isNotBlank(eTagValue))
            {
                etagField = new PreEncodedHttpField(HttpHeader.ETAG, eTagValue);
            }
            _etagField = etagField;
            _contentLengthValue = httpContent.getContentLengthValue();
            boolean isValid = true;

            // Read the content into memory if the HttpContent does not already have a buffer.
            RetainableByteBuffer buffer = null;
            try
            {
                if (_contentLengthValue <= _maxCachedFileSize)
                    buffer = IOResources.toRetainableByteBuffer(httpContent.getResource(), _bufferPool, _useDirectByteBuffers);
            }
            catch (Throwable t)
            {
                isValid = false;
                if (LOG.isDebugEnabled())
                    LOG.warn("Failed to read Resource: {}", httpContent.getResource(), t);
                else
                    LOG.warn("Failed to read Resource: {} - {}", httpContent.getResource(), t.toString());
            }

            _buffer = buffer;
            _isValid = isValid;
            _bytesOccupied = httpContent.getBytesOccupied();
            _lastModifiedValue = httpContent.getLastModifiedValue();
            _characterEncoding = httpContent.getCharacterEncoding();
            _compressedFormats = httpContent.getPreCompressedContentFormats();
            _mimeType = httpContent.getMimeType();
            _contentLength = httpContent.getContentLength();
            _lastModifiedInstant = httpContent.getLastModifiedInstant();
            _lastModified = httpContent.getLastModified();
            _lastAccessed = NanoTime.now();
        }

        @Override
        public long getContentLengthValue()
        {
            return _contentLengthValue;
        }

        @Override
        public long getBytesOccupied()
        {
            return _bytesOccupied;
        }

        @Override
        public long getLastAccessedNanos()
        {
            return _lastAccessed;
        }

        @Override
        public void setLastAccessedNanos(long nanosTime)
        {
            _lastAccessed = nanosTime;
        }

        @Override
        public String getKey()
        {
            return _cacheKey;
        }

        @Override
        public void writeTo(Content.Sink sink, long offset, long length, Callback callback)
        {
            sink.write(false, BufferUtil.slice(_buffer.getByteBuffer(), (int)offset, (int)length), callback);
        }

        @Override
        public boolean retain()
        {
            return _referenceCount.tryRetain();
        }

        @Override
        public void release()
        {
            if (_referenceCount.release())
            {
                if (_buffer != null)
                    _buffer.release();
                super.release();
            }
        }

        @Override
        public Set<CompressedContentFormat> getPreCompressedContentFormats()
        {
            return _compressedFormats;
        }

        @Override
        public HttpField getETag()
        {
            return _etagField;
        }

        @Override
        public String getETagValue()
        {
            return _etagField == null ? null : _etagField.getValue();
        }

        @Override
        public String getCharacterEncoding()
        {
            return _characterEncoding;
        }

        @Override
        public MimeTypes.Type getMimeType()
        {
            return _mimeType;
        }

        @Override
        public HttpField getContentLength()
        {
            return _contentLength;
        }

        @Override
        public Instant getLastModifiedInstant()
        {
            return _lastModifiedInstant;
        }

        @Override
        public HttpField getLastModified()
        {
            return _lastModified;
        }

        @Override
        public String getLastModifiedValue()
        {
            return _lastModifiedValue;
        }

        @Override
        public boolean isValid()
        {
            return _isValid;
        }
    }

    protected static class NotFoundHttpContent implements CachingHttpContent
    {
        private volatile long _lastAccessed;

        private final String _key;

        public NotFoundHttpContent(String key)
        {
            _key = key;
            _lastAccessed = NanoTime.now();
        }

        @Override
        public String getKey()
        {
            return _key;
        }

        @Override
        public long getLastAccessedNanos()
        {
            return _lastAccessed;
        }

        @Override
        public void setLastAccessedNanos(long nanosTime)
        {
            _lastAccessed = nanosTime;
        }

        @Override
        public HttpField getContentType()
        {
            return null;
        }

        @Override
        public String getContentTypeValue()
        {
            return null;
        }

        @Override
        public String getCharacterEncoding()
        {
            return null;
        }

        @Override
        public MimeTypes.Type getMimeType()
        {
            return null;
        }

        @Override
        public HttpField getContentEncoding()
        {
            return null;
        }

        @Override
        public String getContentEncodingValue()
        {
            return null;
        }

        @Override
        public HttpField getContentLength()
        {
            return null;
        }

        @Override
        public long getContentLengthValue()
        {
            return 0;
        }

        @Override
        public Instant getLastModifiedInstant()
        {
            return null;
        }

        @Override
        public HttpField getLastModified()
        {
            return null;
        }

        @Override
        public String getLastModifiedValue()
        {
            return null;
        }

        @Override
        public HttpField getETag()
        {
            return null;
        }

        @Override
        public String getETagValue()
        {
            return null;
        }

        @Override
        public Resource getResource()
        {
            return null;
        }

        @Override
        public void writeTo(Content.Sink sink, long offset, long length, Callback callback)
        {
            callback.succeeded();
        }

        @Override
        public Set<CompressedContentFormat> getPreCompressedContentFormats()
        {
            return null;
        }

        @Override
        public void release()
        {
        }

        @Override
        public boolean isValid()
        {
            return true;
        }

        @Override
        public boolean retain()
        {
            return true;
        }
    }
}
