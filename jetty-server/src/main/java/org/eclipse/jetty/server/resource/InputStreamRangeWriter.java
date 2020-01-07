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
import java.io.InputStream;
import java.io.OutputStream;

import org.eclipse.jetty.util.IO;

/**
 * Default Range Writer for InputStream
 */
public class InputStreamRangeWriter implements RangeWriter
{

    public static final int NO_PROGRESS_LIMIT = 3;

    public interface InputStreamSupplier
    {
        InputStream newInputStream() throws IOException;
    }

    private final InputStreamSupplier inputStreamSupplier;
    private boolean closed = false;
    private InputStream inputStream;
    private long pos;

    /**
     * Create InputStremRangeWriter
     *
     * @param inputStreamSupplier Supplier of the InputStream.  If the stream needs to be regenerated, such as the next
     * requested range being before the current position, then the current InputStream is closed and a new one obtained
     * from this supplier.
     */
    public InputStreamRangeWriter(InputStreamSupplier inputStreamSupplier)
    {
        this.inputStreamSupplier = inputStreamSupplier;
    }

    @Override
    public void close() throws IOException
    {
        closed = true;
        if (inputStream != null)
        {
            inputStream.close();
        }
    }

    @Override
    public void writeTo(OutputStream outputStream, long skipTo, long length) throws IOException
    {
        if (closed)
        {
            throw new IOException("RangeWriter is closed");
        }

        if (inputStream == null)
        {
            inputStream = inputStreamSupplier.newInputStream();
            pos = 0;
        }

        if (skipTo < pos)
        {
            inputStream.close();
            inputStream = inputStreamSupplier.newInputStream();
            pos = 0;
        }
        if (pos < skipTo)
        {
            long skipSoFar = pos;
            long actualSkipped;
            int noProgressLoopLimit = NO_PROGRESS_LIMIT;
            // loop till we reach desired point, break out on lack of progress.
            while (noProgressLoopLimit > 0 && skipSoFar < skipTo)
            {
                actualSkipped = inputStream.skip(skipTo - skipSoFar);
                if (actualSkipped == 0)
                {
                    noProgressLoopLimit--;
                }
                else if (actualSkipped > 0)
                {
                    skipSoFar += actualSkipped;
                    noProgressLoopLimit = NO_PROGRESS_LIMIT;
                }
                else
                {
                    // negative values means the stream was closed or reached EOF
                    // either way, we've hit a state where we can no longer
                    // fulfill the requested range write.
                    throw new IOException("EOF reached before InputStream skip destination");
                }
            }

            if (noProgressLoopLimit <= 0)
            {
                throw new IOException("No progress made to reach InputStream skip position " + (skipTo - pos));
            }

            pos = skipTo;
        }

        IO.copy(inputStream, outputStream, length);
        pos += length;
    }
}
