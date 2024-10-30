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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import org.eclipse.jetty.http.EtagUtils;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Compression extends ContainerLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(Compression.class);
    private final String encodingName;
    private final String etagSuffix;
    private final String etagSuffixQuote;
    private ByteBufferPool byteBufferPool;
    private Container container;
    private int bufferSize = 2048;

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
    public boolean acceptsCompression(HttpFields headers, long contentLength)
    {
        if (contentLength >= 0 && contentLength < getMinCompressSize())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} excluded minCompressSize {}", this, headers);
            return false;
        }

        // check the accept encoding header
        if (!headers.contains(HttpHeader.ACCEPT_ENCODING, getEncodingName()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} excluded not {} acceptable {}", this, getEncodingName(), headers);
            return false;
        }

        return true;
    }

    /**
     * Acquire a {@link RetainableByteBuffer} that is managed by this {@link Compression} implementation
     * which is suitable for compressed output from an {@link EncoderSink} or compressed input from a {@link DecoderSource}.
     *
     * <p>
     * It is recommended to use this method so that any compression specific details can be
     * managed by this Compression implementation (such as ByteOrder or buffer pooling)
     * </p>
     *
     * <p>
     * The size of the buffer comes from {@link Compression} implementation.
     * </p>
     *
     * @return the ByteBuffer suitable for this compression implementation.
     */
    public abstract RetainableByteBuffer acquireByteBuffer();

    /**
     * Acquire a {@link RetainableByteBuffer} that is managed by this {@link Compression} implementation
     * which is suitable for compressed output from an {@link EncoderSink} or compressed input from a {@link DecoderSource}.
     *
     * <p>
     * It is recommended to use this method so that any compression specific details can be
     * managed by this Compression implementation (such as ByteOrder or buffer pooling)
     * </p>
     *
     * @param length the requested size of the buffer
     * @return the ByteBuffer suitable for this compression implementation.
     */
    public abstract RetainableByteBuffer acquireByteBuffer(int length);

    /**
     * Get an etag with suffix that represents this compression implementation.
     *
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

    public int getBufferSize()
    {
        return bufferSize;
    }

    public void setBufferSize(int size)
    {
        if (size <= 0)
            throw new IllegalArgumentException("Invalid buffer size: " + size);
        this.bufferSize = size;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return this.byteBufferPool;
    }

    public void setByteBufferPool(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;
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
     * The container is often a source for common components (beans) that can
     * be shared across different implementations.
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

    public abstract DecoderConfig getDefaultDecoderConfig();

    public abstract void setDefaultDecoderConfig(DecoderConfig decoderConfig);

    public abstract EncoderConfig getDefaultEncoderConfig();

    public abstract void setDefaultEncoderConfig(EncoderConfig encoderConfig);

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
     * Not an exhaustive list, just the most commonly seen extensions.
     * </p>
     *
     * @return the list of common extension names (all lowercase) for this compression implementation.
     * ordered by most common to least common.
     */
    public abstract List<String> getFileExtensionNames();

    public abstract int getMinCompressSize();

    public abstract void setMinCompressSize(int minCompressSize);

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
     * Create a new {@link InputStream} to decode (decompress) with this compression implementation.
     *
     * @param in the input stream to write the decoded (decompressed) bytes to
     * @return the {@link InputStream} implementation for this compression.
     * @throws IOException if unable to create InputStream
     */
    public InputStream newDecoderInputStream(InputStream in) throws IOException
    {
        return newDecoderInputStream(in, getDefaultDecoderConfig());
    }

    /**
     * Create a new {@link InputStream} to decode (decompress) with this compression implementation.
     *
     * @param in the input stream to write the decoded (decompressed) bytes to
     * @param config the {@link DecoderConfig} for this input stream.
     * @return the {@link InputStream} implementation for this compression.
     * @throws IOException if unable to create InputStream
     */
    public abstract InputStream newDecoderInputStream(InputStream in, DecoderConfig config) throws IOException;

    /**
     * Create a new {@link DecoderSource} for this compression implementation
     *
     * @param source the source to write the decoded bytes to
     * @return a new {@link DecoderSource}
     */
    public DecoderSource newDecoderSource(Content.Source source)
    {
        return newDecoderSource(source, getDefaultDecoderConfig());
    }

    /**
     * Create a new {@link DecoderSource} for this compression implementation
     *
     * @param source the source to write the decoded bytes to
     * @param config the {@link DecoderConfig} for this source.
     * @return a new {@link DecoderSource}
     */
    public abstract DecoderSource newDecoderSource(Content.Source source, DecoderConfig config);

    /**
     * Create a new {@link OutputStream} to encode (compress) with this compression implementation.
     *
     * @param out the output stream to write the encoded (compressed) bytes to
     * @return the {@link OutputStream} implementation for this compression.
     * @throws IOException if unable to create OutputStream
     */
    public OutputStream newEncoderOutputStream(OutputStream out) throws IOException
    {
        return newEncoderOutputStream(out, getDefaultEncoderConfig());
    }

    /**
     * Create a new {@link OutputStream} to encode (compress) with this compression implementation.
     *
     * @param out the output stream to write the encoded (compressed) bytes to
     * @param config the {@link EncoderConfig} for this output stream.
     * @return the {@link OutputStream} implementation for this compression.
     * @throws IOException if unable to create OutputStream
     */
    public abstract OutputStream newEncoderOutputStream(OutputStream out, EncoderConfig config) throws IOException;

    /**
     * Create a new {@link EncoderSink} for this compression implementation
     *
     * @param sink the sink to write the encoded bytes to
     * @return a new {@link EncoderSink}
     */
    public EncoderSink newEncoderSink(Content.Sink sink)
    {
        return newEncoderSink(sink, getDefaultEncoderConfig());
    }

    /**
     * Create a new {@link EncoderSink} for this compression implementation
     *
     * @param sink the sink to write the encoded bytes to
     * @param config the {@link EncoderConfig} for this sink.
     * @return a new {@link EncoderSink}
     */
    public abstract EncoderSink newEncoderSink(Content.Sink sink, EncoderConfig config);

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

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        if (byteBufferPool == null)
        {
            byteBufferPool = ByteBufferPool.NON_POOLING;
        }
    }
}
