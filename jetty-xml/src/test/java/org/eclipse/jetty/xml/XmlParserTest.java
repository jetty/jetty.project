//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.xml;

import java.net.URL;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class XmlParserTest
{
    @Test
    public void testXmlParser() throws Exception
    {
        XmlParser parser = new XmlParser();

        URL configURL = XmlConfiguration.class.getResource("configure_10_0.dtd");
        parser.redirectEntity("configure_10_0.dtd", configURL);
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
    public void testNewSAXParserFactory()
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
        // look to see it was set at parser level
        assertFalse(saxParser.isNamespaceAware());
    }
}
