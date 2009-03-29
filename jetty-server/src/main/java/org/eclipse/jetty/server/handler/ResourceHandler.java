// ========================================================================
// Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.WriterOutputStream;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;


/* ------------------------------------------------------------ */
/** Resource Handler.
 * 
 * This handle will serve static content and handle If-Modified-Since headers.
 * No caching is done.
 * Requests that cannot be handled are let pass (Eg no 404's)
 * 
 * 
 * @org.apache.xbean.XBean
 */
public class ResourceHandler extends AbstractHandler
{
    ContextHandler _context;
    Resource _baseResource;
    String[] _welcomeFiles={"index.html"};
    MimeTypes _mimeTypes = new MimeTypes();
    ByteArrayBuffer _cacheControl;

    /* ------------------------------------------------------------ */
    public ResourceHandler()
    {
    }

    /* ------------------------------------------------------------ */
    public MimeTypes getMimeTypes()
    {
        return _mimeTypes;
    }

    /* ------------------------------------------------------------ */
    public void setMimeTypes(MimeTypes mimeTypes)
    {
        _mimeTypes = mimeTypes;
    }

    /* ------------------------------------------------------------ */
    public void doStart()
    throws Exception
    {
        Context scontext = ContextHandler.getCurrentContext();
        _context = (scontext==null?null:scontext.getContextHandler());
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the resourceBase.
     */
    public Resource getBaseResource()
    {
        if (_baseResource==null)
            return null;
        return _baseResource;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the base resource as a string.
     */
    public String getResourceBase()
    {
        if (_baseResource==null)
            return null;
        return _baseResource.toString();
    }

    
    /* ------------------------------------------------------------ */
    /**
     * @param base The resourceBase to set.
     */
    public void setBaseResource(Resource base) 
    {
        _baseResource=base;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param resourceBase The base resource as a string.
     */
    public void setResourceBase(String resourceBase) 
    {
        try
        {
            setBaseResource(Resource.newResource(resourceBase));
        }
        catch (Exception e)
        {
            Log.warn(e);
            throw new IllegalArgumentException(resourceBase);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the cacheControl header to set on all static content.
     */
    public String getCacheControl()
    {
        return _cacheControl.toString();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param cacheControl the cacheControl header to set on all static content.
     */
    public void setCacheControl(String cacheControl)
    {
        _cacheControl=cacheControl==null?null:new ByteArrayBuffer(cacheControl);
    }

    /* ------------------------------------------------------------ */
    /* 
     */
    public Resource getResource(String path) throws MalformedURLException
    {
        if (path==null || !path.startsWith("/"))
            throw new MalformedURLException(path);
        
        Resource base = _baseResource;
        if (base==null)
        {
            if (_context==null)
                return null;            
            base=_context.getBaseResource();
            if (base==null)
                return null;
        }

        try
        {
            path=URIUtil.canonicalPath(path);
            Resource resource=base.addPath(path);
            return resource;
        }
        catch(Exception e)
        {
            Log.ignore(e);
        }
                    
        return null;
    }

    /* ------------------------------------------------------------ */
    protected Resource getResource(HttpServletRequest request) throws MalformedURLException
    {
        String path_info=request.getPathInfo();
        if (path_info==null)
            return null;
        return getResource(path_info);
    }


    /* ------------------------------------------------------------ */
    public String[] getWelcomeFiles()
    {
        return _welcomeFiles;
    }

    /* ------------------------------------------------------------ */
    public void setWelcomeFiles(String[] welcomeFiles)
    {
        _welcomeFiles=welcomeFiles;
    }
    
    /* ------------------------------------------------------------ */
    protected Resource getWelcome(Resource directory) throws MalformedURLException, IOException
    {
        for (int i=0;i<_welcomeFiles.length;i++)
        {
            Resource welcome=directory.addPath(_welcomeFiles[i]);
            if (welcome.exists() && !welcome.isDirectory())
                return welcome;
        }

        return null;
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.server.server.Handler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, int)
     */
    public void handle(String target, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        Request base_request = request instanceof Request?(Request)request:HttpConnection.getCurrentConnection().getRequest();
        if (base_request.isHandled())
            return;
        
        boolean skipContentBody = false;
        if(!HttpMethods.GET.equals(request.getMethod()))
        {
            if(!HttpMethods.HEAD.equals(request.getMethod()))
                return;
            skipContentBody = true;
        }
     
        Resource resource=getResource(request);
        
        if (resource==null || !resource.exists())
            return;

        // We are going to server something
        base_request.setHandled(true);
        
        if (resource.isDirectory())
        {
            if (!request.getPathInfo().endsWith(URIUtil.SLASH))
            {
                response.sendRedirect(URIUtil.addPaths(request.getRequestURI(),URIUtil.SLASH));
                return;
            }
            resource=getWelcome(resource);

            if (resource==null || !resource.exists() || resource.isDirectory())
            {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }
        
        // set some headers
        long last_modified=resource.lastModified();
        if (last_modified>0)
        {
            long if_modified=request.getDateHeader(HttpHeaders.IF_MODIFIED_SINCE);
            if (if_modified>0 && last_modified/1000<=if_modified/1000)
            {
                response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                return;
            }
        }
        
        Buffer mime=_mimeTypes.getMimeByExtension(resource.toString());
        if (mime==null)
            mime=_mimeTypes.getMimeByExtension(request.getPathInfo());
        
        // set the headers
        doResponseHeaders(response,resource,mime!=null?mime.toString():null);
        response.setDateHeader(HttpHeaders.LAST_MODIFIED,last_modified);
        if(skipContentBody)
            return;
        // Send the content
        OutputStream out =null;
        try {out = response.getOutputStream();}
        catch(IllegalStateException e) {out = new WriterOutputStream(response.getWriter());}
        
        // See if a short direct method can be used?
        if (out instanceof HttpConnection.Output)
        {
            // TODO file mapped buffers
            ((HttpConnection.Output)out).sendContent(resource.getInputStream());
        }
        else
        {
            // Write content normally
            resource.writeTo(out,0,resource.length());
        }
    }

    /* ------------------------------------------------------------ */
    /** Set the response headers.
     * This method is called to set the response headers such as content type and content length.
     * May be extended to add additional headers.
     * @param response
     * @param resource
     * @param mimeType
     */
    protected void doResponseHeaders(HttpServletResponse response, Resource resource, String mimeType)
    {
        if (mimeType!=null)
            response.setContentType(mimeType);

        long length=resource.length();
        
        if (response instanceof Response)
        {
            HttpFields fields = ((Response)response).getHttpFields();

            if (length>0)
                fields.putLongField(HttpHeaders.CONTENT_LENGTH_BUFFER,length);
                
            if (_cacheControl!=null)
                fields.put(HttpHeaders.CACHE_CONTROL_BUFFER,_cacheControl);
        }
        else
        {
            if (length>0)
                response.setHeader(HttpHeaders.CONTENT_LENGTH,TypeUtil.toString(length));
                
            if (_cacheControl!=null)
                response.setHeader(HttpHeaders.CACHE_CONTROL,_cacheControl.toString());
        }
        
    }
}
