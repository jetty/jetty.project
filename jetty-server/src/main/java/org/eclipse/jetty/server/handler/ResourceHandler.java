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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ResourceContentFactory;
import org.eclipse.jetty.server.ResourceService;
import org.eclipse.jetty.server.ResourceService.WelcomeFactory;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;

/**
 * Resource Handler.
 *
 * This handle will serve static content and handle If-Modified-Since headers. No caching is done. Requests for resources that do not exist are let pass (Eg no
 * 404's).
 */
public class ResourceHandler extends HandlerWrapper implements ResourceFactory, WelcomeFactory
{
    private static final Logger LOG = Log.getLogger(ResourceHandler.class);

    Resource _baseResource;
    ContextHandler _context;
    Resource _defaultStylesheet;
    MimeTypes _mimeTypes;
    private final ResourceService _resourceService;
    Resource _stylesheet;
    String[] _welcomes = {"index.html"};

    public ResourceHandler(ResourceService resourceService)
    {
        _resourceService = resourceService;
    }

    public ResourceHandler()
    {
        this(new ResourceService()
        {
            @Override
            protected void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
            }
        });
        _resourceService.setGzipEquivalentFileExtensions(new ArrayList<>(Arrays.asList(".svgz")));
    }

    @Override
    public String getWelcomeFile(String pathInContext)
    {
        if (_welcomes == null)
            return null;

        for (int i = 0; i < _welcomes.length; i++)
        {
            String welcomeInContext = URIUtil.addPaths(pathInContext, _welcomes[i]);
            Resource welcome = getResource(welcomeInContext);
            if (welcome != null && welcome.exists())
                return welcomeInContext;
        }
        // not found
        return null;
    }

    @Override
    public void doStart() throws Exception
    {
        Context scontext = ContextHandler.getCurrentContext();
        _context = (scontext == null ? null : scontext.getContextHandler());
        if (_mimeTypes == null)
            _mimeTypes = _context == null ? new MimeTypes() : _context.getMimeTypes();

        _resourceService.setContentFactory(new ResourceContentFactory(this, _mimeTypes, _resourceService.getPrecompressedFormats()));
        _resourceService.setWelcomeFactory(this);

        super.doStart();
    }

    /**
     * @return Returns the resourceBase.
     */
    public Resource getBaseResource()
    {
        if (_baseResource == null)
            return null;
        return _baseResource;
    }

    /**
     * @return the cacheControl header to set on all static content.
     */
    public String getCacheControl()
    {
        return _resourceService.getCacheControl().getValue();
    }

    /**
     * @return file extensions that signify that a file is gzip compressed. Eg ".svgz"
     */
    public List<String> getGzipEquivalentFileExtensions()
    {
        return _resourceService.getGzipEquivalentFileExtensions();
    }

    public MimeTypes getMimeTypes()
    {
        return _mimeTypes;
    }

    /**
     * Get the minimum content length for async handling.
     *
     * @return The minimum size in bytes of the content before asynchronous handling is used, or -1 for no async handling or 0 (default) for using
     * {@link HttpServletResponse#getBufferSize()} as the minimum length.
     */
    @Deprecated
    public int getMinAsyncContentLength()
    {
        return -1;
    }

    /**
     * Get minimum memory mapped file content length.
     *
     * @return the minimum size in bytes of a file resource that will be served using a memory mapped buffer, or -1 (default) for no memory mapped buffers.
     */
    @Deprecated
    public int getMinMemoryMappedContentLength()
    {
        return -1;
    }

    @Override
    public Resource getResource(String path)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} getResource({})", _context == null ? _baseResource : _context, _baseResource, path);

        if (path == null || !path.startsWith("/"))
            return null;

        try
        {
            Resource r = null;

            if (_baseResource != null)
            {
                path = URIUtil.canonicalPath(path);
                r = _baseResource.addPath(path);

                if (r != null && r.isAlias() && (_context == null || !_context.checkAlias(path, r)))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("resource={} alias={}", r, r.getAlias());
                    return null;
                }
            }
            else if (_context != null)
                r = _context.getResource(path);

            if ((r == null || !r.exists()) && path.endsWith("/jetty-dir.css"))
                r = getStylesheet();

            return r;
        }
        catch (Exception e)
        {
            LOG.debug(e);
        }

        return null;
    }

    /**
     * @return Returns the base resource as a string.
     */
    public String getResourceBase()
    {
        if (_baseResource == null)
            return null;
        return _baseResource.toString();
    }

    /**
     * @return Returns the stylesheet as a Resource.
     */
    public Resource getStylesheet()
    {
        if (_stylesheet != null)
        {
            return _stylesheet;
        }
        else
        {
            if (_defaultStylesheet == null)
            {
                _defaultStylesheet = Resource.newResource(this.getClass().getResource("/jetty-dir.css"));
            }
            return _defaultStylesheet;
        }
    }

    public String[] getWelcomeFiles()
    {
        return _welcomes;
    }

    /*
     * @see org.eclipse.jetty.server.Handler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, int)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (baseRequest.isHandled())
            return;

        if (!HttpMethod.GET.is(request.getMethod()) && !HttpMethod.HEAD.is(request.getMethod()))
        {
            // try another handler
            super.handle(target, baseRequest, request, response);
            return;
        }

        if (_resourceService.doGet(request, response))
            baseRequest.setHandled(true);
        else
            // no resource - try other handlers
            super.handle(target, baseRequest, request, response);
    }

    /**
     * @return If true, range requests and responses are supported
     */
    public boolean isAcceptRanges()
    {
        return _resourceService.isAcceptRanges();
    }

    /**
     * @return If true, directory listings are returned if no welcome file is found. Else 403 Forbidden.
     */
    public boolean isDirAllowed()
    {
        return _resourceService.isDirAllowed();
    }

    /**
     * Get the directory option.
     *
     * @return true if directories are listed.
     */
    public boolean isDirectoriesListed()
    {
        return _resourceService.isDirAllowed();
    }

    /**
     * @return True if ETag processing is done
     */
    public boolean isEtags()
    {
        return _resourceService.isEtags();
    }

    /**
     * @return If set to true, then static content will be served as gzip content encoded if a matching resource is found ending with ".gz"
     */
    @Deprecated
    public boolean isGzip()
    {
        for (CompressedContentFormat formats : _resourceService.getPrecompressedFormats())
        {
            if (CompressedContentFormat.GZIP._encoding.equals(formats._encoding))
                return true;
        }
        return false;
    }

    /**
     * @return Precompressed resources formats that can be used to serve compressed variant of resources.
     */
    public CompressedContentFormat[] getPrecompressedFormats()
    {
        return _resourceService.getPrecompressedFormats();
    }

    /**
     * @return true, only the path info will be applied to the resourceBase
     */
    public boolean isPathInfoOnly()
    {
        return _resourceService.isPathInfoOnly();
    }

    /**
     * @return If true, welcome files are redirected rather than forwarded to.
     */
    public boolean isRedirectWelcome()
    {
        return _resourceService.isRedirectWelcome();
    }

    /**
     * @param acceptRanges If true, range requests and responses are supported
     */
    public void setAcceptRanges(boolean acceptRanges)
    {
        _resourceService.setAcceptRanges(acceptRanges);
    }

    /**
     * @param base The resourceBase to server content from. If null the
     * context resource base is used.
     */
    public void setBaseResource(Resource base)
    {
        _baseResource = base;
    }

    /**
     * @param cacheControl the cacheControl header to set on all static content.
     */
    public void setCacheControl(String cacheControl)
    {
        _resourceService.setCacheControl(new PreEncodedHttpField(HttpHeader.CACHE_CONTROL, cacheControl));
    }

    /**
     * @param dirAllowed If true, directory listings are returned if no welcome file is found. Else 403 Forbidden.
     */
    public void setDirAllowed(boolean dirAllowed)
    {
        _resourceService.setDirAllowed(dirAllowed);
    }

    /**
     * Set the directory.
     *
     * @param directory true if directories are listed.
     */
    public void setDirectoriesListed(boolean directory)
    {
        _resourceService.setDirAllowed(directory);
    }

    /**
     * @param etags True if ETag processing is done
     */
    public void setEtags(boolean etags)
    {
        _resourceService.setEtags(etags);
    }

    /**
     * @param gzip If set to true, then static content will be served as gzip content encoded if a matching resource is found ending with ".gz"
     */
    @Deprecated
    public void setGzip(boolean gzip)
    {
        setPrecompressedFormats(gzip ? new CompressedContentFormat[]{
            CompressedContentFormat.GZIP
        } : new CompressedContentFormat[0]);
    }

    /**
     * @param gzipEquivalentFileExtensions file extensions that signify that a file is gzip compressed. Eg ".svgz"
     */
    public void setGzipEquivalentFileExtensions(List<String> gzipEquivalentFileExtensions)
    {
        _resourceService.setGzipEquivalentFileExtensions(gzipEquivalentFileExtensions);
    }

    /**
     * @param precompressedFormats The list of precompresed formats to serve in encoded format if matching resource found.
     * For example serve gzip encoded file if ".gz" suffixed resource is found.
     */
    public void setPrecompressedFormats(CompressedContentFormat[] precompressedFormats)
    {
        _resourceService.setPrecompressedFormats(precompressedFormats);
    }

    public void setMimeTypes(MimeTypes mimeTypes)
    {
        _mimeTypes = mimeTypes;
    }

    /**
     * Set the minimum content length for async handling.
     *
     * @param minAsyncContentLength The minimum size in bytes of the content before asynchronous handling is used, or -1 for no async handling or 0 for using
     * {@link HttpServletResponse#getBufferSize()} as the minimum length.
     */
    @Deprecated
    public void setMinAsyncContentLength(int minAsyncContentLength)
    {
    }

    /**
     * Set minimum memory mapped file content length.
     *
     * @param minMemoryMappedFileSize the minimum size in bytes of a file resource that will be served using a memory mapped buffer, or -1 for no memory mapped buffers.
     */
    @Deprecated
    public void setMinMemoryMappedContentLength(int minMemoryMappedFileSize)
    {
    }

    /**
     * @param pathInfoOnly true, only the path info will be applied to the resourceBase
     */
    public void setPathInfoOnly(boolean pathInfoOnly)
    {
        _resourceService.setPathInfoOnly(pathInfoOnly);
    }

    /**
     * @param redirectWelcome If true, welcome files are redirected rather than forwarded to.
     * redirection is always used if the ResourceHandler is not scoped by
     * a ContextHandler
     */
    public void setRedirectWelcome(boolean redirectWelcome)
    {
        _resourceService.setRedirectWelcome(redirectWelcome);
    }

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

    /**
     * @param stylesheet The location of the stylesheet to be used as a String.
     */
    public void setStylesheet(String stylesheet)
    {
        try
        {
            _stylesheet = Resource.newResource(stylesheet);
            if (!_stylesheet.exists())
            {
                LOG.warn("unable to find custom stylesheet: " + stylesheet);
                _stylesheet = null;
            }
        }
        catch (Exception e)
        {
            LOG.warn(e.toString());
            LOG.debug(e);
            throw new IllegalArgumentException(stylesheet);
        }
    }

    public void setWelcomeFiles(String[] welcomeFiles)
    {
        _welcomes = welcomeFiles;
    }
}
