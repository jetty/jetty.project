package org.eclipse.jetty.servlets.gzip;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Stream that does nothing. (Used by SHA1SUM routines)
 */
public class NoOpOutputStream extends OutputStream
{
    @Override
    public void close() throws IOException
    {
        /* noop */
    }
    
    @Override
    public void flush() throws IOException
    {
        /* noop */
    }
    
    @Override
    public void write(byte[] b) throws IOException
    {
        /* noop */
    }
    
    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        /* noop */
    }
    
    @Override
    public void write(int b) throws IOException
    {
        /* noop */
    }
}
