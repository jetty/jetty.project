//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.xml;

import java.net.URL;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class XmlParserTest
{
    @Test
    public void testXmlParser() throws Exception
    {
        XmlParser parser = new XmlParser();

        URL configURL = XmlConfiguration.class.getClassLoader().getResource("org/eclipse/jetty/xml/configure_9_3.dtd");
        parser.redirectEntity("configure.dtd", configURL);
        parser.redirectEntity("configure_9_3.dtd", configURL);
        parser.redirectEntity("http://www.eclipse.org/jetty/configure_9_3.dtd", configURL);
        parser.redirectEntity("http://jetty.eclipse.org/configure.dtd", configURL);
        parser.redirectEntity("-//Mort Bay Consulting//DTD Configure//EN", configURL);

        URL url = XmlParserTest.class.getClassLoader().getResource("org/eclipse/jetty/xml/configureWithAttr.xml");
        XmlParser.Node testDoc = parser.parse(url.toString());
        String testDocStr = testDoc.toString().trim();

        assertTrue(testDocStr.startsWith("<Configure"));
        assertTrue(testDocStr.endsWith("</Configure>"));
    }

    /**
     * Customize SAXParserFactory behavior.
     */
    @Test
    public void testNewSAXParserFactory() throws SAXException
    {
        XmlParser xmlParser = new XmlParser()
        {
            @Override
            protected SAXParserFactory newSAXParserFactory()
            {
                SAXParserFactory saxParserFactory = super.newSAXParserFactory();
                // Configure at factory level
                saxParserFactory.setXIncludeAware(false);
                return saxParserFactory;
            }
        };

        SAXParser saxParser = xmlParser.getSAXParser();
        assertNotNull(saxParser);

        XMLReader xmlReader = saxParser.getXMLReader();
        // Only run testcase if Xerces is being used.
        assumeTrue(xmlReader.getClass().getName().contains("org.apache.xerces."));
        // look to see it was set at XMLReader level
        assertFalse(xmlReader.getFeature("http://apache.org/xml/features/xinclude"));
    }
}
