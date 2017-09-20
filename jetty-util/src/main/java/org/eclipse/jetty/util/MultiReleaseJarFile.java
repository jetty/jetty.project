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
import java.io.InputStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
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

    private final JarFile jarFile;
    private final int majorVersion;
    private final boolean multiRelease;

    /* Map to hold unversioned name to VersionedJarEntry */
    private final Map<String,VersionedJarEntry> entries;

    public MultiReleaseJarFile(File file) throws IOException
    {
        this(file,JavaVersion.VERSION.getMajor(),false);
    }

    public MultiReleaseJarFile(File file, int majorVersion, boolean includeDirectories) throws IOException
    {
        if (file==null || !file.exists() || !file.canRead() || file.isDirectory())
            throw new IllegalArgumentException("bad jar file: "+file);

        jarFile = new JarFile(file,true,JarFile.OPEN_READ);
        this.majorVersion = majorVersion;

        Manifest manifest = jarFile.getManifest();
        if (manifest==null)
            multiRelease = false;
        else
            multiRelease = Boolean.valueOf(String.valueOf(manifest.getMainAttributes().getValue("Multi-Release")));

        Map<String,VersionedJarEntry> map = new TreeMap<>();
        jarFile.stream()
                .map(VersionedJarEntry::new)
                .filter(e->(includeDirectories||!e.isDirectory()) && e.isApplicable())
                .forEach(e->map.compute(e.name, (k, v) -> v==null || v.isReplacedBy(e) ? e : v));

        for (Iterator<Map.Entry<String,VersionedJarEntry>> i = map.entrySet().iterator();i.hasNext();)
        {
            Map.Entry<String,VersionedJarEntry> e = i.next();
            VersionedJarEntry entry = e.getValue();

            if (entry.inner)
            {
                VersionedJarEntry outer = map.get(entry.outer);

                if (entry.outer==null || outer.version!= entry.version)
                    i.remove();
            }
        }

        entries = Collections.unmodifiableMap(map);
    }

    public boolean isMultiRelease()
    {
        return multiRelease;
    }

    public int getVersion()
    {
        return majorVersion;
    }

    public Stream<VersionedJarEntry> stream()
    {
        return entries.values().stream();
    }

    public VersionedJarEntry getEntry(String name)
    {
        return entries.get(name);
    }

    @Override
    public String toString()
    {
        return String.format("%s[%b,%d]",jarFile.getName(),isMultiRelease(),getVersion());
    }

    public class VersionedJarEntry
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
                v = -1;
                int index = name.indexOf('/', META_INF_VERSIONS.length());
                if (index > META_INF_VERSIONS.length() && index < name.length())
                {
                    try
                    {
                        v = TypeUtil.parseInt(name, META_INF_VERSIONS.length(), index - META_INF_VERSIONS.length(), 10);
                        name = name.substring(index + 1);
                    }
                    catch (NumberFormatException x)
                    {
                        throw new RuntimeException("illegal version in "+jarFile,x);
                    }
                }
            }

            this.entry = entry;
            this.name = name;
            this.version = v;
            this.inner = name.contains("$") && name.toLowerCase().endsWith(".class");
            this.outer = inner ? name.substring(0, name.indexOf('$')) + name.substring(name.length() - 6, name.length()) : null;
        }

        public String getName()
        {
            return name;
        }

        public String getNameInJar()
        {
            return entry.getName();
        }

        public int getVersion()
        {
            return version;
        }

        public boolean isVersioned()
        {
            return version > 0;
        }

        public boolean isDirectory()
        {
            return entry.isDirectory();
        }

        public InputStream getInputStream() throws IOException
        {
            return jarFile.getInputStream(entry);
        }

        boolean isApplicable()
        {
            if (multiRelease)
               return this.version>=0 && this.version <= majorVersion && name.length()>0;
            return this.version==0;
        }

        boolean isReplacedBy(VersionedJarEntry entry)
        {
            if (isDirectory())
                return entry.version==0;
            return this.name.equals(entry.name) && entry.version>version;
        }

        @Override
        public String toString()
        {
            return String.format("%s->%s[%d]",name,entry.getName(),version);
        }
    }
}
