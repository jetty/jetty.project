//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import javax.servlet.MultipartConfigElement;
import javax.servlet.http.Part;

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.MultiPartFormInputStream;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.util.MultiPartInputStreamParser;
import org.eclipse.jetty.util.MultiPartInputStreamParser.NonCompliance;

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

    @SuppressWarnings("deprecation")
    class MultiPartsUtilParser implements MultiParts
    {
        private final MultiPartInputStreamParser _utilParser;
        private final ContextHandler.Context _context;
        private final Request _request;

        public MultiPartsUtilParser(InputStream in, String contentType, MultipartConfigElement config, File contextTmpDir, Request request) throws IOException
        {
            _utilParser = new MultiPartInputStreamParser(in, contentType, config, contextTmpDir);
            _context = request.getContext();
            _request = request;
        }

        @Override
        public Collection<Part> getParts() throws IOException
        {
            Collection<Part> parts = _utilParser.getParts();
            setNonComplianceViolationsOnRequest();
            return parts;
        }

        @Override
        public Part getPart(String name) throws IOException
        {
            Part part = _utilParser.getPart(name);
            setNonComplianceViolationsOnRequest();
            return part;
        }

        @Override
        public void close()
        {
            _utilParser.deleteParts();
        }

        @Override
        public boolean isEmpty()
        {
            return _utilParser.getParsedParts().isEmpty();
        }

        @Override
        public Context getContext()
        {
            return _context;
        }

        private void setNonComplianceViolationsOnRequest()
        {
            @SuppressWarnings("unchecked")
            List<String> violations = (List<String>)_request.getAttribute(HttpCompliance.VIOLATIONS_ATTR);
            if (violations != null)
                return;

            EnumSet<NonCompliance> nonComplianceWarnings = _utilParser.getNonComplianceWarnings();
            violations = new ArrayList<>();
            for (NonCompliance nc : nonComplianceWarnings)
            {
                violations.add(nc.name() + ": " + nc.getURL());
            }
            _request.setAttribute(HttpCompliance.VIOLATIONS_ATTR, violations);
        }
    }
}
