//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import org.eclipse.jetty.http.FileMappingHttpContentFactory;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreCompressedHttpContentFactory;
import org.eclipse.jetty.http.ResourceHttpContentFactory;
import org.eclipse.jetty.http.ValidatingCachingHttpContentFactory;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.NoopByteBufferPool;
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

/**
 * Resource Handler.
 *
 * This handle will serve static content and handle If-Modified-Since headers. No caching is done. Requests for resources that do not exist are let pass (Eg no
 * 404's).
 * TODO there is a lot of URI manipulation, this should be factored out in a utility class.
 *
 * TODO GW: Work out how this logic can be reused by the DefaultServlet... potentially for wrapped output streams
 *
 * Missing:
 *  - current context' mime types
 *  - Default stylesheet (needs Resource impl for classpath resources)
 *  - request ranges
 *  - a way to configure caching or not
 */
public class ResourceHandler extends Handler.Wrapper
{
    private final ResourceService _resourceService = newResourceService();
    private ByteBufferPool _byteBufferPool;
    private Resource _baseResource;
    private MimeTypes _mimeTypes;
    private List<String> _welcomes = List.of("index.html");

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
        ContextHandler.Context context = ContextHandler.getCurrentContext();
        if (_baseResource == null)
        {
            if (context != null)
                _baseResource = context.getBaseResource();
        }

        // TODO: _mimeTypes = _context == null ? new MimeTypes() : _context.getMimeTypes();
        if (_mimeTypes == null)
            _mimeTypes = new MimeTypes();

        _byteBufferPool = getByteBufferPool(context);

        ResourceService resourceService = getResourceService();
        resourceService.setHttpContentFactory(newHttpContentFactory());
        resourceService.setWelcomeFactory(setupWelcomeFactory());
        if (getStylesheet() == null)
            setStylesheet(getServer().getDefaultStyleSheet());

        super.doStart();
    }

    private static ByteBufferPool getByteBufferPool(ContextHandler.Context context)
    {
        if (context == null)
            return new NoopByteBufferPool();
        Server server = context.getContextHandler().getServer();
        if (server == null)
            return new NoopByteBufferPool();
        ByteBufferPool byteBufferPool = server.getBean(ByteBufferPool.class);
        return (byteBufferPool == null) ? new NoopByteBufferPool() : byteBufferPool;
    }

    public HttpContent.Factory getHttpContentFactory()
    {
        return _resourceService.getHttpContentFactory();
    }

    protected HttpContent.Factory newHttpContentFactory()
    {
        HttpContent.Factory contentFactory = new ResourceHttpContentFactory(ResourceFactory.of(_baseResource), _mimeTypes);
        contentFactory = new PreCompressedHttpContentFactory(contentFactory, _resourceService.getPrecompressedFormats());
        contentFactory = new FileMappingHttpContentFactory(contentFactory);
        contentFactory = new ValidatingCachingHttpContentFactory(contentFactory, Duration.ofSeconds(1).toMillis(), _byteBufferPool);
        return contentFactory;
    }

    protected ResourceService.WelcomeFactory setupWelcomeFactory()
    {
        return request ->
        {
            if (_welcomes == null)
                return null;

            for (String welcome : _welcomes)
            {
                String pathInContext = Request.getPathInContext(request);
                String welcomeInContext = URIUtil.addPaths(pathInContext, welcome);
                Resource welcomePath = _baseResource.resolve(pathInContext).resolve(welcome);
                if (Resources.isReadableFile(welcomePath))
                    return welcomeInContext;
            }
            // not found
            return null;
        };
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        if (!HttpMethod.GET.is(request.getMethod()) && !HttpMethod.HEAD.is(request.getMethod()))
        {
            // try another handler
            return super.handle(request);
        }

        HttpContent content = _resourceService.getContent(Request.getPathInContext(request), request);
        if (content == null)
            return super.handle(request); // no content - try other handlers

        return (rq, rs, cb) -> _resourceService.doGet(rq, rs, cb, content);
    }

    /**
     * @return Returns the resourceBase.
     */
    public Resource getBaseResource()
    {
        return _baseResource;
    }

    /**
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
    public Resource getStylesheet()
    {
        return _resourceService.getStylesheet();
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

    public void setWelcomeMode(ResourceService.WelcomeMode welcomeMode)
    {
        _resourceService.setWelcomeMode(welcomeMode);
    }

    /**
     * @param stylesheet The location of the stylesheet to be used as a String.
     */
    public void setStylesheet(Resource stylesheet)
    {
        _resourceService.setStylesheet(stylesheet);
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
        protected boolean rehandleWelcome(Request request, Response response, Callback callback, String welcomeTarget) throws Exception
        {
            HttpURI newHttpURI = HttpURI.build(request.getHttpURI())
                .pathQuery(welcomeTarget);
            Request newRequest = new Request.Wrapper(request)
            {
                @Override
                public HttpURI getHttpURI()
                {
                    return newHttpURI;
                }
            };

            Request.Processor processor = getServer().handle(newRequest);
            if (processor == null)
                return false;

            processor.process(newRequest, response, callback);
            return true;
        }
    }
}
