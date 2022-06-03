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

package org.eclipse.jetty.util.resource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;

/**
 * Bad Resource.
 *
 * A Resource that is returned for a bade URL.  Acts as a resource
 * that does not exist and throws appropriate exceptions.
 */
class BadResource extends URLResource
{

    private String _message = null;

    BadResource(URL url, String message)
    {
        super(url, null);
        _message = message;
    }

    @Override
    public boolean exists()
    {
        return false;
    }

    @Override
    public long lastModified()
    {
        return -1;
    }

    @Override
    public boolean isDirectory()
    {
        return false;
    }

    @Override
    public long length()
    {
        return -1;
    }

    @Override
    public InputStream getInputStream() throws IOException
    {
        throw new FileNotFoundException(_message);
    }

    @Override
    public boolean delete()
        throws SecurityException
    {
        throw new SecurityException(_message);
    }

    @Override
    public boolean renameTo(Resource dest)
        throws SecurityException
    {
        throw new SecurityException(_message);
    }

    @Override
    public String[] list()
    {
        return null;
    }

    @Override
    public void copyTo(Path destination) throws IOException
    {
        throw new SecurityException(_message);
    }

    @Override
    public String toString()
    {
        return super.toString() + "; BadResource=" + _message;
    }
}
