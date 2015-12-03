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

import java.io.IOException;

import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpContent.Factory;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.ResourceHttpContent;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;

public class ResourceContentFactory implements Factory
{
    private final ResourceFactory _factory;
    private final MimeTypes _mimeTypes;
    private final int _maxBufferSize;
    private final boolean _gzip;
    

    /* ------------------------------------------------------------ */
    public ResourceContentFactory(ResourceFactory factory, MimeTypes mimeTypes, int maxBufferSize, boolean gzip)
    {
        _factory=factory;
        _mimeTypes=mimeTypes;
        _maxBufferSize=maxBufferSize;
        _gzip=gzip;
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
    @Override
    public HttpContent getContent(String pathInContext)
        throws IOException
    {
       
        // try loading the content from our factory.
        Resource resource=_factory.getResource(pathInContext);
        HttpContent loaded = load(pathInContext,resource);
        return loaded;
    }
    
    
    /* ------------------------------------------------------------ */
    private HttpContent load(String pathInContext, Resource resource)
        throws IOException
    {   
        if (resource==null || !resource.exists())
            return null;
        
        if (resource.isDirectory())
            return new ResourceHttpContent(resource,_mimeTypes.getMimeByExtension(resource.toString()),_maxBufferSize);
        
        // Look for a gzip resource or content
        String mt = _mimeTypes.getMimeByExtension(pathInContext);
        if (_gzip)
        {
            // Is there a gzip resource? 
            String pathInContextGz=pathInContext+".gz";
            Resource resourceGz=_factory.getResource(pathInContextGz);
            if (resourceGz.exists() && resourceGz.lastModified()>=resource.lastModified() && resourceGz.length()<resource.length())
                return new ResourceHttpContent(resource,mt,_maxBufferSize,
                       new ResourceHttpContent(resourceGz,_mimeTypes.getMimeByExtension(pathInContextGz),_maxBufferSize));
        }
        
        return new ResourceHttpContent(resource,mt,_maxBufferSize);
    }
    
    
    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return "ResourceContentFactory["+_factory+"]@"+hashCode();
    }
    

}
