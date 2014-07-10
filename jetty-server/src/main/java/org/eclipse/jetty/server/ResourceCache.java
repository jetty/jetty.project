//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;


/* ------------------------------------------------------------ */
/** 
 * 
 */
public class ResourceCache
{
    private static final Logger LOG = Log.getLogger(ResourceCache.class);

    private final ConcurrentMap<String,Content> _cache;
    private final AtomicInteger _cachedSize;
    private final AtomicInteger _cachedFiles;
    private final ResourceFactory _factory;
    private final ResourceCache _parent;
    private final MimeTypes _mimeTypes;
    private final boolean _etagSupported;
    private final boolean  _useFileMappedBuffer;
    
    private int _maxCachedFileSize =4*1024*1024;
    private int _maxCachedFiles=2048;
    private int _maxCacheSize =32*1024*1024;
    
    /* ------------------------------------------------------------ */
    /** Constructor.
     * @param mimeTypes Mimetype to use for meta data
     */
    public ResourceCache(ResourceCache parent, ResourceFactory factory, MimeTypes mimeTypes,boolean useFileMappedBuffer,boolean etags)
    {
        _factory = factory;
        _cache=new ConcurrentHashMap<String,Content>();
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
                    Content content = _cache.remove(path);
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
     * then a {@link ResourceAsHttpContent} instance is return. If 
     * the resource does not exist, then null is returned.
     * @throws IOException Problem loading the resource
     */
    public HttpContent lookup(String pathInContext)
        throws IOException
    {
        // Is the content in this cache?
        Content content =_cache.get(pathInContext);
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
     * @param resource
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
        Content content=null;
        
        if (resource==null || !resource.exists())
            return null;
        
        // Will it fit in the cache?
        if (!resource.isDirectory() && isCacheable(resource))
        {   
            // Create the Content (to increment the cache sizes before adding the content 
            content = new Content(pathInContext,resource);

            // reduce the cache to an acceptable size.
            shrinkCache();

            // Add it to the cache.
            Content added = _cache.putIfAbsent(pathInContext,content);
            if (added!=null)
            {
                content.invalidate();
                content=added;
            }

            return content;
        }
        
        return new HttpContent.ResourceAsHttpContent(resource,_mimeTypes.getMimeByExtension(resource.toString()),getMaxCachedFileSize(),_etagSupported);
        
    }
    
    /* ------------------------------------------------------------ */
    private void shrinkCache()
    {
        // While we need to shrink
        while (_cache.size()>0 && (_cachedFiles.get()>_maxCachedFiles || _cachedSize.get()>_maxCacheSize))
        {
            // Scan the entire cache and generate an ordered list by last accessed time.
            SortedSet<Content> sorted= new TreeSet<Content>(
                    new Comparator<Content>()
                    {
                        public int compare(Content c1, Content c2)
                        {
                            if (c1._lastAccessed<c2._lastAccessed)
                                return -1;
                            
                            if (c1._lastAccessed>c2._lastAccessed)
                                return 1;

                            if (c1._length<c2._length)
                                return -1;
                            
                            return c1._key.compareTo(c2._key);
                        }
                    });
            for (Content content : _cache.values())
                sorted.add(content);
            
            // Invalidate least recently used first
            for (Content content : sorted)
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
            if (_useFileMappedBuffer && resource.getFile()!=null) 
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
    public class Content implements HttpContent
    {
        final Resource _resource;
        final int _length;
        final String _key;
        final long _lastModified;
        final ByteBuffer _lastModifiedBytes;
        final ByteBuffer _contentType;
        final String _etag;
        
        volatile long _lastAccessed;
        AtomicReference<ByteBuffer> _indirectBuffer=new AtomicReference<ByteBuffer>();
        AtomicReference<ByteBuffer> _directBuffer=new AtomicReference<ByteBuffer>();

        /* ------------------------------------------------------------ */
        Content(String pathInContext,Resource resource)
        {
            _key=pathInContext;
            _resource=resource;

            String mimeType = _mimeTypes.getMimeByExtension(_resource.toString());
            _contentType=(mimeType==null?null:BufferUtil.toBuffer(mimeType));
            boolean exists=resource.exists();
            _lastModified=exists?resource.lastModified():-1;
            _lastModifiedBytes=_lastModified<0?null:BufferUtil.toBuffer(DateGenerator.formatDate(_lastModified));
            
            _length=exists?(int)resource.length():0;
            _cachedSize.addAndGet(_length);
            _cachedFiles.incrementAndGet();
            _lastAccessed=System.currentTimeMillis();
            
            _etag=ResourceCache.this._etagSupported?resource.getWeakETag():null;
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
        public String getETag()
        {
            return _etag;
        }
        
        /* ------------------------------------------------------------ */
        boolean isValid()
        {
            if (_lastModified==_resource.lastModified() && _length==_resource.length())
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
            _cachedSize.addAndGet(-_length);
            _cachedFiles.decrementAndGet();
            _resource.close(); 
        }

        /* ------------------------------------------------------------ */
        @Override
        public String getLastModified()
        {
            return BufferUtil.toString(_lastModifiedBytes);
        }

        /* ------------------------------------------------------------ */
        @Override
        public String getContentType()
        {
            return BufferUtil.toString(_contentType);
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
        public long getContentLength()
        {
            return _length;
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
            return String.format("%s %s %d %s %s",_resource,_resource.exists(),_resource.lastModified(),_contentType,_lastModifiedBytes);
        }   
    }
}
