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

package org.eclipse.jetty.ee9.webapp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WorkDirExtension.class)
public class WebDescriptorTest
{
    public WorkDir workDir;

    /**
     * Test to ensure that the XMLParser XML entity mapping is functioning properly.
     */
    @Test
    public void testXmlWithXsd() throws Exception
    {
        // TODO: need to address ee8 issues with missing jsp-configType from the <xsd:include schemaLocation="jsp_2_3.xsd"/> that seems to be a missing resource
        // org.xml.sax.SAXParseException; systemId: jar:file:///path/to/jetty-servlet-api-4.0.6.jar!/javax/servlet/resources/web-common_4_0.xsd; lineNumber: 142; columnNumber: 50;
        // src-resolve: Cannot resolve the name 'javaee:jsp-configType' to a(n) 'type definition' component.
        Assumptions.assumeTrue(ContextHandler.ENVIRONMENT.getName().endsWith("9"));

        Path xml = workDir.getEmptyPathDir().resolve("test.xml");
        Files.writeString(xml, getWebAppXml(ContextHandler.ENVIRONMENT), StandardCharsets.UTF_8);

        Resource xmlRes = ResourceFactory.root().newResource(xml);
        WebDescriptor webDescriptor = new WebDescriptor(xmlRes);
        XmlParser xmlParser = WebDescriptor.newParser(true);
        // This should not throw an exception, if it does then you have a bad state.
        // Such as missing required XML resource entities.
        webDescriptor.parse(xmlParser);
    }

    private String getWebAppXml(Environment env)
    {
        String namespace = "https://jakarta.ee/xml/ns/jakartaee";
        String schemaLocation = "https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_5_0.xsd";
        String version = "5.0";

        if (env.getName().equals("ee8"))
        {
            namespace = "http://xmlns.jcp.org/xml/ns/javaee";
            schemaLocation = "http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/web-app_4_0.xsd";
            version = "4.0";
        }

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <web-app xmlns="%s"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     metadata-complete="false"
                     xsi:schemaLocation="%s"
                     version="%s">
              <display-name>Empty WebApp Descriptor</display-name>
            </web-app>
            """.formatted(namespace, schemaLocation, version);
    }
}
