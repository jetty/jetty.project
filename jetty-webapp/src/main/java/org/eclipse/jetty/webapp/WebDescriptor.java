//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.webapp;

import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlParser;
import org.xml.sax.InputSource;

/**
 * Descriptor
 *
 * A web descriptor (web.xml/web-defaults.xml/web-overrides.xml).
 */
public class WebDescriptor extends Descriptor
{
    private static final Logger LOG = Log.getLogger(WebDescriptor.class);

    protected static XmlParser _nonValidatingStaticParser;
    protected MetaDataComplete _metaDataComplete;
    protected int _majorVersion = 3; //default to container version
    protected int _minorVersion = 0;
    protected ArrayList<String> _classNames = new ArrayList<String>();
    protected boolean _distributable;

    protected boolean _isOrdered = false;
    protected List<String> _ordering = new ArrayList<String>();

    @Override
    public XmlParser ensureParser() throws ClassNotFoundException
    {
        synchronized (WebDescriptor.class)
        {
            if (_nonValidatingStaticParser == null)
                _nonValidatingStaticParser = newParser(false);
        }

        if (!isValidating())
            return _nonValidatingStaticParser;
        else
            return newParser(true);
    }

    public static XmlParser newParser(boolean validating) throws ClassNotFoundException
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
                URL dtd22 = Loader.getResource("javax/servlet/resources/web-app_2_2.dtd");
                URL dtd23 = Loader.getResource("javax/servlet/resources/web-app_2_3.dtd");
                URL j2ee14xsd = Loader.getResource("javax/servlet/resources/j2ee_1_4.xsd");
                URL javaee5 = Loader.getResource("javax/servlet/resources/javaee_5.xsd");
                URL javaee6 = Loader.getResource("javax/servlet/resources/javaee_6.xsd");
                URL javaee7 = Loader.getResource("javax/servlet/resources/javaee_7.xsd");

                URL webapp24xsd = Loader.getResource("javax/servlet/resources/web-app_2_4.xsd");
                URL webapp25xsd = Loader.getResource("javax/servlet/resources/web-app_2_5.xsd");
                URL webapp30xsd = Loader.getResource("javax/servlet/resources/web-app_3_0.xsd");
                URL webapp31xsd = Loader.getResource("javax/servlet/resources/web-app_3_1.xsd");

                URL webcommon30xsd = Loader.getResource("javax/servlet/resources/web-common_3_0.xsd");
                URL webcommon31xsd = Loader.getResource("javax/servlet/resources/web-common_3_1.xsd");

                URL webfragment30xsd = Loader.getResource("javax/servlet/resources/web-fragment_3_0.xsd");
                URL webfragment31xsd = Loader.getResource("javax/servlet/resources/web-fragment_3_1.xsd");

                URL schemadtd = Loader.getResource("javax/servlet/resources/XMLSchema.dtd");
                URL xmlxsd = Loader.getResource("javax/servlet/resources/xml.xsd");
                URL webservice11xsd = Loader.getResource("javax/servlet/resources/j2ee_web_services_client_1_1.xsd");
                URL webservice12xsd = Loader.getResource("javax/servlet/resources/javaee_web_services_client_1_2.xsd");
                URL webservice13xsd = Loader.getResource("javax/servlet/resources/javaee_web_services_client_1_3.xsd");
                URL webservice14xsd = Loader.getResource("javax/servlet/resources/javaee_web_services_client_1_4.xsd");
                URL datatypesdtd = Loader.getResource("javax/servlet/resources/datatypes.dtd");

                URL jsp20xsd = null;
                URL jsp21xsd = null;
                URL jsp22xsd = null;
                URL jsp23xsd = null;

                try
                {
                    //try both javax/servlet/resources and javax/servlet/jsp/resources to load 
                    jsp20xsd = Loader.getResource("javax/servlet/resources/jsp_2_0.xsd");
                    jsp21xsd = Loader.getResource("javax/servlet/resources/jsp_2_1.xsd");
                    jsp22xsd = Loader.getResource("javax/servlet/resources/jsp_2_2.xsd");
                    jsp23xsd = Loader.getResource("javax/servlet/resources/jsp_2_3.xsd");
                }
                catch (Exception e)
                {
                    LOG.ignore(e);
                }
                finally
                {
                    if (jsp20xsd == null)
                        jsp20xsd = Loader.getResource("javax/servlet/jsp/resources/jsp_2_0.xsd");
                    if (jsp21xsd == null)
                        jsp21xsd = Loader.getResource("javax/servlet/jsp/resources/jsp_2_1.xsd");
                    if (jsp22xsd == null)
                        jsp22xsd = Loader.getResource("javax/servlet/jsp/resources/jsp_2_2.xsd");
                    if (jsp23xsd == null)
                        jsp23xsd = Loader.getResource("javax/servlet/jsp/resources/jsp_2_3.xsd");
                }

                redirectEntity("web-app_2_2.dtd", dtd22);
                redirectEntity("-//Sun Microsystems, Inc.//DTD Web Application 2.2//EN", dtd22);
                redirectEntity("web.dtd", dtd23);
                redirectEntity("web-app_2_3.dtd", dtd23);
                redirectEntity("-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN", dtd23);
                redirectEntity("XMLSchema.dtd", schemadtd);
                redirectEntity("http://www.w3.org/2001/XMLSchema.dtd", schemadtd);
                redirectEntity("-//W3C//DTD XMLSCHEMA 200102//EN", schemadtd);

                redirectEntity("jsp_2_0.xsd", jsp20xsd);
                redirectEntity("http://java.sun.com/xml/ns/j2ee/jsp_2_0.xsd", jsp20xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/jsp_2_1.xsd", jsp21xsd);
                redirectEntity("jsp_2_2.xsd", jsp22xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/jsp_2_2.xsd", jsp22xsd);
                redirectEntity("jsp_2_3.xsd", jsp23xsd);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/jsp_2_3.xsd", jsp23xsd);

                redirectEntity("j2ee_1_4.xsd", j2ee14xsd);
                redirectEntity("http://java.sun.com/xml/ns/j2ee/j2ee_1_4.xsd", j2ee14xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/javaee_5.xsd", javaee5);
                redirectEntity("http://java.sun.com/xml/ns/javaee/javaee_6.xsd", javaee6);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/javaee_7.xsd", javaee7);

                redirectEntity("web-common_3_0.xsd", webcommon30xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/web-common_3_0.xsd", webcommon30xsd);
                redirectEntity("web-common_3_1.xsd", webcommon31xsd);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-common_3_1.xsd", webcommon31xsd);

                redirectEntity("web-app_2_4.xsd", webapp24xsd);
                redirectEntity("http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd", webapp24xsd);
                redirectEntity("web-app_2_5.xsd", webapp25xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd", webapp25xsd);
                redirectEntity("web-app_3_0.xsd", webapp30xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd", webapp30xsd);
                redirectEntity("web-app_3_1.xsd", webapp31xsd);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-app_3_1.xsd", webapp31xsd);

                redirectEntity("web-fragment_3_0.xsd", webfragment30xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/web-fragment_3_0.xsd", webfragment30xsd);
                redirectEntity("web-fragment_3_1.xsd", webfragment31xsd);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-fragment_3_1.xsd", webfragment31xsd);

                redirectEntity("xml.xsd", xmlxsd);
                redirectEntity("http://www.w3.org/2001/xml.xsd", xmlxsd);
                redirectEntity("datatypes.dtd", datatypesdtd);
                redirectEntity("http://www.w3.org/2001/datatypes.dtd", datatypesdtd);

                redirectEntity("j2ee_web_services_client_1_1.xsd", webservice11xsd);
                redirectEntity("http://www.ibm.com/webservices/xsd/j2ee_web_services_client_1_1.xsd", webservice11xsd);
                redirectEntity("javaee_web_services_client_1_2.xsd", webservice12xsd);
                redirectEntity("http://www.ibm.com/webservices/xsd/javaee_web_services_client_1_2.xsd", webservice12xsd);
                redirectEntity("javaee_web_services_client_1_3.xsd", webservice13xsd);
                redirectEntity("http://java.sun.com/xml/ns/javaee/javaee_web_services_client_1_3.xsd", webservice13xsd);
                redirectEntity("javaee_web_services_client_1_4.xsd", webservice14xsd);
                redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/javaee_web_services_client_1_4.xsd", webservice14xsd);
            }
        };

        return xmlParser;
    }

    public WebDescriptor(Resource xml)
    {
        super(xml);
    }

    @Override
    public void parse()
        throws Exception
    {
        super.parse();
        processVersion();
        processOrdering();
    }

    public MetaDataComplete getMetaDataComplete()
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
            _metaDataComplete = MetaDataComplete.True; // does not apply before 2.5
        else
        {
            String s = _root.getAttribute("metadata-complete");
            if (s == null)
                _metaDataComplete = MetaDataComplete.NotSet;
            else
                _metaDataComplete = Boolean.valueOf(s).booleanValue() ? MetaDataComplete.True : MetaDataComplete.False;
        }

        if (LOG.isDebugEnabled())
            LOG.debug(_xml.toString() + ": Calculated metadatacomplete = " + _metaDataComplete + " with version=" + version);
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

    public void addClassName(String className)
    {
        if (!_classNames.contains(className))
            _classNames.add(className);
    }

    public ArrayList<String> getClassNames()
    {
        return _classNames;
    }

    public void setDistributable(boolean distributable)
    {
        _distributable = distributable;
    }

    public boolean isDistributable()
    {
        return _distributable;
    }

    @Override
    public void setValidating(boolean validating)
    {
        _validating = validating;
    }

    public boolean isValidating()
    {
        return _validating;
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
