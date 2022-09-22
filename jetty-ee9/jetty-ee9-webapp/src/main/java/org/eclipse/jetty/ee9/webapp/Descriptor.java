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

package org.eclipse.jetty.ee9.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

public abstract class Descriptor
{
    private static final Logger LOG = LoggerFactory.getLogger(Descriptor.class);

    protected Path _xml;
    protected XmlParser.Node _root;
    protected String _dtd;

    /**
     * @deprecated use {@link Descriptor(Path)} instead
     */
    @Deprecated
    public Descriptor(Resource resource)
    {
        this(Objects.requireNonNull(resource, "Resource must exist").getPath());
    }

    public Descriptor(Path xml)
    {
        _xml = Objects.requireNonNull(xml, "Path must exist");
        if (!Files.exists(_xml))
            throw new IllegalArgumentException("Descriptor does not exist: " + xml);
        if (!Files.isRegularFile(_xml))
            throw new IllegalArgumentException("Descriptor is not a file: " + xml);
    }

    public void parse(XmlParser parser)
        throws Exception
    {
        if (_root == null)
        {
            Objects.requireNonNull(parser);
            try (InputStream is = Files.newInputStream(_xml, StandardOpenOption.READ))
            {
                _root = parser.parse(is);
                _dtd = parser.getDTD();
            }
            catch (SAXException | IOException e)
            {
                LOG.warn("Unable to parse {}", _xml, e);
                throw e;
            }
        }
    }

    public boolean isParsed()
    {
        return _root != null;
    }

    public Path getPath()
    {
        return _xml;
    }

    public Resource getResource()
    {
        throw new UnsupportedOperationException("getResource() not supported");
    }

    public XmlParser.Node getRoot()
    {
        return _root;
    }

    @Override
    public String toString()
    {
        return this.getClass().getSimpleName() + "(" + _xml + ")";
    }
}
