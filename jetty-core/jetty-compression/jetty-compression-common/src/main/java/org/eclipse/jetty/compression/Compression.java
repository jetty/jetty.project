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
import java.nio.ByteOrder;
import java.util.Set;

import org.eclipse.jetty.http.EtagUtils;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
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
     * @param outputBufferSize the desired output buffer size (can be overridden by
     * compression implementation)
     * @return a new Encoder
     */
    public abstract Encoder newEncoder(int outputBufferSize);

    /**
     * Get a new Encoder (possibly pooled) for this compression implementation.
     *
     * @param pool the desired ByteBufferPool to use for this Encoder
     * @param outputBufferSize the desired output buffer size (can be overridden by
     * compression implementation)
     * @return a new Encoder
     */
    public abstract Encoder newEncoder(ByteBufferPool pool, int outputBufferSize);

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
     * A Decoder (compression)
     */
    public interface Decoder
    {
        void cleanup(); // TODO: consider AutoCloseable instead

        /**
         * Decode the input chunk, returning the decoded chunk.
         *
         * @param input the input chunk, can be fully consumed.
         * @return the output chunk (never null)
         * @throws IOException if unable to decode chunk.
         */
        // TODO: make input is RetainableByteBuffer
        RetainableByteBuffer decode(Content.Chunk input) throws IOException;

        /**
         * The decoder has finished.
         *
         * @return true if decoder is finished (usually means the decoder reached the trailer bytes)
         */
        boolean isFinished();
    }

    /**
     * A Encoder (decompression)
     */
    public interface Encoder
    {
        /**
         * Create the initial buffer, might include encoder headers.
         *
         * @return the newly acquired initial buffer to use for
         */
        RetainableByteBuffer acquireInitialOutputBuffer();

        /**
         * Write the encoder trailer to the provided output buffer.
         *
         * @param outputBuffer the buffer to write trailer to
         */
        void addTrailer(ByteBuffer outputBuffer);

        /**
         * Begin the encoder process, initializing any
         * internals necessary for performing the encoding.
         */
        void begin();

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
         * Get encoder specific {@code ByteOrder} to use for ByteBuffers.
         *
         * @return the encoder {@code ByteOrder}
         */
        // TODO: Look into removing
        ByteOrder getByteOrder();

        /**
         * Get the size of the trailer for this encoder.
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
         * Cleanup and release any resources that the encoder is managing.
         *
         * <p>
         *     Note: this does not include the {@link RetainableByteBuffer}
         *     returned from the {@link #acquireInitialOutputBuffer()} call.
         * </p>
         */
        void release();

        /**
         * Provide input buffer to encoder
         *
         * @param content the input buffer
         */
        // TODO: rename to addInput
        void setInput(ByteBuffer content);
    }
}
