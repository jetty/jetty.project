//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.resource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

import org.eclipse.jetty.util.BufferUtil;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A Memory only Resource with static content.
 */
public class MemoryResource extends Resource
{
    private final byte[] content;
    private final long lastModified;

    public MemoryResource(String rawContents)
    {
        this.content = rawContents.getBytes(UTF_8);
        this.lastModified = System.currentTimeMillis();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[size=%d]", this.getClass().getSimpleName(), this.hashCode(), this.content.length);
    }

    @Override
    public boolean isContainedIn(Resource r)
    {
        // always false
        return false;
    }

    @Override
    public void close()
    {
        // does nothing
    }

    @Override
    public boolean exists()
    {
        return true;
    }

    @Override
    public boolean isDirectory()
    {
        return false;
    }

    @Override
    public long lastModified()
    {
        return lastModified;
    }

    @Override
    public long length()
    {
        return content.length;
    }

    @Override
    public URL getURL()
    {
        try
        {
            return getURI().toURL();
        }
        catch (MalformedURLException e)
        {
            throw new RuntimeException("Unrecognized URL", e);
        }
    }

    @Override
    public URI getURI()
    {
        return URI.create("memory://");
    }

    @Override
    public File getFile()
    {
        return null; // always null
    }

    @Override
    public String getName()
    {
        return "memory";
    }

    @Override
    public InputStream getInputStream() throws IOException
    {
        return new ByteArrayInputStream(content);
    }

    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException
    {
        return new ByteBufferChannel(content);
    }

    @Override
    public boolean delete() throws SecurityException
    {
        return true;
    }

    @Override
    public boolean renameTo(Resource dest) throws SecurityException
    {
        if (dest instanceof MemoryResource)
            return true;
        return false;
    }

    @Override
    public String[] list()
    {
        return new String[0]; // always empty
    }

    @Override
    public Resource addPath(String path) throws IOException, MalformedURLException
    {
        // memory has no paths, so it always returns the same location
        return this;
    }

    private static class ByteBufferChannel implements ReadableByteChannel
    {
        private final ByteBuffer content;

        public ByteBufferChannel(byte[] buf)
        {
            content = BufferUtil.toBuffer(buf);
        }

        @Override
        public boolean isOpen()
        {
            return content.hasRemaining();
        }

        @Override
        public void close() throws IOException
        {
            content.position(content.limit());
        }

        @Override
        public int read(ByteBuffer dst) throws IOException
        {
            if (content.hasRemaining())
            {
                return BufferUtil.put(this.content, dst);
            }
            return -1;
        }
    }
}
