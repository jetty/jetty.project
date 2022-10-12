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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.EtagUtils;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MimeTypes.Type;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.PrecompressedHttpContent;
import org.eclipse.jetty.http.ResourceHttpContent;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CachedContentFactory implements HttpContent.ContentFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(CachedContentFactory.class);

    private final ConcurrentMap<String, CachedHttpContent> _cache;
    private final AtomicInteger _cachedSize;
    private final AtomicInteger _cachedFiles;
    private final ResourceFactory _factory;
    private final CachedContentFactory _parent;
    private final MimeTypes _mimeTypes;
    private final boolean _etags;
    private final boolean _useFileMappedBuffer;

    private int _maxCachedFileSize = 128 * 1024 * 1024;
    private int _maxCachedFiles = 2048;
    private int _maxCacheSize = 256 * 1024 * 1024;

    /**
     * Constructor.
     *
     * @param parent the parent resource cache
     * @param factory the resource factory
     * @param mimeTypes Mimetype to use for meta data
     * @param useFileMappedBuffer true to file memory mapped buffers
     * @param etags true to support etags
     */
    public CachedContentFactory(CachedContentFactory parent, ResourceFactory factory, MimeTypes mimeTypes, boolean useFileMappedBuffer, boolean etags)
    {
        _factory = factory;
        _cache = new ConcurrentHashMap<>();
        _cachedSize = new AtomicInteger();
        _cachedFiles = new AtomicInteger();
        _mimeTypes = mimeTypes;
        _parent = parent;
        _useFileMappedBuffer = useFileMappedBuffer;
        _etags = etags;
    }

    public int getCachedSize()
    {
        return _cachedSize.get();
    }

    public int getCachedFiles()
    {
        return _cachedFiles.get();
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

    public void flushCache()
    {
        while (_cache.size() > 0)
        {
            for (String path : _cache.keySet())
            {
                CachedHttpContent content = _cache.remove(path);
                if (content != null)
                    content.invalidate();
            }
        }
    }

    /**
     * <p>Returns an entry from the cache, or creates a new one.</p>
     *
     * @param pathInContext The key into the cache
     * previously been allocated and returned by the {@link HttpContent#getBuffer()} calls.
     * @return The entry matching {@code pathInContext}, or a new entry
     * if no matching entry was found. If the content exists but is not cacheable,
     * then a {@link ResourceHttpContent} instance is returned. If
     * the resource does not exist, then null is returned.
     * @throws IOException if the resource cannot be retrieved
     */
    @Override
    public HttpContent getContent(String pathInContext) throws IOException
    {
        // Is the content in this cache?
        CachedHttpContent content = _cache.get(pathInContext);
        if (content != null && (content).isValid())
            return content;

        // try loading the content from our factory.
        Resource resource = _factory.newResource(pathInContext);
        HttpContent loaded = load(pathInContext, resource);
        if (loaded != null)
            return loaded;

        // Is the content in the parent cache?
        if (_parent != null)
            return _parent.getContent(pathInContext);
        return null;
    }

    /**
     * @param resource the resource to test
     * @return whether the resource is cacheable. The default implementation tests the cache sizes.
     */
    protected boolean isCacheable(Resource resource)
    {
        if (_maxCachedFiles <= 0)
            return false;

        long len = resource.length();

        // Will it fit in the cache?
        return (len > 0 && (_useFileMappedBuffer || (len < _maxCachedFileSize && len < _maxCacheSize)));
    }

    private HttpContent load(String pathInContext, Resource resource) throws IOException
    {
        if (resource == null || !resource.exists())
            return null;

        if (resource.isDirectory())
            return new ResourceHttpContent(resource, _mimeTypes.getMimeByExtension(resource.toString()));

        // Will it fit in the cache?
        if (isCacheable(resource))
        {
            CachedHttpContent content = new CachedHttpContent(pathInContext, resource);
            CachedHttpContent added = _cache.putIfAbsent(pathInContext, content);
            if (added != null)
            {
                content.invalidate();
                content = added;
            }

            return content;
        }

        // Look for non Cacheable precompressed resource or content
        return new ResourceHttpContent(resource, _mimeTypes.getMimeByExtension(pathInContext));
    }

    private void shrinkCache()
    {
        // While we need to shrink
        while (_cache.size() > 0 && (_cachedFiles.get() > _maxCachedFiles || _cachedSize.get() > _maxCacheSize))
        {
            // Scan the entire cache and generate an ordered list by last accessed time.
            SortedSet<CachedHttpContent> sorted = new TreeSet<>(
                Comparator.comparing((CachedHttpContent c) -> c._lastAccessed)
                    .thenComparingLong(c -> c._contentLengthValue)
                    .thenComparing(c -> c._key));
            sorted.addAll(_cache.values());

            // Invalidate least recently used first
            for (CachedHttpContent content : sorted)
            {
                if (_cachedFiles.get() <= _maxCachedFiles && _cachedSize.get() <= _maxCacheSize)
                    break;
                if (content == _cache.remove(content.getKey()))
                    content.invalidate();
            }
        }
    }

    protected ByteBuffer getIndirectBuffer(Resource resource)
    {
        try
        {
            return BufferUtil.toBuffer(resource, false);
        }
        catch (IOException | IllegalArgumentException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to get Indirect Buffer for {}", resource, e);
        }
        return null;
    }

    protected ByteBuffer getMappedBuffer(Resource resource)
    {
        // Only use file mapped buffers for cached resources, otherwise too much virtual memory commitment for
        // a non shared resource.  Also ignore max buffer size
        try
        {
            if (_useFileMappedBuffer && resource.getPath() != null && resource.length() <= Integer.MAX_VALUE)
                return BufferUtil.toMappedBuffer(resource);
        }
        catch (IOException | IllegalArgumentException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to get Mapped Buffer for {}", resource, e);
        }
        return null;
    }

    protected ByteBuffer getDirectBuffer(Resource resource)
    {
        try
        {
            return BufferUtil.toBuffer(resource, true);
        }
        catch (IOException | IllegalArgumentException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to get Direct Buffer for {}", resource, e);
        }
        return null;
    }

    @Override
    public String toString()
    {
        return "ResourceCache[" + _parent + "," + _factory + "]@" + hashCode();
    }

    /**
     * MetaData associated with a context Resource.
     */
    public class CachedHttpContent implements HttpContent
    {
        private final String _key;
        private final Resource _resource;
        private final long _contentLengthValue;
        private final HttpField _contentType;
        private final String _characterEncoding;
        private final MimeTypes.Type _mimeType;
        private final HttpField _contentLength;
        private final HttpField _lastModified;
        private final Instant _lastModifiedValue;
        private final HttpField _etag;
        private final AtomicReference<ByteBuffer> _buffer = new AtomicReference<>();
        private volatile Instant _lastAccessed;

        CachedHttpContent(String pathInContext, Resource resource)
        {
            _key = pathInContext;
            _resource = resource;

            String contentType = _mimeTypes.getMimeByExtension(_resource.toString());
            _contentType = contentType == null ? null : new PreEncodedHttpField(HttpHeader.CONTENT_TYPE, contentType);
            _characterEncoding = _contentType == null ? null : MimeTypes.getCharsetFromContentType(contentType);
            _mimeType = _contentType == null ? null : MimeTypes.CACHE.get(MimeTypes.getContentTypeWithoutCharset(contentType));

            boolean exists = resource.exists();
            _lastModifiedValue = exists ? resource.lastModified() : null;
            _lastModified = _lastModifiedValue == null ? null
                : new PreEncodedHttpField(HttpHeader.LAST_MODIFIED, DateGenerator.formatDate(_lastModifiedValue));

            _contentLengthValue = exists ? resource.length() : 0;
            _contentLength = new PreEncodedHttpField(HttpHeader.CONTENT_LENGTH, Long.toString(_contentLengthValue));

            if (_cachedFiles.incrementAndGet() > _maxCachedFiles)
                shrinkCache();

            _lastAccessed = Instant.now();
            _etag = CachedContentFactory.this._etags ? EtagUtils.createWeakEtagField(resource) : null;
        }

        public String getKey()
        {
            return _key;
        }

        public boolean isCached()
        {
            return _key != null;
        }

        @Override
        public Resource getResource()
        {
            return _resource;
        }

        @Override
        public HttpField getETag()
        {
            return _etag;
        }

        @Override
        public String getETagValue()
        {
            if (_etag == null)
                return null;
            return _etag.getValue();
        }

        boolean isValid()
        {
            if (Objects.equals(_lastModifiedValue, _resource.lastModified()) && _contentLengthValue == _resource.length())
            {
                _lastAccessed = Instant.now();
                return true;
            }

            if (this == _cache.remove(_key))
                invalidate();
            return false;
        }

        protected void invalidate()
        {
            ByteBuffer buffer = _buffer.getAndSet(null);

            // Mapped buffer are not counted in the cache size
            if (buffer != null && !(buffer instanceof MappedByteBuffer mapped))
                _cachedSize.addAndGet(-BufferUtil.length(buffer));

            _cachedFiles.decrementAndGet();
        }

        @Override
        public Instant getLastModifiedInstant()
        {
            return _lastModifiedValue;
        }

        @Override
        public HttpField getLastModified()
        {
            return _lastModified;
        }

        @Override
        public String getLastModifiedValue()
        {
            return _lastModified == null ? null : _lastModified.getValue();
        }

        @Override
        public HttpField getContentType()
        {
            return _contentType;
        }

        @Override
        public String getContentTypeValue()
        {
            return _contentType == null ? null : _contentType.getValue();
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
        public String getCharacterEncoding()
        {
            return _characterEncoding;
        }

        @Override
        public Type getMimeType()
        {
            return _mimeType;
        }

        @Override
        public void release()
        {
        }

        public ByteBuffer getBuffer()
        {
            ByteBuffer buffer = _buffer.get();
            if (buffer != null)
                return buffer.asReadOnlyBuffer();

            // No buffer, so let's try a mapped buffer
            ByteBuffer mapped = CachedContentFactory.this.getMappedBuffer(_resource);
            if (mapped != null)
                buffer = _buffer.updateAndGet(b -> b == null ? mapped : b);

            if (_resource.length() > _maxCachedFileSize)
                return null;

            ByteBuffer direct = CachedContentFactory.this.getDirectBuffer(_resource);
            ByteBuffer indirect = direct == null ? null : CachedContentFactory.this.getIndirectBuffer(_resource);
            ByteBuffer allocated = direct == null ? indirect : direct;
            if (allocated != null)
            {
                buffer = _buffer.updateAndGet(b -> b == null ? allocated : b);
                if (buffer == allocated)
                {
                    if (_cachedSize.addAndGet(BufferUtil.length(buffer)) > _maxCacheSize)
                        shrinkCache();
                }
            }

            return buffer == null ? null : buffer.asReadOnlyBuffer();
        }

        @Override
        public HttpField getContentLength()
        {
            return _contentLength;
        }

        @Override
        public long getContentLengthValue()
        {
            return _contentLengthValue;
        }

        @Override
        public String toString()
        {
            return String.format("CachedContent@%x{r=%s,e=%b,lm=%s,ct=%s}", hashCode(), _resource, _resource.exists(), _lastModified, _contentType);
        }
    }

    public class CachedPrecompressedHttpContent extends PrecompressedHttpContent
    {
        private final CachedHttpContent _content;
        private final CachedHttpContent _precompressedContent;
        private final HttpField _etag;

        CachedPrecompressedHttpContent(CachedHttpContent content, CachedHttpContent precompressedContent, CompressedContentFormat format)
        {
            super(content, precompressedContent, format);
            _content = content;
            _precompressedContent = precompressedContent;

            _etag = (CachedContentFactory.this._etags) ? EtagUtils.createWeakEtagField(_content.getResource(), format.getEtagSuffix()) : null;
        }

        public boolean isValid()
        {
            return _precompressedContent.isValid() && _content.isValid() &&
                newerThanOrEqual(_precompressedContent.getResource(), _content.getResource());
        }

        /**
         * <p>Utility to compare {@link Resource#lastModified()} of two resources.</p>
         * @param resource1 the first resource to test.
         * @param resource2 the second resource to test.
         * @return true if modified time of resource1 is newer or equal to that of resource2.
         */
        private static boolean newerThanOrEqual(Resource resource1, Resource resource2)
        {
            return !resource2.lastModified().isAfter(resource1.lastModified());
        }

        @Override
        public HttpField getETag()
        {
            if (_etag != null)
                return _etag;
            return super.getETag();
        }

        @Override
        public String getETagValue()
        {
            if (_etag != null)
                return _etag.getValue();
            return super.getETagValue();
        }

        @Override
        public String toString()
        {
            return "Cached" + super.toString();
        }
    }
}
