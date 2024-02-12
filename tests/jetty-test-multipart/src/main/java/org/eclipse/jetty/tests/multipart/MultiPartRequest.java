//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.multipart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.IO;

/**
 * Represents a Raw Multipart Form
 */
public class MultiPartRequest
{
    private Map<String, String> headers = new HashMap<>();
    private final URL rawMultipartFormURL;

    public MultiPartRequest(URL rawMultipartFormURL)
    {
        this.rawMultipartFormURL = rawMultipartFormURL;
    }

    public void addHeader(String name, String value)
    {
        String prev = headers.put(name, value);

        if (prev != null)
            throw new IllegalStateException("Lost previous header [" + name + ": " + prev + "] when setting value to " + value);
    }

    public Map<String, String> getHeaders()
    {
        return headers;
    }

    public ByteBuffer asByteBuffer() throws IOException
    {
        try (InputStream in = rawMultipartFormURL.openStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            IO.copy(in, baos);
            return ByteBuffer.wrap(baos.toByteArray());
        }
    }

    public InputStream asInputStream() throws IOException
    {
        return rawMultipartFormURL.openStream();
    }

    public String getFormName()
    {
        return FileID.getFileName(rawMultipartFormURL.getPath());
    }

    @Override
    public String toString()
    {
        return getFormName();
    }
}
