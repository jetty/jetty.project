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

package org.eclipse.jetty.ee10.webapp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlParser;
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
        Path xml = workDir.getEmptyPathDir().resolve("test.xml");
        Files.writeString(xml, """
            <?xml version="1.0" encoding="UTF-8"?>
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     metadata-complete="false"
                     xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd"
                     version="6.0">
              <display-name>Empty WebApp Descriptor</display-name>
            </web-app>
            """, StandardCharsets.UTF_8);

        WebDescriptor webDescriptor = new WebDescriptor(ResourceFactory.root().newResource(xml));
        XmlParser xmlParser = WebDescriptor.newParser(true);
        // This should not throw an exception, if it does then you have a bad state.
        // Such as missing required XML resource entities.
        webDescriptor.parse(xmlParser);
    }
}
