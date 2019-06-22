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

/*
 * Used to switch between the old and new implementation of MultiPart Form InputStream Parsing.
 * The new implementation is preferred will be used as default unless specified otherwise constructor.
 */
public interface MultiParts extends Closeable
{
    Collection<Part> getParts() throws IOException;

    Part getPart(String name) throws IOException;

    boolean isEmpty();

    ContextHandler.Context getContext();

    class MultiPartsHttpParser implements MultiParts
    {
        private final MultiPartFormInputStream _httpParser;
        private final ContextHandler.Context _context;

        public MultiPartsHttpParser(InputStream in, String contentType, MultipartConfigElement config, File contextTmpDir, Request request) throws IOException
        {
            _httpParser = new MultiPartFormInputStream(in, contentType, config, contextTmpDir);
            _context = request.getContext();
        }

        @Override
        public Collection<Part> getParts() throws IOException
        {
            return _httpParser.getParts();
        }

        @Override
        public Part getPart(String name) throws IOException
        {
            return _httpParser.getPart(name);
        }

        @Override
        public void close()
        {
            _httpParser.deleteParts();
        }

        @Override
        public boolean isEmpty()
        {
            return _httpParser.isEmpty();
        }

        @Override
        public Context getContext()
        {
            return _context;
        }
    }
}
