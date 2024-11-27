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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.AsciiLowerCaseSet;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

/**
 * Configuration for a specific compression behavior per matching path from the {@link CompressionHandler}.
 *
 * <p>
 * Configuration is split between compression (of responses) and decompression (of requests).
 * </p>
 *
 * <p>
 * Experimental Configuration, subject to change while the implementation is being settled.
 * Please provide feedback at the <a href="https://github.com/jetty/jetty.project/issues">Jetty Issue tracker</a>
 * to influence the direction / development of these experimental features.
 * </p>
 */
@ManagedObject("Compression Configuration")
public class CompressionConfig extends AbstractLifeCycle
{
    /**
     * Set of {@code Accept-Encoding} encodings that are supported for compressing Response content.
     */
    private final IncludeExcludeSet<String, String> compressEncodings;
    /**
     * Set of {@code Content-Encoding} encodings that are supported for decompressing Request content.
     */
    private final IncludeExcludeSet<String, String> decompressEncodings;
    /**
     * Set of HTTP Methods that are supported for compressing Response content.
     */
    private final IncludeExcludeSet<String, String> compressMethods;
    /**
     * Set of HTTP Methods that are supported for decompressing Request content.
     */
    private final IncludeExcludeSet<String, String> decompressMethods;
    /**
     * Mime-Types that support decompressing of Request content.
     */
    private final IncludeExcludeSet<String, String> compressMimeTypes;
    /**
     * Mime-Types that support compressing Response content.
     */
    private final IncludeExcludeSet<String, String> decompressMimeTypes;
    /**
     * Set of paths that support compressing Response content.
     */
    private final IncludeExcludeSet<String, String> compressPaths;
    /**
     * Set of paths that support decompressing Request content.
     */
    private final IncludeExcludeSet<String, String> decompressPaths;
    /**
     * Optional preferred order of encoders for compressing Response content.
     */
    private final List<String> compressPreferredEncoderOrder;

    private final HttpField vary;

    private CompressionConfig(Builder builder)
    {
        this.compressPreferredEncoderOrder = builder.compressPreferredEncoderOrder;
        this.compressEncodings = builder.compressEncodings.asImmutable();
        this.decompressEncodings = builder.decompressEncodings.asImmutable();
        this.compressMethods = builder.decompressMethods.asImmutable();
        this.decompressMethods = builder.decompressMethods.asImmutable();
        this.compressMimeTypes = builder.compressMimeTypes.asImmutable();
        this.decompressMimeTypes = builder.decompressMimeTypes.asImmutable();
        this.compressPaths = builder.compressPaths.asImmutable();
        this.decompressPaths = builder.decompressPaths.asImmutable();
        this.vary = builder.vary;
    }

    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * Get the set of excluded HTTP methods for Response compression.
     *
     * @return the set of excluded HTTP methods
     * @see #getCompressIncludeMethods()
     */
    @ManagedAttribute("Set of HTTP Method Exclusions")
    public Set<String> getCompressExcludeMethods()
    {
        Set<String> excluded = compressMethods.getExcluded();
        return Collections.unmodifiableSet(excluded);
    }

    /**
     * Get the set of excluded MIME types for Response compression.
     *
     * @return the set of excluded MIME types
     * @see #getCompressIncludeMimeTypes()
     */
    @ManagedAttribute("Set of Mime-Types Excluded from Response compression")
    public Set<String> getCompressExcludeMimeTypes()
    {
        Set<String> excluded = compressMimeTypes.getExcluded();
        return Collections.unmodifiableSet(excluded);
    }

    /**
     * Get the set of excluded Path Specs for response compression.
     *
     * @return the set of excluded Path Specs
     * @see #getCompressIncludePaths()
     */
    @ManagedAttribute("Set of Response Compression Path Exclusions")
    public Set<String> getCompressExcludePaths()
    {
        Set<String> excluded = compressPaths.getExcluded();
        return Collections.unmodifiableSet(excluded);
    }

    /**
     * Get the set of included HTTP methods for Response compression
     *
     * @return the set of included HTTP methods
     * @see #getCompressExcludeMethods()
     */
    @ManagedAttribute("Set of HTTP Method Inclusions")
    public Set<String> getCompressIncludeMethods()
    {
        Set<String> includes = compressMethods.getIncluded();
        return Collections.unmodifiableSet(includes);
    }

    /**
     * Get the set of included MIME types for Response compression.
     *
     * @return the filter list of included MIME types
     * @see #getCompressExcludeMimeTypes()
     */
    @ManagedAttribute("Set of Mime-Types Included in Response compression")
    public Set<String> getCompressIncludeMimeTypes()
    {
        Set<String> includes = compressMimeTypes.getIncluded();
        return Collections.unmodifiableSet(includes);
    }

    /**
     * Get the set of included Path Specs for response compression.
     *
     * @return the set of included Path Specs
     * @see #getCompressExcludePaths()
     */
    @ManagedAttribute("Set of Response Compression Path Inclusions")
    public Set<String> getCompressIncludePaths()
    {
        Set<String> includes = compressPaths.getIncluded();
        return Collections.unmodifiableSet(includes);
    }

    /**
     * Get the preferred order of encoders for compressing response content.
     *
     * <p>
     *     See {@link Builder#compressPreferredEncoderOrder(List)} for details
     *     on how the {@code Accept-Encoding} request header interacts with
     *     this configuration.
     * </p>
     *
     * @return the preferred order of encoders.
     * @see Builder#compressPreferredEncoderOrder(List)
     */
    @ManagedAttribute("Preferred Compression Encoder Order")
    public List<String> getCompressPreferredEncoderOrder()
    {
        return Collections.unmodifiableList(compressPreferredEncoderOrder);
    }

    /**
     * Return the encoder that best matches the provided details.
     *
     * @param requestAcceptEncoding the HTTP {@code Accept-Encoding} header list (includes only supported encodings,
     *      and possibly the {@code *} glob value)
     * @param request the request itself
     * @param pathInContext the path in context
     * @return the selected compression encoding
     */
    public String getCompressionEncoding(List<String> requestAcceptEncoding, Request request, String pathInContext)
    {
        if (requestAcceptEncoding == null || requestAcceptEncoding.isEmpty())
            return null;

        List<String> preferredEncoders = calcPreferredEncoders(requestAcceptEncoding);
        String matchedEncoding = selectEncoderMatch(preferredEncoders);

        if (matchedEncoding == null)
            return null;

        if (!compressMethods.test(request.getMethod()))
            return null;

        if (!compressPaths.test(pathInContext))
            return null;

        return matchedEncoding;
    }

    protected List<String> calcPreferredEncoders(List<String> requestAcceptEncoding)
    {
        if (compressPreferredEncoderOrder.isEmpty())
        {
            List<String> result = new ArrayList<>(requestAcceptEncoding);
            result.removeIf((str) -> str.equals("*"));
            return result;
        }

        if (requestAcceptEncoding.contains("*"))
        {
            // anything else in request Accept-Encoding is moot if glob exists.
            return compressPreferredEncoderOrder;
        }

        List<String> preferredEncoderOrder = new ArrayList<>();
        for (String preferredEncoder: compressPreferredEncoderOrder)
        {
            if (requestAcceptEncoding.contains(preferredEncoder))
            {
                preferredEncoderOrder.add(preferredEncoder);
            }
        }
        return preferredEncoderOrder;
    }

    protected String selectEncoderMatch(List<String> preferredEncoders)
    {
        for (String encoding : preferredEncoders)
        {
            if (compressEncodings.test(encoding))
            {
                return encoding;
            }
        }
        return null;
    }

    /**
     * Get the set of excluded HTTP methods for Request decompression.
     *
     * @return the set of excluded HTTP methods
     * @see #getDecompressIncludeMethods()
     */
    @ManagedAttribute("Set of HTTP Method Exclusions")
    public Set<String> getDecompressExcludeMethods()
    {
        Set<String> excluded = decompressMethods.getExcluded();
        return Collections.unmodifiableSet(excluded);
    }

    /**
     * Get the set of excluded Path Specs for request decompression.
     *
     * @return the set of excluded Path Specs
     * @see #getDecompressIncludePaths()
     */
    @ManagedAttribute("Set of Request Decompression Path Exclusions")
    public Set<String> getDecompressExcludePaths()
    {
        Set<String> excluded = decompressPaths.getExcluded();
        return Collections.unmodifiableSet(excluded);
    }

    /**
     * Get the set of included HTTP methods for Request decompression
     *
     * @return the set of included HTTP methods
     * @see #getDecompressExcludeMethods()
     */
    @ManagedAttribute("Set of HTTP Method Inclusions")
    public Set<String> getDecompressIncludeMethods()
    {
        Set<String> includes = decompressMethods.getIncluded();
        return Collections.unmodifiableSet(includes);
    }

    /**
     * Get the set of included Path Specs for request decompression.
     *
     * @return the set of included Path Specs
     * @see #getDecompressExcludePaths()
     */
    @ManagedAttribute("Set of Request Decompression Path Inclusions")
    public Set<String> getDecompressIncludePaths()
    {
        Set<String> includes = decompressPaths.getIncluded();
        return Collections.unmodifiableSet(includes);
    }

    public String getDecompressionEncoding(String requestContentEncoding, Request request, String pathInContext)
    {
        String matchedEncoding = null;

        if (decompressEncodings.test(requestContentEncoding))
            matchedEncoding = requestContentEncoding;

        if (!decompressMethods.test(request.getMethod()))
            return null;

        String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (!decompressMimeTypes.test(contentType))
            return null;

        if (!decompressPaths.test(pathInContext))
            return null;

        return matchedEncoding;
    }

    /**
     * @return The VARY field to use.
     */
    public HttpField getVary()
    {
        return vary;
    }

    public boolean isCompressMethodSupported(String method)
    {
        return compressMethods.test(method);
    }

    public boolean isCompressMimeTypeSupported(String mimeType)
    {
        return compressMimeTypes.test(mimeType);
    }

    public boolean isDecompressMethodSupported(String method)
    {
        return decompressMethods.test(method);
    }

    public boolean isDecompressMimeTypeSupported(String mimeType)
    {
        return decompressMimeTypes.test(mimeType);
    }

    /**
     * Builder of CompressionConfig immutable instances.
     *
     * <p><em>Notes about PathSpec strings</em></p>
     *
     * <p>
     * There are 2 syntaxes supported, Servlet {@code url-pattern} based, and
     * Regex based.  This means that the initial characters on the path spec
     * line are very strict, and determine the behavior of the path matching.
     * </p>
     *
     * <ul>
     * <li>If the spec starts with {@code '^'} the spec is assumed to be
     * a regex based path spec and will match with normal Java regex rules.</li>
     * <li>If the spec starts with {@code '/'} then spec is assumed to be
     * a Servlet url-pattern rules path spec for either an exact match
     * or prefix based match.</li>
     * <li>If the spec starts with {@code '*.'} then spec is assumed to be
     * a Servlet url-pattern rules path spec for a suffix based match.</li>
     * <li>All other syntaxes are unsupported</li>
     * </ul>
     *
     * <p>
     *     Note: inclusion take precedence over exclude.
     * </p>
     */
    public static class Builder
    {
        /**
         * Set of {@code Content-Encoding} encodings that are supported for decompressing Request content.
         */
        private final IncludeExclude<String> decompressEncodings = new IncludeExclude<>();
        /**
         * Set of {@code Accept-Encoding} encodings that are supported for compressing Response content.
         */
        private final IncludeExclude<String> compressEncodings = new IncludeExclude<>();
        /**
         * Set of HTTP Methods that are supported for decompressing Request content.
         */
        private final IncludeExclude<String> decompressMethods = new IncludeExclude<>();
        /**
         * Set of HTTP Methods that are supported for compressing Response content.
         */
        private final IncludeExclude<String> compressMethods = new IncludeExclude<>();
        /**
         * Set of paths that support decompressing of Request content.
         */
        private final IncludeExclude<String> decompressPaths = new IncludeExclude<>(PathSpecSet.class);
        /**
         * Set of paths that support compressing Response content.
         */
        private final IncludeExclude<String> compressPaths = new IncludeExclude<>(PathSpecSet.class);
        /**
         * Mime-Types that support decompressing of Request content.
         */
        private final IncludeExclude<String> compressMimeTypes = new IncludeExclude<>(AsciiLowerCaseSet.class);
        /**
         * Mime-Types that support compressing Response content.
         */
        private final IncludeExclude<String> decompressMimeTypes = new IncludeExclude<>(AsciiLowerCaseSet.class);
        /**
         * Optional preferred order of encoders for compressing Response content.
         */
        private final List<String> compressPreferredEncoderOrder = new ArrayList<>();

        private HttpField vary = new PreEncodedHttpField(HttpHeader.VARY, HttpHeader.ACCEPT_ENCODING.asString());

        public CompressionConfig build()
        {
            return new CompressionConfig(this);
        }

        /**
         * A {@code Accept-Encoding} encoding to exclude.
         *
         * @param encoding the encoding to exclude
         * @return this builder
         */
        public Builder compressExcludeEncoding(String encoding)
        {
            this.compressEncodings.exclude(encoding);
            return this;
        }

        /**
         * An HTTP method to exclude for Response compression.
         *
         * @param method the method to exclude
         * @return this builder
         */
        public Builder compressExcludeMethod(String method)
        {
            this.compressMethods.exclude(method);
            return this;
        }

        /**
         * A non-compressible mimetype to exclude for Response compression.
         *
         * <p>
         * The response {@code Content-Type} is evaluated.
         * </p>
         *
         * @param mimetype the mimetype to exclude
         * @return this builder
         */
        public Builder compressExcludeMimeType(String mimetype)
        {
            this.compressMimeTypes.exclude(mimetype);
            return this;
        }

        /**
         * A path that does not support response content compression.
         *
         * <p>
         * See {@link Builder} for details on PathSpec string.
         * </p>
         *
         * @param pathSpecString the path spec string to exclude.  The pathInContext
         * is used to match against this path spec.
         * @return this builder.
         * @see #compressIncludePath(String)
         */
        public Builder compressExcludePath(String pathSpecString)
        {
            this.compressPaths.exclude(pathSpecString);
            return this;
        }

        /**
         * A {@code Accept-Encoding} encoding to include.
         *
         * @param encoding the encoding to include
         * @return this builder
         */
        public Builder compressIncludeEncoding(String encoding)
        {
            this.compressEncodings.include(encoding);
            return this;
        }

        /**
         * An HTTP method to include for Response compression.
         *
         * @param method the method to include
         * @return this builder
         */
        public Builder compressIncludeMethod(String method)
        {
            this.compressMethods.include(method);
            return this;
        }

        /**
         * A compressible mimetype to include for Response compression.
         *
         * <p>
         * The response {@code Content-Type} is evaluated.
         * </p>
         *
         * @param mimetype the mimetype to include
         * @return this builder
         */
        public Builder compressIncludeMimeType(String mimetype)
        {
            this.compressMimeTypes.include(mimetype);
            return this;
        }

        /**
         * A path that supports response content compression.
         *
         * <p>
         * See {@link Builder} for details on PathSpec string.
         * </p>
         *
         * @param pathSpecString the path spec string to include.  The pathInContext
         * is used to match against this path spec.
         * @return this builder.
         * @see #compressExcludePath(String)
         */
        public Builder compressIncludePath(String pathSpecString)
        {
            this.compressPaths.include(pathSpecString);
            return this;
        }

        /**
         * Control the preferred order of encoders when compressing response content.
         *
         * <p>
         *     If set to an empty List this preferred order is not considered
         *     when selecting the encoder from the {@code Accept-Encoding} Request header.
         * </p>
         * <p>
         *     If set, the union of matching encoders is the end result used to determine
         *     what encoder should be used for compressing response content.
         * </p>
         * <p>
         *     Of special note, the {@code Accept-Encoding: *} (glob) header value will
         *     return the {@code compressPreferredEncoderOrder} if provided here, otherwise
         *     the {@code *} (glob) header value will be ignored if this
         *     {@code compressPreferredEncoderOrder} is not provided.
         * </p>
         * <table style="border: 1px solid black; border-collapse: separate; border-spacing: 0px;">
         * <caption style="font-weight: bold; font-size: 1.2em">Encoder order resolution</caption>
         * <colgroup>
         *     <col><col><col>
         * </colgroup>
         * <thead style="background-color: lightgray">
         * <tr>
         *     <th>{@code compressPreferredEncoderOrder}</th>
         *     <th>{@code Accept-Encoding} header</th>
         *     <th>Resulting encoders considered</th>
         * </tr>
         * </thead>
         * <tbody style="text-align: left; vertical-align: top;">
         * <tr>
         *     <td>{@code <empty>}</td>
         *     <td>{@code gzip, br}</td>
         *     <td>{@code gzip, br}</td>
         * </tr>
         * <tr>
         *     <td>{@code <empty>}</td>
         *     <td>{@code br, gzip}</td>
         *     <td>{@code br, gzip}</td>
         * </tr>
         * <tr>
         *     <td>{@code br, gzip}</td>
         *     <td>{@code gzip, br, zstd}</td>
         *     <td>{@code br, gzip}</td>
         * </tr>
         * <tr>
         *     <td>{@code zstd, br}</td>
         *     <td>{@code gzip, br}</td>
         *     <td>{@code br}</td>
         * </tr>
         * <tr>
         *     <td>{@code zstd, br, gzip}</td>
         *     <td>{@code *}</td>
         *     <td>{@code zstd, br, gzip}</td>
         * </tr>
         * <tr>
         *     <td>{@code <empty>}</td>
         *     <td>{@code *}</td>
         *     <td>{@code <empty>}</td>
         * </tr>
         * </tbody>
         * </table>
         *
         * @param encoders the encoders, in order, to use for compressing response content.
         *   Will replace any previously set order.
         * @return this builder.
         */
        public Builder compressPreferredEncoderOrder(List<String> encoders)
        {
            this.compressPreferredEncoderOrder.clear();
            if (encoders != null)
                this.compressPreferredEncoderOrder.addAll(encoders);
            return this;
        }

        /**
         * A {@code Content-Encoding} encoding to exclude.
         *
         * @param encoding the encoding to exclude
         * @return this builder
         */
        public Builder decompressExcludeEncoding(String encoding)
        {
            this.decompressEncodings.exclude(encoding);
            return this;
        }

        /**
         * An HTTP method to exclude for Request decompression.
         *
         * @param method the method to exclude
         * @return this builder
         */
        public Builder decompressExcludeMethod(String method)
        {
            this.decompressMethods.exclude(method);
            return this;
        }

        /**
         * A non-compressed mimetype to exclude for Request decompression.
         *
         * <p>
         * The Request {@code Content-Type} is evaluated.
         * </p>
         *
         * @param mimetype the mimetype to exclude
         * @return this builder
         */
        public Builder decompressExcludeMimeType(String mimetype)
        {
            this.decompressMimeTypes.exclude(mimetype);
            return this;
        }

        /**
         * A path that does not support request content decompression.
         *
         * <p>
         * See {@link Builder} for details on PathSpec string.
         * </p>
         *
         * @param pathSpecString the path spec string to exclude.  The pathInContext
         * is used to match against this path spec.
         * @return this builder.
         * @see #decompressIncludePath(String)
         */
        public Builder decompressExcludePath(String pathSpecString)
        {
            this.decompressPaths.exclude(pathSpecString);
            return this;
        }

        /**
         * A {@code Content-Encoding} encoding to include.
         *
         * @param encoding the encoding to include
         * @return this builder
         */
        public Builder decompressIncludeEncoding(String encoding)
        {
            this.decompressEncodings.include(encoding);
            return this;
        }

        /**
         * An HTTP method to include for Request decompression.
         *
         * @param method the method to include
         * @return this builder
         */
        public Builder decompressIncludeMethod(String method)
        {
            this.decompressMethods.include(method);
            return this;
        }

        /**
         * A compressed mimetype to include for Request decompression.
         *
         * <p>
         * The request {@code Content-Type} is evaluated.
         * </p>
         *
         * @param mimetype the mimetype to include
         * @return this builder
         */
        public Builder decompressIncludeMimeType(String mimetype)
        {
            this.decompressMimeTypes.include(mimetype);
            return this;
        }

        /**
         * A path that supports request content decompression.
         *
         * <p>
         * See {@link Builder} for details on PathSpec string.
         * </p>
         *
         * @param pathSpecString the path spec string to include.  The pathInContext
         * is used to match against this path spec.
         * @return this builder.
         * @see #decompressExcludePath(String)
         */
        public Builder decompressIncludePath(String pathSpecString)
        {
            this.decompressPaths.include(pathSpecString);
            return this;
        }

        /**
         * Setup MimeType exclusion and path exclusion from the provided {@link MimeTypes} configuration.
         *
         * @param mimeTypes the mime types to iterate.
         * @return this builder.
         */
        public Builder from(MimeTypes mimeTypes)
        {
            for (String type : mimeTypes.getMimeMap().values())
            {
                if ("image/svg+xml".equals(type))
                {
                    compressExcludeMimeType(type);
                    decompressExcludeMimeType(type);
                    compressExcludePath("*.svgz");
                    decompressExcludePath("*.svgz");
                }
                else if (type.startsWith("image/") ||
                    type.startsWith("audio/") ||
                    type.startsWith("video/"))
                {
                    compressExcludeMimeType(type);
                    decompressExcludeMimeType(type);
                }
            }

            Stream.of("application/compress",
                "application/zip",
                "application/gzip",
                "application/x-bzip2",
                "application/brotli",
                "application/x-br",
                "application/x-xz",
                "application/x-rar-compressed",
                "application/vnd.bzip3",
                "application/zstd",
                // It is possible to use SSE with CompressionHandler, but only if you use `gzip` encoding with syncFlush to true which will impact performance.
                "text/event-stream"
            ).forEach((type) ->
            {
                compressExcludeMimeType(type);
                decompressExcludeMimeType(type);
            });

            return this;
        }

        /**
         * Initialize builder with existing {@link CompressionConfig}
         *
         * @param config existing config to base builder off of
         * @return this builder.
         */
        public Builder from(CompressionConfig config)
        {
            this.compressEncodings.addAll(config.compressEncodings);
            this.decompressEncodings.addAll(config.decompressEncodings);
            this.compressMethods.addAll(config.compressMethods);
            this.decompressMethods.addAll(config.decompressMethods);
            this.compressMimeTypes.addAll(config.compressMimeTypes);
            this.decompressMimeTypes.addAll(config.decompressMimeTypes);
            this.compressPaths.addAll(config.compressPaths);
            this.decompressPaths.addAll(config.decompressPaths);
            this.vary = config.vary;
            return this;
        }

        /**
         * Specify the Response {@code Vary} header field to use.
         *
         * @param vary the {@code Vary} HTTP field to use.  If it is not an instance of {@link PreEncodedHttpField},
         * then it will be converted to one.
         * @return this builder
         */
        public Builder varyHeader(HttpField vary)
        {
            if (vary == null || (vary instanceof PreEncodedHttpField))
                this.vary = vary;
            else
                this.vary = new PreEncodedHttpField(vary.getHeader(), vary.getName(), vary.getValue());
            return this;
        }

        // TODO: compression specific config (eg: compression level, strategy, etc)
        // TODO: dictionary support

        // TODO: Add configuration for decompression body size limit (to help with decompression bombs)
        // See: apache httpd mod_deflate DeflateInflateLimitRequestBody config
        // TODO: Add configuration for decompression ration burst / limit (to help with decompression bombs)
        // See: apache httpd mod_deflate DeflateInflateRatioBurst and DeflateInflateRatioLimit configs
    }
}
