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

import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;

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
        XmlParser xmlParser = new XmlParser(validating)
        {
            boolean mapped = false;

            @Override
            protected InputSource resolveEntity(String pid, String sid)
            {
                if (!mapped)
                {
                    mapResources();
                    mapped = true;
                }
                InputSource is = super.resolveEntity(pid, sid);
                return is;
            }

            void mapResources()
            {
                //set up cache of DTDs and schemas locally
                final URL dtd22 = Loader.getRequiredResource("jakarta/servlet/resources/web-app_2_2.dtd");
                final URL dtd23 = Loader.getRequiredResource("jakarta/servlet/resources/web-app_2_3.dtd");
                final URL j2ee14xsd = Loader.getRequiredResource("jakarta/servlet/resources/j2ee_1_4.xsd");
                final URL javaee5 = Loader.getRequiredResource("jakarta/servlet/resources/javaee_5.xsd");
                final URL javaee6 = Loader.getRequiredResource("jakarta/servlet/resources/javaee_6.xsd");
                final URL javaee7 = Loader.getRequiredResource("jakarta/servlet/resources/javaee_7.xsd");
                final URL javaee8 = Loader.getRequiredResource("jakarta/servlet/resources/javaee_8.xsd");
                final URL jakartaee9 = Loader.getRequiredResource("jakarta/servlet/resources/jakartaee_9.xsd");

                final URL webapp24xsd = Loader.getRequiredResource("jakarta/servlet/resources/web-app_2_4.xsd");
                final URL webapp25xsd = Loader.getRequiredResource("jakarta/servlet/resources/web-app_2_5.xsd");
                final URL webapp30xsd = Loader.getRequiredResource("jakarta/servlet/resources/web-app_3_0.xsd");
                final URL webapp31xsd = Loader.getRequiredResource("jakarta/servlet/resources/web-app_3_1.xsd");
                final URL webapp40xsd = Loader.getRequiredResource("jakarta/servlet/resources/web-app_4_0.xsd");
                final URL webapp50xsd = Loader.getRequiredResource("jakarta/servlet/resources/web-app_5_0.xsd");

                final URL webcommon30xsd = Loader.getRequiredResource("jakarta/servlet/resources/web-common_3_0.xsd");
                final URL webcommon31xsd = Loader.getRequiredResource("jakarta/servlet/resources/web-common_3_1.xsd");
                final URL webcommon40xsd = Loader.getRequiredResource("jakarta/servlet/resources/web-common_4_0.xsd");
                final URL webcommon50xsd = Loader.getRequiredResource("jakarta/servlet/resources/web-common_5_0.xsd");
                
                final URL webfragment30xsd = Loader.getRequiredResource("jakarta/servlet/resources/web-fragment_3_0.xsd");
                final URL webfragment31xsd = Loader.getRequiredResource("jakarta/servlet/resources/web-fragment_3_1.xsd");
                final URL webfragment40xsd = Loader.getRequiredResource("jakarta/servlet/resources/web-fragment_4_0.xsd");
                final URL webfragment50xsd = Loader.getRequiredResource("jakarta/servlet/resources/web-fragment_5_0.xsd");
                
                final URL webservice11xsd = Loader.getRequiredResource("jakarta/servlet/resources/j2ee_web_services_client_1_1.xsd");
                final URL webservice12xsd = Loader.getRequiredResource("jakarta/servlet/resources/javaee_web_services_client_1_2.xsd");
                final URL webservice13xsd = Loader.getRequiredResource("jakarta/servlet/resources/javaee_web_services_client_1_3.xsd");
                final URL webservice14xsd = Loader.getRequiredResource("jakarta/servlet/resources/javaee_web_services_client_1_4.xsd");
                final URL webservice20xsd = Loader.getRequiredResource("jakarta/servlet/resources/jakartaee_web_services_client_2_0.xsd");

                URL jsp20xsd = null;
                URL jsp21xsd = null;
                URL jsp22xsd = null;
                URL jsp23xsd = null;
                URL jsp30xsd = null;
                try
                {
                    //try both jakarta/servlet/resources and jakarta/servlet/jsp/resources to load 
                    jsp20xsd = Loader.getResource("jakarta/servlet/resources/jsp_2_0.xsd");
                    jsp21xsd = Loader.getResource("jakarta/servlet/resources/jsp_2_1.xsd");
                    jsp22xsd = Loader.getResource("jakarta/servlet/resources/jsp_2_2.xsd");
                    jsp23xsd = Loader.getResource("jakarta/servlet/resources/jsp_2_3.xsd");
                    jsp30xsd = Loader.getResource("jakarta/servlet/resources/jsp_3_0.xsd");
                }
                catch (Exception e)
                {
                    LOG.trace("IGNORED", e);
                }
                finally
                {
                    if (jsp20xsd == null)
                        jsp20xsd = Loader.getResource("jakarta/servlet/jsp/resources/jsp_2_0.xsd");
                    if (jsp21xsd == null)
                        jsp21xsd = Loader.getResource("jakarta/servlet/jsp/resources/jsp_2_1.xsd");
                    if (jsp22xsd == null)
                        jsp22xsd = Loader.getResource("jakarta/servlet/jsp/resources/jsp_2_2.xsd");
                    if (jsp23xsd == null)
                        jsp23xsd = Loader.getResource("jakarta/servlet/jsp/resources/jsp_2_3.xsd");
                    if (jsp30xsd == null)
                        jsp30xsd = Loader.getResource("jakarta/servlet/jsp/resources/jsp_3_0.xsd");
                }

                redirectEntity("jsp_2_0.xsd", jsp20xsd);
                redirectEntity("http://java.sun.com/xml/ns/j2ee/jsp_2_0.xsd", jsp20xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/jsp_2_1.xsd", jsp21xsd);
                redirectEntity("jsp_2_2.xsd", jsp22xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/jsp_2_2.xsd", jsp22xsd);
                redirectEntity("jsp_2_3.xsd", jsp23xsd);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/jsp_2_3.xsd", jsp23xsd);
                redirectEntity("jsp_3_0.xsd", jsp30xsd);
                redirectEntity("https://jakarta.ee/xml/ns/jakartaee/jsp_3_0.xsd", jsp30xsd);
                
                redirectEntity("j2ee_1_4.xsd", j2ee14xsd);
                redirectEntity("http://java.sun.com/xml/ns/j2ee/j2ee_1_4.xsd", j2ee14xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/javaee_5.xsd", javaee5);
                redirectEntity("http://java.sun.com/xml/ns/javaee/javaee_6.xsd", javaee6);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/javaee_7.xsd", javaee7);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/javaee_8.xsd", javaee8);
                redirectEntity("https://jakarta.ee/xml/ns/jakartaee/javaee_9.xsd", jakartaee9);

                redirectEntity("web-common_3_0.xsd", webcommon30xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/web-common_3_0.xsd", webcommon30xsd);
                redirectEntity("web-common_3_1.xsd", webcommon31xsd);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-common_3_1.xsd", webcommon31xsd);
                redirectEntity("web-common_4_0.xsd", webcommon40xsd);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-common_4_0.xsd", webcommon40xsd);
                redirectEntity("web-common_5_0.xsd", webcommon50xsd);
                redirectEntity("https://jakarta.ee/xml/ns/jakartaee/web-common_5_0.xsd", webcommon50xsd);
                
                redirectEntity("web-app_2_4.xsd", webapp24xsd);
                redirectEntity("http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd", webapp24xsd);
                redirectEntity("web-app_2_5.xsd", webapp25xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd", webapp25xsd);
                redirectEntity("web-app_3_0.xsd", webapp30xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd", webapp30xsd);
                redirectEntity("web-app_3_1.xsd", webapp31xsd);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-app_3_1.xsd", webapp31xsd);
                redirectEntity("web-app_4_0.xsd", webapp40xsd);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-app_4_0.xsd", webapp40xsd);
                redirectEntity("web-app_5_0.xsd", webapp50xsd);
                redirectEntity("https://jakarta.ee/xml/ns/jakartaee/web-app_5_0.xsd", webapp50xsd);
                
                // Handle linewrap hyphen error in PDF spec
                redirectEntity("webapp_4_0.xsd", webapp40xsd);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/webapp_4_0.xsd", webapp40xsd);
                redirectEntity("webapp_5_0.xsd", webapp50xsd);
                redirectEntity("https://jakarta.ee/xml/ns/jakartaee/webapp_5_0.xsd", webapp50xsd);
                
                // handle jakartaee coordinates
                redirectEntity("http://xmlns.eclipse.org/xml/ns/jakartaee/web-app_4_0.xsd", webapp40xsd);
                redirectEntity("https://jakarta.ee/xml/ns/jakartaee/web-app_5_0.xsd", webapp50xsd);
                
                redirectEntity("web-fragment_3_0.xsd", webfragment30xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/web-fragment_3_0.xsd", webfragment30xsd);
                redirectEntity("web-fragment_3_1.xsd", webfragment31xsd);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-fragment_3_1.xsd", webfragment31xsd);
                redirectEntity("web-fragment_4_0.xsd", webfragment40xsd);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-fragment_4_0.xsd", webfragment40xsd);
                redirectEntity("web-fragment_5_0.xsd", webfragment50xsd);
                redirectEntity("https://jakarta.ee/xml/ns/jakartaee/web-fragment_5_0.xsd", webfragment50xsd);
                
                redirectEntity("j2ee_web_services_client_1_1.xsd", webservice11xsd);
                redirectEntity("http://www.ibm.com/webservices/xsd/j2ee_web_services_client_1_1.xsd", webservice11xsd);
                redirectEntity("javaee_web_services_client_1_2.xsd", webservice12xsd);
                redirectEntity("http://www.ibm.com/webservices/xsd/javaee_web_services_client_1_2.xsd", webservice12xsd);
                redirectEntity("javaee_web_services_client_1_3.xsd", webservice13xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/javaee_web_services_client_1_3.xsd", webservice13xsd);
                redirectEntity("javaee_web_services_client_1_4.xsd", webservice14xsd);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/javaee_web_services_client_1_4.xsd", webservice14xsd);
                redirectEntity("jakartaee_web_services_client_2_0.xsd", webservice20xsd);
                redirectEntity("https://jakarta.ee/xml/ns/jakartaee/jakartaee_web_services_client_2_0.xsd", webservice20xsd);
            }
        };

        return xmlParser;
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
