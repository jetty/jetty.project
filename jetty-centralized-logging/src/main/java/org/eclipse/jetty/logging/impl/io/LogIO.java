// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.logging.impl.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;

/**
 * Simplified IO for logging reasons only.
 */
public class LogIO
{
    public static void close(InputStream stream)
    {
        try
        {
            if (stream != null)
                stream.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }

    public static void close(OutputStream stream)
    {
        try
        {
            if (stream != null)
                stream.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }

    public static void close(Reader reader)
    {
        try
        {
            if (reader != null)
                reader.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }

    public static void close(Writer writer)
    {
        try
        {
            if (writer != null)
                writer.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }

    public static void copy(Reader in, Writer out) throws IOException
    {
        final int bufferSize = 8096;
        char buffer[] = new char[bufferSize];
        int len = bufferSize;

        if (out instanceof PrintWriter)
        {
            PrintWriter pout = (PrintWriter)out;
            while (!pout.checkError())
            {
                len = in.read(buffer,0,bufferSize);
                if (len == -1)
                    break;
                out.write(buffer,0,len);
            }
        }
        else
        {
            while (true)
            {
                len = in.read(buffer,0,bufferSize);
                if (len == -1)
                    break;
                out.write(buffer,0,len);
            }
        }
    }

    public static String toString(Reader in) throws IOException
    {
        StringWriter writer = new StringWriter();
        copy(in,writer);
        return writer.toString();
    }

}
