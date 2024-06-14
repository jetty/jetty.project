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

package org.eclipse.jetty.server.handler;

import java.time.Duration;
import java.util.List;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.content.FileMappingHttpContentFactory;
import org.eclipse.jetty.http.content.HttpContent;
import org.eclipse.jetty.http.content.PreCompressedHttpContentFactory;
import org.eclipse.jetty.http.content.ResourceHttpContentFactory;
import org.eclipse.jetty.http.content.ValidatingCachingHttpContentFactory;
import org.eclipse.jetty.http.content.VirtualHttpContentFactory;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ResourceService;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resource Handler will serve static content and handle If-Modified-Since headers. No caching is done.
 * Requests for resources that do not exist are let pass (Eg no 404's).
 */
public class ResourceHandler extends Handler.Wrapper
{
    // TODO there is a lot of URI manipulation, this should be factored out in a utility class.
    // TODO Missing:
    //    - current context' mime types
    //    - Default stylesheet (needs Resource impl for classpath resources)
    //    - request ranges
    //    - a way to configure caching or not
    private static final Logger LOG = LoggerFactory.getLogger(ResourceHandler.class);

    private final ResourceService _resourceService = newResourceService();
    private ByteBufferPool _byteBufferPool;
    private Resource _baseResource;
    private Resource _styleSheet;
    private MimeTypes _mimeTypes;
    private List<String> _welcomes = List.of("index.html");
    private boolean _useFileMapping = true;

    public ResourceHandler()
    {
        this(null);
    }

    public ResourceHandler(Handler handler)
    {
        super(handler);
    }

    protected ResourceService newResourceService()
    {
        return new HandlerResourceService();
    }

    public ResourceService getResourceService()
    {
        return _resourceService;
    }

    @Override
    public void doStart() throws Exception
    {
        Context context = ContextHandler.getCurrentContext(getServer());
        if (_baseResource == null)
        {
            if (context != null)
                _baseResource = context.getBaseResource();
        }
        else if (_baseResource.isAlias())
        {
            LOG.warn("Base Resource should not be an alias");
        }

        setMimeTypes(context == null ? MimeTypes.DEFAULTS : context.getMimeTypes());

        _byteBufferPool = getByteBufferPool(context);
        ResourceService resourceService = getResourceService();
        resourceService.setHttpContentFactory(newHttpContentFactory());
        resourceService.setWelcomeFactory(setupWelcomeFactory());
        if (getStyleSheet() == null)
            setStyleSheet(getServer().getDefaultStyleSheet());

        super.doStart();
    }

    private ByteBufferPool getByteBufferPool(Context context)
    {
        if (context == null)
            return ByteBufferPool.NON_POOLING;
        Server server = getServer();
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
        HttpContent.Factory contentFactory = new ResourceHttpContentFactory(getBaseResource(), getMimeTypes());
        if (isUseFileMapping())
            contentFactory = new FileMappingHttpContentFactory(contentFactory);
        contentFactory = new VirtualHttpContentFactory(contentFactory, getStyleSheet(), "text/css");
        contentFactory = new PreCompressedHttpContentFactory(contentFactory, getPrecompressedFormats());
        contentFactory = new ValidatingCachingHttpContentFactory(contentFactory, Duration.ofSeconds(1).toMillis(), getByteBufferPool());
        return contentFactory;
    }

    protected ResourceService.WelcomeFactory setupWelcomeFactory()
    {
        return (content, request) ->
        {
            if (_welcomes == null)
                return null;

            for (String welcome : _welcomes)
            {
                String pathInContext = Request.getPathInContext(request);
                String welcomeInContext = URIUtil.addPaths(pathInContext, welcome);
                Resource welcomePath = content.getResource().resolve(welcome);
                if (Resources.isReadableFile(welcomePath))
                    return welcomeInContext;
            }
            // not found
            return null;
        };
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (!HttpMethod.GET.is(request.getMethod()) && !HttpMethod.HEAD.is(request.getMethod()))
        {
            // try another handler
            return super.handle(request, response, callback);
        }

        HttpContent content = _resourceService.getContent(Request.getPathInContext(request), request);
        if (content == null)
        {
            return super.handle(request, response, callback); // no content - try other handlers
        }

        _resourceService.doGet(request, response, callback, content);
        return true;
    }

    /**
     * @return Returns the resourceBase.
     */
    public Resource getBaseResource()
    {
        return _baseResource;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return _byteBufferPool;
    }

    /**
     * Get the cacheControl header to set on all static content..
     * @return the cacheControl header to set on all static content.
     */
    public String getCacheControl()
    {
        return _resourceService.getCacheControl();
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
     * @return Returns the stylesheet as a Resource.
     */
    public Resource getStyleSheet()
    {
        return (_styleSheet == null) ? getServer().getDefaultStyleSheet() : _styleSheet;
    }

    public List<String> getWelcomeFiles()
    {
        return _welcomes;
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
     * @return True if ETag processing is done
     */
    public boolean isEtags()
    {
        return _resourceService.isEtags();
    }

    public boolean isUseFileMapping()
    {
        return _useFileMapping;
    }

    /**
     * @return Precompressed resources formats that can be used to serve compressed variant of resources.
     */
    public List<CompressedContentFormat> getPrecompressedFormats()
    {
        return _resourceService.getPrecompressedFormats();
    }

    public ResourceService.WelcomeMode getWelcomeMode()
    {
        return _resourceService.getWelcomeMode();
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
        if (isStarted())
            throw new IllegalStateException(getState());
        _baseResource = base;
    }

    /**
     * @param base The resourceBase to server content from. If null the
     * context resource base is used.  If non-null the {@link Resource} is created
     * from {@link ResourceFactory#of(org.eclipse.jetty.util.component.Container)} for
     * this context.
     */
    public void setBaseResourceAsString(String base)
    {
        setBaseResource(base == null ? null : ResourceFactory.of(this).newResource(base));
    }

    /**
     * Set the cacheControl header to set on all static content..
     * @param cacheControl the cacheControl header to set on all static content.
     */
    public void setCacheControl(String cacheControl)
    {
        _resourceService.setCacheControl(cacheControl);
    }

    /**
     * @param dirAllowed If true, directory listings are returned if no welcome file is found. Else 403 Forbidden.
     */
    public void setDirAllowed(boolean dirAllowed)
    {
        _resourceService.setDirAllowed(dirAllowed);
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
    public void setPrecompressedFormats(CompressedContentFormat... precompressedFormats)
    {
        setPrecompressedFormats(List.of(precompressedFormats));
    }

    /**
     * @param precompressedFormats The list of precompresed formats to serve in encoded format if matching resource found.
     * For example serve gzip encoded file if ".gz" suffixed resource is found.
     */
    public void setPrecompressedFormats(List<CompressedContentFormat> precompressedFormats)
    {
        _resourceService.setPrecompressedFormats(precompressedFormats);
    }

    public void setEncodingCacheSize(int encodingCacheSize)
    {
        _resourceService.setEncodingCacheSize(encodingCacheSize);
    }

    public int getEncodingCacheSize()
    {
        return _resourceService.getEncodingCacheSize();
    }

    public void setMimeTypes(MimeTypes mimeTypes)
    {
        _mimeTypes = mimeTypes;
    }

    public void setUseFileMapping(boolean useFileMapping)
    {
        if (isRunning())
            throw new IllegalStateException("Unable to set useFileMapping on started " + this);
        _useFileMapping = useFileMapping;
    }

    public void setWelcomeMode(ResourceService.WelcomeMode welcomeMode)
    {
        _resourceService.setWelcomeMode(welcomeMode);
    }

    /**
     * @param styleSheet The location of the style sheet to be used as a String.
     */
    public void setStyleSheet(Resource styleSheet)
    {
        _styleSheet = styleSheet;
    }

    public void setWelcomeFiles(String... welcomeFiles)
    {
        setWelcomeFiles(List.of(welcomeFiles));
    }

    public void setWelcomeFiles(List<String> welcomeFiles)
    {
        _welcomes = welcomeFiles;
    }

    /**
     * Utility class to create a ContextHandler containing a ResourceHandler.
     */
    public static class ResourceContext extends ContextHandler
    {
        public ResourceContext()
        {
            setHandler(new ResourceHandler());
        }
    }

    private class HandlerResourceService extends ResourceService
    {
        @Override
        protected void rehandleWelcome(Request request, Response response, Callback callback, String welcomeTarget) throws Exception
        {
            HttpURI newHttpURI = HttpURI.build(request.getHttpURI()).pathQuery(welcomeTarget);
            Request newRequest = Request.serveAs(request, newHttpURI);

            if (getServer().handle(newRequest, response, callback))
                return;

            super.rehandleWelcome(request, response, callback, welcomeTarget);
        }
    }
}
