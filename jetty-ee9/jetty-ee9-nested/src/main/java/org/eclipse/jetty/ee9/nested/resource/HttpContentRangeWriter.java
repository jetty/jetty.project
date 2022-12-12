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

package org.eclipse.jetty.ee9.nested.resource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Objects;

import org.eclipse.jetty.http.content.HttpContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Range Writer selection for HttpContent
 */
public class HttpContentRangeWriter
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpContentRangeWriter.class);

    /**
     * Obtain a new RangeWriter for the supplied HttpContent.
     *
     * @param content the HttpContent to base RangeWriter on
     * @return the RangeWriter best suited for the supplied HttpContent
     */
    public static RangeWriter newRangeWriter(HttpContent content) throws IOException
    {
        Objects.requireNonNull(content, "HttpContent");

        // Try direct buffer
        ByteBuffer buffer = content.getByteBuffer();
        if (buffer != null)
            return new ByteBufferRangeWriter(buffer);

        return new SeekableByteChannelRangeWriter(() -> Files.newByteChannel(content.getResource().getPath()));
    }
}
