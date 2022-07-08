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

package org.eclipse.jetty.io.content;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;

/**
 * <p>An {@link OutputStream} backed by a {@link Content.Sink}.
 * Any content written to this {@link OutputStream} is written
 * to the {@link Content.Sink#write(boolean, ByteBuffer, Callback)}
 * with a callback that blocks the caller until it is succeeded or
 * failed.</p>
 */
public class ContentSinkOutputStream extends OutputStream
{
    private final Blocker.Shared _blocking = new Blocker.Shared();
    private final Content.Sink sink;

    public ContentSinkOutputStream(Content.Sink sink)
    {
        this.sink = sink;
    }

    @Override
    public void write(int b) throws IOException
    {
        write(new byte[]{(byte)b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        try (Blocker.Callback callback = _blocking.callback())
        {
            sink.write(false, ByteBuffer.wrap(b, off, len), callback);
            callback.block();
        }
        catch (Throwable x)
        {
            throw IO.rethrow(x);
        }
    }

    @Override
    public void flush() throws IOException
    {
        try (Blocker.Callback callback = _blocking.callback())
        {
            sink.write(false, null, callback);
            callback.block();
        }
        catch (Throwable x)
        {
            throw IO.rethrow(x);
        }
    }

    @Override
    public void close() throws IOException
    {
        try (Blocker.Callback callback = _blocking.callback())
        {
            sink.write(true, null, callback);
            callback.block();
        }
        catch (Throwable x)
        {
            throw IO.rethrow(x);
        }
    }
}
