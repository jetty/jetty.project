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

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import jakarta.servlet.Servlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Descriptor
 *
 * A web descriptor (web.xml/web-defaults.xml/web-overrides.xml).
 */
public class WebDescriptor extends Descriptor
{
    private static final Logger LOG = LoggerFactory.getLogger(WebDescriptor.class);

    public static XmlParser __nonValidatingStaticParser = newParser(false);
    protected MetaData.Complete _metaDataComplete;
    protected int _majorVersion = 4; //default to container version
    protected int _minorVersion = 0;
    protected ArrayList<String> _classNames = new ArrayList<>();
    protected boolean _distributable;
    protected boolean _isOrdered = false;
    protected List<String> _ordering = new ArrayList<>();

    /**
     * Check if the descriptor is metadata-complete.
     *
     * @param d the descriptor (web.xml, web-fragment.xml,
     * web-default.xml, web-override.xml) to check
     * @return true iff metadata-complete=true is declared in the
     * descriptor
     */
    public static boolean isMetaDataComplete(WebDescriptor d)
    {
        return (d != null && d.getMetaDataComplete() == MetaData.Complete.True);
    }

    /**
     * Get a parser for parsing web descriptor content.
     *
     * @param validating true if the parser should validate syntax, false otherwise
     * @return an XmlParser for web descriptors
     */
    public static XmlParser getParser(boolean validating)
    {
        if (!validating)
            return __nonValidatingStaticParser;
        else
            return newParser(true);
    }

    /**
     * Create a new parser for parsing web descriptors.
     *
     * @param validating if true, the parser will validate syntax
     * @return an XmlParser
     */
    public static XmlParser newParser(boolean validating)
    {
        try
        {
            return new WebDescriptorParser(validating);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Unable to instantiate WebDescriptorParser", e);
        }
    }

    private static class WebDescriptorParser extends XmlParser
    {
        public WebDescriptorParser(boolean validating) throws IOException
        {
            super(validating);
            String catalogName = "catalog-%s.xml".formatted(ServletContextHandler.__environment.getName());
            URL url = WebDescriptor.class.getResource(catalogName);
            if (url == null)
                throw new IllegalStateException("Catalog not found: %s/%s".formatted(WebDescriptor.class.getPackageName(), catalogName));
            addCatalog(URI.create(url.toExternalForm()), Servlet.class);
        }
    }

    public WebDescriptor(Resource xml)
    {
        super(xml);
    }

    @Override
    public void parse(XmlParser parser)
        throws Exception
    {
        super.parse(parser);
        processVersion();
        processOrdering();
        processDistributable();
    }

    public MetaData.Complete getMetaDataComplete()
    {
        return _metaDataComplete;
    }

    public int getMajorVersion()
    {
        return _majorVersion;
    }

    public int getMinorVersion()
    {
        return _minorVersion;
    }

    public void processVersion()
    {
        String version = _root.getAttribute("version", "DTD");
        if ("DTD".equals(version))
        {
            _majorVersion = 2;
            _minorVersion = 3;

            if (_dtd != null && _dtd.indexOf("web-app_2_2") >= 0)
            {
                _majorVersion = 2;
                _minorVersion = 2;
            }
        }
        else
        {
            int dot = version.indexOf(".");
            if (dot > 0)
            {
                _majorVersion = Integer.parseInt(version.substring(0, dot));
                _minorVersion = Integer.parseInt(version.substring(dot + 1));
            }
        }

        if (_majorVersion <= 2 && _minorVersion < 5)
            _metaDataComplete = MetaData.Complete.True; // does not apply before 2.5
        else
        {
            String s = (String)_root.getAttribute("metadata-complete");
            if (s == null)
                _metaDataComplete = MetaData.Complete.NotSet;
            else
                _metaDataComplete = Boolean.valueOf(s).booleanValue() ? MetaData.Complete.True : MetaData.Complete.False;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{}: Calculated metadatacomplete = {} with version = {}", _xml.toString(), _metaDataComplete, version);
    }

    public void processOrdering()
    {
        //Process the web.xml's optional <absolute-ordering> element
        XmlParser.Node ordering = _root.get("absolute-ordering");
        if (ordering == null)
            return;

        _isOrdered = true;
        //If an absolute-ordering was already set, then ignore it in favor of this new one
        // _processor.setOrdering(new AbsoluteOrdering());

        Iterator<Object> iter = ordering.iterator();
        XmlParser.Node node = null;
        while (iter.hasNext())
        {
            Object o = iter.next();
            if (!(o instanceof XmlParser.Node))
                continue;
            node = (XmlParser.Node)o;

            if (node.getTag().equalsIgnoreCase("others"))
                //((AbsoluteOrdering)_processor.getOrdering()).addOthers();
                _ordering.add("others");
            else if (node.getTag().equalsIgnoreCase("name"))
                //((AbsoluteOrdering)_processor.getOrdering()).add(node.toString(false,true));
                _ordering.add(node.toString(false, true));
        }
    }

    public void processDistributable()
    {
        XmlParser.Node distributable = _root.get("distributable");
        if (distributable == null)
            return; //no <distributable> element
        _distributable = true;
    }

    public void addClassName(String className)
    {
        if (!_classNames.contains(className))
            _classNames.add(className);
    }

    public ArrayList<String> getClassNames()
    {
        return _classNames;
    }

    public boolean isDistributable()
    {
        return _distributable;
    }

    public boolean isOrdered()
    {
        return _isOrdered;
    }

    public List<String> getOrdering()
    {
        return _ordering;
    }
}
