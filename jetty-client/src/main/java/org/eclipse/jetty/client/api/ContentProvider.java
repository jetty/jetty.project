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

package org.eclipse.jetty.client.api;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Iterator;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.internal.RequestContentAdapter;
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
 *
 * @deprecated use {@link Request.Content} instead, or {@link #toRequestContent(ContentProvider)}
 * to convert ContentProvider to {@link Request.Content}.
 */
@Deprecated
public interface ContentProvider extends Iterable<ByteBuffer>
{
    /**
     * <p>Converts a ContentProvider to a {@link Request.Content}.</p>
     *
     * @param provider the ContentProvider to convert
     * @return a {@link Request.Content} that wraps the ContentProvider
     */
    public static Request.Content toRequestContent(ContentProvider provider)
    {
        return new RequestContentAdapter(provider);
    }

    /**
     * @return the content length, if known, or -1 if the content length is unknown
     */
    long getLength();

    /**
     * <p>Whether this ContentProvider can produce exactly the same content more
     * than once.</p>
     * <p>Implementations should return {@code true} only if the content can be
     * produced more than once, which means that invocations to {@link #iterator()}
     * must return a new, independent, iterator instance over the content.</p>
     * <p>The {@link HttpClient} implementation may use this method in particular
     * cases where it detects that it is safe to retry a request that failed.</p>
     *
     * @return whether the content can be produced more than once
     */
    default boolean isReproducible()
    {
        return false;
    }

    /**
     * An extension of {@link ContentProvider} that provides a content type string
     * to be used as a {@code Content-Type} HTTP header in requests.
     *
     * @deprecated use {@link Request.Content} instead
     */
    @Deprecated
    public interface Typed extends ContentProvider
    {
        /**
         * @return the content type string such as "application/octet-stream" or
         * "application/json;charset=UTF8", or null if no content type must be set
         */
        public String getContentType();
    }
}
