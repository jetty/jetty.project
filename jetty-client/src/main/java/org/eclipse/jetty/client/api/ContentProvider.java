//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.api;

import java.nio.ByteBuffer;

/**
 * {@link ContentProvider} provides a source of request content.
 * <p/>
 * Implementations should return an {@link Iterator} over the request content.
 * If the request content comes from a source that needs to be closed (for
 * example, an {@link InputStream}), then the iterator implementation class
 * must implement {@link Closeable} and will be closed when the request is
 * completed (either successfully or failed).
 * <p/>
 * Applications should rely on utility classes such as {@link ByteBufferContentProvider}
 * or {@link PathContentProvider}.
 */
public interface ContentProvider extends Iterable<ByteBuffer>
{
    /**
     * @return the content length, if known, or -1 if the content length is unknown
     */
    long getLength();

    /**
     * An extension of {@link ContentProvider} that provides a content type string
     * to be used as a {@code Content-Type} HTTP header in requests.
     */
    public interface Typed extends ContentProvider
    {
        /**
         * @return the content type string such as "application/octet-stream" or
         * "application/json;charset=UTF8", or null if no content type must be set
         */
        public String getContentType();
    }
}
