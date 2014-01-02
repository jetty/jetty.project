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

package org.eclipse.jetty.spdy;

import java.util.zip.ZipException;

public interface CompressionFactory
{
    public Compressor newCompressor();

    public Decompressor newDecompressor();

    public interface Compressor
    {
        public void setInput(byte[] input);

        public void setDictionary(byte[] dictionary);

        public int compress(byte[] output);
    }

    public interface Decompressor
    {
        public void setDictionary(byte[] dictionary);

        public void setInput(byte[] input);

        public int decompress(byte[] output) throws ZipException;
    }
}
