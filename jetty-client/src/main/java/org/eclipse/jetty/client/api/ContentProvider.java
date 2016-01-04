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

package org.eclipse.jetty.client.api;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Iterator;

import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.eclipse.jetty.client.util.PathContentProvider;

/**
 * <p>{@link ContentProvider} provides a source of request content.</p>
 * <p>Implementations should return an {@link Iterator} over the request content.
 * If the request content comes from a source that needs to be closed (for
 * example, an {@link java.io.InputStream}), then the iterator implementation class
 * must implement {@link Closeable} and will be closed when the request is
 * completed (either successfully or failed).</p>
 * <p>Applications should rely on utility classes such as {@link ByteBufferContentProvider}
 * or {@link PathContentProvider}.</p>
 * <p>{@link ContentProvider} provides a {@link #getLength() length} of the content
 * it represents.
 * If the length is positive, it typically overrides any {@code Content-Length}
 * header set by applications; if the length is negative, it typically removes
 * any {@code Content-Length} header set by applications, resulting in chunked
 * content (i.e. {@code Transfer-Encoding: chunked}) being sent to the server.</p>
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
