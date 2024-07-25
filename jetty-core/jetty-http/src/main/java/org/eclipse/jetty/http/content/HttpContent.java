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

package org.eclipse.jetty.http.content;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes.Type;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.Resource;

/**
 * <p>The {@code HttpContent} interface represents all the information about a
 * static {@link Resource} that is needed to evaluate conditional headers
 * and to eventually serve the actual content.
 * It can be implemented either transiently (values and fields generated on
 * demand) or persistently (values and fields pre-generated in anticipation
 * of reuse in from a cache).
 * </p>
 */
public interface HttpContent
{
    /**
     * Get the {@link HttpHeader#CONTENT_TYPE} of this HTTP content.
     *
     * @return the content type field, or null if the type of this content is not known.
     */
    HttpField getContentType();

    /**
     * Get the {@link HttpHeader#CONTENT_ENCODING} of this HTTP content.
     *
     * @return the content encoding field, or null if the encoding of this content is not known.
     */
    HttpField getContentEncoding();

    /**
     * Get the {@link HttpHeader#CONTENT_LENGTH} of this HTTP content. The value of the returned field
     * must always match the value returned by {@link #getContentLengthValue()}.
     *
     * @return the content length field, or null if the length of this content is not known.
     */
    HttpField getContentLength();

    /**
     * Get the {@link HttpHeader#LAST_MODIFIED} of this HTTP content. The value of the returned field
     * must always match the value returned by {@link #getLastModifiedInstant()}.
     *
     * @return the last modified field, or null if the last modification time of this content is not known.
     */
    HttpField getLastModified();

    /**
     * Get the {@link HttpHeader#ETAG} of this HTTP content.
     *
     * @return the ETag, or null if this content has no ETag.
     */
    HttpField getETag();

    /**
     * Get the character encoding of this HTTP content.
     *
     * @return the character encoding, or null if the character encoding of this content is not known.
     */
    String getCharacterEncoding();

    /**
     * Get the Mime type of this HTTP content.
     *
     * @return the mime type, or null if the mime type of this content is not known.
     */
    Type getMimeType();

    /**
     * Get the last modified instant of this resource.
     *
     * @return the last modified instant, or null if that instant of this content is not known.
     * @see #getLastModified()
     */
    Instant getLastModifiedInstant();

    /**
     * Get the content length of this resource.
     *
     * @return the content length of this resource, or -1 if it is not known.
     * @see #getContentLength()
     */
    long getContentLengthValue();

    /**
     * Get the {@link Resource} backing this HTTP content.
     *
     * @return the backing resource.
     */
    Resource getResource();

    /**
     * Asynchronously write a subset of this HTTP content to a {@link Content.Sink}.
     * Calling this method does not consume the content, so it can be used repeatedly.
     *
     * @param sink the sink to write to.
     * @param offset the offset byte of the resource to start from.
     * @param length the length of the resource's contents to copy, -1 for the full length.
     * @param callback the callback to notify when writing is done.
     */
    void writeTo(Content.Sink sink, long offset, long length, Callback callback);

    /**
     * Get available pre-compressed formats for this content.
     *
     * @return Set of available pre-compressed formats for this content, or null if this has not been checked.
     */
    Set<CompressedContentFormat> getPreCompressedContentFormats();

    // TODO get rid of these?
    default String getContentTypeValue()
    {
        HttpField contentType = getContentType();
        return contentType == null ? null : contentType.getValue();
    }

    default String getContentEncodingValue()
    {
        HttpField contentEncoding = getContentEncoding();
        return contentEncoding == null ? null : contentEncoding.getValue();
    }

    default String getETagValue()
    {
        HttpField eTag = getETag();
        return eTag == null ? null : eTag.getValue();
    }

    /**
     * Factory of {@link HttpContent}.
     */
    interface Factory
    {
        /**
         * Get the {@link HttpContent} instance of a path.
         *
         * @param path The path.
         * @return A {@link HttpContent} instance.
         * @throws IOException if unable to get content
         */
        HttpContent getContent(String path) throws IOException;
    }

    // TODO update IOResources to use a RBB.Dynamic

    /**
     * HttpContent Wrapper.
     */
    class Wrapper implements HttpContent
    {
        private final HttpContent _delegate;

        public Wrapper(HttpContent content)
        {
            _delegate = content;
        }

        public HttpContent getWrapped()
        {
            return _delegate;
        }

        @Override
        public HttpField getContentType()
        {
            return _delegate.getContentType();
        }

        @Override
        public String getCharacterEncoding()
        {
            return _delegate.getCharacterEncoding();
        }

        @Override
        public Type getMimeType()
        {
            return _delegate.getMimeType();
        }

        @Override
        public HttpField getContentEncoding()
        {
            return _delegate.getContentEncoding();
        }

        @Override
        public HttpField getContentLength()
        {
            return _delegate.getContentLength();
        }

        @Override
        public long getContentLengthValue()
        {
            return _delegate.getContentLengthValue();
        }

        @Override
        public Instant getLastModifiedInstant()
        {
            return _delegate.getLastModifiedInstant();
        }

        @Override
        public HttpField getLastModified()
        {
            return _delegate.getLastModified();
        }

        @Override
        public HttpField getETag()
        {
            return _delegate.getETag();
        }

        @Override
        public Resource getResource()
        {
            return _delegate.getResource();
        }

        @Override
        public void writeTo(Content.Sink sink, long offset, long length, Callback callback)
        {
            _delegate.writeTo(sink, offset, length, callback);
        }

        @Override
        public Set<CompressedContentFormat> getPreCompressedContentFormats()
        {
            return _delegate.getPreCompressedContentFormats();
        }

        @Override
        public String toString()
        {
            return "%s@%x[%s]".formatted(getClass().getSimpleName(), hashCode(), _delegate);
        }
    }
}
