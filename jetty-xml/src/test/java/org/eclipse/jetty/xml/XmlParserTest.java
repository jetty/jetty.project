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

package org.eclipse.jetty.xml;

import java.net.URL;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class XmlParserTest
{
    @Test
    public void testXmlParser() throws Exception
    {
        XmlParser parser = new XmlParser();

        URL configURL = XmlConfiguration.class.getClassLoader().getResource("org/eclipse/jetty/xml/configure_9_3.dtd");
        parser.redirectEntity("configure.dtd", configURL);
        parser.redirectEntity("configure_9_3.dtd", configURL);
        //parser.redirectEntity("http://www.eclipse.org/jetty/configure_9_3.dtd", configURL);
        parser.redirectEntity("http://jetty.eclipse.org/configure.dtd", configURL);
        parser.redirectEntity("-//Mort Bay Consulting//DTD Configure//EN", configURL);

        URL url = XmlParserTest.class.getClassLoader().getResource("org/eclipse/jetty/xml/configureWithAttr.xml");
        XmlParser.Node testDoc = parser.parse(url.toString());
        String testDocStr = testDoc.toString().trim();

        assertTrue(testDocStr.startsWith("<Configure"));
        assertTrue(testDocStr.endsWith("</Configure>"));
    }
}
