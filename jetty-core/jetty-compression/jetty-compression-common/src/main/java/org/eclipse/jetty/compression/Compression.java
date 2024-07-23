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

package org.eclipse.jetty.compression;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;

import org.eclipse.jetty.http.EtagUtils;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public abstract class Compression extends ContainerLifeCycle
{
    private final String encodingName;
    private final String etagSuffix;
    private final String etagSuffixQuote;
    private Container container;

    public Compression(String encoding)
    {
        encodingName = encoding;
        etagSuffix = StringUtil.isEmpty(EtagUtils.ETAG_SEPARATOR) ? "" : (EtagUtils.ETAG_SEPARATOR + encodingName);
        etagSuffixQuote = etagSuffix + "\"";
    }

    /**
     * Test if the {@code Accept-Encoding} request header and {@code Content-Length} response
     * header are suitable to allow compression for the response compression implementation.
     *
     * @param headers the request headers
     * @param contentLength the content length
     * @return true if compression is allowed
     */
    public abstract boolean acceptsCompression(HttpFields headers, long contentLength);

    /**
     * Acquire a {@link RetainableByteBuffer} that is managed by this {@link Compression} implementation
     * which is suitable for compressed output from an {@link Encoder} or compressed input from a {@link Decoder}.
     *
     * <p>
     *     It is recommended to use this method so that any compression specific details can be
     *     managed by this Compression implementation (such as ByteOrder or buffer pooling)
     * </p>
     *
     * @return the ByteBuffer suitable for this compression implementation.
     */
    public abstract RetainableByteBuffer acquireByteBuffer();

    /**
     * Get an etag with suffix that represents this compression implementation.
     * @param etag an etag
     * @return the etag with compression suffix
     */
    public String etag(String etag)
    {
        if (StringUtil.isEmpty(EtagUtils.ETAG_SEPARATOR))
            return etag;
        int end = etag.length() - 1;
        if (etag.charAt(end) == '"')
            return etag.substring(0, end) + etagSuffixQuote;
        return etag + etagSuffix;
    }

    /**
     * Get the container being used for common components.
     *
     * @return the container for common components
     */
    public Container getContainer()
    {
        return container != null ? container : this;
    }

    /**
     * Set the container that this compression implementation should use.
     *
     * <p>
     *     The container is often a source for common components (beans) that can
     *     be shared across different implementations.
     * </p>
     *
     * @param container the container (often the Server itself).
     */
    public void setContainer(Container container)
    {
        if (isRunning())
            throw new IllegalStateException("Cannot set container on running component");
        this.container = container;
    }

    /**
     * The {@link HttpField} for {@code Content-Encoding} suitable for this Compression implementation.
     *
     * @return the HttpField for {@code Content-Encoding}.
     */
    public abstract HttpField getContentEncodingField();

    /**
     * The name of the encoding if seen in the HTTP protocol in fields like {@code Content-Encoding}
     * or {@code Accept-Encoding}.  This name is also reused for the {@code ETag} representations
     * of the compressed content.
     *
     * @return the name of the Content-Encoding for this compression implementation.
     */
    public String getEncodingName()
    {
        return encodingName;
    }

    /**
     * Get the ETag suffix.
     *
     * @return the etag suffix for this compression.
     */
    public String getEtagSuffix()
    {
        return etagSuffix;
    }

    /**
     * The filename extensions for this compression implementation.
     *
     * <p>
     *     Not an exhaustive list, just the most commonly seen extensions.
     * </p>
     *
     * @return the set of common extension names (all lowercase) for this compression implementation.
     */
    public abstract Set<String> getFileExtensionNames();

    /**
     * @return the name of the compression implementation.
     */
    public abstract String getName();

    /**
     * The {@link HttpField} for {@code X-Content-Encoding} suitable for this Compression implementation.
     *
     * @return the HttpField for {@code X-Content-Encoding}.
     */
    public abstract HttpField getXContentEncodingField();

    /**
     * Get a new Decoder (possibly pooled) for this compression implementation.
     *
     * @return a new Decoder
     */
    public abstract Decoder newDecoder();

    /**
     * Get a new Decoder (possibly pooled) for this compression implementation.
     *
     * @param pool the desired ByteBufferPool to use for this Decoder
     * @return a new Decoder
     */
    public abstract Decoder newDecoder(ByteBufferPool pool);

    /**
     * Get a new Encoder (possibly pooled) for this compression implementation.
     *
     * @return a new Encoder
     */
    public abstract Encoder newEncoder();

    /**
     * Strip compression suffixes off etags
     *
     * @param etagsList the list of etags to strip
     * @return the tags stripped of compression suffixes.
     */
    public String stripSuffixes(String etagsList)
    {
        if (StringUtil.isEmpty(EtagUtils.ETAG_SEPARATOR))
            return etagsList;

        // This is a poor implementation that ignores list and tag structure
        while (true)
        {
            int i = etagsList.lastIndexOf(etagSuffix);
            if (i < 0)
                return etagsList;
            etagsList = etagsList.substring(0, i) + etagsList.substring(i + etagSuffix.length());
        }
    }

    /**
     * A Decoder for decompression
     */
    public interface Decoder extends AutoCloseable
    {
        /**
         * Decode the input buffer, returning the next decoded buffer.
         *
         * <p>
         *     The input buffer might not be fully read (or even read at all) if
         *     there are pending output buffers from a previous {@code .decode(RetainableByteBuffer)} operation.
         *     It is the responsibility of the user of this API to give the same input buffer back to
         *     this method if the buffer has remaining bytes.
         * </p>
         *
         * @param input the input buffer to read from.
         * @return the output buffer. never null, use {@link #isFinished()} to know if the decompression is done.
         * @throws IOException if unable to decode chunk.
         */
        RetainableByteBuffer decode(ByteBuffer input) throws IOException;

        /**
         * The decoder has finished.
         *
         * @return true if decoder is finished (usually means the decoder reached the end of the compressed content)
         */
        boolean isFinished();
    }

    /**
     * A Encoder for compression.
     */
    public interface Encoder extends AutoCloseable
    {
        /**
         * Write the encoder trailer to the provided output buffer.
         *
         * @param outputBuffer the buffer to write trailer to
         */
        void addTrailer(ByteBuffer outputBuffer);

        /**
         * Encode what exists in the input buffer to the provided output buffer.
         *
         * @param outputBuffer the output buffer to write to
         * @return the size in bytes put into the output buffer
         *
         * @throws IOException if unable to encode the input buffer
         */
        int encode(ByteBuffer outputBuffer) throws IOException;

        /**
         * Indicate that we are finished providing input.
         * Encoder will not accept more input after this.
         */
        void finishInput();

        /**
         * Get the size of the encoder trailer for this encoder.
         *
         * <p>
         *     Useful for ByteBuffer size calculations.
         * </p>
         *
         * @return the size of the trailer (0 for no trailer)
         */
        int getTrailerSize();

        /**
         * Test if output is finished.
         *
         * @return true if output is finished.
         */
        boolean isOutputFinished();

        /**
         * Test if input is needed.
         *
         * @return true if input is needed.
         */
        boolean needsInput();

        /**
         * Provide input buffer to encoder
         *
         * @param content the input buffer
         */
        void addInput(ByteBuffer content);
    }
}
