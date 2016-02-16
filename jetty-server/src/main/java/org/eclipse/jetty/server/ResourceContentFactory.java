//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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


/**
 * A HttpContent.Factory for transient content.  The HttpContent's created by 
 * this factory are not intended to be cached, so memory limits for individual
 * HttpOutput streams are enforced.
 */
public class ResourceContentFactory implements Factory
{
    private final ResourceFactory _factory;
    private final MimeTypes _mimeTypes;
    private final boolean _gzip;
    
    /* ------------------------------------------------------------ */
    public ResourceContentFactory(ResourceFactory factory, MimeTypes mimeTypes, boolean gzip)
    {
        _factory=factory;
        _mimeTypes=mimeTypes;
        _gzip=gzip;
    }

    /* ------------------------------------------------------------ */
    @Override
    public HttpContent getContent(String pathInContext,int maxBufferSize)
        throws IOException
    {
        // try loading the content from our factory.
        Resource resource=_factory.getResource(pathInContext);
        HttpContent loaded = load(pathInContext,resource,maxBufferSize);
        return loaded;
    }
    
    
    /* ------------------------------------------------------------ */
    private HttpContent load(String pathInContext, Resource resource, int maxBufferSize)
        throws IOException
    {   
        if (resource==null || !resource.exists())
            return null;
        
        if (resource.isDirectory())
            return new ResourceHttpContent(resource,_mimeTypes.getMimeByExtension(resource.toString()),maxBufferSize);
        
        // Look for a gzip resource or content
        String mt = _mimeTypes.getMimeByExtension(pathInContext);
        if (_gzip)
        {
            // Is there a gzip resource? 
            String pathInContextGz=pathInContext+".gz";
            Resource resourceGz=_factory.getResource(pathInContextGz);
            if (resourceGz.exists() && resourceGz.lastModified()>=resource.lastModified() && resourceGz.length()<resource.length())
                return new ResourceHttpContent(resource,mt,maxBufferSize,
                       new ResourceHttpContent(resourceGz,_mimeTypes.getMimeByExtension(pathInContextGz),maxBufferSize));
        }
        
        return new ResourceHttpContent(resource,mt,maxBufferSize);
    }
    
    
    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return "ResourceContentFactory["+_factory+"]@"+hashCode();
    }
    

}
