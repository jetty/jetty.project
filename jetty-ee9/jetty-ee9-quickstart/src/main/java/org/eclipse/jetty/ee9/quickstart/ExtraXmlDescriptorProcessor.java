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

package org.eclipse.jetty.ee9.quickstart;

import org.eclipse.jetty.ee9.webapp.Descriptor;
import org.eclipse.jetty.ee9.webapp.IterativeDescriptorProcessor;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.xml.XmlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ExtraXmlDescriptorProcessor
 *
 * Saves literal XML snippets from web.xml.
 */

public class ExtraXmlDescriptorProcessor extends IterativeDescriptorProcessor
{
    private static final Logger LOG = LoggerFactory.getLogger(ExtraXmlDescriptorProcessor.class);

    private final StringBuilder _buffer = new StringBuilder();
    private String _originAttribute;
    private String _origin;

    public ExtraXmlDescriptorProcessor()
    {
        try
        {
            registerVisitor("env-entry", getClass().getMethod("saveSnippet", __signature));
            registerVisitor("resource-ref", getClass().getMethod("saveSnippet", __signature));
            registerVisitor("resource-env-ref", getClass().getMethod("saveSnippet", __signature));
            registerVisitor("message-destination-ref", getClass().getMethod("saveSnippet", __signature));
            registerVisitor("data-source", getClass().getMethod("saveSnippet", __signature));
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void start(WebAppContext context, Descriptor descriptor)
    {
        LOG.debug("process {}", descriptor);
        _origin = (StringUtil.isBlank(_originAttribute) ? null : "  <!-- " + descriptor.getURI() + " -->\n");
    }

    @Override
    public void end(WebAppContext context, Descriptor descriptor)
    {
    }

    public void setOriginAttribute(String name)
    {
        _originAttribute = name;
    }
    
    public void saveSnippet(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
        throws Exception
    {
        //Note: we have to output the origin as a comment field instead of
        //as an attribute like the other other elements because
        //we are copying these elements _verbatim_ from the descriptor
        LOG.debug("save {}", node.getTag());
        if (_origin != null)
            _buffer.append(_origin);
        _buffer.append("  ").append(node.toString()).append("\n");
    }

    public String getXML()
    {
        return _buffer.toString();
    }
}
