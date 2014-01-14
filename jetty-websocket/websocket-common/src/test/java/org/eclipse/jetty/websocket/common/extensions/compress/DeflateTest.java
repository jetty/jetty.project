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

package org.eclipse.jetty.websocket.common.extensions.compress;

import java.nio.ByteBuffer;
import java.util.zip.Deflater;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.common.util.Hex;
import org.junit.Ignore;
import org.junit.Test;

public class DeflateTest
{
    private int bufSize = 8 * 1024;

    public String deflate(String inputHex, Deflater deflater, int flushMode)
    {
        byte uncompressed[] = Hex.asByteArray(inputHex);
        deflater.setInput(uncompressed,0,uncompressed.length);
        deflater.finish();

        ByteBuffer out = ByteBuffer.allocate(bufSize);
        byte buf[] = new byte[64];
        while (!deflater.finished())
        {
            int len = deflater.deflate(buf,0,buf.length,flushMode);
            out.put(buf,0,len);
        }

        out.flip();
        return Hex.asHex(out);
    }

    @Test
    @Ignore("just noisy")
    public void deflateAllTypes()
    {
        int levels[] = new int[]
        { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
        boolean nowraps[] = new boolean[]
        { true, false };
        int strategies[] = new int[]
        { Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY };
        int flushmodes[] = new int[]
        { Deflater.NO_FLUSH, Deflater.SYNC_FLUSH, Deflater.FULL_FLUSH };

        String inputHex = Hex.asHex(StringUtil.getUtf8Bytes("time:"));
        for (int level : levels)
        {
            for (boolean nowrap : nowraps)
            {
                Deflater deflater = new Deflater(level,nowrap);
                for (int strategy : strategies)
                {
                    deflater.setStrategy(strategy);
                    for (int flushmode : flushmodes)
                    {
                        String result = deflate(inputHex,deflater,flushmode);
                        System.out.printf("%d | %b | %d | %d | \"%s\"%n",level,nowrap,strategy,flushmode,result);
                    }
                }
            }
        }
    }
}
