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
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.compression.server.internal.CompressionResponse;
import org.eclipse.jetty.compression.server.internal.DecompressionRequest;
import org.eclipse.jetty.http.EtagUtils;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.pathmap.MappedResource;
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

/**
 * CompressionHandler to provide compression of response bodies and decompression of request bodies.
 *
 * <p>
 *     Supports any arbitrary content-encoding via {@link org.eclipse.jetty.compression.Compression} implementations
 *     such as {@code gzip}, {@code zstd}, and {@code brotli}.
 *     By default, there are no {@link Compression} implementations that will be automatically added.
 *     It is up to the user to call {@link #addCompression(Compression)} to add which implementations that they want to use.
 * </p>
 *
 * <p>
 *     Configuration is handled by associating a {@link CompressionConfig} against a {@link PathSpec}.
 *     By default, if no configuration is specified, then a default {@link CompressionConfig} is
 *     assigned to the {@code /} {@link PathSpec}.
 * </p>
 *
 * <p>
 *     Experimental CompressionHandler, subject to change while the implementation is being settled.
 *     Please provide feedback at the <a href="https://github.com/jetty/jetty.project/issues">Jetty Issue tracker</a>
 *     to influence the direction / development of these experimental features.
 * </p>
 */
public class CompressionHandler extends Handler.Wrapper
{
    public static final String HANDLER_ETAGS = CompressionHandler.class.getPackageName() + ".ETag";

    private static final Logger LOG = LoggerFactory.getLogger(CompressionHandler.class);
    private final Map<String, Compression> supportedEncodings = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final PathMappings<CompressionConfig> pathConfigs = new PathMappings<>();

    public CompressionHandler()
    {
        addBean(pathConfigs);
    }

    public void addCompression(Compression compression)
    {
        if (isRunning())
            throw new IllegalStateException("Unable to add Compression on running DynamicCompressionHandler");

        supportedEncodings.put(compression.getEncodingName(), compression);
        compression.setContainer(this);
        addBean(compression);
    }

    /**
     * Obtain a CompressionConfig for the specified PathSpec.
     *
     * <p>
     *     This is different from {@link #getConfiguration(PathSpec)}, which will return null
     *     if the mapping to the provided {@link PathSpec} does not exist.
     * </p>
     *
     * @param pathSpec the {@link PathSpec} to look for.
     * @return the {@link CompressionConfig} associated with the {@link PathSpec}, mapping is created if it didn't previously exist.
     */
    public CompressionConfig ensureConfiguration(PathSpec pathSpec)
    {
        return pathConfigs.computeIfAbsent(pathSpec, (spec) -> CompressionConfig.builder().build());
    }

    /**
     * Obtain a CompressionConfig for the specified PathSpec.
     *
     * <p>
     *     This is different from {@link #getConfiguration(PathSpec)}, which will return null
     *     if the mapping to the provided {@link PathSpec} does not exist.
     * </p>
     *
     * @param pathSpecString the string representation of the path spec.
     * @return the {@link CompressionConfig} associated with the {@link PathSpec}, mapping is created if it didn't previously exist.
     * @see #ensureConfiguration(PathSpec)
     * @see PathSpec#from(String)
     */
    public CompressionConfig ensureConfiguration(String pathSpecString)
    {
        PathSpec pathSpec = PathSpec.from(pathSpecString);
        return ensureConfiguration(pathSpec);
    }

    /**
     * Get the {@link CompressionConfig} associated with this {@link PathSpec}
     *
     * @param pathSpec the PathSpec to look for
     * @return the {@link CompressionConfig} mapped to the {@link PathSpec}, null if nothing is mapped to the {@link PathSpec}
     */
    public CompressionConfig getConfiguration(PathSpec pathSpec)
    {
        return pathConfigs.get(pathSpec);
    }

    /**
     * Get the {@link CompressionConfig} associated with this {@link PathSpec}
     *
     * @param pathSpecString the string representation of the path spec.
     * @return the {@link CompressionConfig} mapped to the {@link PathSpec}, null if nothing is mapped to the {@link PathSpec}
     */
    public CompressionConfig getConfiguration(String pathSpecString)
    {
        PathSpec pathSpec = PathSpec.from(pathSpecString);
        return getConfiguration(pathSpec);
    }

    /**
     * Establish a {@link CompressionConfig} associated with the specific {@link PathSpec}
     * @param pathSpec the path spec to use as the key
     * @param config the config to use as the value
     * @return the old {@link CompressionConfig} if one was previously set.
     * @see PathMappings#put(PathSpec, Object)
     */
    public CompressionConfig putConfiguration(PathSpec pathSpec, CompressionConfig config)
    {
        return pathConfigs.put(pathSpec, config);
    }

    /**
     * Establish a {@link CompressionConfig} associated with the specific {@link PathSpec}
     * @param pathSpecString the string representation of the path spec.
     * @param config the config to use as the value
     * @return the old {@link CompressionConfig} if one was previously set.
     * @see PathMappings#put(PathSpec, Object)
     */
    public CompressionConfig putConfiguration(String pathSpecString, CompressionConfig config)
    {
        PathSpec pathSpec = PathSpec.from(pathSpecString);
        return putConfiguration(pathSpec, config);
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
        // Tracks the `If-Match` or `If-None-Match` request headers contains an etag separator.
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
                            if ("*".equals(value) || supportedEncodings.containsKey(lvalue))
                            {
                                if (requestAcceptEncoding == null)
                                    requestAcceptEncoding = new ArrayList<>();
                                requestAcceptEncoding.add(lvalue);
                            }
                        }
                    }
                }
                case IF_MATCH, IF_NONE_MATCH -> etagMatches |= field.getValue().contains(EtagUtils.ETAG_SEPARATOR);
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
            decompressionRequest = newDecompressionRequest(request, decompressEncoding);
        }

        // Wrap the response and callback IFF we can be deflated and will try to deflate
        if (compressEncoding != null)
        {
            if (config.getVary() != null)
            {
                // The response may vary based on the presence or lack of Accept-Encoding.
                response.getHeaders().ensureField(config.getVary());
            }

            Response compression = newCompressionResponse(request, response, callback, compressEncoding, config);
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

        // ensure that the preferred encoder order is sane for the configuration.
        for (MappedResource<CompressionConfig> pathConfig : pathConfigs)
        {
            List<String> preferredEncoders = pathConfig.getResource().getCompressPreferredEncoderOrder();
            if (preferredEncoders.isEmpty())
                continue;
            ListIterator<String> preferredIter = preferredEncoders.listIterator();
            while (preferredIter.hasNext())
            {
                String listedEncoder = preferredIter.next();
                if (!supportedEncodings.containsKey(listedEncoder))
                {
                    LOG.warn("Unable to find compression encoder {} from configuration for pathspec {} in registered compression encoders [{}]",
                        listedEncoder, pathConfig.getPathSpec(),
                        String.join(", ", supportedEncodings.keySet()));
                    preferredIter.remove(); // remove bad encoding
                }
            }
        }

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        supportedEncodings.values().forEach(this::removeBean);
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

    private Response newCompressionResponse(Request request, Response response, Callback callback, String compressEncoding, CompressionConfig config)
    {
        Compression compression = getCompression(compressEncoding);
        if (compression == null)
            return response;

        return new CompressionResponse(compression, request, response, callback, config);
    }

    private Request newDecompressionRequest(Request request, String decompressEncoding)
    {
        Compression compression = getCompression(decompressEncoding);
        if (compression == null)
            return request;

        return new DecompressionRequest(compression, request);
    }
}
