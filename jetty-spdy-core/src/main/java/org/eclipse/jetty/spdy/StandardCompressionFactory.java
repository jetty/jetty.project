/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

public class StandardCompressionFactory implements CompressionFactory
{
    @Override
    public Compressor newCompressor()
    {
        return new StandardCompressor();
    }

    @Override
    public Decompressor newDecompressor()
    {
        return new StandardDecompressor();
    }

    public static class StandardCompressor implements Compressor
    {
        private final Deflater deflater = new Deflater();

        @Override
        public void setInput(byte[] input)
        {
            deflater.setInput(input);
        }

        @Override
        public void setDictionary(byte[] dictionary)
        {
            deflater.setDictionary(dictionary);
        }

        @Override
        public int compress(byte[] output)
        {
            return deflater.deflate(output, 0, output.length, Deflater.SYNC_FLUSH);
        }
    }

    public static class StandardDecompressor implements CompressionFactory.Decompressor
    {
        private final Inflater inflater = new Inflater();

        @Override
        public void setDictionary(byte[] dictionary)
        {
            inflater.setDictionary(dictionary);
        }

        @Override
        public void setInput(byte[] input)
        {
            inflater.setInput(input);
        }

        @Override
        public int decompress(byte[] output) throws ZipException
        {
            try
            {
                return inflater.inflate(output);
            }
            catch (DataFormatException x)
            {
                throw (ZipException)new ZipException().initCause(x);
            }
        }
    }
}
