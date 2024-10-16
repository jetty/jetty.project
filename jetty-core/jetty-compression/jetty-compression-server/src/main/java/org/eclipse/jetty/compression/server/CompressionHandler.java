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

package org.eclipse.jetty.compression.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.http.EtagUtils;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.pathmap.MatchedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompressionHandler extends Handler.Wrapper
{
    public static final String HANDLER_ETAGS = CompressionHandler.class.getPackageName() + ".ETag";

    private static final Logger LOG = LoggerFactory.getLogger(CompressionHandler.class);
    // TODO: make into a case-insensitive map
    private final Map<String, Compression> supportedEncodings = new HashMap<>();
    private final PathMappings<CompressionConfig> pathConfigs = new PathMappings<CompressionConfig>();

    public void addCompression(Compression compression)
    {
        if (isRunning())
            throw new IllegalStateException("Unable to add Compression on running DynamicCompressionHandler");

        supportedEncodings.put(compression.getEncodingName(), compression);
        compression.setContainer(this);
        addBean(compression);
    }

    public CompressionConfig getConfiguration(PathSpec pathSpec)
    {
        // TODO: should we return null here?
        //   It would make using this for additive changes to an existing configuration tricky (from an XML)
        return pathConfigs.computeIfAbsent(pathSpec, (spec) ->
        {
            return CompressionConfig.builder().build();
        });
    }

    public CompressionConfig getConfiguration(String pathSpecString)
    {
        PathSpec pathSpec = PathSpec.from(pathSpecString);
        return getConfiguration(pathSpec);
    }

    public void putConfiguration(PathSpec pathSpec, CompressionConfig config)
    {
        pathConfigs.put(pathSpec, config);
    }

    public void putConfiguration(String pathSpecString, CompressionConfig config)
    {
        PathSpec pathSpec = PathSpec.from(pathSpecString);
        putConfiguration(pathSpec, config);
    }

    @Override
    public boolean handle(final Request request, final Response response, final Callback callback) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} handle {}", this, request);

        Handler next = getHandler();
        if (next == null)
            return false;

        // TODO: are both request decompression and response compression covered?
        // Are we already being compressed?
        if (Request.as(request, DecompressionRequest.class) != null)
            return next.handle(request, response, callback);

        String pathInContext = Request.getPathInContext(request);

        MatchedResource<CompressionConfig> matchedConfig = this.pathConfigs.getMatched(pathInContext);
        if (matchedConfig == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Skipping Compression: Path {} has no matching compression config", pathInContext);
            // No configuration, skip
            return next.handle(request, response, callback);
        }

        CompressionConfig config = matchedConfig.getResource();

        // The `Content-Encoding` request header indicating that the request body content compression technique.
        String requestContentEncoding = null;
        // The `Accept-Encoding` request header indicating the supported list of compression encoding techniques.
        List<String> requestAcceptEncoding = null;
        // Tracks the `If-Match` or `If-None-Match` request headers contains a etag separator.
        boolean etagMatches = false;

        HttpFields fields = request.getHeaders();
        for (ListIterator<HttpField> i = fields.listIterator(fields.size()); i.hasPrevious(); )
        {
            HttpField field = i.previous();
            HttpHeader header = field.getHeader();
            if (header == null)
                continue;
            switch (header)
            {
                case CONTENT_ENCODING ->
                {
                    String contentEncoding = field.getValue();
                    if (supportedEncodings.containsKey(contentEncoding))
                        requestContentEncoding = contentEncoding;
                }
                case ACCEPT_ENCODING ->
                {
                    // Get ordered list of supported encodings
                    List<String> values = field.getValueList();
                    if (values != null)
                    {
                        for (String value : values)
                        {
                            String lvalue = StringUtil.asciiToLowerCase(value);
                            // only track encodings that are supported by this handler
                            if (supportedEncodings.containsKey(lvalue))
                            {
                                if (requestAcceptEncoding == null)
                                    requestAcceptEncoding = new ArrayList<>();
                                requestAcceptEncoding.add(lvalue);
                            }
                        }
                    }
                }
                case IF_MATCH, IF_NONE_MATCH ->
                {
                    etagMatches |= field.getValue().contains(EtagUtils.ETAG_SEPARATOR);
                }
            }
        }

        String decompressEncoding = config.getDecompressionEncoding(requestContentEncoding, request, pathInContext);
        String compressEncoding = config.getCompressionEncoding(requestAcceptEncoding, request, pathInContext);

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Request[{}] Content-Encoding={}, Accept-Encoding={}, decompressEncoding={}, compressEncoding={}",
                request, requestContentEncoding, requestAcceptEncoding, decompressEncoding, compressEncoding);
        }

        // Can we skip looking at the request and wrapping request or response?
        if (decompressEncoding == null && compressEncoding == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Skipping Compression and Decompression: no request encoding matches");
            // No need for a Vary header, as we will never deflate
            return next.handle(request, response, callback);
        }

        Request decompressionRequest = request;
        Response compressionResponse = response;
        Callback compressionCallback = callback;

        // We need to wrap the request IFF we are inflating or have seen etags with compression separators
        if (decompressEncoding != null || etagMatches)
        {
            decompressionRequest = newDecompressionRequest(request, decompressEncoding, config);
        }

        // Wrap the response and callback IFF we can be deflated and will try to deflate
        if (compressEncoding != null)
        {
            if (config.getVary() != null)
            {
                // The response may vary based on the presence or lack of Accept-Encoding.
                response.getHeaders().ensureField(config.getVary());
            }

            Response compression = newCompressionResponse(this, request, response, callback, compressEncoding, config);
            compressionResponse = compression;
            if (compression instanceof Callback dynamicCallback)
                compressionCallback = dynamicCallback;
        }

        // Call handle() with the possibly wrapped request, response and callback
        if (next.handle(decompressionRequest, compressionResponse, compressionCallback))
            return true;

        // If the request was not accepted, destroy any compressRequest wrapper
        if (request instanceof DecompressionRequest decompressRequest)
        {
            decompressRequest.destroy();
        }
        return false;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,supported=%s}", getClass().getSimpleName(), hashCode(), getState(), String.join(",", supportedEncodings.keySet()));
    }

    @Override
    protected void doStart() throws Exception
    {
        if (pathConfigs.isEmpty())
        {
            // add default configuration if no paths have been configured.
            pathConfigs.put("/",
                CompressionConfig.builder()
                    .from(MimeTypes.DEFAULTS)
                    .build());
        }

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();

        supportedEncodings.values().forEach(
            (codec) ->
            {
                removeBean(codec);
            }
        );
    }

    private Compression getCompression(String encoding)
    {
        Compression compression = supportedEncodings.get(encoding);
        if (compression == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("No Compression found for encoding type {}", encoding);
            return null;
        }

        return compression;
    }

    private Response newCompressionResponse(CompressionHandler compressionHandler, Request request, Response response, Callback callback, String compressEncoding, CompressionConfig config)
    {
        Compression compression = getCompression(compressEncoding);
        if (compression == null)
            return response;

        return new CompressionResponse(compression, request, response, callback, config);
    }

    private Request newDecompressionRequest(Request request, String decompressEncoding, CompressionConfig config)
    {
        Compression compression = getCompression(decompressEncoding);
        if (compression == null)
            return request;

        return new DecompressionRequest(compression, request, config);
    }
}
