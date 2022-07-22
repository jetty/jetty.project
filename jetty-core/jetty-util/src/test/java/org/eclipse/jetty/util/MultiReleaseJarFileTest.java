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

package org.eclipse.jetty.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class MultiReleaseJarFileTest
{
    private final Path example = MavenTestingUtils.getTestResourcePathFile("example.jar");

    @Test
    public void testBase() throws Exception
    {
        try (MultiReleaseJarFile jarFile = new MultiReleaseJarFile(example))
        {
            Set<String> actual = jarFile.stream().map(Path::toString).collect(Collectors.toSet());
            String[] expected = {
                // exists in base only
                "/org/example/OnlyInBase.class",
                "/org/example/InBoth$InnerBase.class",
                // exists in versions/9
                "/org/example/Nowhere$NoOuter.class",
                "/org/example/InBoth$Inner9.class",
                "/org/example/OnlyIn9.class",
                "/org/example/onlyIn9/OnlyIn9.class",
                // exists in versions/10
                "/org/example/In10Only.class",
                // exists in base and is overridden by version specific entry
                "/org/example/InBoth.class",
                "/org/example/InBoth$InnerBoth.class",
                "/WEB-INF/web.xml",
                "/WEB-INF/classes/App.class",
                "/WEB-INF/lib/depend.jar"
            };

            assertThat(actual, Matchers.containsInAnyOrder(expected));
        }
    }

    @Test
    public void testClassLoaderJava9() throws Exception
    {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{example.toUri().toURL()}))
        {
            assertThat(readFile(loader.getResource("org/example/OnlyInBase.class")), is("org/example/OnlyInBase.class"));
            assertThat(readFile(loader.getResource("org/example/OnlyIn9.class")), is("META-INF/versions/9/org/example/OnlyIn9.class"));
            assertThat(readFile(loader.getResource("WEB-INF/web.xml")), is("META-INF/versions/9/WEB-INF/web.xml"));
            assertThat(readFile(loader.getResource("WEB-INF/classes/App.class")), is("META-INF/versions/9/WEB-INF/classes/App.class"));
            assertThat(readFile(loader.getResource("WEB-INF/lib/depend.jar")), is("META-INF/versions/9/WEB-INF/lib/depend.jar"));
        }
    }

    private String readFile(URL url) throws IOException
    {
        try(InputStream in = url.openStream())
        {
            return IO.toString(in, StandardCharsets.UTF_8);
        }
    }
}
