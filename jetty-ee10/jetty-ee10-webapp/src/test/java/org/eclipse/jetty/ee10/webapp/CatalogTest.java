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

package org.eclipse.jetty.ee10.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.xml.catalog.Catalog;
import javax.xml.catalog.CatalogFeatures;
import javax.xml.catalog.CatalogManager;

import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sanity check for the Servlet descriptor XML catalog.
 */
public class CatalogTest
{
    private static Catalog catalog;

    @BeforeAll
    public static void loadCatalog() throws URISyntaxException
    {
        URL url = WebDescriptor.class.getResource("catalog.xml");
        assertNotNull(url, "Catalog not found: catalog.xml");

        catalog = CatalogManager.catalog(CatalogFeatures.builder().build(), url.toURI());
        assertNotNull(catalog, "Catalog should have been loaded");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // To generate this list from command line.
        // $ xmllint --xpath "//@publicId" catalog.xml  | sed -s "s/publicId=\(.*\)/\1, /"

        // 2.2
        "web-app_2_2.dtd",
        "-//Sun Microsystems, Inc.//DTD Web Application 2.2//EN",

        // 2.3
        "web-app_2_3.dtd",
        "web.dtd",
        "-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN",

        // 2.4
        "j2ee_1_4.xsd",
        "web-app_2_4.xsd",
        "j2ee_web_services_client_1_1.xsd",

        // 2.5
        "javaee_5.xsd",
        "web-app_2_5.xsd",
        "javaee_web_services_client_1_2.xsd",

        // 3.0
        "javaee_6.xsd",
        "web-app_3_0.xsd",
        "web-common_3_0.xsd",
        "web-fragment_3_0.xsd",
        "javaee_web_services_client_1_3.xsd",

        // 3.1
        "javaee_7.xsd",
        "web-app_3_1.xsd",
        "web-common_3_1.xsd",
        "web-fragment_3_1.xsd",
        "javaee_8.xsd",

        // 4.0
        "web-app_4_0.xsd",
        "web-common_4_0.xsd",
        "web-fragment_4_0.xsd",
        "javaee_web_services_client_1_4.xsd",

        // 5.0
        "jakartaee_9.xsd",
        "web-app_5_0.xsd",
        "webapp_5_0.xsd",
        "web-common_5_0.xsd",
        "web-fragment_5_0.xsd",
        "jakartaee_web_services_client_2_0.xsd",

        // 6.0
        "web-app_6_0.xsd",
        "webapp_6_0.xsd",
        "web-common_6_0.xsd",
        "web-fragment_6_0.xsd",
        "jakartaee_web_services_client_2_0.xsd",
    })
    public void ensurePublicIdExists(String publicId) throws IOException
    {
        String result = catalog.matchPublic(publicId);
        assertNotNull(result);
        URL url = new URL(result);
        try (InputStream in = url.openStream();
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             StringWriter writer = new StringWriter())
        {
            IO.copy(reader, writer);
            String contents = writer.toString();
            assertNotNull(contents);
            assertThat("URL [%s] has contents".formatted(url), contents.trim().length(), greaterThan(1000));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // To generate this list from command line.
        // $ xmllint --xpath "//@systemId" catalog.xml  | sed -s "s/systemId=\(.*\)/\1, /"

        // 2.4
        "http://java.sun.com/xml/ns/j2ee/j2ee_1_4.xsd",
        "https://java.sun.com/xml/ns/j2ee/j2ee_1_4.xsd",
        "http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd",
        "https://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd",
        "http://www.ibm.com/webservices/xsd/j2ee_web_services_client_1_1.xsd",
        "https://www.ibm.com/webservices/xsd/j2ee_web_services_client_1_1.xsd",

        // 2.5
        "http://java.sun.com/xml/ns/javaee/javaee_5.xsd",
        "https://java.sun.com/xml/ns/javaee/javaee_5.xsd",
        "http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd",
        "https://java.sun.com/xml/ns/javaee/web-app_2_5.xsd",
        "http://www.ibm.com/webservices/xsd/javaee_web_services_client_1_2.xsd",
        "https://www.ibm.com/webservices/xsd/javaee_web_services_client_1_2.xsd",

        // 3.0
        "http://java.sun.com/xml/ns/javaee/javaee_6.xsd",
        "https://java.sun.com/xml/ns/javaee/javaee_6.xsd",
        "http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd",
        "https://java.sun.com/xml/ns/javaee/web-app_3_0.xsd",
        "http://java.sun.com/xml/ns/javaee/web-common_3_0.xsd",
        "https://java.sun.com/xml/ns/javaee/web-common_3_0.xsd",
        "http://java.sun.com/xml/ns/javaee/web-fragment_3_0.xsd",
        "https://java.sun.com/xml/ns/javaee/web-fragment_3_0.xsd",
        "http://java.sun.com/xml/ns/javaee/javaee_web_services_client_1_3.xsd",
        "https://java.sun.com/xml/ns/javaee/javaee_web_services_client_1_3.xsd",

        // 3.1
        "http://xmlns.jcp.org/xml/ns/javaee/javaee_7.xsd",
        "https://xmlns.jcp.org/xml/ns/javaee/javaee_7.xsd",
        "http://xmlns.jcp.org/xml/ns/javaee/web-app_3_1.xsd",
        "https://xmlns.jcp.org/xml/ns/javaee/web-app_3_1.xsd",
        "http://xmlns.jcp.org/xml/ns/javaee/web-common_3_1.xsd",
        "https://xmlns.jcp.org/xml/ns/javaee/web-common_3_1.xsd",
        "http://xmlns.jcp.org/xml/ns/javaee/web-fragment_3_1.xsd",
        "https://xmlns.jcp.org/xml/ns/javaee/web-fragment_3_1.xsd",

        // 4.0
        "http://xmlns.jcp.org/xml/ns/javaee/javaee_8.xsd",
        "https://xmlns.jcp.org/xml/ns/javaee/javaee_8.xsd",
        "http://xmlns.jcp.org/xml/ns/javaee/web-app_4_0.xsd",
        "https://xmlns.jcp.org/xml/ns/javaee/web-app_4_0.xsd",
        "http://xmlns.jcp.org/xml/ns/javaee/web-common_4_0.xsd",
        "https://xmlns.jcp.org/xml/ns/javaee/web-common_4_0.xsd",
        "http://xmlns.jcp.org/xml/ns/javaee/web-fragment_4_0.xsd",
        "https://xmlns.jcp.org/xml/ns/javaee/web-fragment_4_0.xsd",
        "http://xmlns.jcp.org/xml/ns/javaee/javaee_web_services_client_1_4.xsd",
        "https://xmlns.jcp.org/xml/ns/javaee/javaee_web_services_client_1_4.xsd",

        // 5.0
        "http://javax.ee/xml/ns/javaxee/javaee_9.xsd",
        "https://javax.ee/xml/ns/javaxee/javaee_9.xsd",
        "https://jakarta.ee/xml/ns/jakartaee/web-app_5_0.xsd",
        "https://jakarta.ee/xml/ns/jakartaee/webapp_5_0.xsd",
        "https://jakarta.ee/xml/ns/jakartaee/web-common_5_0.xsd",
        "https://jakarta.ee/xml/ns/jakartaee/web-fragment_5_0.xsd",
        "https://jakarta.ee/xml/ns/jakartaee/jakartaee_web_services_client_2_0.xsd",

        // 6.0
        "https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd",
        "https://jakarta.ee/xml/ns/jakartaee/webapp_6_0.xsd",
        "https://jakarta.ee/xml/ns/jakartaee/web-common_6_0.xsd",
        "https://jakarta.ee/xml/ns/jakartaee/web-fragment_6_0.xsd",
        "https://jakarta.ee/xml/ns/jakartaee/jakartaee_web_services_client_2_0.xsd",
    })
    public void ensureSystemIdExists(String systemId) throws IOException
    {
        String result = catalog.matchSystem(systemId);
        assertNotNull(result);
        URL url = new URL(result);
        try (InputStream in = url.openStream();
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             StringWriter writer = new StringWriter())
        {
            IO.copy(reader, writer);
            String contents = writer.toString();
            assertNotNull(contents);
            assertThat("URL [%s] has contents".formatted(url), contents.trim().length(), greaterThan(1000));
        }
    }
}
