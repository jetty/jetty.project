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

package org.eclipse.jetty.ee9.jsp;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.tomcat.util.descriptor.tld.TaglibXml;
import org.apache.tomcat.util.descriptor.tld.TldResourcePath;
import org.eclipse.jetty.ee9.apache.jsp.JettyTldPreScanned;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TestJettyTldPreScanned
 */
public class TestJettyTldPreScanned
{

    /**
     * Test that a tld inside a jar can be scanned, as can a tld not inside a jar.
     */
    @Test
    public void testIt()
        throws Exception
    {
        File jar = MavenTestingUtils.getTestResourceFile("taglib.jar");
        File tld = MavenTestingUtils.getTestResourceFile("META-INF/foo-taglib.tld");

        List<URL> list = new ArrayList<>();
        list.add(new URL("jar:" + jar.toURI().toURL().toString() + "!/META-INF/bar-taglib.tld"));
        list.add(tld.toURI().toURL());

        JettyTldPreScanned preScanned = new JettyTldPreScanned(new ServletContextHandler().getServletContext(), false, false, false, list);
        preScanned.scanJars();
        Map<TldResourcePath, TaglibXml> map = preScanned.getTldResourcePathTaglibXmlMap();
        assertNotNull(map);
        assertEquals(2, map.size());
        for (TldResourcePath p : map.keySet())
        {
            URL u = p.getUrl();
            TaglibXml tlx = map.get(p);
            assertNotNull(tlx);
            if (!"foo".equals(tlx.getShortName()) && !"bar".equals(tlx.getShortName()))
                fail("unknown tag");
        }
    }
}
