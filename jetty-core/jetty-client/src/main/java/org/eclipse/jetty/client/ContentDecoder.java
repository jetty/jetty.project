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

package org.eclipse.jetty.client;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.RetainableByteBuffer;

/**
 * {@link ContentDecoder} decodes content bytes of a response.
 *
 * @see Factory
 */
public interface ContentDecoder
{
    /**
     * <p>Processes the response just before the decoding of the response content.</p>
     * <p>Typical processing may involve modifying the response headers, for example
     * by temporarily removing the {@code Content-Length} header, or modifying the
     * {@code Content-Encoding} header.</p>
     */
    public default void beforeDecoding(Response response)
    {
    }

    /**
     * <p>Decodes the bytes in the given {@code buffer} and returns the decoded bytes.</p>
     * <p>The returned {@link RetainableByteBuffer} containing the decoded bytes may
     * be empty and <b>must</b> be released via {@link RetainableByteBuffer#release()}.</p>
     *
     * @param buffer the buffer containing encoded bytes
     * @return a buffer containing decoded bytes that must be released
     */
    public abstract RetainableByteBuffer decode(ByteBuffer buffer);

    /**
     * <p>Processes the exchange after the response content has been decoded.</p>
     * <p>Typical processing may involve modifying the response headers, for example
     * updating the {@code Content-Length} header to the length of the decoded
     * response content.
     */
    public default void afterDecoding(Response response)
    {
    }

    /**
     * Factory for {@link ContentDecoder}s; subclasses must implement {@link #newContentDecoder()}.
     * <p>
     * {@link Factory} have an {@link #getEncoding() encoding}, which is the string used in
     * {@code Accept-Encoding} request header and in {@code Content-Encoding} response headers.
     * <p>
     * {@link Factory} instances are configured in {@link HttpClient} via
     * {@link HttpClient#getContentDecoderFactories()}.
     */
    public abstract static class Factory
    {
        private final String encoding;

        protected Factory(String encoding)
        {
            this.encoding = encoding;
        }

        /**
         * @return the type of the decoders created by this factory
         */
        public String getEncoding()
        {
            return encoding;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (!(obj instanceof Factory that))
                return false;
            return encoding.equals(that.encoding);
        }

        @Override
        public int hashCode()
        {
            return encoding.hashCode();
        }

        /**
         * Factory method for {@link ContentDecoder}s
         *
         * @return a new instance of a {@link ContentDecoder}
         */
        public abstract ContentDecoder newContentDecoder();
    }

    public static class Factories implements Iterable<ContentDecoder.Factory>
    {
        private final Map<String, Factory> factories = new LinkedHashMap<>();
        private HttpField acceptEncodingField;

        public HttpField getAcceptEncodingField()
        {
            return acceptEncodingField;
        }

        @Override
        public Iterator<Factory> iterator()
        {
            return factories.values().iterator();
        }

        public void clear()
        {
            factories.clear();
            acceptEncodingField = null;
        }

        public Factory put(Factory factory)
        {
            Factory result = factories.put(factory.getEncoding(), factory);
            String value = String.join(",", factories.keySet());
            acceptEncodingField = new HttpField(HttpHeader.ACCEPT_ENCODING, value);
            return result;
        }
    }
}
