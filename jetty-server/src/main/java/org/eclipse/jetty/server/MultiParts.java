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

package org.eclipse.jetty.server;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import javax.servlet.MultipartConfigElement;
import javax.servlet.http.Part;

import org.eclipse.jetty.http.MultiPartFormInputStream;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;

public class MultiParts implements Closeable
{
    private final MultiPartFormInputStream _httpParser;
    private final ContextHandler.Context _context;

    public MultiParts(InputStream in, String contentType, MultipartConfigElement config, File contextTmpDir, Request request)
    {
        _httpParser = new MultiPartFormInputStream(in, contentType, config, contextTmpDir);
        _context = request.getContext();
    }

    public Collection<Part> getParts() throws IOException
    {
        return _httpParser.getParts();
    }

    public Part getPart(String name) throws IOException
    {
        return _httpParser.getPart(name);
    }

    @Override
    public void close()
    {
        _httpParser.deleteParts();
    }

    public boolean isEmpty()
    {
        return _httpParser.isEmpty();
    }

    public Context getContext()
    {
        return _context;
    }
}
