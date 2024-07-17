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

package org.eclipse.jetty.compression.zstandard;

import java.io.IOException;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;

public class ZStandardDecoder implements Compression.Decoder
{
    public ZStandardDecoder(ZStandardCompression ZStandardCompression, ByteBufferPool pool)
    {

    }

    @Override
    public Content.Chunk decode(Content.Chunk input) throws IOException
    {
        return null;
    }

    @Override
    public boolean isFinished()
    {
        return false;
    }

    @Override
    public void cleanup()
    {

    }
}
