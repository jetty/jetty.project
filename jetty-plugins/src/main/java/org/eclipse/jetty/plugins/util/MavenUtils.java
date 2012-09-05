//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
// ========================================================================
//
package org.eclipse.jetty.plugins.util;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

public class MavenUtils
{
    private final static String DEFAULT_REPOSITORY_LOCATION = System.getProperty("user.home") + "/.m2/repository/";

    /**
     * Looks for maven's settings.xml in $M2_HOME/conf/settings.xml
     *
     * @return a file representing the global settings.xml
     */
    static File findGlobalSettingsXml()
    {
        String m2Home = System.getenv("M2_HOME");
        return new File(m2Home + "/conf/settings.xml");
    }

    /**
     * Looks for maven's settings.xml in ${user.home}/.m2/settings.xml
     *
     * @return a file representing the user's settings.xml
     */
    static File findUserSettingsXml()
    {
        String userHome = System.getProperty("user.home");
        return new File(userHome + "/.m2/settings.xml");
    }

    /**
     * Read the local repository location from settings.xml. A setting in the user's settings.xml will override the
     * global one.
     *
     * @return location of the local maven repo
     */
    public static String getLocalRepositoryLocation()
    {
        String repositoryLocation = DEFAULT_REPOSITORY_LOCATION;
        try
        {
            // find the global setting
            String tempRepositoryLocation = parseSettingsXml(findGlobalSettingsXml());
            if (tempRepositoryLocation != null)
                repositoryLocation = tempRepositoryLocation;

            // override with user settings.xml
            tempRepositoryLocation = parseSettingsXml(findUserSettingsXml());
            if (tempRepositoryLocation != null)
                repositoryLocation = tempRepositoryLocation;
        }
        catch (IOException | SAXException e)
        {
            throw new IllegalStateException(e);
        }
        return repositoryLocation;
    }

    private static String parseSettingsXml(File settingsXml) throws IOException, SAXException
    {
        if(!settingsXml.exists())
            return null;

        // readability is more important than efficiency here, so we just recreate those objects
        XMLReader reader = XMLReaderFactory.createXMLReader();
        SettingsXmlContentHandler settingsXmlContentHandler = new SettingsXmlContentHandler();
        reader.setContentHandler(settingsXmlContentHandler);

        InputSource source = new InputSource(new FileReader(settingsXml));
        reader.parse(source);
        return settingsXmlContentHandler.getRepositoryLocation();
    }

    private static class SettingsXmlContentHandler implements ContentHandler
    {
        private String repositoryLocation;
        private String currentValue;

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException
        {
            currentValue = new String(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException
        {
            if (localName.equals("localRepository"))
            {
                repositoryLocation = currentValue;
            }
        }

        public String getRepositoryLocation()
        {
            return repositoryLocation;
        }

        @Override
        public void setDocumentLocator(Locator locator)
        {
        }

        @Override
        public void startDocument() throws SAXException
        {
        }

        @Override
        public void endDocument() throws SAXException
        {
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException
        {
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException
        {
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException
        {
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException
        {
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException
        {
        }

        @Override
        public void skippedEntity(String name) throws SAXException
        {
        }
    }
}
