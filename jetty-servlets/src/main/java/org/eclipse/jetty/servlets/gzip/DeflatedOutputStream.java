package org.eclipse.jetty.servlets.gzip;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Deflater;

/**
 * Reimplementation of {@link java.util.zip.DeflaterOutputStream} that supports reusing the buffer.
 */
public class DeflatedOutputStream extends FilterOutputStream
{
    protected final Deflater _def;
    protected final byte[] _buf;
    protected boolean closed = false;

    public DeflatedOutputStream(OutputStream out, Deflater deflater, byte[] buffer)
    {
        super(out);
        _def = deflater;
        _buf = buffer;
    }

    @Override
    public void write(int b) throws IOException
    {
        byte[] buf = new byte[1];
        buf[0] = (byte)(b & 0xff);
        write(buf,0,1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        if (_def.finished())
            throw new IOException("Stream already finished");
        if ((off | len | (off + len) | (b.length - (off + len))) < 0)
            throw new IndexOutOfBoundsException();
        if (len == 0)
            return;
        if (!_def.finished())
        {
            _def.setInput(b,off,len);
            while (!_def.needsInput())
            {
                deflate();
            }
        }
    }

    private void deflate() throws IOException
    {
        int len = _def.deflate(_buf,0,_buf.length);
        if (len > 0)
        {
            out.write(_buf,0,len);
        }
    }

    public synchronized void finish() throws IOException
    {
        if (!_def.finished())
        {
            _def.finish();
            while (!_def.finished())
            {
                deflate();
            }
        }
    }

    @Override
    public synchronized void close() throws IOException
    {
        if (!closed)
        {
            finish();
            out.close();
            closed = true;
        }
    }

}
