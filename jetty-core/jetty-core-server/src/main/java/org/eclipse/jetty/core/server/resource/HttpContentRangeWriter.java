//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.resource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

import org.eclipse.jetty.http.HttpContent;
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
    public static RangeWriter newRangeWriter(HttpContent content)
    {
        Objects.requireNonNull(content, "HttpContent");

        // Try direct buffer
        ByteBuffer buffer = content.getDirectBuffer();
        if (buffer == null)
        {
            buffer = content.getIndirectBuffer();
        }
        if (buffer != null)
        {
            return new ByteBufferRangeWriter(buffer);
        }

        try
        {
            ReadableByteChannel channel = content.getReadableByteChannel();
            if (channel != null)
            {
                if (channel instanceof SeekableByteChannel)
                {
                    SeekableByteChannel seekableByteChannel = (SeekableByteChannel)channel;
                    return new SeekableByteChannelRangeWriter(seekableByteChannel, () -> (SeekableByteChannel)content.getReadableByteChannel());
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("Skipping non-SeekableByteChannel option {} from content {}", channel, content);
                channel.close();
            }
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Skipping ReadableByteChannel option", e);
        }

        return new InputStreamRangeWriter(() -> content.getInputStream());
    }
}
