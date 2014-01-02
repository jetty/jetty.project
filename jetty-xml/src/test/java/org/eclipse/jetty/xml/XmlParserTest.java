//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertTrue;

import java.net.URL;

import org.junit.Test;

public class XmlParserTest
{
    @Test
    public void testXmlParser() throws Exception
    {
        XmlParser parser = new XmlParser();

        URL configURL = XmlConfiguration.class.getClassLoader().getResource("org/eclipse/jetty/xml/configure_6_0.dtd");
        parser.redirectEntity("configure.dtd", configURL);
        parser.redirectEntity("configure_1_3.dtd", configURL);
        parser.redirectEntity("http://jetty.eclipse.org/configure.dtd", configURL);
        parser.redirectEntity("-//Mort Bay Consulting//DTD Configure//EN", configURL);
        parser.redirectEntity("http://jetty.eclipse.org/configure_1_3.dtd", configURL);
        parser.redirectEntity("-//Mort Bay Consulting//DTD Configure 1.3//EN", configURL);
        parser.redirectEntity("configure_1_2.dtd", configURL);
        parser.redirectEntity("http://jetty.eclipse.org/configure_1_2.dtd", configURL);
        parser.redirectEntity("-//Mort Bay Consulting//DTD Configure 1.2//EN", configURL);
        parser.redirectEntity("configure_1_1.dtd", configURL);
        parser.redirectEntity("http://jetty.eclipse.org/configure_1_1.dtd", configURL);
        parser.redirectEntity("-//Mort Bay Consulting//DTD Configure 1.1//EN", configURL);
        parser.redirectEntity("configure_1_0.dtd", configURL);
        parser.redirectEntity("http://jetty.eclipse.org/configure_1_0.dtd", configURL);
        parser.redirectEntity("-//Mort Bay Consulting//DTD Configure 1.0//EN", configURL);

        URL url = XmlParserTest.class.getClassLoader().getResource("org/eclipse/jetty/xml/configure.xml");
        XmlParser.Node testDoc = parser.parse(url.toString());
        String testDocStr = testDoc.toString().trim();

        assertTrue(testDocStr.startsWith("<Configure"));
        assertTrue(testDocStr.endsWith("</Configure>"));
    }

}
