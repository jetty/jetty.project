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
