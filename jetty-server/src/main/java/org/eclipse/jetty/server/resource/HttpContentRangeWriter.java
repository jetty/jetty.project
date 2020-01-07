//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.resource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Range Writer selection for HttpContent
 */
public class HttpContentRangeWriter
{
    private static final Logger LOG = Log.getLogger(HttpContentRangeWriter.class);

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
                    LOG.debug("Skipping non-SeekableByteChannel option " + channel + " from content " + content);
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
