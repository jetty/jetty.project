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

package org.eclipse.jetty.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Map;

import org.eclipse.jetty.http.MimeTypes.Type;
import org.eclipse.jetty.util.resource.Resource;

/**
 * HttpContent interface.
 * <p>This information represents all the information about a
 * static resource that is needed to evaluate conditional headers
 * and to serve the content if need be.     It can be implemented
 * either transiently (values and fields generated on demand) or
 * persistently (values and fields pre-generated in anticipation of
 * reuse in from a cache).
 * </p>
 */
public interface HttpContent
{
    HttpField getContentType();

    String getContentTypeValue();

    String getCharacterEncoding();

    Type getMimeType();

    HttpField getContentEncoding();

    String getContentEncodingValue();

    HttpField getContentLength();

    long getContentLengthValue();

    HttpField getLastModified();

    String getLastModifiedValue();

    HttpField getETag();

    String getETagValue();

    ByteBuffer getIndirectBuffer();

    ByteBuffer getDirectBuffer();

    Resource getResource();

    InputStream getInputStream() throws IOException;

    ReadableByteChannel getReadableByteChannel() throws IOException;

    void release();

    Map<CompressedContentFormat, ? extends HttpContent> getPrecompressedContents();

    public interface ContentFactory
    {
        /**
         * @param path The path within the context to the resource
         * @param maxBuffer The maximum buffer to allocated for this request.  For cached content, a larger buffer may have
         * previously been allocated and returned by the {@link HttpContent#getDirectBuffer()} or {@link HttpContent#getIndirectBuffer()} calls.
         * @return A {@link HttpContent}
         * @throws IOException if unable to get content
         */
        HttpContent getContent(String path, int maxBuffer) throws IOException;
    }
}
