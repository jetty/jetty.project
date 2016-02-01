//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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
     * Factory for {@link ContentDecoder}s; subclasses must implement {@link #newContentDecoder()}.
     * <p>
     * {@link Factory} have an {@link #getEncoding() encoding}, which is the string used in
     * {@code Accept-Encoding} request header and in {@code Content-Encoding} response headers.
     * <p>
     * {@link Factory} instances are configured in {@link HttpClient} via
     * {@link HttpClient#getContentDecoderFactories()}.
     */
    public static abstract class Factory
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
            if (this == obj) return true;
            if (!(obj instanceof Factory)) return false;
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
