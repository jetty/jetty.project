//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IO Utilities.
 * Provides stream handling utilities in
 * singleton Threadpool implementation accessed by static members.
 */
public class IO
{
    private static final Logger LOG = LoggerFactory.getLogger(IO.class);

    public static final String
        CRLF = "\r\n";

    public static final byte[]
        CRLF_BYTES = {(byte)'\r', (byte)'\n'};

    public static final int bufferSize = 64 * 1024;

    static class Job implements Runnable
    {
        InputStream in;
        OutputStream out;
        Reader read;
        Writer write;

        Job(InputStream in, OutputStream out)
        {
            this.in = in;
            this.out = out;
            this.read = null;
            this.write = null;
        }

        Job(Reader read, Writer write)
        {
            this.in = null;
            this.out = null;
            this.read = read;
            this.write = write;
        }

        @Override
        public void run()
        {
            try
            {
                if (in != null)
                    copy(in, out, -1);
                else
                    copy(read, write, -1);
            }
            catch (IOException e)
            {
                LOG.trace("IGNORED", e);
                try
                {
                    if (out != null)
                        out.close();
                    if (write != null)
                        write.close();
                }
                catch (IOException e2)
                {
                    LOG.trace("IGNORED", e2);
                }
            }
        }
    }

    /**
     * Copy Stream in to Stream out until EOF or exception.
     *
     * @param in the input stream to read from (until EOF)
     * @param out the output stream to write to
     * @throws IOException if unable to copy streams
     */
    public static void copy(InputStream in, OutputStream out)
        throws IOException
    {
        copy(in, out, -1);
    }

    /**
     * Copy Reader to Writer out until EOF or exception.
     *
     * @param in the read to read from (until EOF)
     * @param out the writer to write to
     * @throws IOException if unable to copy the streams
     */
    public static void copy(Reader in, Writer out)
        throws IOException
    {
        copy(in, out, -1);
    }

    /**
     * Copy Stream in to Stream for byteCount bytes or until EOF or exception.
     *
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
        byte[] buffer = new byte[bufferSize];
        int len = bufferSize;

        if (byteCount >= 0)
        {
            while (byteCount > 0)
            {
                int max = byteCount < bufferSize ? (int)byteCount : bufferSize;
                len = in.read(buffer, 0, max);

                if (len == -1)
                    break;

                byteCount -= len;
                out.write(buffer, 0, len);
            }
        }
        else
        {
            while (true)
            {
                len = in.read(buffer, 0, bufferSize);
                if (len < 0)
                    break;
                out.write(buffer, 0, len);
            }
        }
    }

    /**
     * Copy Reader to Writer for byteCount bytes or until EOF or exception.
     *
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
        char[] buffer = new char[bufferSize];
        int len = bufferSize;

        if (byteCount >= 0)
        {
            while (byteCount > 0)
            {
                if (byteCount < bufferSize)
                    len = in.read(buffer, 0, (int)byteCount);
                else
                    len = in.read(buffer, 0, bufferSize);

                if (len == -1)
                    break;

                byteCount -= len;
                out.write(buffer, 0, len);
            }
        }
        else if (out instanceof PrintWriter)
        {
            PrintWriter pout = (PrintWriter)out;
            while (!pout.checkError())
            {
                len = in.read(buffer, 0, bufferSize);
                if (len == -1)
                    break;
                out.write(buffer, 0, len);
            }
        }
        else
        {
            while (true)
            {
                len = in.read(buffer, 0, bufferSize);
                if (len == -1)
                    break;
                out.write(buffer, 0, len);
            }
        }
    }

    /**
     * Copy files or directories
     *
     * @param from the file to copy
     * @param to the destination to copy to
     * @throws IOException if unable to copy
     */
    public static void copy(File from, File to) throws IOException
    {
        if (from.isDirectory())
            copyDir(from, to);
        else
            copyFile(from, to);
    }

    public static void copyDir(File from, File to) throws IOException
    {
        if (to.exists())
        {
            if (!to.isDirectory())
                throw new IllegalArgumentException(to.toString());
        }
        else
            to.mkdirs();

        File[] files = from.listFiles();
        if (files != null)
        {
            for (int i = 0; i < files.length; i++)
            {
                String name = files[i].getName();
                if (".".equals(name) || "..".equals(name))
                    continue;
                copy(files[i], new File(to, name));
            }
        }
    }

    public static void copyFile(File from, File to) throws IOException
    {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to))
        {
            copy(in, out);
        }
    }

    public static IOException rethrow(Throwable cause)
    {
        if (cause instanceof ExecutionException xx)
            cause = xx.getCause();
        if (cause instanceof CompletionException xx)
            cause = xx.getCause();
        if (cause instanceof IOException)
            return (IOException)cause;
        if (cause instanceof Error)
            throw (Error)cause;
        if (cause instanceof RuntimeException)
            throw (RuntimeException)cause;
        if (cause instanceof InterruptedException)
            return (InterruptedIOException)new InterruptedIOException().initCause(cause);
        return new IOException(cause);
    }

    /**
     * Read Path to string.
     *
     * @param path the path to read from (until EOF)
     * @param charset the charset to read with
     * @return the String parsed from path (default Charset)
     * @throws IOException if unable to read the path (or handle the charset)
     */
    public static String toString(Path path, Charset charset)
        throws IOException
    {
        byte[] buf = Files.readAllBytes(path);
        return new String(buf, charset);
    }

    /**
     * Read input stream to string.
     *
     * @param in the stream to read from (until EOF)
     * @return the String parsed from stream (default Charset)
     * @throws IOException if unable to read the stream (or handle the charset)
     */
    public static String toString(InputStream in)
        throws IOException
    {
        return toString(in, (Charset)null);
    }

    /**
     * Read input stream to string.
     *
     * @param in the stream to read from (until EOF)
     * @param encoding the encoding to use (can be null to use default Charset)
     * @return the String parsed from the stream
     * @throws IOException if unable to read the stream (or handle the charset)
     */
    public static String toString(InputStream in, String encoding)
        throws IOException
    {
        return toString(in, encoding == null ? null : Charset.forName(encoding));
    }

    /**
     * Read input stream to string.
     *
     * @param in the stream to read from (until EOF)
     * @param encoding the Charset to use (can be null to use default Charset)
     * @return the String parsed from the stream
     * @throws IOException if unable to read the stream (or handle the charset)
     */
    public static String toString(InputStream in, Charset encoding)
        throws IOException
    {
        StringWriter writer = new StringWriter();
        InputStreamReader reader = encoding == null ? new InputStreamReader(in) : new InputStreamReader(in, encoding);

        copy(reader, writer);
        return writer.toString();
    }

    /**
     * Read input stream to string.
     *
     * @param in the reader to read from (until EOF)
     * @return the String parsed from the reader
     * @throws IOException if unable to read the stream (or handle the charset)
     */
    public static String toString(Reader in)
        throws IOException
    {
        StringWriter writer = new StringWriter();
        copy(in, writer);
        return writer.toString();
    }

    /**
     * Delete File.
     * This delete will recursively delete directories - BE CAREFUL
     *
     * @param file The file (or directory) to be deleted.
     * @return true if file was deleted, or directory referenced was deleted.
     * false if file doesn't exist, or was null.
     */
    public static boolean delete(File file)
    {
        if (file == null)
            return false;
        if (!file.exists())
            return false;
        if (file.isDirectory())
        {
            File[] files = file.listFiles();
            for (int i = 0; files != null && i < files.length; i++)
            {
                delete(files[i]);
            }
        }
        return file.delete();
    }

    public static boolean delete(Path path)
    {
        if (path == null)
            return false;
        try
        {
            Files.walkFileTree(path, new SimpleFileVisitor<>()
            {
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
                {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
                {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    /**
     * Test if directory is empty.
     *
     * @param dir the directory
     * @return true if directory is null, doesn't exist, or has no content.
     * false if not a directory, or has contents
     */
    public static boolean isEmptyDir(File dir)
    {
        if (dir == null)
            return true;
        if (!dir.exists())
            return true;
        if (!dir.isDirectory())
            return false;
        String[] list = dir.list();
        if (list == null)
            return true;
        return list.length <= 0;
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
            LOG.trace("IGNORED", ignore);
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

    public static byte[] readBytes(InputStream in)
        throws IOException
    {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        copy(in, bout);
        return bout.toByteArray();
    }

    /**
     * A gathering write utility wrapper.
     * <p>
     * This method wraps a gather write with a loop that handles the limitations of some operating systems that have a
     * limit on the number of buffers written. The method loops on the write until either all the content is written or
     * no progress is made.
     *
     * @param out The GatheringByteChannel to write to
     * @param buffers The buffers to write
     * @param offset The offset into the buffers array
     * @param length The length in buffers to write
     * @return The total bytes written
     * @throws IOException if unable write to the GatheringByteChannel
     */
    public static long write(GatheringByteChannel out, ByteBuffer[] buffers, int offset, int length) throws IOException
    {
        long total = 0;
        write:
        while (length > 0)
        {
            // Write as much as we can
            long wrote = out.write(buffers, offset, length);

            // If we can't write any more, give up
            if (wrote == 0)
                break;

            // count the total
            total += wrote;

            // Look for unwritten content
            for (int i = offset; i < buffers.length; i++)
            {
                if (buffers[i].hasRemaining())
                {
                    // loop with new offset and length;
                    length = length - (i - offset);
                    offset = i;
                    continue write;
                }
            }
            length = 0;
        }

        return total;
    }

    /**
     * <p>Convert an object to a {@link File} if possible.</p>
     * @param fileObject A File, String, Path or null to be converted into a File
     * @return A File representation of the passed argument or null.
     */
    public static File asFile(Object fileObject)
    {
        if (fileObject == null)
            return null;
        if (fileObject instanceof File)
            return (File)fileObject;
        if (fileObject instanceof String)
            return new File((String)fileObject);
        if (fileObject instanceof Path)
            return ((Path)fileObject).toFile();

        return null;
    }
}









