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
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * <p>Utility class to create a stream of Multi Release {@link JarEntry}s</p>
 * <p>This is the java 9 version of this class.
 * A java 8 version of this class is included as the base version in a Multi Release
 * jetty-util jar, that uses does not use java 9 APIs.</p>
 */
public class MultiReleaseJarFile
{
    private static final String META_INF_VERSIONS = "META-INF/versions/";

    public static JarFile open(File file) throws IOException
    {
        return new JarFile(file,true,JarFile.OPEN_READ,Runtime.version());
    }

    public static Stream<VersionedJarEntry> streamVersioned(JarFile jf)
    {
        return jf.stream()
                .map(VersionedJarEntry::new);
    }

    public static Stream<JarEntry> stream(JarFile jf)
    {
        if (!jf.isMultiRelease())
            return jf.stream();

        // Map to hold unversioned name to VersionedJarEntry
        Map<String,VersionedJarEntry> entries = new TreeMap<>();

        // Fill the map, removing non applicable entries and replacing versions with later versions.
        streamVersioned(jf)
                .filter(e->e.isApplicable(jf.getVersion().major()))
                .forEach(e->entries.compute(e.name, (k, v) -> v==null || v.isReplacedBy(e) ? e : v));

        // filter the values to remove non applicable inner classes and map to versioned entry
        return entries.values().stream()
                .filter(e-> {
                    if (!e.inner)
                        return true;
                    VersionedJarEntry outer = entries.get(e.outer);
                    return outer != null && outer.version == e.version;
                })
                .map(e->e.resolve(jf));
    }

    public static class VersionedJarEntry
    {
        final JarEntry entry;
        final String name;
        final int version;
        final boolean inner;
        final String outer;

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
                        v = Integer.parseInt(name, META_INF_VERSIONS.length(), index, 10);
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
            this.inner = name.contains("$") && name.toLowerCase().endsWith(".class");
            this.outer = inner?name.substring(0,name.indexOf('$'))+name.substring(name.length()-6,name.length()):null;
        }

        public int version()
        {
            return version;
        }

        public boolean isVersioned()
        {
            return version > 0;
        }

        boolean isApplicable(int version)
        {
            return this.version>=0 && this.version <= version;
        }

        boolean isReplacedBy(VersionedJarEntry entry)
        {
            return this.name.equals(entry.name) && entry.version>version;
        }

        @Override
        public String toString()
        {
            return entry.toString() + (version==0?"[base]":("["+version+"]"));
        }

        public JarEntry resolve(JarFile jf)
        {
            if (version>0)
                return jf.getJarEntry(name);
            return entry;
        }
    }
}
