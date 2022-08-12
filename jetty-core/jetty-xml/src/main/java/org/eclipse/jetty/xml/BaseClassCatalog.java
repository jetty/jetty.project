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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import javax.xml.catalog.Catalog;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jetty.util.TypeUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * <p>
 * A catalog implementation where the {@code xml:base} is defined externally
 * of the catalog XML, allowing for runtime determination of the {@code xml:base}
 * (such as pointing to the contents of a remote location)
 * </p>
 * <p>
 * This is a temporary Catalog implementation, and should be removed once
 * all of our usages of {@code servlet-api-<ver>.jar} have their own
 * {@code catalog.xml} files.
 * </p>
 */
public class BaseClassCatalog implements Catalog, EntityResolver
{
    public static BaseClassCatalog load(URI uriToCatalogXml, Class<?> baseClass) throws IOException
    {
        return new CatalogReader(uriToCatalogXml, baseClass).parse();
    }

    private static class CatalogReader
    {
        private final URI catalogUri;
        private final Class<?> baseClass;

        public CatalogReader(URI uriToCatalogXml, Class<?> baseClass)
        {
            this.catalogUri = Objects.requireNonNull(uriToCatalogXml, "Catalog XML");
            this.baseClass = Objects.requireNonNull(baseClass, "Base Class");
        }

        public BaseClassCatalog parse() throws IOException
        {
            try (InputStream in = catalogUri.toURL().openStream())
            {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(true);
                dbf.setValidating(false);
                DocumentBuilder db = dbf.newDocumentBuilder();
                dbf.setFeature("http://apache.org/xml/features/validation/schema", false);
                dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                InputSource input = new InputSource(in);
                Document doc = db.parse(input);

                Element root = doc.getDocumentElement();
                Map<String, String> publicIdMap = getMapping(root, "public", "publicId");
                Map<String, String> systemIdMap = getMapping(root, "system", "systemId");
                return new BaseClassCatalog(publicIdMap, systemIdMap);
            }
            catch (ParserConfigurationException | SAXException e)
            {
                throw new IOException("Unable to parse: " + catalogUri, e);
            }
        }

        private Map<String, String> getMapping(Element root, String elementName, String attrIdName) throws IOException
        {
            Map<String, String> mapping = new HashMap<>();
            NodeList nodeList = root.getElementsByTagNameNS("*", elementName);
            for (int i = 0; i < nodeList.getLength(); i++)
            {
                Element elem = (Element)nodeList.item(i);
                String id = elem.getAttribute(attrIdName);
                String ref = elem.getAttribute("uri");
                try
                {
                    if (new URI(ref).isAbsolute())
                        mapping.put(id, ref);
                    else
                    {
                        URL url = baseClass.getResource(ref);
                        if (url == null)
                            throw new FileNotFoundException("Unable to find ref [%s/%s] in same archive as %s: %s"
                                .formatted(baseClass.getPackageName().replace('.', '/'),
                                    ref, baseClass.getName(),
                                    TypeUtil.getLocationOfClass(baseClass)));
                        mapping.put(id, url.toExternalForm());
                    }
                }
                catch (URISyntaxException e)
                {
                    throw new IOException("Unable to parse %s - bad URI in: %s".formatted(catalogUri, elem), e);
                }
            }
            return mapping;
        }
    }

    private final Map<String, String> publicIdMap;
    private final Map<String, String> systemIdMap;

    private BaseClassCatalog(Map<String, String> publicIdMap, Map<String, String> systemIdMap)
    {
        this.publicIdMap = Objects.requireNonNull(publicIdMap, "Public ID Map");
        this.systemIdMap = Objects.requireNonNull(systemIdMap, "System ID Map");
    }

    @Override
    public Stream<Catalog> catalogs()
    {
        // empty, we have no alternative catalogs
        return Stream.of();
    }

    @Override
    public String matchPublic(String publicId)
    {
        return publicIdMap.get(publicId);
    }

    @Override
    public String matchSystem(String systemId)
    {
        return systemIdMap.get(systemId);
    }

    @Override
    public String matchURI(String uri)
    {
        for (Map.Entry<String, String> entry : publicIdMap.entrySet())
        {
            if (entry.getValue().equals(uri))
                return entry.getKey();
        }
        for (Map.Entry<String, String> entry : systemIdMap.entrySet())
        {
            if (entry.getValue().equals(uri))
                return entry.getKey();
        }
        return null;
    }

    /**
     * Implementation of {@link org.xml.sax.EntityResolver}
     *
     * @see org.xml.sax.EntityResolver#resolveEntity(String, String)
     */
    @Override
    public InputSource resolveEntity(String publicId, String systemId)
    {
        String resolvedSystemId = null;

        if (systemId != null)
        {
            // DTD's need to be searched in a simple filename only form to maintain
            // backward compat with older DTD spec in regard to SYSTEM identifiers.
            if (systemId.toLowerCase(Locale.ENGLISH).endsWith(".dtd"))
            {
                int idx = systemId.lastIndexOf('/');
                if (idx >= 0)
                {
                    resolvedSystemId = matchSystem(systemId.substring(idx + 1));
                }
            }

            // Search full systemId
            if (resolvedSystemId == null)
                resolvedSystemId = matchSystem(systemId);
        }

        if (resolvedSystemId == null && publicId != null)
        {
            resolvedSystemId = matchPublic(publicId);
        }

        if (resolvedSystemId == null && systemId != null)
        {
            resolvedSystemId = matchURI(systemId);
        }

        if (resolvedSystemId != null)
        {
            return new InputSource(resolvedSystemId);
        }

        return null;
    }
}
