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

import java.net.URISyntaxException;
import java.net.URL;
import javax.xml.catalog.Catalog;
import javax.xml.catalog.CatalogFeatures;
import javax.xml.catalog.CatalogManager;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class CatalogTest
{
    @Test
    public void loadCatalogOrgW3() throws URISyntaxException
    {
        URL url = XmlParser.class.getResource("catalog-org.w3.xml");
        assertNotNull(url, "Catalog not found: catalog-org.w3.xml");

        Catalog catalog = CatalogManager.catalog(CatalogFeatures.builder().build(), url.toURI());
        assertNotNull(catalog, "Catalog should have been loaded");

        // Show that even a short hand usage in catalog results in full URL to resource
        String result = catalog.matchPublic("datatypes");
        assertThat(result, startsWith("file:/"));
        assertThat(result, endsWith("org/eclipse/jetty/xml/datatypes.dtd"));

        // Show that even a URI references results in full URL to resource
        result = catalog.matchSystem("https://www.w3.org/2001/XMLSchema.dtd");
        assertThat(result, startsWith("file:/"));
        assertThat(result, endsWith("org/eclipse/jetty/xml/XMLSchema.dtd"));
    }
}
