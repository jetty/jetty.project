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

package org.eclipse.jetty.ee9.nested.resource;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.eclipse.jetty.http.content.HttpContent;

/**
 * Range Writer selection for HttpContent
 */
public class HttpContentRangeWriter
{
    /**
     * Obtain a new RangeWriter for the supplied HttpContent.
     *
     * @param content the HttpContent to base RangeWriter on
     * @return the RangeWriter best suited for the supplied HttpContent
     */
    public static RangeWriter newRangeWriter(HttpContent content)
    {
        Objects.requireNonNull(content, "HttpContent");

        // Try direct buffer
        ByteBuffer buffer = content.getByteBuffer();
        if (buffer != null)
            return new ByteBufferRangeWriter(buffer);

        // Try path's SeekableByteChannel
        Path path = content.getResource().getPath();
        if (path != null)
            return new SeekableByteChannelRangeWriter(() -> Files.newByteChannel(path));

        // Fallback to InputStream
        return new InputStreamRangeWriter(() -> content.getResource().newInputStream());
    }
}
