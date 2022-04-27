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

import java.nio.file.Path;
import java.util.List;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ResourceBase;
import org.eclipse.jetty.server.ResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *  - getContent in HttpContent should go
 *  - Default stylesheet (needs Path impl for classpath resources)
 *  - request ranges
 *  - a way to configure caching or not
 */
public class ResourceHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(ResourceHandler.class);

    private final ResourceService _resourceService;

    public ResourceHandler()
    {
        _resourceService = new ResourceService();
    }

    @Override
    public void doStart() throws Exception
    {
        Context context = ContextHandler.getCurrentContext();
// TODO        _context = (context == null ? null : context.getContextHandler());
//        if (_mimeTypes == null)
//            _mimeTypes = _context == null ? new MimeTypes() : _context.getMimeTypes();

        super.doStart();
    }

    @Override
    public Request.Processor handle(Request request) throws Exception
    {
        if (!HttpMethod.GET.is(request.getMethod()) && !HttpMethod.HEAD.is(request.getMethod()))
        {
            // try another handler
            return super.handle(request);
        }

        HttpContent content = _resourceService.getContentFactory().getContent(request.getPathInContext(), request.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize());
        if (content == null)
        {
            // no content - try other handlers
            return super.handle(request);
        }
        else
        {
            // TODO is it possible to get rid of the lambda allocation?
            // TODO GW: perhaps HttpContent can extend Request.Processor?
            return (rq, rs, cb) -> _resourceService.doGet(rq, rs, cb, content);
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
    public ResourceBase getResourceBase()
    {
        return _resourceService.getResourceBase();
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
        return _resourceService.getMimeTypes();
    }

    /**
     * @return Returns the stylesheet as a Resource.
     */
    public Path getStylesheet()
    {
        return _resourceService.getStylesheet();
    }

    public List<String> getWelcomeFiles()
    {
        return _resourceService.getWelcomeFiles();
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
    public void setResourceBase(ResourceBase base)
    {
        _resourceService.setResourceBase(base);
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
        _resourceService.setMimeTypes(mimeTypes);
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
    // TODO accept a Path instead of a String?
    public void setStylesheet(String stylesheet)
    {
        _resourceService.setStylesheet(stylesheet);
    }

    public void setWelcomeFiles(List<String> welcomeFiles)
    {
        _resourceService.setWelcomeFiles(welcomeFiles);
    }

}
