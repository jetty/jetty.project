//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.ContextHandler.APIContext;
import org.eclipse.jetty.ee9.nested.ResourceService.WelcomeFactory;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.content.FileMappingHttpContentFactory;
import org.eclipse.jetty.http.content.HttpContent;
import org.eclipse.jetty.http.content.PreCompressedHttpContentFactory;
import org.eclipse.jetty.http.content.ResourceHttpContentFactory;
import org.eclipse.jetty.http.content.ValidatingCachingHttpContentFactory;
import org.eclipse.jetty.http.content.VirtualHttpContentFactory;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resource Handler.
 *
 * This handle will serve static content and handle If-Modified-Since headers. No caching is done. Requests for resources that do not exist are let pass (Eg no
 * 404's).
 */
public class ResourceHandler extends HandlerWrapper implements WelcomeFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(ResourceHandler.class);

    private ByteBufferPool _bufferPool;
    Resource _baseResource;
    ContextHandler _context;
    Resource _defaultStyleSheet;
    MimeTypes _mimeTypes;
    private final ResourceService _resourceService;
    Resource _styleSheet;
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
        _resourceService.setGzipEquivalentFileExtensions(List.of(".svgz"));
    }

    @Override
    public String getWelcomeFile(String pathInContext)
    {
        if (_welcomes == null)
            return null;

        for (String s : _welcomes)
        {
            String welcomeInContext = URIUtil.addPaths(pathInContext, s);
            Resource welcome = _baseResource.resolve(welcomeInContext);
            if (welcome.exists())
                return welcomeInContext;
        }
        // not found
        return null;
    }

    @Override
    public void doStart() throws Exception
    {
        APIContext scontext = ContextHandler.getCurrentContext();
        _context = (scontext == null ? null : scontext.getContextHandler());
        if (_mimeTypes == null)
            _mimeTypes = _context == null ? MimeTypes.DEFAULTS : _context.getMimeTypes();

        _bufferPool = getByteBufferPool(_context);
        if (_resourceService.getHttpContentFactory() == null)
            _resourceService.setHttpContentFactory(newHttpContentFactory());
        _resourceService.setWelcomeFactory(this);

        super.doStart();
    }

    private static ByteBufferPool getByteBufferPool(ContextHandler contextHandler)
    {
        if (contextHandler == null)
            return ByteBufferPool.NON_POOLING;
        Server server = contextHandler.getServer();
        if (server == null)
            return ByteBufferPool.NON_POOLING;
        return server.getByteBufferPool();
    }

    public HttpContent.Factory getHttpContentFactory()
    {
        return _resourceService.getHttpContentFactory();
    }

    protected HttpContent.Factory newHttpContentFactory()
    {
        Resource baseResource = getBaseResource();
        HttpContent.Factory contentFactory = new ResourceHttpContentFactory(baseResource, _mimeTypes);
        contentFactory = new FileMappingHttpContentFactory(contentFactory);
        contentFactory = new VirtualHttpContentFactory(contentFactory, getStyleSheet(), "text/css");
        contentFactory = new PreCompressedHttpContentFactory(contentFactory, _resourceService.getPrecompressedFormats());
        contentFactory = new ValidatingCachingHttpContentFactory(contentFactory, Duration.ofSeconds(1).toMillis(), _bufferPool);
        return contentFactory;
    }

    /**
     * @return Returns the resourceBase.
     */
    public Resource getBaseResource()
    {
        if (_baseResource == null)
            return _context.getBaseResource();
        return _baseResource;
    }

    /**
     * Get the cacheControl header to set on all static content..
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
     * @return Returns the base resource as a string.
     */
    @Deprecated
    public String getResourceBase()
    {
        if (_baseResource == null)
            return null;
        return _baseResource.toString();
    }

    /**
     * @return Returns the stylesheet as a Resource.
     */
    public Resource getStyleSheet()
    {
        if (_styleSheet != null)
        {
            return _styleSheet;
        }
        else
        {
            if (_defaultStyleSheet == null)
            {
                _defaultStyleSheet = getServer().getDefaultStyleSheet();
            }
            return _defaultStyleSheet;
        }
    }

    public String[] getWelcomeFiles()
    {
        return _welcomes;
    }

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
     * @param basePath The resourceBase to server content from. If null the
     * context resource base is used.
     */
    public void setBaseResource(Path basePath)
    {
        setBaseResource(ResourceFactory.root().newResource(basePath));
    }

    /**
     * Set the cacheControl header to set on all static content..
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
     * Set file extensions that signify that a file is gzip compressed. Eg ".svgz".
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
     * Set true, only the path info will be applied to the resourceBase.
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
     * @deprecated use {@link #setBaseResource(Resource)}
     */
    @Deprecated
    public void setResourceBase(String resourceBase)
    {
        setBaseResourceAsString(resourceBase);
    }

    /**
     * @param baseResource The base resource as a string.
     * @deprecated use {@link #setBaseResource(Resource)}
     */
    @Deprecated
    public void setBaseResourceAsString(String baseResource)
    {
        try
        {
            setBaseResource(ResourceFactory.of(this).newResource(baseResource));
        }
        catch (Exception e)
        {
            LOG.warn("Invalid Base Resource reference: {}", baseResource, e);
            throw new IllegalArgumentException(baseResource);
        }
    }

    /**
     * @param styleSheet The location of the style sheet to be used as a String.
     */
    public void setStyleSheet(String styleSheet)
    {
        try
        {
            _styleSheet = ResourceFactory.of(this).newResource(styleSheet);
            if (!_styleSheet.exists())
            {
                LOG.warn("unable to find custom styleSheet: {}", styleSheet);
                _styleSheet = null;
            }
        }
        catch (Exception e)
        {
            LOG.warn("Invalid StyleSheet reference: {}", styleSheet, e);
            throw new IllegalArgumentException(styleSheet);
        }
    }

    public void setWelcomeFiles(String[] welcomeFiles)
    {
        _welcomes = welcomeFiles;
    }
}
