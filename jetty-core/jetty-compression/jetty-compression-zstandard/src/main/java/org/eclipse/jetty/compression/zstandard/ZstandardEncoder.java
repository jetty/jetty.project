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
import java.nio.ByteBuffer;

import org.eclipse.jetty.compression.Compression;

public class ZstandardEncoder implements Compression.Encoder
{
    public ZstandardEncoder(ZstandardCompression compression)
    {

    }

    @Override
    public void addInput(ByteBuffer content)
    {

    }

    @Override
    public void finishInput()
    {

    }

    @Override
    public boolean isOutputFinished()
    {
        return false;
    }

    @Override
    public boolean needsInput()
    {
        return false;
    }

    @Override
    public int encode(ByteBuffer outputBuffer) throws IOException
    {
        return 0;
    }

    @Override
    public int getTrailerSize()
    {
        return 0;
    }

    @Override
    public void addTrailer(ByteBuffer outputBuffer)
    {

    }

    @Override
    public void close()
    {

    }
}
