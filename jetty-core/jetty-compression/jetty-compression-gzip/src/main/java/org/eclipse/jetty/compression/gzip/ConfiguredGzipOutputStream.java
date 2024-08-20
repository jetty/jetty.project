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

package org.eclipse.jetty.compression.gzip;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.zip.GZIPOutputStream;

/**
 * A {@link GZIPOutputStream} that you can configure, unlike the
 * JVM default provided one.
 */
public class ConfiguredGzipOutputStream extends GZIPOutputStream
{
    public ConfiguredGzipOutputStream(OutputStream outputStream) throws IOException
    {
        super(outputStream);
    }

    /**
     * @see java.util.zip.Deflater#setLevel(int)
     */
    public void setLevel(int level)
    {
        def.setLevel(level);
    }

    /**
     * @see java.util.zip.Deflater#setStrategy(int)
     */
    public void setStrategy(int strategy)
    {
        def.setStrategy(strategy);
    }

    /**
     * @see java.util.zip.Deflater#setDictionary(byte[])
     */
    public void setDictionary(byte[] dictionary)
    {
        def.setDictionary(dictionary);
    }

    /**
     * @see java.util.zip.Deflater#setDictionary(ByteBuffer)
     */
    public void setDictionary(ByteBuffer dictionary)
    {
        def.setDictionary(dictionary);
    }

    /**
     * @see java.util.zip.Deflater#setDictionary(byte[], int, int)
     */
    public void setDictionary(byte[] dictionary, int off, int len)
    {
        def.setDictionary(dictionary, off, len);
    }
}
