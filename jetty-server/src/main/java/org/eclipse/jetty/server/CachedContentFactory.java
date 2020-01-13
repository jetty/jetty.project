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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MimeTypes.Type;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.PrecompressedHttpContent;
import org.eclipse.jetty.http.ResourceHttpContent;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;

public class CachedContentFactory implements HttpContent.ContentFactory
{
    private static final Logger LOG = Log.getLogger(CachedContentFactory.class);
    private static final Map<CompressedContentFormat, CachedPrecompressedHttpContent> NO_PRECOMPRESSED = Collections.unmodifiableMap(Collections.emptyMap());

    private final ConcurrentMap<String, CachedHttpContent> _cache;
    private final AtomicInteger _cachedSize;
    private final AtomicInteger _cachedFiles;
    private final ResourceFactory _factory;
    private final CachedContentFactory _parent;
    private final MimeTypes _mimeTypes;
    private final boolean _etags;
    private final CompressedContentFormat[] _precompressedFormats;
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
     * @param precompressedFormats array of precompression formats to support
     */
    public CachedContentFactory(CachedContentFactory parent, ResourceFactory factory, MimeTypes mimeTypes, boolean useFileMappedBuffer, boolean etags, CompressedContentFormat[] precompressedFormats)
    {
        _factory = factory;
        _cache = new ConcurrentHashMap<>();
        _cachedSize = new AtomicInteger();
        _cachedFiles = new AtomicInteger();
        _mimeTypes = mimeTypes;
        _parent = parent;
        _useFileMappedBuffer = useFileMappedBuffer;
        _etags = etags;
        _precompressedFormats = precompressedFormats;
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

    @Deprecated
    public HttpContent lookup(String pathInContext) throws IOException
    {
        return getContent(pathInContext, _maxCachedFileSize);
    }

    /**
     * <p>Returns an entry from the cache, or creates a new one.</p>
     *
     * @param pathInContext The key into the cache
     * @param maxBufferSize The maximum buffer size allocated for this request.  For cached content, a larger buffer may have
     * previously been allocated and returned by the {@link HttpContent#getDirectBuffer()} or {@link HttpContent#getIndirectBuffer()} calls.
     * @return The entry matching {@code pathInContext}, or a new entry
     * if no matching entry was found. If the content exists but is not cacheable,
     * then a {@link ResourceHttpContent} instance is returned. If
     * the resource does not exist, then null is returned.
     * @throws IOException if the resource cannot be retrieved
     */
    @Override
    public HttpContent getContent(String pathInContext, int maxBufferSize) throws IOException
    {
        // Is the content in this cache?
        CachedHttpContent content = _cache.get(pathInContext);
        if (content != null && (content).isValid())
            return content;

        // try loading the content from our factory.
        Resource resource = _factory.getResource(pathInContext);
        HttpContent loaded = load(pathInContext, resource, maxBufferSize);
        if (loaded != null)
            return loaded;

        // Is the content in the parent cache?
        if (_parent != null)
        {
            HttpContent httpContent = _parent.getContent(pathInContext, maxBufferSize);
            if (httpContent != null)
                return httpContent;
        }

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

    private HttpContent load(String pathInContext, Resource resource, int maxBufferSize)
    {
        if (resource == null || !resource.exists())
            return null;

        if (resource.isDirectory())
            return new ResourceHttpContent(resource, _mimeTypes.getMimeByExtension(resource.toString()), getMaxCachedFileSize());

        // Will it fit in the cache?
        if (isCacheable(resource))
        {
            CachedHttpContent content;

            // Look for precompressed resources
            if (_precompressedFormats.length > 0)
            {
                Map<CompressedContentFormat, CachedHttpContent> precompresssedContents = new HashMap<>(_precompressedFormats.length);
                for (CompressedContentFormat format : _precompressedFormats)
                {
                    String compressedPathInContext = pathInContext + format._extension;
                    CachedHttpContent compressedContent = _cache.get(compressedPathInContext);
                    if (compressedContent == null || compressedContent.isValid())
                    {
                        compressedContent = null;
                        Resource compressedResource = _factory.getResource(compressedPathInContext);
                        if (compressedResource.exists() && compressedResource.lastModified() >= resource.lastModified() &&
                            compressedResource.length() < resource.length())
                        {
                            compressedContent = new CachedHttpContent(compressedPathInContext, compressedResource, null);
                            CachedHttpContent added = _cache.putIfAbsent(compressedPathInContext, compressedContent);
                            if (added != null)
                            {
                                compressedContent.invalidate();
                                compressedContent = added;
                            }
                        }
                    }
                    if (compressedContent != null)
                        precompresssedContents.put(format, compressedContent);
                }
                content = new CachedHttpContent(pathInContext, resource, precompresssedContents);
            }
            else
                content = new CachedHttpContent(pathInContext, resource, null);

            // Add it to the cache.
            CachedHttpContent added = _cache.putIfAbsent(pathInContext, content);
            if (added != null)
            {
                content.invalidate();
                content = added;
            }

            return content;
        }

        // Look for non Cacheable precompressed resource or content
        String mt = _mimeTypes.getMimeByExtension(pathInContext);
        if (_precompressedFormats.length > 0)
        {
            // Is the precompressed content cached?
            Map<CompressedContentFormat, HttpContent> compressedContents = new HashMap<>();
            for (CompressedContentFormat format : _precompressedFormats)
            {
                String compressedPathInContext = pathInContext + format._extension;
                CachedHttpContent compressedContent = _cache.get(compressedPathInContext);
                if (compressedContent != null && compressedContent.isValid() && compressedContent.getResource().lastModified() >= resource.lastModified())
                    compressedContents.put(format, compressedContent);

                // Is there a precompressed resource?
                Resource compressedResource = _factory.getResource(compressedPathInContext);
                if (compressedResource.exists() && compressedResource.lastModified() >= resource.lastModified() &&
                    compressedResource.length() < resource.length())
                    compressedContents.put(format,
                        new ResourceHttpContent(compressedResource, _mimeTypes.getMimeByExtension(compressedPathInContext), maxBufferSize));
            }
            if (!compressedContents.isEmpty())
                return new ResourceHttpContent(resource, mt, maxBufferSize, compressedContents);
        }

        return new ResourceHttpContent(resource, mt, maxBufferSize);
    }

    private void shrinkCache()
    {
        // While we need to shrink
        while (_cache.size() > 0 && (_cachedFiles.get() > _maxCachedFiles || _cachedSize.get() > _maxCacheSize))
        {
            // Scan the entire cache and generate an ordered list by last accessed time.
            SortedSet<CachedHttpContent> sorted = new TreeSet<>((c1, c2) ->
            {
                if (c1._lastAccessed < c2._lastAccessed)
                    return -1;

                if (c1._lastAccessed > c2._lastAccessed)
                    return 1;

                if (c1._contentLengthValue < c2._contentLengthValue)
                    return -1;

                return c1._key.compareTo(c2._key);
            });
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
                LOG.debug(e);
        }
        return null;
    }

    protected ByteBuffer getMappedBuffer(Resource resource)
    {
        // Only use file mapped buffers for cached resources, otherwise too much virtual memory commitment for
        // a non shared resource.  Also ignore max buffer size
        try
        {
            if (_useFileMappedBuffer && resource.getFile() != null && resource.length() < Integer.MAX_VALUE)
                return BufferUtil.toMappedBuffer(resource.getFile());
        }
        catch (IOException | IllegalArgumentException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(e);
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
                LOG.debug(e);
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
        private final long _lastModifiedValue;
        private final HttpField _etag;
        private final Map<CompressedContentFormat, CachedPrecompressedHttpContent> _precompressed;
        private final AtomicReference<ByteBuffer> _indirectBuffer = new AtomicReference<>();
        private final AtomicReference<ByteBuffer> _directBuffer = new AtomicReference<>();
        private final AtomicReference<ByteBuffer> _mappedBuffer = new AtomicReference<>();
        private volatile long _lastAccessed;

        CachedHttpContent(String pathInContext, Resource resource, Map<CompressedContentFormat, CachedHttpContent> precompressedResources)
        {
            _key = pathInContext;
            _resource = resource;

            String contentType = _mimeTypes.getMimeByExtension(_resource.toString());
            _contentType = contentType == null ? null : new PreEncodedHttpField(HttpHeader.CONTENT_TYPE, contentType);
            _characterEncoding = _contentType == null ? null : MimeTypes.getCharsetFromContentType(contentType);
            _mimeType = _contentType == null ? null : MimeTypes.CACHE.get(MimeTypes.getContentTypeWithoutCharset(contentType));

            boolean exists = resource.exists();
            _lastModifiedValue = exists ? resource.lastModified() : -1L;
            _lastModified = _lastModifiedValue == -1 ? null
                : new PreEncodedHttpField(HttpHeader.LAST_MODIFIED, DateGenerator.formatDate(_lastModifiedValue));

            _contentLengthValue = exists ? resource.length() : 0;
            _contentLength = new PreEncodedHttpField(HttpHeader.CONTENT_LENGTH, Long.toString(_contentLengthValue));

            if (_cachedFiles.incrementAndGet() > _maxCachedFiles)
                shrinkCache();

            _lastAccessed = System.currentTimeMillis();

            _etag = CachedContentFactory.this._etags ? new PreEncodedHttpField(HttpHeader.ETAG, resource.getWeakETag()) : null;

            if (precompressedResources != null)
            {
                _precompressed = new HashMap<>(precompressedResources.size());
                for (Map.Entry<CompressedContentFormat, CachedHttpContent> entry : precompressedResources.entrySet())
                {
                    _precompressed.put(entry.getKey(), new CachedPrecompressedHttpContent(this, entry.getValue(), entry.getKey()));
                }
            }
            else
            {
                _precompressed = NO_PRECOMPRESSED;
            }
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
            return _etag.getValue();
        }

        boolean isValid()
        {
            if (_lastModifiedValue == _resource.lastModified() && _contentLengthValue == _resource.length())
            {
                _lastAccessed = System.currentTimeMillis();
                return true;
            }

            if (this == _cache.remove(_key))
                invalidate();
            return false;
        }

        protected void invalidate()
        {
            ByteBuffer indirect = _indirectBuffer.getAndSet(null);
            if (indirect != null)
                _cachedSize.addAndGet(-BufferUtil.length(indirect));

            ByteBuffer direct = _directBuffer.getAndSet(null);
            if (direct != null)
                _cachedSize.addAndGet(-BufferUtil.length(direct));

            _mappedBuffer.getAndSet(null);

            _cachedFiles.decrementAndGet();
            _resource.close();
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

        @Override
        public ByteBuffer getIndirectBuffer()
        {
            if (_resource.length() > _maxCachedFileSize)
            {
                return null;
            }

            ByteBuffer buffer = _indirectBuffer.get();
            if (buffer == null)
            {
                ByteBuffer buffer2 = CachedContentFactory.this.getIndirectBuffer(_resource);
                if (buffer2 == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Could not load indirect buffer from " + this);
                    return null;
                }

                if (_indirectBuffer.compareAndSet(null, buffer2))
                {
                    buffer = buffer2;
                    if (_cachedSize.addAndGet(BufferUtil.length(buffer)) > _maxCacheSize)
                        shrinkCache();
                }
                else
                {
                    buffer = _indirectBuffer.get();
                }
            }
            return buffer == null ? null : buffer.asReadOnlyBuffer();
        }

        @Override
        public ByteBuffer getDirectBuffer()
        {
            ByteBuffer buffer = _mappedBuffer.get();
            if (buffer == null)
                buffer = _directBuffer.get();
            if (buffer == null)
            {
                ByteBuffer mapped = CachedContentFactory.this.getMappedBuffer(_resource);
                if (mapped != null)
                {
                    if (_mappedBuffer.compareAndSet(null, mapped))
                        buffer = mapped;
                    else
                        buffer = _mappedBuffer.get();
                }
                // Since MappedBuffers don't use heap, we don't care about the resource.length
                else if (_resource.length() < _maxCachedFileSize)
                {
                    ByteBuffer direct = CachedContentFactory.this.getDirectBuffer(_resource);
                    if (direct != null)
                    {
                        if (_directBuffer.compareAndSet(null, direct))
                        {
                            buffer = direct;
                            if (_cachedSize.addAndGet(BufferUtil.length(buffer)) > _maxCacheSize)
                                shrinkCache();
                        }
                        else
                        {
                            buffer = _directBuffer.get();
                        }
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Could not load " + this);
                    }
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
        public InputStream getInputStream() throws IOException
        {
            ByteBuffer indirect = getIndirectBuffer();
            if (indirect != null && indirect.hasArray())
                return new ByteArrayInputStream(indirect.array(), indirect.arrayOffset() + indirect.position(), indirect.remaining());

            return _resource.getInputStream();
        }

        @Override
        public ReadableByteChannel getReadableByteChannel() throws IOException
        {
            return _resource.getReadableByteChannel();
        }

        @Override
        public String toString()
        {
            return String.format("CachedContent@%x{r=%s,e=%b,lm=%s,ct=%s,c=%d}", hashCode(), _resource, _resource.exists(), _lastModified, _contentType, _precompressed.size());
        }

        @Override
        public Map<CompressedContentFormat, ? extends HttpContent> getPrecompressedContents()
        {
            if (_precompressed.size() == 0)
                return null;
            Map<CompressedContentFormat, CachedPrecompressedHttpContent> ret = _precompressed;
            for (Map.Entry<CompressedContentFormat, CachedPrecompressedHttpContent> entry : _precompressed.entrySet())
            {
                if (!entry.getValue().isValid())
                {
                    if (ret == _precompressed)
                        ret = new HashMap<>(_precompressed);
                    ret.remove(entry.getKey());
                }
            }
            return ret;
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

            _etag = (CachedContentFactory.this._etags) ? new PreEncodedHttpField(HttpHeader.ETAG, _content.getResource().getWeakETag(format._etag)) : null;
        }

        public boolean isValid()
        {
            return _precompressedContent.isValid() && _content.isValid() && _content.getResource().lastModified() <= _precompressedContent.getResource().lastModified();
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
