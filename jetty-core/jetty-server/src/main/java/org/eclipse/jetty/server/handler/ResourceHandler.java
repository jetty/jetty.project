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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.util.List;

import org.eclipse.jetty.http.CachingContentFactory;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ResourceContentFactory;
import org.eclipse.jetty.server.ResourceService;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;

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
    private final ResourceService _resourceService;

    private Resource _resourceBase;
    private MimeTypes _mimeTypes;
    private List<String> _welcomes = List.of("index.html");

    public ResourceHandler()
    {
        _resourceService = new ResourceService();
    }

    @Override
    public void doStart() throws Exception
    {
        if (_resourceBase == null)
        {
            Context context = ContextHandler.getCurrentContext();
            if (context != null)
                _resourceBase = context.getBaseResource();
        }

// TODO
//            _mimeTypes = _context == null ? new MimeTypes() : _context.getMimeTypes();
        if (_mimeTypes == null)
            _mimeTypes = new MimeTypes();

        setupContentFactory();

        super.doStart();
    }

    private void setupContentFactory()
    {
        HttpContent.ContentFactory contentFactory = new CachingContentFactory(new ResourceContentFactory(_resourceBase, _mimeTypes, _resourceService.getPrecompressedFormats()));
        _resourceService.setContentFactory(contentFactory);
        _resourceService.setWelcomeFactory(pathInContext ->
        {
            if (_welcomes == null)
                return null;

            for (String welcome : _welcomes)
            {
                // TODO GW: This logic needs to be extensible so that a welcome file may be a servlet (yeah I know it shouldn't
                //          be called a welcome file then.   So for example if /foo/index.jsp is the welcome file, we can't
                //          serve it's contents - rather we have to let the servlet layer to either a redirect or a RequestDispatcher to it.
                //          Worse yet, if there was a servlet mapped to /foo/index.html, then we need to be able to dispatch to it
                //          EVEN IF the file does not exist.
                String welcomeInContext = URIUtil.addPaths(pathInContext, welcome);
                Resource welcomePath = _resourceBase.resolve(pathInContext).resolve(welcome);
                if (welcomePath != null && welcomePath.exists())
                    return welcomeInContext;
            }
            // not found
            return null;
        });
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        if (!HttpMethod.GET.is(request.getMethod()) && !HttpMethod.HEAD.is(request.getMethod()))
        {
            // try another handler
            return super.handle(request);
        }

        HttpContent content = _resourceService.getContent(request.getPathInContext(), request.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize());
        if (content == null)
        {
            // no content - try other handlers
            return super.handle(request);
        }
        else
        {
            // TODO is it possible to get rid of the lambda allocation?
            // TODO GW: perhaps HttpContent can extend Request.Processor?
            return (rq, rs, cb) -> _resourceService.doGet(new ResourceHandlerGenericRequest(rq), new ResourceHandlerGenericResponse(rs), cb, content);
        }
    }

    // for testing only
    HttpContent.ContentFactory getContentFactory()
    {
        return _resourceService.getContentFactory();
    }

    /**
     * @return Returns the resourceBase.
     */
    public Resource getResourceBase()
    {
        return _resourceBase;
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
        _resourceBase = base;
        setupContentFactory();
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
    public void setPrecompressedFormats(CompressedContentFormat[] precompressedFormats)
    {
        _resourceService.setPrecompressedFormats(precompressedFormats);
        setupContentFactory();
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
        setupContentFactory();
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
     * @param stylesheet The location of the stylesheet to be used as a String.
     */
    // TODO accept a Resource instead of a String?
    public void setStylesheet(String stylesheet)
    {
        _resourceService.setStylesheet(stylesheet);
    }

    public void setWelcomeFiles(List<String> welcomeFiles)
    {
        _welcomes = welcomeFiles;
    }

    private static class ResourceHandlerGenericRequest implements ResourceService.GenericRequest
    {
        private final Request request;

        ResourceHandlerGenericRequest(Request request)
        {
            this.request = request;
        }

        @Override
        public HttpFields getHeaders()
        {
            return request.getHeaders();
        }

        @Override
        public HttpURI getHttpURI()
        {
            return request.getHttpURI();
        }

        @Override
        public String getPathInContext()
        {
            return request.getPathInContext();
        }

        @Override
        public String getContextPath()
        {
            return request.getContext().getContextPath();
        }
    }

    private static class ResourceHandlerGenericResponse implements ResourceService.GenericResponse
    {
        private final Response response;

        ResourceHandlerGenericResponse(Response response)
        {
            this.response = response;
        }

        @Override
        public HttpFields.Mutable getHeaders()
        {
            return response.getHeaders();
        }

        @Override
        public boolean isCommitted()
        {
            return response.isCommitted();
        }

        @Override
        public int getOutputBufferSize()
        {
            return response.getRequest().getConnectionMetaData().getHttpConfiguration().getOutputBufferSize();
        }

        @Override
        public boolean isUseOutputDirectByteBuffers()
        {
            return response.getRequest().getConnectionMetaData().getHttpConfiguration().isUseOutputDirectByteBuffers();
        }

        @Override
        public void sendRedirect(Callback callback, String uri)
        {
            Response.sendRedirect(response.getRequest(), response, callback, uri);
        }

        @Override
        public void writeError(Callback callback, int status)
        {
            Response.writeError(response.getRequest(), response, callback, status);
        }

        @Override
        public void write(HttpContent content, Callback callback)
        {
            try
            {
                ByteBuffer buffer = content.getBuffer();
                if (buffer != null)
                    writeLast(buffer, callback);
                else
                    new ContentWriterIteratingCallback(content, response, callback).iterate();
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }

        @Override
        public void writeLast(ByteBuffer byteBuffer, Callback callback)
        {
            response.write(true, byteBuffer, callback);
        }
    }

    private static class ContentWriterIteratingCallback extends IteratingCallback
    {
        private final ReadableByteChannel source;
        private final Content.Sink sink;
        private final Callback callback;
        private final ByteBuffer byteBuffer;

        public ContentWriterIteratingCallback(HttpContent content, Response target, Callback callback) throws IOException
        {
            // TODO: is it possible to do zero-copy transfer?
//            WritableByteChannel c = Response.asWritableByteChannel(target);
//            FileChannel fileChannel = (FileChannel) source;
//            fileChannel.transferTo(0, contentLength, c);

            this.source = Files.newByteChannel(content.getResource().getPath());
            this.sink = target;
            this.callback = callback;
            int outputBufferSize = target.getRequest().getConnectionMetaData().getHttpConfiguration().getOutputBufferSize();
            boolean useOutputDirectByteBuffers = target.getRequest().getConnectionMetaData().getHttpConfiguration().isUseOutputDirectByteBuffers();
            this.byteBuffer = useOutputDirectByteBuffers ? ByteBuffer.allocateDirect(outputBufferSize) : ByteBuffer.allocate(outputBufferSize); // TODO use pool
        }

        @Override
        protected Action process() throws Throwable
        {
            if (!source.isOpen())
                return Action.SUCCEEDED;
            byteBuffer.clear();
            int read = source.read(byteBuffer);
            if (read == -1)
            {
                IO.close(source);
                sink.write(true, BufferUtil.EMPTY_BUFFER, this);
                return Action.SCHEDULED;
            }
            byteBuffer.flip();
            sink.write(false, byteBuffer, this);
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            callback.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            callback.failed(x);
        }
    }
}
