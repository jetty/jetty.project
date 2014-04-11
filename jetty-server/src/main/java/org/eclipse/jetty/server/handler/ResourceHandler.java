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
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.WriterOutputStream;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.FileResource;
import org.eclipse.jetty.util.resource.Resource;


/* ------------------------------------------------------------ */
/** Resource Handler.
 *
 * This handle will serve static content and handle If-Modified-Since headers.
 * No caching is done.
 * Requests for resources that do not exist are let pass (Eg no 404's).
 *
 *
 * @org.apache.xbean.XBean
 */
public class ResourceHandler extends HandlerWrapper
{
    private static final Logger LOG = Log.getLogger(ResourceHandler.class);

    ContextHandler _context;
    Resource _baseResource;
    Resource _defaultStylesheet;
    Resource _stylesheet;
    String[] _welcomeFiles={"index.html"};
    MimeTypes _mimeTypes = new MimeTypes();
    ByteArrayBuffer _cacheControl;
    boolean _aliases;
    boolean _directory;
    boolean _etags;

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
    /**
     * @return True if resource aliases are allowed.
     */
    public boolean isAliases()
    {
        return _aliases;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set if resource aliases (eg symlink, 8.3 names, case insensitivity) are allowed.
     * Allowing aliases can significantly increase security vulnerabilities.
     * If this handler is deployed inside a ContextHandler, then the
     * {@link ContextHandler#isAliases()} takes precedent.
     * @param aliases True if aliases are supported.
     */
    public void setAliases(boolean aliases)
    {
        _aliases = aliases;
    }

    /* ------------------------------------------------------------ */
    /** Get the directory option.
     * @return true if directories are listed.
     */
    public boolean isDirectoriesListed()
    {
        return _directory;
    }

    /* ------------------------------------------------------------ */
    /** Set the directory.
     * @param directory true if directories are listed.
     */
    public void setDirectoriesListed(boolean directory)
    {
        _directory = directory;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if ETag processing is done
     */
    public boolean isEtags()
    {
        return _etags;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param etags True if ETag processing is done
     */
    public void setEtags(boolean etags)
    {
        _etags = etags;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void doStart()
    throws Exception
    {
        Context scontext = ContextHandler.getCurrentContext();
        _context = (scontext==null?null:scontext.getContextHandler());

        if (_context!=null)
            _aliases=_context.isAliases();

        if (!_aliases && !FileResource.getCheckAliases())
            throw new IllegalStateException("Alias checking disabled");

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
            LOG.warn(e.toString());
            LOG.debug(e);
            throw new IllegalArgumentException(resourceBase);
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the stylesheet as a Resource.
     */
    public Resource getStylesheet()
    {
    	if(_stylesheet != null)
    	{
    	    return _stylesheet;
    	}
    	else
    	{
    	    if(_defaultStylesheet == null)
    	    {
    	        try
    	        {
    	            _defaultStylesheet =  Resource.newResource(this.getClass().getResource("/jetty-dir.css"));
    	        }
    	        catch(IOException e)
    	        {
    	            LOG.warn(e.toString());
    	            LOG.debug(e);
    	        }	 
    	    }
    	    return _defaultStylesheet;
    	}
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param stylesheet The location of the stylesheet to be used as a String.
     */
    public void setStylesheet(String stylesheet)
    {
        try
        {
            _stylesheet = Resource.newResource(stylesheet);
            if(!_stylesheet.exists())
            {
                LOG.warn("unable to find custom stylesheet: " + stylesheet);
                _stylesheet = null;
            }
        }
    	catch(Exception e)
    	{
    		LOG.warn(e.toString());
            LOG.debug(e);
            throw new IllegalArgumentException(stylesheet.toString());
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
            return base.addPath(path);
        }
        catch(Exception e)
        {
            LOG.ignore(e);
        }

        return null;
    }

    /* ------------------------------------------------------------ */
    protected Resource getResource(HttpServletRequest request) throws MalformedURLException
    {
        String servletPath;
        String pathInfo;
        Boolean included = request.getAttribute(Dispatcher.INCLUDE_REQUEST_URI) != null;
        if (included != null && included.booleanValue())
        {
            servletPath = (String)request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH);
            pathInfo = (String)request.getAttribute(Dispatcher.INCLUDE_PATH_INFO);
 
            if (servletPath == null && pathInfo == null)
            {
                servletPath = request.getServletPath();
                pathInfo = request.getPathInfo();
            }
        }
        else
        {
            servletPath = request.getServletPath();
            pathInfo = request.getPathInfo();
        }
        
        String pathInContext=URIUtil.addPaths(servletPath,pathInfo);
        return getResource(pathInContext);
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
     * @see org.eclipse.jetty.server.Handler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, int)
     */
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (baseRequest.isHandled())
            return;

        boolean skipContentBody = false;

        if(!HttpMethods.GET.equals(request.getMethod()))
        {
            if(!HttpMethods.HEAD.equals(request.getMethod()))
            {
                //try another handler
                super.handle(target, baseRequest, request, response);
                return;
            }
            skipContentBody = true;
        }
        
        Resource resource = getResource(request);
        
        if (resource==null || !resource.exists())
        {
            if (target.endsWith("/jetty-dir.css"))
            {	                
                resource = getStylesheet();
                if (resource==null)
                    return;
                response.setContentType("text/css");
            }
            else 
            {
                //no resource - try other handlers
                super.handle(target, baseRequest, request, response);
                return;
            }
        }
            
        if (!_aliases && resource.getAlias()!=null)
        {
            LOG.info(resource+" aliased to "+resource.getAlias());
            return;
        }

        // We are going to serve something
        baseRequest.setHandled(true);

        if (resource.isDirectory())
        {
            if (!request.getPathInfo().endsWith(URIUtil.SLASH))
            {
                response.sendRedirect(response.encodeRedirectURL(URIUtil.addPaths(request.getRequestURI(),URIUtil.SLASH)));
                return;
            }

            Resource welcome=getWelcome(resource);
            if (welcome!=null && welcome.exists())
                resource=welcome;
            else
            {
                doDirectory(request,response,resource);
                baseRequest.setHandled(true);
                return;
            }
        }

        // set some headers
        long last_modified=resource.lastModified();
        String etag=null;
        if (_etags)
        {
            // simple handling of only a single etag
            String ifnm = request.getHeader(HttpHeaders.IF_NONE_MATCH);
            etag=resource.getWeakETag();
            if (ifnm!=null && resource!=null && ifnm.equals(etag))
            {
                response.setStatus(HttpStatus.NOT_MODIFIED_304);
                baseRequest.getResponse().getHttpFields().put(HttpHeaders.ETAG_BUFFER,etag);
                return;
            }
        }
        
        
        if (last_modified>0)
        {
            long if_modified=request.getDateHeader(HttpHeaders.IF_MODIFIED_SINCE);
            if (if_modified>0 && last_modified/1000<=if_modified/1000)
            {
                response.setStatus(HttpStatus.NOT_MODIFIED_304);
                return;
            }
        }

        Buffer mime=_mimeTypes.getMimeByExtension(resource.toString());
        if (mime==null)
            mime=_mimeTypes.getMimeByExtension(request.getPathInfo());

        // set the headers
        doResponseHeaders(response,resource,mime!=null?mime.toString():null);
        response.setDateHeader(HttpHeaders.LAST_MODIFIED,last_modified);
        if (_etags)
            baseRequest.getResponse().getHttpFields().put(HttpHeaders.ETAG_BUFFER,etag);
        
        if(skipContentBody)
            return;
        // Send the content
        OutputStream out =null;
        try {out = response.getOutputStream();}
        catch(IllegalStateException e) {out = new WriterOutputStream(response.getWriter());}

        // See if a short direct method can be used?
        if (out instanceof AbstractHttpConnection.Output)
        {
            // TODO file mapped buffers
            ((AbstractHttpConnection.Output)out).sendContent(resource.getInputStream());
        }
        else
        {
            // Write content normally
            resource.writeTo(out,0,resource.length());
        }
    }

    /* ------------------------------------------------------------ */
    protected void doDirectory(HttpServletRequest request,HttpServletResponse response, Resource resource)
        throws IOException
    {
        if (_directory)
        {
            String listing = resource.getListHTML(request.getRequestURI(),request.getPathInfo().lastIndexOf("/") > 0);
            response.setContentType("text/html; charset=UTF-8");
            response.getWriter().println(listing);
        }
        else
            response.sendError(HttpStatus.FORBIDDEN_403);
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
                response.setHeader(HttpHeaders.CONTENT_LENGTH,Long.toString(length));

            if (_cacheControl!=null)
                response.setHeader(HttpHeaders.CACHE_CONTROL,_cacheControl.toString());
        }

    }
}
