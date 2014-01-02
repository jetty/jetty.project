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
import java.util.Comparator;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpContent.ResourceAsHttpContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.View;
import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
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
    private final boolean _etags;

    private boolean  _useFileMappedBuffer=true;
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
        _etags=etags;
        _useFileMappedBuffer=useFileMappedBuffer;
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
    public void setUseFileMappedBuffer(boolean useFileMappedBuffer)
    {
        _useFileMappedBuffer = useFileMappedBuffer;
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
        
        return new HttpContent.ResourceAsHttpContent(resource,_mimeTypes.getMimeByExtension(resource.toString()),getMaxCachedFileSize(),_etags);
        
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
    protected Buffer getIndirectBuffer(Resource resource)
    {
        try
        {
            int len=(int)resource.length();
            if (len<0)
            {
                LOG.warn("invalid resource: "+String.valueOf(resource)+" "+len);
                return null;
            }
            Buffer buffer = new IndirectNIOBuffer(len);
            InputStream is = resource.getInputStream();
            buffer.readFrom(is,len);
            is.close();
            return buffer;
        }
        catch(IOException e)
        {
            LOG.warn(e);
            return null;
        }
    }

    /* ------------------------------------------------------------ */
    protected Buffer getDirectBuffer(Resource resource)
    {
        try
        {
            if (_useFileMappedBuffer && resource.getFile()!=null) 
                return new DirectNIOBuffer(resource.getFile());

            int len=(int)resource.length();
            if (len<0)
            {
                LOG.warn("invalid resource: "+String.valueOf(resource)+" "+len);
                return null;
            }
            Buffer buffer = new DirectNIOBuffer(len);
            InputStream is = resource.getInputStream();
            buffer.readFrom(is,len);
            is.close();
            return buffer;
        }
        catch(IOException e)
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
        final Buffer _lastModifiedBytes;
        final Buffer _contentType;
        final Buffer _etagBuffer;
        
        volatile long _lastAccessed;
        AtomicReference<Buffer> _indirectBuffer=new AtomicReference<Buffer>();
        AtomicReference<Buffer> _directBuffer=new AtomicReference<Buffer>();

        /* ------------------------------------------------------------ */
        Content(String pathInContext,Resource resource)
        {
            _key=pathInContext;
            _resource=resource;

            _contentType=_mimeTypes.getMimeByExtension(_resource.toString());
            boolean exists=resource.exists();
            _lastModified=exists?resource.lastModified():-1;
            _lastModifiedBytes=_lastModified<0?null:new ByteArrayBuffer(HttpFields.formatDate(_lastModified));
            
            _length=exists?(int)resource.length():0;
            _cachedSize.addAndGet(_length);
            _cachedFiles.incrementAndGet();
            _lastAccessed=System.currentTimeMillis();
            
            _etagBuffer=_etags?new ByteArrayBuffer(resource.getWeakETag()):null;
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
        public Resource getResource()
        {
            return _resource;
        }

        /* ------------------------------------------------------------ */
        public Buffer getETag()
        {
            return _etagBuffer;
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
            _resource.release(); 
        }

        /* ------------------------------------------------------------ */
        public Buffer getLastModified()
        {
            return _lastModifiedBytes;
        }

        /* ------------------------------------------------------------ */
        public Buffer getContentType()
        {
            return _contentType;
        }

        /* ------------------------------------------------------------ */
        public void release()
        {
            // don't release while cached. Release when invalidated.
        }

        /* ------------------------------------------------------------ */
        public Buffer getIndirectBuffer()
        {
            Buffer buffer = _indirectBuffer.get();
            if (buffer==null)
            {
                Buffer buffer2=ResourceCache.this.getIndirectBuffer(_resource);
                
                if (buffer2==null)
                    LOG.warn("Could not load "+this);
                else if (_indirectBuffer.compareAndSet(null,buffer2))
                    buffer=buffer2;
                else
                    buffer=_indirectBuffer.get();
            }
            if (buffer==null)
                return null;
            return new View(buffer);
        }
        

        /* ------------------------------------------------------------ */
        public Buffer getDirectBuffer()
        {
            Buffer buffer = _directBuffer.get();
            if (buffer==null)
            {
                Buffer buffer2=ResourceCache.this.getDirectBuffer(_resource);

                if (buffer2==null)
                    LOG.warn("Could not load "+this);
                else if (_directBuffer.compareAndSet(null,buffer2))
                    buffer=buffer2;
                else
                    buffer=_directBuffer.get();
            }
            if (buffer==null)
                return null;
                        
            return new View(buffer);
        }
        
        /* ------------------------------------------------------------ */
        public long getContentLength()
        {
            return _length;
        }

        /* ------------------------------------------------------------ */
        public InputStream getInputStream() throws IOException
        {
            Buffer indirect = getIndirectBuffer();
            if (indirect!=null && indirect.array()!=null)
                return new ByteArrayInputStream(indirect.array(),indirect.getIndex(),indirect.length());
           
            return _resource.getInputStream();
        }   

        /* ------------------------------------------------------------ */
        @Override
        public String toString()
        {
            return String.format("%s %s %d %s %s",_resource,_resource.exists(),_resource.lastModified(),_contentType,_lastModifiedBytes);
        }   
    }
}
