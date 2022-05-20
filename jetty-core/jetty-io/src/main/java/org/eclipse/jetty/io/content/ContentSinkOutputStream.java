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
import org.eclipse.jetty.util.Blocking;
import org.eclipse.jetty.util.IO;

public class ContentSinkOutputStream extends OutputStream
{
    private final Blocking.Shared _blocking = new Blocking.Shared();
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
        try (Blocking.Callback callback = _blocking.callback())
        {
            sink.write(false, callback, ByteBuffer.wrap(b, off, len));
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
        try (Blocking.Callback callback = _blocking.callback())
        {
            sink.write(false, callback);
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
        try (Blocking.Callback callback = _blocking.callback())
        {
            sink.write(true, callback);
            callback.block();
        }
        catch (Throwable x)
        {
            throw IO.rethrow(x);
        }
    }
}
