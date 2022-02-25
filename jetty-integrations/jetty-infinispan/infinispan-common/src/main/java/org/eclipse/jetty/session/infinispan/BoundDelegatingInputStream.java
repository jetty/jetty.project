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

package org.eclipse.jetty.session.infinispan;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;

/**
 * BoundDelegatingInputStream
 *
 * An InputStream that delegates methods to an ObjectInput. The ObjectInput must start
 * with an integer containing the length of the data.
 */
public class BoundDelegatingInputStream extends InputStream
{

    protected final ObjectInput objectInput;
    private final int length;
    private int position = 0;
    
    public BoundDelegatingInputStream(ObjectInput objectInput) throws IOException
    {
        this.objectInput = objectInput;
        this.length = objectInput.readInt();
    }

    @Override
    public int read() throws IOException
    {
        if (position < length)
        {
            position++;
            return objectInput.read();
        }
        return -1;
    }

    @Override
    public int read(byte[] b) throws IOException
    {
        int available = length - position; 
        int read = -1;
        if (position == length)
        {
            return read;
        }
        if (b.length > available)
        {
            read = objectInput.read(b, 0, available);
        }
        else
        {
            read = objectInput.read(b);
        }
        if (read != -1)
        {
            position += read;
        }
        return read;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        int read = -1;
        if (position == length)
        {
            return read;
        }
        if (position + len > length)
        {
            read = objectInput.read(b, off, length - position);
        }
        else
        {
            read = objectInput.read(b, off, len); 
        }
        if (read != -1)
        {
            position += read;
        }
        return read;
    }

    @Override
    public long skip(long n) throws IOException
    {
        long skip = 0;
        if (position + n < length)
        {
            skip = objectInput.skip(length - position);
        }
        else
        {
            skip = objectInput.skip(n);
        }
        if (skip > 0)
        {
            position += skip;
        }
        return skip;
    }

    @Override
    public int available() throws IOException
    {
        if (position < length)
        {
            int available = objectInput.available();
            if (position + available > length)
            {
                return length - position;
            }
            else
            {
                return available;
            }
        }
        return 0;
    }

    @Override
    public void close() throws IOException
    {
        objectInput.close();
    }
    
}
