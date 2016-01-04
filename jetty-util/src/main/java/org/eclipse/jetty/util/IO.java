//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;
import java.nio.charset.Charset;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ======================================================================== */
/** IO Utilities.
 * Provides stream handling utilities in
 * singleton Threadpool implementation accessed by static members.
 */
public class IO
{
    private static final Logger LOG = Log.getLogger(IO.class);

    /* ------------------------------------------------------------------- */
    public final static String
        CRLF      = "\015\012";

    /* ------------------------------------------------------------------- */
    public final static byte[]
        CRLF_BYTES    = {(byte)'\015',(byte)'\012'};

    /* ------------------------------------------------------------------- */
    public static final int bufferSize = 64*1024;

    /* ------------------------------------------------------------------- */
    static class Job implements Runnable
    {
        InputStream in;
        OutputStream out;
        Reader read;
        Writer write;

        Job(InputStream in,OutputStream out)
        {
            this.in=in;
            this.out=out;
            this.read=null;
            this.write=null;
        }
        Job(Reader read,Writer write)
        {
            this.in=null;
            this.out=null;
            this.read=read;
            this.write=write;
        }

        /* ------------------------------------------------------------ */
        /*
         * @see java.lang.Runnable#run()
         */
        public void run()
        {
            try {
                if (in!=null)
                    copy(in,out,-1);
                else
                    copy(read,write,-1);
            }
            catch(IOException e)
            {
                LOG.ignore(e);
                try{
                    if (out!=null)
                        out.close();
                    if (write!=null)
                        write.close();
                }
                catch(IOException e2)
                {
                    LOG.ignore(e2);
                }
            }
        }
    }

    /* ------------------------------------------------------------------- */
    /** Copy Stream in to Stream out until EOF or exception.
     * @param in the input stream to read from (until EOF)
     * @param out the output stream to write to
     * @throws IOException if unable to copy streams
     */
    public static void copy(InputStream in, OutputStream out)
         throws IOException
    {
        copy(in,out,-1);
    }

    /* ------------------------------------------------------------------- */
    /** Copy Reader to Writer out until EOF or exception.
     * @param in the read to read from (until EOF)
     * @param out the writer to write to
     * @throws IOException if unable to copy the streams
     */
    public static void copy(Reader in, Writer out)
         throws IOException
    {
        copy(in,out,-1);
    }

    /* ------------------------------------------------------------------- */
    /** Copy Stream in to Stream for byteCount bytes or until EOF or exception.
     * @param in the stream to read from
     * @param out the stream to write to
     * @param byteCount the number of bytes to copy
     * @throws IOException if unable to copy the streams
     */
    public static void copy(InputStream in,
                            OutputStream out,
                            long byteCount)
         throws IOException
    {
        byte buffer[] = new byte[bufferSize];
        int len=bufferSize;

        if (byteCount>=0)
        {
            while (byteCount>0)
            {
                int max = byteCount<bufferSize?(int)byteCount:bufferSize;
                len=in.read(buffer,0,max);

                if (len==-1)
                    break;

                byteCount -= len;
                out.write(buffer,0,len);
            }
        }
        else
        {
            while (true)
            {
                len=in.read(buffer,0,bufferSize);
                if (len<0 )
                    break;
                out.write(buffer,0,len);
            }
        }
    }

    /* ------------------------------------------------------------------- */
    /** Copy Reader to Writer for byteCount bytes or until EOF or exception.
     * @param in the Reader to read from
     * @param out the Writer to write to
     * @param byteCount the number of bytes to copy
     * @throws IOException if unable to copy streams
     */
    public static void copy(Reader in,
                            Writer out,
                            long byteCount)
         throws IOException
    {
        char buffer[] = new char[bufferSize];
        int len=bufferSize;

        if (byteCount>=0)
        {
            while (byteCount>0)
            {
                if (byteCount<bufferSize)
                    len=in.read(buffer,0,(int)byteCount);
                else
                    len=in.read(buffer,0,bufferSize);

                if (len==-1)
                    break;

                byteCount -= len;
                out.write(buffer,0,len);
            }
        }
        else if (out instanceof PrintWriter)
        {
            PrintWriter pout=(PrintWriter)out;
            while (!pout.checkError())
            {
                len=in.read(buffer,0,bufferSize);
                if (len==-1)
                    break;
                out.write(buffer,0,len);
            }
        }
        else
        {
            while (true)
            {
                len=in.read(buffer,0,bufferSize);
                if (len==-1)
                    break;
                out.write(buffer,0,len);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /** Copy files or directories
     * @param from the file to copy
     * @param to the destination to copy to
     * @throws IOException if unable to copy
     */
    public static void copy(File from,File to) throws IOException
    {
        if (from.isDirectory())
            copyDir(from,to);
        else
            copyFile(from,to);
    }

    /* ------------------------------------------------------------ */
    public static void copyDir(File from,File to) throws IOException
    {
        if (to.exists())
        {
            if (!to.isDirectory())
                throw new IllegalArgumentException(to.toString());
        }
        else
            to.mkdirs();

        File[] files = from.listFiles();
        if (files!=null)
        {
            for (int i=0;i<files.length;i++)
            {
                String name = files[i].getName();
                if (".".equals(name) || "..".equals(name))
                    continue;
                copy(files[i],new File(to,name));
            }
        }
    }

    /* ------------------------------------------------------------ */
    public static void copyFile(File from,File to) throws IOException
    {
        try (InputStream in=new FileInputStream(from);
                OutputStream out=new FileOutputStream(to))
        {
            copy(in,out);
        }
    }

    /* ------------------------------------------------------------ */
    /** Read input stream to string.
     * @param in the stream to read from (until EOF)
     * @return the String parsed from stream (default Charset)
     * @throws IOException if unable to read the stream (or handle the charset)
     */
    public static String toString(InputStream in)
        throws IOException
    {
        return toString(in,(Charset)null);
    }

    /* ------------------------------------------------------------ */
    /** Read input stream to string.
     * @param in the stream to read from (until EOF)
     * @param encoding the encoding to use (can be null to use default Charset)
     * @return the String parsed from the stream
     * @throws IOException if unable to read the stream (or handle the charset)
     */
    public static String toString(InputStream in,String encoding)
        throws IOException
    {
        return toString(in, encoding==null?null:Charset.forName(encoding));
    }

    /** Read input stream to string.
     * @param in the stream to read from (until EOF)
     * @param encoding the Charset to use (can be null to use default Charset)
     * @return the String parsed from the stream
     * @throws IOException if unable to read the stream (or handle the charset)
     */
    public static String toString(InputStream in, Charset encoding)
            throws IOException
    {
        StringWriter writer=new StringWriter();
        InputStreamReader reader = encoding==null?new InputStreamReader(in):new InputStreamReader(in,encoding);

        copy(reader,writer);
        return writer.toString();
    }

    /* ------------------------------------------------------------ */
    /** Read input stream to string.
     * @param in the reader to read from (until EOF)
     * @return the String parsed from the reader
     * @throws IOException if unable to read the stream (or handle the charset)
     */
    public static String toString(Reader in)
        throws IOException
    {
        StringWriter writer=new StringWriter();
        copy(in,writer);
        return writer.toString();
    }


    /* ------------------------------------------------------------ */
    /** Delete File.
     * This delete will recursively delete directories - BE CAREFULL
     * @param file The file (or directory) to be deleted.
     * @return true if anything was deleted. (note: this does not mean that all content in a directory was deleted)
     */
    public static boolean delete(File file)
    {
        if (!file.exists())
            return false;
        if (file.isDirectory())
        {
            File[] files = file.listFiles();
            for (int i=0;files!=null && i<files.length;i++)
                delete(files[i]);
        }
        return file.delete();
    }

    /**
     * Closes an arbitrary closable, and logs exceptions at ignore level
     *
     * @param closeable the closeable to close
     */
    public static void close(Closeable closeable)
    {
        try
        {
            if (closeable != null)
                closeable.close();
        }
        catch (IOException ignore)
        {
            LOG.ignore(ignore);
        }
    }

    /**
     * closes an input stream, and logs exceptions
     *
     * @param is the input stream to close
     */
    public static void close(InputStream is)
    {
        close((Closeable)is);
    }

    /**
     * closes an output stream, and logs exceptions
     *
     * @param os the output stream to close
     */
    public static void close(OutputStream os)
    {
        close((Closeable)os);
    }

    /**
     * closes a reader, and logs exceptions
     *
     * @param reader the reader to close
     */
    public static void close(Reader reader)
    {
        close((Closeable)reader);
    }

    /**
     * closes a writer, and logs exceptions
     *
     * @param writer the writer to close
     */
    public static void close(Writer writer)
    {
        close((Closeable)writer);
    }

    /* ------------------------------------------------------------ */
    public static byte[] readBytes(InputStream in)
        throws IOException
    {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        copy(in,bout);
        return bout.toByteArray();
    }

    /* ------------------------------------------------------------ */
    /**
     * A gathering write utility wrapper.
     * <p>
     * This method wraps a gather write with a loop that handles the limitations of some operating systems that have a
     * limit on the number of buffers written. The method loops on the write until either all the content is written or
     * no progress is made.
     *
     * @param out
     *            The GatheringByteChannel to write to
     * @param buffers
     *            The buffers to write
     * @param offset
     *            The offset into the buffers array
     * @param length
     *            The length in buffers to write
     * @return The total bytes written
     * @throws IOException
     *             if unable write to the GatheringByteChannel
     */
    public static long write(GatheringByteChannel out, ByteBuffer[] buffers, int offset, int length) throws IOException
    {
        long total=0;
        write: while (length>0)
        {
            // Write as much as we can
            long wrote=out.write(buffers,offset,length);

            // If we can't write any more, give up
            if (wrote==0)
                break;

            // count the total
            total+=wrote;

            // Look for unwritten content
            for (int i=offset;i<buffers.length;i++)
            {
                if (buffers[i].hasRemaining())
                {
                    // loop with new offset and length;
                    length=length-(i-offset);
                    offset=i;
                    continue write;
                }
            }
            length=0;
        }

        return total;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return An outputstream to nowhere
     */
    public static OutputStream getNullStream()
    {
        return __nullStream;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return An outputstream to nowhere
     */
    public static InputStream getClosedStream()
    {
        return __closedStream;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static class NullOS extends OutputStream
    {
        @Override
        public void close(){}
        @Override
        public void flush(){}
        @Override
        public void write(byte[]b){}
        @Override
        public void write(byte[]b,int i,int l){}
        @Override
        public void write(int b){}
    }
    private static NullOS __nullStream = new NullOS();


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static class ClosedIS extends InputStream
    {
        @Override
        public int read() throws IOException
        {
            return -1;
        }
    }
    private static ClosedIS __closedStream = new ClosedIS();

    /* ------------------------------------------------------------ */
    /**
     * @return An writer to nowhere
     */
    public static Writer getNullWriter()
    {
        return __nullWriter;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return An writer to nowhere
     */
    public static PrintWriter getNullPrintWriter()
    {
        return __nullPrintWriter;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private static class NullWrite extends Writer
    {
        @Override
        public void close(){}
        @Override
        public void flush(){}
        @Override
        public void write(char[]b){}
        @Override
        public void write(char[]b,int o,int l){}
        @Override
        public void write(int b){}
        @Override
        public void write(String s){}
        @Override
        public void write(String s,int o,int l){}
    }
    private static NullWrite __nullWriter = new NullWrite();
    private static PrintWriter __nullPrintWriter = new PrintWriter(__nullWriter);

}









