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

package org.eclipse.jetty.xml;

import java.net.URL;
import java.nio.file.Path;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class XmlParserTest
{
    @Test
    public void testXmlParser() throws Exception
    {
        // we want to parse a simple XML, no dtds, no xsds.
        // just do it, without validation
        XmlParser parser = new XmlParser(false);
        URL url = XmlParserTest.class.getResource("configureSimple.xml");
        assertNotNull(url);
        XmlParser.Node testDoc = parser.parse(url.toString());
        String testDocStr = testDoc.toString().trim();

        assertTrue(testDocStr.startsWith("<Configure"));
        assertTrue(testDocStr.endsWith("</Configure>"));
    }

    @Test
    public void testAddCatalogSimple() throws Exception
    {
        XmlParser parser = new XmlParser(true);
        URL catalogUrl = XmlParser.class.getResource("catalog-configure.xml");
        assertNotNull(catalogUrl);
        parser.addCatalog(catalogUrl.toURI());

        URL xmlUrl = XmlParserTest.class.getResource("configureWithAttr.xml");
        assertNotNull(xmlUrl);
        XmlParser.Node testDoc = parser.parse(xmlUrl.toString());
        String testDocStr = testDoc.toString().trim();

        assertTrue(testDocStr.startsWith("<Configure"));
        assertTrue(testDocStr.endsWith("</Configure>"));
    }

    @Test
    public void testAddCatalogOverrideBaseUri() throws Exception
    {
        XmlParser parser = new XmlParser(true);
        ClassLoader classLoader = XmlParser.class.getClassLoader();
        URL catalogUrl = classLoader.getResource("org/eclipse/jetty/xml/deep/catalog-test.xml");
        assertNotNull(catalogUrl);

        parser.addCatalog(catalogUrl.toURI(), XmlParserTest.class);

        Path testXml = MavenTestingUtils.getTestResourcePathFile("xmls/test.xml");
        XmlParser.Node testDoc = parser.parse(testXml.toUri().toString());
        String testDocStr = testDoc.toString().trim();

        assertTrue(testDocStr.startsWith("<test"));
        assertTrue(testDocStr.endsWith("</test>"));
    }
}
