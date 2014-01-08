//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlets.gzip;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Reimplementation of {@link java.util.zip.GZIPOutputStream} that supports reusing a {@link Deflater} instance.
 */
public class GzipOutputStream extends DeflatedOutputStream
{

    private final static byte[] GZIP_HEADER = new byte[]
    { (byte)0x1f, (byte)0x8b, Deflater.DEFLATED, 0, 0, 0, 0, 0, 0, 0 };

    private final CRC32 _crc = new CRC32();

    public GzipOutputStream(OutputStream out, Deflater deflater, byte[] buffer) throws IOException
    {
        super(out,deflater,buffer);
        out.write(GZIP_HEADER);
    }

    @Override
    public synchronized void write(byte[] buf, int off, int len) throws IOException
    {
        super.write(buf,off,len);
        _crc.update(buf,off,len);
    }

    @Override
    public synchronized void finish() throws IOException
    {
        if (!_def.finished())
        {
            super.finish();
            byte[] trailer = new byte[8];
            writeInt((int)_crc.getValue(),trailer,0);
            writeInt(_def.getTotalIn(),trailer,4);
            out.write(trailer);
        }
    }

    private void writeInt(int i, byte[] buf, int offset)
    {
        int o = offset;
        buf[o++] = (byte)(i & 0xFF);
        buf[o++] = (byte)((i >>> 8) & 0xFF);
        buf[o++] = (byte)((i >>> 16) & 0xFF);
        buf[o++] = (byte)((i >>> 24) & 0xFF);
    }

}
