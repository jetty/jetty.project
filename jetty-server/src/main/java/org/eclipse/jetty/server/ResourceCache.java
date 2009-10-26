// ========================================================================
// Copyright (c) 2000-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.View;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;


/* ------------------------------------------------------------ */
/** 
 * 
 */
public class ResourceCache extends AbstractLifeCycle
{
    protected final Map _cache;
    private final MimeTypes _mimeTypes;
    private int _maxCachedFileSize =1024*1024;
    private int _maxCachedFiles=2048;
    private int _maxCacheSize =16*1024*1024;

    protected int _cachedSize;
    protected int _cachedFiles;
    protected Content _mostRecentlyUsed;
    protected Content _leastRecentlyUsed;

    /* ------------------------------------------------------------ */
    /** Constructor.
     */
    public ResourceCache(MimeTypes mimeTypes)
    {
        _cache=new HashMap();
        _mimeTypes=mimeTypes;
    }

    /* ------------------------------------------------------------ */
    public int getCachedSize()
    {
        return _cachedSize;
    }
    
    /* ------------------------------------------------------------ */
    public int getCachedFiles()
    {
        return _cachedFiles;
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
        flushCache();
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
        flushCache();
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
    }
    
    /* ------------------------------------------------------------ */
    public void flushCache()
    {
        if (_cache!=null)
        {
            synchronized(this)
            {
                ArrayList<Content> values=new ArrayList<Content>(_cache.values());
                for (Content content : values)
                    content.invalidate();
                
                _cache.clear();
                
                _cachedSize=0;
                _cachedFiles=0;
                _mostRecentlyUsed=null;
                _leastRecentlyUsed=null;
            }
        }
    }

    /* ------------------------------------------------------------ */
    /** Get a Entry from the cache.
     * Get either a valid entry object or create a new one if possible.
     *
     * @param pathInContext The key into the cache
     * @param factory If no matching entry is found, this {@link ResourceFactory} will be used to create the {@link Resource} 
     *                for the new enry that is created.
     * @return The entry matching <code>pathInContext</code>, or a new entry if no matching entry was found
     */
    public Content lookup(String pathInContext, ResourceFactory factory)
        throws IOException
    {
        Content content=null;
        
        // Look up cache operations
        synchronized(_cache)
        {
            // Look for it in the cache
            content = (Content)_cache.get(pathInContext);
        
            if (content!=null && content.isValid())
            {
                return content;
            }    
        }
        Resource resource=factory.getResource(pathInContext);
        return load(pathInContext,resource);
    }

    /* ------------------------------------------------------------ */
    public Content lookup(String pathInContext, Resource resource)
        throws IOException
    {
        Content content=null;
        
        // Look up cache operations
        synchronized(_cache)
        {
            // Look for it in the cache
            content = (Content)_cache.get(pathInContext);
        
            if (content!=null && content.isValid())
            {
                return content;
            }    
        }
        return load(pathInContext,resource);
    }

    /* ------------------------------------------------------------ */
    private Content load(String pathInContext, Resource resource)
        throws IOException
    {
        Content content=null;
        if (resource!=null && resource.exists() && !resource.isDirectory())
        {
            long len = resource.length();
            if (len>0 && len<_maxCachedFileSize && len<_maxCacheSize)
            {   
                int must_be_smaller_than=_maxCacheSize-(int)len;
                
                synchronized(_cache)
                {
                    // check the cache is not full of locked content before loading content

                    while(_leastRecentlyUsed!=null && (_cachedSize>must_be_smaller_than || (_maxCachedFiles>0 && _cachedFiles>=_maxCachedFiles)))
                        _leastRecentlyUsed.invalidate();
                    
                    if(_cachedSize>must_be_smaller_than || (_maxCachedFiles>0 && _cachedFiles>=_maxCachedFiles))
                        return null;
                }
                
                content = new Content(resource);
                fill(content);

                synchronized(_cache)
                {
                    // check that somebody else did not fill this spot.
                    Content content2 =(Content)_cache.get(pathInContext);
                    if (content2!=null)
                    {
                        content.release();
                        return content2;
                    }

                    while(_leastRecentlyUsed!=null && (_cachedSize>must_be_smaller_than || (_maxCachedFiles>0 && _cachedFiles>=_maxCachedFiles)))
                        _leastRecentlyUsed.invalidate();
                    
                    if(_cachedSize>must_be_smaller_than || (_maxCachedFiles>0 && _cachedFiles>=_maxCachedFiles))
                        return null; // this could waste an allocated File or DirectBuffer
                    
                    content.cache(pathInContext);
                    
                    return content;
                }
            }
        }

        return null; 
    }

    /* ------------------------------------------------------------ */
    /** Remember a Resource Miss!
     * @param pathInContext
     * @param resource
     * @throws IOException
     */
    public void miss(String pathInContext, Resource resource)
        throws IOException
    {
        synchronized(_cache)
        {
            while(_maxCachedFiles>0 && _cachedFiles>=_maxCachedFiles && _leastRecentlyUsed!=null)
                _leastRecentlyUsed.invalidate();
            if (_maxCachedFiles>0 && _cachedFiles>=_maxCachedFiles)
                return;
            
            // check that somebody else did not fill this spot.
            Miss miss = new Miss(resource);
            Content content2 =(Content)_cache.get(pathInContext);
            if (content2!=null)
            {
                miss.release();
                return;
            }

            miss.cache(pathInContext);
        }
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public synchronized void doStart()
        throws Exception
    {
        _cache.clear();
        _cachedSize=0;
        _cachedFiles=0;
    }

    /* ------------------------------------------------------------ */
    /** Stop the context.
     */
    @Override
    public void doStop()
        throws InterruptedException
    {
        flushCache();
    }

    /* ------------------------------------------------------------ */
    protected void fill(Content content)
        throws IOException
    {
        try
        {
            InputStream in = content.getResource().getInputStream();
            int len=(int)content.getResource().length();
            Buffer buffer = new ByteArrayBuffer(len);
            buffer.readFrom(in,len);
            in.close();
            content.setBuffer(buffer);
        }
        finally
        {
            content.getResource().release();
        }
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** MetaData associated with a context Resource.
     */
    public class Content implements HttpContent
    {
        final Resource _resource;
        final long _lastModified;
        boolean _locked;
        String _key;
        Content _prev;
        Content _next;
        
        Buffer _lastModifiedBytes;
        Buffer _contentType;
        Buffer _buffer;

        /* ------------------------------------------------------------ */
        Content(Resource resource)
        {
            _resource=resource;

            _next=this;
            _prev=this;
            _contentType=_mimeTypes.getMimeByExtension(_resource.toString());
            
            _lastModified=resource.lastModified();
        }


        /* ------------------------------------------------------------ */
        /**
         * @return true if the content is locked in the cache
         */
        public boolean isLocked()
        {
            return _locked;
        }


        /* ------------------------------------------------------------ */
        /**
         * @param locked true if the content is locked in the cache
         */
        public void setLocked(boolean locked)
        {
            synchronized (_cache)
            {
                if (_locked && !locked)
                {
                    _locked = locked;
                    _next=_mostRecentlyUsed;
                    _mostRecentlyUsed=this;
                    if (_next!=null)
                        _next._prev=this;
                    _prev=null;
                    if (_leastRecentlyUsed==null)
                        _leastRecentlyUsed=this;
                }
                else if (!_locked && locked)
                {
                    if (_prev!=null)
                        _prev._next=_next;
                    if (_next!=null)
                        _next._prev=_prev;
                    _next=_prev=null;
                }
                else
                    _locked = locked;
            }
        }


        /* ------------------------------------------------------------ */
        void cache(String pathInContext)
        {
            _key=pathInContext;
            
            if (!_locked)
            {
                _next=_mostRecentlyUsed;
                _mostRecentlyUsed=this;
                if (_next!=null)
                    _next._prev=this;
                _prev=null;
                if (_leastRecentlyUsed==null)
                    _leastRecentlyUsed=this;
            }
            _cache.put(_key,this);
            if (_buffer!=null)
                _cachedSize+=_buffer.length();
            _cachedFiles++;
            if (_lastModified!=-1)
                _lastModifiedBytes=new ByteArrayBuffer(HttpFields.formatDate(_lastModified));
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
        public Resource getResource()
        {
            return _resource;
        }
        
        /* ------------------------------------------------------------ */
        boolean isValid()
        {
            if (_lastModified==_resource.lastModified())
            {
                if (!_locked && _mostRecentlyUsed!=this)
                {
                    Content tp = _prev;
                    Content tn = _next;

                    _next=_mostRecentlyUsed;
                    _mostRecentlyUsed=this;
                    if (_next!=null)
                        _next._prev=this;
                    _prev=null;

                    if (tp!=null)
                        tp._next=tn;
                    if (tn!=null)
                        tn._prev=tp;

                    if (_leastRecentlyUsed==this && tp!=null)
                        _leastRecentlyUsed=tp;
                }
                return true;
            }

            invalidate();
            return false;
        }

        /* ------------------------------------------------------------ */
        public void invalidate()
        {
            synchronized(this)
            {
                // Invalidate it
                _cache.remove(_key);
                _key=null;
                if (_buffer!=null)
                    _cachedSize=_cachedSize-_buffer.length();
                _cachedFiles--;
                
                if (_mostRecentlyUsed==this)
                    _mostRecentlyUsed=_next;
                else
                    _prev._next=_next;
                
                if (_leastRecentlyUsed==this)
                    _leastRecentlyUsed=_prev;
                else
                    _next._prev=_prev;
                
                _prev=null;
                _next=null;
                _resource.release();
                
            }
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
        public void setContentType(Buffer type)
        {
            _contentType=type;
        }

        /* ------------------------------------------------------------ */
        public void release()
        {
        }

        /* ------------------------------------------------------------ */
        public Buffer getBuffer()
        {
            if (_buffer==null)
                return null;
            return new View(_buffer);
        }
        
        /* ------------------------------------------------------------ */
        public void setBuffer(Buffer buffer)
        {
            _buffer=buffer;
        }

        /* ------------------------------------------------------------ */
        public long getContentLength()
        {
            if (_buffer==null)
                return -1;
            return _buffer.length();
        }

        /* ------------------------------------------------------------ */
        public InputStream getInputStream() throws IOException
        {
            return _resource.getInputStream();
        }   

        /* ------------------------------------------------------------ */
        @Override
        public String toString()
        {
            return "{"+_resource+","+_contentType+","+_lastModifiedBytes+"}";
        }
        
        
    }
    

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /** MetaData associated with a context Resource.
     */
    public class Miss extends Content
    {
        Miss(Resource resource)
        {
            super(resource);
        }

        /* ------------------------------------------------------------ */
        @Override
        boolean isValid()
        {
            if (_resource.exists())
            {
                invalidate();
                return false;
            }
            return true;
        }
    }
}
