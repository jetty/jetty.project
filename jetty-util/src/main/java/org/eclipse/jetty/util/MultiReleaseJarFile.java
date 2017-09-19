//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.io.File;
import java.io.IOException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * <p>Utility class to create a stream of Multi Release {@link JarEntry}s</p>
 * <p>This is the java 8 version of this class.
 * A java 9 version of this class is included as a Multi Release class in the
 * jetty-util jar, that uses java 9 APIs to correctly handle Multi Release jars.</p>
 */
public class MultiReleaseJarFile
{
    private static final String META_INF_VERSIONS = "META-INF/versions/";

    public static JarFile open(File file) throws IOException
    {
        return new JarFile(file);
    }

    public static Stream<VersionedJarEntry> streamVersioned(JarFile jf)
    {
        return jf.stream()
                .map(VersionedJarEntry::new);
    }

    public static Stream<JarEntry> stream(JarFile jf)
    {
        // Java 8 version of this class, ignores all versioned entries.
        return streamVersioned(jf)
                .filter(e->!e.isVersioned())
                .map(e->e.resolve(jf));
    }

    public static class VersionedJarEntry
    {
        final JarEntry entry;
        final String name;
        final int version;

        VersionedJarEntry(JarEntry entry)
        {
            int v = 0;
            String name = entry.getName();
            if (name.startsWith(META_INF_VERSIONS))
            {
                v=-1;
                int index = name.indexOf('/', META_INF_VERSIONS.length());
                if (index >= 0 && index < name.length())
                {
                    try
                    {
                        v = Integer.parseInt(name.substring(META_INF_VERSIONS.length(), index), 10);
                        name = name.substring(index + 1);
                    }
                    catch (NumberFormatException x)
                    {
                    }
                }
            }

            this.entry = entry;
            this.name = name;
            this.version = v;
        }

        public int version()
        {
            return version;
        }

        public boolean isVersioned()
        {
            return version > 0;
        }

        @Override
        public String toString()
        {
            return entry.toString() + (version==0?"[base]":("["+version+"]"));
        }

        public JarEntry resolve(JarFile jf)
        {
            return entry;
        }
    }
}
