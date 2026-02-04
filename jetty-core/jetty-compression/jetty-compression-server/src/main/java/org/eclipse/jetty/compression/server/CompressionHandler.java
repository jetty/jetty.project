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

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.compression.server.internal.CompressionResponse;
import org.eclipse.jetty.compression.server.internal.DecompressionRequest;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.QuotedQualityCSV;
import org.eclipse.jetty.http.pathmap.MatchedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>CompressionHandler to provide compression of response bodies and decompression of request bodies.</p>
 * <p>Supports any arbitrary {@code Content-Encoding} via {@link org.eclipse.jetty.compression.Compression}
 * implementations such as {@code gzip}, {@code zstd}, and {@code brotli}, discovered via {@link ServiceLoader}.</p>
 * <p>Configuration is handled by associating a {@link CompressionConfig} against a {@link PathSpec}.
 * By default, if no configuration is specified, then a default {@link CompressionConfig} is
 * assigned to the {@code /} {@link PathSpec}.</p>
 */
public class CompressionHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(CompressionHandler.class);
    private final HttpField varyAcceptEncoding = new PreEncodedHttpField(HttpHeader.VARY, HttpHeader.ACCEPT_ENCODING.asString());
    private final Map<String, Compression> supportedEncodings = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final PathMappings<CompressionConfig> pathConfigs = new PathMappings<>();

    public CompressionHandler()
    {
        installBean(pathConfigs);
    }

    public CompressionHandler(Handler handler)
    {
        super(handler);
        installBean(pathConfigs);
    }

    /**
     * Registers support for a Compression implementation to this Handler.
     *
     * @param compression the compression implementation.
     * @return the previously registered compression with the same encoding name, can be null.
     */
    public Compression putCompression(Compression compression)
    {
        Compression previous = supportedEncodings.put(compression.getEncodingName(), compression);
        compression.setContainer(this);
        updateBean(previous, compression, true);
        return previous;
    }

    /**
     * Unregisters a specific Compression implementation.
     *
     * @param encodingName the encoding name of the compression to remove.
     * @return the Compression that was removed, can be null if no Compression exists on that encoding name.
     */
    public Compression removeCompression(String encodingName)
    {
        Compression compression = supportedEncodings.remove(encodingName);
        removeBean(compression);
        return compression;
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
    protected void doStart() throws Exception
    {
        if (supportedEncodings.isEmpty())
        {
            // No explicit compression configured, discover them via ServiceLoader.
            TypeUtil.serviceStream(ServiceLoader.load(Compression.class)).forEach(this::putCompression);
        }
        supportedEncodings.values().forEach(compression ->
        {
            if (compression.getByteBufferPool() == null)
                compression.setByteBufferPool(getServer().getByteBufferPool());
        });

        if (pathConfigs.isEmpty())
        {
            // Add default configuration if no paths have been configured.
            pathConfigs.put("/", CompressionConfig.builder().defaults().build());
        }

        super.doStart();

        pathConfigs.values().forEach(c -> LifeCycle.start(c));
    }

    @Override
    protected void doStop() throws Exception
    {
        pathConfigs.values().forEach(c -> LifeCycle.stop(c));
        super.doStop();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("handling {} {} {}", request, response, this);

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
                LOG.debug("skipping compression: path {} has no matching compression config", pathInContext);
            // No configuration, skip
            return next.handle(request, response, callback);
        }

        CompressionConfig config = matchedConfig.getResource();

        // The `Content-Encoding` request header indicating that the request body content compression technique.
        String requestContentEncoding = null;
        // The `Accept-Encoding` request header indicating the supported list of compression encoding techniques.
        List<QuotedQualityCSV.QualityValue> requestAcceptEncoding = List.of();
        // Tracks the `If-Match` or `If-None-Match` request headers contains an etag separator.
        String ifNoneMatch = null;
        String ifMatch = null;

        QuotedQualityCSV qualityCSV = null;
        HttpFields fields = request.getHeaders();
        for (HttpField field : fields)
        {
            HttpHeader header = field.getHeader();
            if (header == null)
                continue;
            switch (header)
            {
                case CONTENT_ENCODING ->
                {
                    // We are only interested in the last encoding.
                    String contentEncoding = field.getValue();
                    if (supportedEncodings.containsKey(contentEncoding))
                        requestContentEncoding = contentEncoding;
                    else
                        requestContentEncoding = null;
                }
                case ACCEPT_ENCODING ->
                {
                    // Collect all Accept-Encoding headers.
                    if (qualityCSV == null)
                    {
                        HttpChannel httpChannel = HttpChannel.from(request);
                        HttpCompliance httpCompliance = httpChannel.getConnectionMetaData().getHttpConfiguration().getHttpCompliance();
                        ComplianceViolation.Listener complianceListener = httpChannel.getComplianceViolationListener();
                        qualityCSV = new QuotedQualityCSV(httpCompliance, complianceListener, null);
                    }
                    qualityCSV.addValue(field.getValue());
                }
                case IF_MATCH -> ifMatch = HttpField.asList(ifMatch, field.getValue());
                case IF_NONE_MATCH -> ifNoneMatch = HttpField.asList(ifNoneMatch, field.getValue());
            }
        }

        if (qualityCSV != null)
            requestAcceptEncoding = qualityCSV.getQualityValues();

        String decompressEncoding = config.getDecompressionEncoding(supportedEncodings.keySet(), request, requestContentEncoding, pathInContext);

        String compressEncoding;
        try
        {
            compressEncoding = config.getCompressionEncoding(supportedEncodings.keySet(), request, requestAcceptEncoding, pathInContext);
        }
        catch (Throwable x)
        {
            if (x instanceof HttpException http)
            {
                int statusCode = http.getCode();
                if (statusCode == HttpStatus.UNSUPPORTED_MEDIA_TYPE_415)
                {
                    String accepted = http.getReason();
                    if (StringUtil.isNotBlank(accepted))
                        response.getHeaders().put(HttpHeader.ACCEPT_ENCODING, accepted);
                    Response.writeError(request, response, callback, http.getCode(), null, x);
                    return true;
                }
            }
            throw x;
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("request[{}] Content-Encoding={}, Accept-Encoding={}, decompressEncoding={}, compressEncoding={}",
                request, requestContentEncoding, requestAcceptEncoding, decompressEncoding, compressEncoding);
        }

        String originalEtag = null;
        // wrap request if etags need to be adjusted
        if (ifMatch != null || ifNoneMatch != null)
        {
            request = new StripEtagRequest(request, ifMatch, ifNoneMatch);
            originalEtag = (ifMatch != null) ? ifMatch : ifNoneMatch;
        }

        // wrap the request if we can decompress.
        if (decompressEncoding != null)
            request = newDecompressionRequest(request, decompressEncoding);

        // wrap the response if we can deflate.
        if (compressEncoding != null)
        {
            // The response may vary based on the presence or lack of Accept-Encoding.
            response.getHeaders().ensureField(varyAcceptEncoding);
            response = newCompressionResponse(request, response, compressEncoding, config, originalEtag);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("handle {} {} {}", request, response, this);

        if (next.handle(request, response, callback))
            return true;

        if (decompressEncoding != null)
            Request.as(request, DecompressionRequest.class).destroy();

        return false;
    }

    private Compression getCompression(String encoding)
    {
        Compression compression = encoding == null ? null : supportedEncodings.get(encoding);
        if (compression == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("no compression found for encoding type {}", encoding);
            return null;
        }

        return compression;
    }

    private Response newCompressionResponse(Request request, Response response, String compressEncoding, CompressionConfig config, String originalEtag)
    {
        Compression compression = getCompression(compressEncoding);
        if (compression == null)
            return response;

        return new CompressionResponse(request, response, compression, config, originalEtag);
    }

    private Request newDecompressionRequest(Request request, String decompressEncoding)
    {
        Compression compression = getCompression(decompressEncoding);
        if (compression == null)
            return request;

        return new DecompressionRequest(compression, request);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,supported=%s}", TypeUtil.toShortName(getClass()), hashCode(), getState(), String.join(",", supportedEncodings.keySet()));
    }

    private class StripEtagRequest extends Request.Wrapper
    {
        private final HttpFields _strippedHttpFields;

        StripEtagRequest(Request request, String ifMatch, String ifNoneMatch)
        {
            super(request);
            {
                HttpFields.Mutable fields = HttpFields.build(request.getHeaders());
                if (ifMatch != null)
                {
                    for (Compression known : supportedEncodings.values())
                        ifMatch = known.stripSuffixes(ifMatch);
                    fields.put(HttpHeader.IF_MATCH, ifMatch);
                }
                if (ifNoneMatch != null)
                {
                    for (Compression known : supportedEncodings.values())
                        ifNoneMatch = known.stripSuffixes(ifNoneMatch);
                    fields.put(HttpHeader.IF_NONE_MATCH, ifNoneMatch);
                }
                _strippedHttpFields = fields.asImmutable();
            }
        }

        @Override
        public HttpFields getHeaders()
        {
            return _strippedHttpFields;
        }
    }
}
