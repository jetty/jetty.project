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

package org.eclipse.jetty.ee10.webapp;

import java.util.Objects;

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlParser;

public abstract class Descriptor
{
    protected Resource _xml;
    protected XmlParser.Node _root;
    protected String _dtd;

    public Descriptor(Resource xml)
    {
        _xml = Objects.requireNonNull(xml);
    }

    public void parse(XmlParser parser)
        throws Exception
    {

        if (_root == null)
        {
            Objects.requireNonNull(parser);
            try
            {
                _root = parser.parse(_xml.getInputStream());
                _dtd = parser.getDTD();
            }
            finally
            {
                _xml.close();
            }
        }
    }

    public boolean isParsed()
    {
        return _root != null;
    }

    public Resource getResource()
    {
        return _xml;
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
