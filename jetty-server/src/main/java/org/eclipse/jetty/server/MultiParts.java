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

package org.eclipse.jetty.server;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;

/*
 * Used to switch between the old and new implementation of MultiPart Form InputStream Parsing.
 * The new implementation is preferred will be used as default unless specified otherwise constructor.
 */
public interface MultiParts extends Closeable
{
    enum NonCompliance
    {
        CR_LINE_TERMINATION("https://tools.ietf.org/html/rfc2046#section-4.1.1"),
        LF_LINE_TERMINATION("https://tools.ietf.org/html/rfc2046#section-4.1.1"),
        NO_CRLF_AFTER_PREAMBLE("https://tools.ietf.org/html/rfc2046#section-5.1.1"),
        BASE64_TRANSFER_ENCODING("https://tools.ietf.org/html/rfc7578#section-4.7"),
        QUOTED_PRINTABLE_TRANSFER_ENCODING("https://tools.ietf.org/html/rfc7578#section-4.7"),
        TRANSFER_ENCODING("https://tools.ietf.org/html/rfc7578#section-4.7");

        final String _rfcRef;

        NonCompliance(String rfcRef)
        {
            _rfcRef = rfcRef;
        }

        public String getURL()
        {
            return _rfcRef;
        }
    }

    Collection<Part> getParts() throws IOException;

    Part getPart(String name) throws IOException;

    boolean isEmpty();

    Context getContext();

    EnumSet<NonCompliance> getNonComplianceWarnings();

    class MultiPartsHttpParser implements MultiParts
    {
        private final MultiPartFormInputStream _httpParser;
        private final ContextHandler.Context _context;
        private final Request _request;

        public MultiPartsHttpParser(InputStream in, String contentType, MultipartConfigElement config, File contextTmpDir, Request request) throws IOException
        {
            _httpParser = new MultiPartFormInputStream(in, contentType, config, contextTmpDir);
            _context = request.getContext();
            _request = request;
        }

        @Override
        public Collection<Part> getParts() throws IOException
        {
            Collection<Part> parts = _httpParser.getParts();
            setNonComplianceViolationsOnRequest();
            return parts;
        }

        @Override
        public Part getPart(String name) throws IOException
        {
            Part part = _httpParser.getPart(name);
            setNonComplianceViolationsOnRequest();
            return part;
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

        @Override
        public EnumSet<NonCompliance> getNonComplianceWarnings()
        {
            return _httpParser.getNonComplianceWarnings();
        }

        private void setNonComplianceViolationsOnRequest()
        {
            @SuppressWarnings("unchecked")
            List<String> violations = (List<String>)_request.getAttribute(HttpCompliance.VIOLATIONS_ATTR);
            if (violations != null)
                return;

            EnumSet<NonCompliance> nonComplianceWarnings = _httpParser.getNonComplianceWarnings();
            violations = new ArrayList<>();
            for (NonCompliance nc : nonComplianceWarnings)
            {
                violations.add(nc.name() + ": " + nc.getURL());
            }
            _request.setAttribute(HttpCompliance.VIOLATIONS_ATTR, violations);
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

        @Override
        public EnumSet<NonCompliance> getNonComplianceWarnings()
        {
            return _utilParser.getNonComplianceWarnings();
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
