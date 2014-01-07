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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

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
    public static int bufferSize = 64*1024;
    
    /* ------------------------------------------------------------------- */
    // TODO get rid of this singleton!
    private static class Singleton {
        static final QueuedThreadPool __pool=new QueuedThreadPool();
        static
        {
            try{__pool.start();}
            catch(Exception e){LOG.warn(e); System.exit(1);}
        }
    }

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
     * in own thread
     */
    public static void copyThread(InputStream in, OutputStream out)
    {
        try{
            Job job=new Job(in,out);
            if (!Singleton.__pool.dispatch(job))
                job.run();
        }
        catch(Exception e)
        {
            LOG.warn(e);
        }
    }
    
    /* ------------------------------------------------------------------- */
    /** Copy Stream in to Stream out until EOF or exception.
     */
    public static void copy(InputStream in, OutputStream out)
         throws IOException
    {
        copy(in,out,-1);
    }
    
    /* ------------------------------------------------------------------- */
    /** Copy Stream in to Stream out until EOF or exception
     * in own thread
     */
    public static void copyThread(Reader in, Writer out)
    {
        try
        {
            Job job=new Job(in,out);
            if (!Singleton.__pool.dispatch(job))
                job.run();
        }
        catch(Exception e)
        {
            LOG.warn(e);
        }
    }
    
    /* ------------------------------------------------------------------- */
    /** Copy Reader to Writer out until EOF or exception.
     */
    public static void copy(Reader in, Writer out)
         throws IOException
    {
        copy(in,out,-1);
    }
    
    /* ------------------------------------------------------------------- */
    /** Copy Stream in to Stream for byteCount bytes or until EOF or exception.
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
     * @param from
     * @param to
     * @throws IOException
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
        FileInputStream in=new FileInputStream(from);
        FileOutputStream out=new FileOutputStream(to);
        copy(in,out);
        in.close();
        out.close();
    }
    
    /* ------------------------------------------------------------ */
    /** Read input stream to string.
     */
    public static String toString(InputStream in)
        throws IOException
    {
        return toString(in,null);
    }
    
    /* ------------------------------------------------------------ */
    /** Read input stream to string.
     */
    public static String toString(InputStream in,String encoding)
        throws IOException
    {
        StringWriter writer=new StringWriter();
        InputStreamReader reader = encoding==null?new InputStreamReader(in):new InputStreamReader(in,encoding);
        
        copy(reader,writer);
        return writer.toString();
    }
    
    /* ------------------------------------------------------------ */
    /** Read input stream to string.
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
     * @param file The file to be deleted.
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

    /* ------------------------------------------------------------ */
    /**
     * closes any {@link Closeable}
     *
     * @param c the closeable to close
     */
    public static void close(Closeable c)
    {
        try
        {
            if (c != null)
                c.close();
        }
        catch (IOException e)
        {
            LOG.ignore(e);
        }
    }
    
    /**
     * closes an input stream, and logs exceptions
     *
     * @param is the input stream to close
     */
    public static void close(InputStream is)
    {
        try
        {
            if (is != null)
                is.close();
        }
        catch (IOException e)
        {
            LOG.ignore(e);
        }
    }

    /**
     * closes a reader, and logs exceptions
     * 
     * @param reader the reader to close
     */
    public static void close(Reader reader)
    {
        try
        {
            if (reader != null)
                reader.close();
        } catch (IOException e)
        {
            LOG.ignore(e);
        }
    }

    /**
     * closes a writer, and logs exceptions
     * 
     * @param writer the writer to close
     */
    public static void close(Writer writer)
    {
        try
        {
            if (writer != null)
                writer.close();
        } catch (IOException e)
        {
            LOG.ignore(e);
        }
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
     * closes an output stream, and logs exceptions
     *
     * @param os the output stream to close
     */
    public static void close(OutputStream os)
    {
        try
        {
            if (os != null)
                os.close();
        }
        catch (IOException e)
        {
            LOG.ignore(e);
        }
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









