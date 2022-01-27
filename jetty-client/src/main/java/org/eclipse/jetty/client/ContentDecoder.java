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

package org.eclipse.jetty.client;

import java.nio.ByteBuffer;

/**
 * {@link ContentDecoder} decodes content bytes of a response.
 *
 * @see Factory
 */
public interface ContentDecoder
{
    /**
     * <p>Decodes the bytes in the given {@code buffer} and returns decoded bytes, if any.</p>
     *
     * @param buffer the buffer containing encoded bytes
     * @return a buffer containing decoded bytes, if any
     */
    public abstract ByteBuffer decode(ByteBuffer buffer);

    /**
     * <p>Releases the ByteBuffer returned by {@link #decode(ByteBuffer)}.</p>
     *
     * @param decoded the ByteBuffer returned by {@link #decode(ByteBuffer)}
     */
    public default void release(ByteBuffer decoded)
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
            if (!(obj instanceof Factory))
                return false;
            Factory that = (Factory)obj;
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
}
