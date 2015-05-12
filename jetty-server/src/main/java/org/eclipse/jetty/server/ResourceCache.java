//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.util.Comparator;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MimeTypes.Type;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.ResourceHttpContent;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;


public class ResourceCache
{
    private static final Logger LOG = Log.getLogger(ResourceCache.class);

    private final ConcurrentMap<String,CachedHttpContent> _cache;
    private final AtomicInteger _cachedSize;
    private final AtomicInteger _cachedFiles;
    private final ResourceFactory _factory;
    private final ResourceCache _parent;
    private final MimeTypes _mimeTypes;
    private final boolean _etagSupported;
    private final boolean  _useFileMappedBuffer;
    
    private int _maxCachedFileSize =128*1024*1024;
    private int _maxCachedFiles=2048;
    private int _maxCacheSize =256*1024*1024;
    
    /* ------------------------------------------------------------ */
    /** Constructor.
     * @param parent the parent resource cache
     * @param factory the resource factory
     * @param mimeTypes Mimetype to use for meta data
     * @param useFileMappedBuffer true to file memory mapped buffers
     * @param etags true to support etags 
     */
    public ResourceCache(ResourceCache parent, ResourceFactory factory, MimeTypes mimeTypes,boolean useFileMappedBuffer,boolean etags)
    {
        _factory = factory;
        _cache=new ConcurrentHashMap<String,CachedHttpContent>();
        _cachedSize=new AtomicInteger();
        _cachedFiles=new AtomicInteger();
        _mimeTypes=mimeTypes;
        _parent=parent;
        _useFileMappedBuffer=useFileMappedBuffer;
        _etagSupported=etags;
    }

    /* ------------------------------------------------------------ */
    public int getCachedSize()
    {
        return _cachedSize.get();
    }
    
    /* ------------------------------------------------------------ */
    public int getCachedFiles()
    {
        return _cachedFiles.get();
    }
    
    /* ------------------------------------------------------------ */
    public int getMaxCachedFileSize()
    {
        return _maxCachedFileSize;
    }

    /* ------------------------------------------------------------ */
    public void setMaxCachedFileSize(int maxCachedFileSize)
    {
        _maxCachedFileSize = maxCachedFileSize;
        shrinkCache();
    }

    /* ------------------------------------------------------------ */
    public int getMaxCacheSize()
    {
        return _maxCacheSize;
    }

    /* ------------------------------------------------------------ */
    public void setMaxCacheSize(int maxCacheSize)
    {
        _maxCacheSize = maxCacheSize;
        shrinkCache();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the maxCachedFiles.
     */
    public int getMaxCachedFiles()
    {
        return _maxCachedFiles;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param maxCachedFiles The maxCachedFiles to set.
     */
    public void setMaxCachedFiles(int maxCachedFiles)
    {
        _maxCachedFiles = maxCachedFiles;
        shrinkCache();
    }

    /* ------------------------------------------------------------ */
    public boolean isUseFileMappedBuffer()
    {
        return _useFileMappedBuffer;
    }

    /* ------------------------------------------------------------ */
    public void flushCache()
    {
        if (_cache!=null)
        {
            while (_cache.size()>0)
            {
                for (String path : _cache.keySet())
                {
                    CachedHttpContent content = _cache.remove(path);
                    if (content!=null)
                        content.invalidate();
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /** Get a Entry from the cache.
     * Get either a valid entry object or create a new one if possible.
     *
     * @param pathInContext The key into the cache
     * @return The entry matching <code>pathInContext</code>, or a new entry 
     * if no matching entry was found. If the content exists but is not cachable, 
     * then a {@link ResourceHttpContent} instance is return. If 
     * the resource does not exist, then null is returned.
     * @throws IOException Problem loading the resource
     */
    public HttpContent lookup(String pathInContext)
        throws IOException
    {
        // Is the content in this cache?
        CachedHttpContent content =_cache.get(pathInContext);
        if (content!=null && (content).isValid())
            return content;
       
        // try loading the content from our factory.
        Resource resource=_factory.getResource(pathInContext);
        HttpContent loaded = load(pathInContext,resource);
        if (loaded!=null)
            return loaded;
        
        // Is the content in the parent cache?
        if (_parent!=null)
        {
            HttpContent httpContent=_parent.lookup(pathInContext);
            if (httpContent!=null)
                return httpContent;
        }
        
        return null;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param resource the resource to test
     * @return True if the resource is cacheable. The default implementation tests the cache sizes.
     */
    protected boolean isCacheable(Resource resource)
    {
        long len = resource.length();

        // Will it fit in the cache?
        return  (len>0 && len<_maxCachedFileSize && len<_maxCacheSize);
    }
    
    /* ------------------------------------------------------------ */
    private HttpContent load(String pathInContext, Resource resource)
        throws IOException
    {
        CachedHttpContent content=null;
        
        if (resource==null || !resource.exists())
            return null;
        
        // Will it fit in the cache?
        if (!resource.isDirectory() && isCacheable(resource))
        {   
            // Create the Content (to increment the cache sizes before adding the content 
            content = new CachedHttpContent(pathInContext,resource);

            // reduce the cache to an acceptable size.
            shrinkCache();

            // Add it to the cache.
            CachedHttpContent added = _cache.putIfAbsent(pathInContext,content);
            if (added!=null)
            {
                content.invalidate();
                content=added;
            }

            return content;
        }
        
        return new ResourceHttpContent(resource,_mimeTypes.getMimeByExtension(resource.toString()),getMaxCachedFileSize(),_etagSupported);
        
    }
    
    /* ------------------------------------------------------------ */
    private void shrinkCache()
    {
        // While we need to shrink
        while (_cache.size()>0 && (_cachedFiles.get()>_maxCachedFiles || _cachedSize.get()>_maxCacheSize))
        {
            // Scan the entire cache and generate an ordered list by last accessed time.
            SortedSet<CachedHttpContent> sorted= new TreeSet<CachedHttpContent>(
                    new Comparator<CachedHttpContent>()
                    {
                        public int compare(CachedHttpContent c1, CachedHttpContent c2)
                        {
                            if (c1._lastAccessed<c2._lastAccessed)
                                return -1;
                            
                            if (c1._lastAccessed>c2._lastAccessed)
                                return 1;

                            if (c1._contentLengthValue<c2._contentLengthValue)
                                return -1;
                            
                            return c1._key.compareTo(c2._key);
                        }
                    });
            for (CachedHttpContent content : _cache.values())
                sorted.add(content);
            
            // Invalidate least recently used first
            for (CachedHttpContent content : sorted)
            {
                if (_cachedFiles.get()<=_maxCachedFiles && _cachedSize.get()<=_maxCacheSize)
                    break;
                if (content==_cache.remove(content.getKey()))
                    content.invalidate();
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    protected ByteBuffer getIndirectBuffer(Resource resource)
    {
        try
        {
            return BufferUtil.toBuffer(resource,true);
        }
        catch(IOException|IllegalArgumentException e)
        {
            LOG.warn(e);
            return null;
        }
    }

    /* ------------------------------------------------------------ */
    protected ByteBuffer getDirectBuffer(Resource resource)
    {
        try
        {
            if (_useFileMappedBuffer && resource.getFile()!=null && resource.length()<Integer.MAX_VALUE) 
                return BufferUtil.toMappedBuffer(resource.getFile());
            
            return BufferUtil.toBuffer(resource,true);
        }
        catch(IOException|IllegalArgumentException e)
        {
            LOG.warn(e);
            return null;
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return "ResourceCache["+_parent+","+_factory+"]@"+hashCode();
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** MetaData associated with a context Resource.
     */
    public class CachedHttpContent implements HttpContent
    {
        final String _key;
        final Resource _resource;
        final int _contentLengthValue;
        final HttpField _contentType;
        final String _characterEncoding;
        final MimeTypes.Type _mimeType;
        final HttpField _contentLength;
        final HttpField _lastModified;
        final long _lastModifiedValue;
        final HttpField _etag;
        
        volatile long _lastAccessed;
        AtomicReference<ByteBuffer> _indirectBuffer=new AtomicReference<ByteBuffer>();
        AtomicReference<ByteBuffer> _directBuffer=new AtomicReference<ByteBuffer>();

        /* ------------------------------------------------------------ */
        CachedHttpContent(String pathInContext,Resource resource)
        {
            _key=pathInContext;
            _resource=resource;

            String contentType = _mimeTypes.getMimeByExtension(_resource.toString());
            _contentType=contentType==null?null:new PreEncodedHttpField(HttpHeader.CONTENT_TYPE,contentType);
            _characterEncoding = _contentType==null?null:MimeTypes.getCharsetFromContentType(contentType);
            _mimeType = _contentType==null?null:MimeTypes.CACHE.get(MimeTypes.getContentTypeWithoutCharset(contentType));
            
            boolean exists=resource.exists();
            _lastModifiedValue=exists?resource.lastModified():-1L;
            _lastModified=_lastModifiedValue==-1?null
                :new PreEncodedHttpField(HttpHeader.LAST_MODIFIED,DateGenerator.formatDate(_lastModifiedValue));
            
            _contentLengthValue=exists?(int)resource.length():0;
            _contentLength=new PreEncodedHttpField(HttpHeader.CONTENT_LENGTH,Long.toString(_contentLengthValue));
            
            _cachedSize.addAndGet(_contentLengthValue);
            _cachedFiles.incrementAndGet();
            _lastAccessed=System.currentTimeMillis();
            
            _etag=ResourceCache.this._etagSupported?new PreEncodedHttpField(HttpHeader.ETAG,resource.getWeakETag()):null;
        }


        /* ------------------------------------------------------------ */
        public String getKey()
        {
            return _key;
        }

        /* ------------------------------------------------------------ */
        public boolean isCached()
        {
            return _key!=null;
        }
        
        /* ------------------------------------------------------------ */
        public boolean isMiss()
        {
            return false;
        }

        /* ------------------------------------------------------------ */
        @Override
        public Resource getResource()
        {
            return _resource;
        }

        /* ------------------------------------------------------------ */
        @Override
        public HttpField getETag()
        {
            return _etag;
        }

        /* ------------------------------------------------------------ */
        @Override
        public String getETagValue()
        {
            return _etag.getValue();
        }
        
        /* ------------------------------------------------------------ */
        boolean isValid()
        {
            if (_lastModifiedValue==_resource.lastModified() && _contentLengthValue==_resource.length())
            {
                _lastAccessed=System.currentTimeMillis();
                return true;
            }

            if (this==_cache.remove(_key))
                invalidate();
            return false;
        }

        /* ------------------------------------------------------------ */
        protected void invalidate()
        {
            // Invalidate it
            _cachedSize.addAndGet(-_contentLengthValue);
            _cachedFiles.decrementAndGet();
            _resource.close(); 
        }

        /* ------------------------------------------------------------ */
        @Override
        public HttpField getLastModified()
        {
            return _lastModified;
        }
        
        /* ------------------------------------------------------------ */
        @Override
        public String getLastModifiedValue()
        {
            return _lastModified==null?null:_lastModified.getValue();
        }


        /* ------------------------------------------------------------ */
        @Override
        public HttpField getContentType()
        {
            return _contentType;
        }
        
        /* ------------------------------------------------------------ */
        @Override
        public String getContentTypeValue()
        {
            return _contentType==null?null:_contentType.getValue();
        }
        
        /* ------------------------------------------------------------ */
        @Override
        public String getCharacterEncoding()
        {
            return _characterEncoding;
        }

        /* ------------------------------------------------------------ */
        @Override
        public Type getMimeType()
        {
            return _mimeType;
        }


        /* ------------------------------------------------------------ */
        @Override
        public void release()
        {
            // don't release while cached. Release when invalidated.
        }

        /* ------------------------------------------------------------ */
        @Override
        public ByteBuffer getIndirectBuffer()
        {
            ByteBuffer buffer = _indirectBuffer.get();
            if (buffer==null)
            {
                ByteBuffer buffer2=ResourceCache.this.getIndirectBuffer(_resource);
                
                if (buffer2==null)
                    LOG.warn("Could not load "+this);
                else if (_indirectBuffer.compareAndSet(null,buffer2))
                    buffer=buffer2;
                else
                    buffer=_indirectBuffer.get();
            }
            if (buffer==null)
                return null;
            return buffer.slice();
        }
        

        /* ------------------------------------------------------------ */
        @Override
        public ByteBuffer getDirectBuffer()
        {
            ByteBuffer buffer = _directBuffer.get();
            if (buffer==null)
            {
                ByteBuffer buffer2=ResourceCache.this.getDirectBuffer(_resource);

                if (buffer2==null)
                    LOG.warn("Could not load "+this);
                else if (_directBuffer.compareAndSet(null,buffer2))
                    buffer=buffer2;
                else
                    buffer=_directBuffer.get();
            }
            if (buffer==null)
                return null;
            return buffer.asReadOnlyBuffer();
        }

        /* ------------------------------------------------------------ */
        @Override
        public HttpField getContentLength()
        {
            return _contentLength;
        }
        
        /* ------------------------------------------------------------ */
        @Override
        public long getContentLengthValue()
        {
            return _contentLengthValue;
        }

        /* ------------------------------------------------------------ */
        @Override
        public InputStream getInputStream() throws IOException
        {
            ByteBuffer indirect = getIndirectBuffer();
            if (indirect!=null && indirect.hasArray())
                return new ByteArrayInputStream(indirect.array(),indirect.arrayOffset()+indirect.position(),indirect.remaining());
           
            return _resource.getInputStream();
        }   
        
        /* ------------------------------------------------------------ */
        @Override
        public ReadableByteChannel getReadableByteChannel() throws IOException
        {
            return _resource.getReadableByteChannel();
        }


        /* ------------------------------------------------------------ */
        @Override
        public String toString()
        {
            return String.format("CachedContent@%x{r=%s,e=%b,lm=%s,ct=%s}",hashCode(),_resource,_resource.exists(),_lastModified,_contentType);
        }   
    }
}
