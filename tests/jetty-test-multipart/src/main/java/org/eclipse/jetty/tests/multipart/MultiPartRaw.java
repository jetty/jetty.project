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

import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.IO;

/**
 * Represents a Raw Multipart Form
 */
public class MultiPartRaw
{
    private final URL rawForm;

    public MultiPartRaw(URL urlToForm)
    {
        this.rawForm = urlToForm;
    }

    public ByteBuffer asByteBuffer() throws IOException
    {
        try (InputStream in = rawForm.openStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            IO.copy(in, baos);
            return ByteBuffer.wrap(baos.toByteArray());
        }
    }

    public InputStream asInputStream() throws IOException
    {
        return rawForm.openStream();
    }

    public String getFormName()
    {
        return FileID.getFileName(rawForm.getPath());
    }

    @Override
    public String toString()
    {
        return getFormName();
    }
}
