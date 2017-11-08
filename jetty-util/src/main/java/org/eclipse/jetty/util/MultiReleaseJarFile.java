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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * <p>Utility class to handle a Multi Release Jar file</p>
 */
public class MultiReleaseJarFile implements Closeable
{
    private static final String META_INF_VERSIONS = "META-INF/versions/";

    private final JarFile jarFile;
    private final int platform;
    private final boolean multiRelease;

    /* Map to hold unversioned name to VersionedJarEntry */
    private final Map<String,VersionedJarEntry> entries;

    /**
     * Construct a multi release jar file for the current JVM version, ignoring directories.
     * @param file The file to open
     */
    public MultiReleaseJarFile(File file) throws IOException
    {
        this(file,JavaVersion.VERSION.getPlatform(),false);
    }

    /**
     * Construct a multi release jar file
     * @param file The file to open
     * @param javaPlatform The  JVM platform to apply when selecting a version.
     * @param includeDirectories true if any directory entries should not be ignored
     * @throws IOException if the jar file cannot be read
     */
    public MultiReleaseJarFile(File file, int javaPlatform, boolean includeDirectories) throws IOException
    {
        if (file==null || !file.exists() || !file.canRead() || file.isDirectory())
            throw new IllegalArgumentException("bad jar file: "+file);

        jarFile = new JarFile(file,true,JarFile.OPEN_READ);
        this.platform = javaPlatform;

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
                VersionedJarEntry outer = entry.outer==null?null:map.get(entry.outer);
                if (outer==null || outer.version!=entry.version)
                    i.remove();
            }
        }

        entries = Collections.unmodifiableMap(map);
    }

    /**
     * @return true IFF the jar is a multi release jar
     */
    public boolean isMultiRelease()
    {
        return multiRelease;
    }

    /**
     * @return The major version applied to this jar for the purposes of selecting entries
     */
    public int getVersion()
    {
        return platform;
    }

    /**
     * @return A stream of versioned entries from the jar, excluded any that are not applicable
     */
    public Stream<VersionedJarEntry> stream()
    {
        return entries.values().stream();
    }

    /** Get a versioned resource entry by name
     * @param name The unversioned name of the resource
     * @return The versioned entry of the resource
     */
    public VersionedJarEntry getEntry(String name)
    {
        return entries.get(name);
    }

    @Override
    public void close() throws IOException
    {
        if (jarFile!=null)
            jarFile.close();
    }

    @Override
    public String toString()
    {
        return String.format("%s[%b,%d]",jarFile.getName(),isMultiRelease(),getVersion());
    }

    /**
     * A versioned Jar entry
     */
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
            this.inner = name.contains("$") && name.toLowerCase(Locale.ENGLISH).endsWith(".class");
            this.outer = inner ? name.substring(0, name.indexOf('$')) + ".class" : null;
        }

        /**
         * @return the unversioned name of the resource
         */
        public String getName()
        {
            return name;
        }

        /**
         * @return The name of the resource within the jar, which could be versioned
         */
        public String getNameInJar()
        {
            return entry.getName();
        }

        /**
         * @return The version of the resource or 0 for a base version
         */
        public int getVersion()
        {
            return version;
        }

        /**
         *
         * @return True iff the entry is not from the base version
         */
        public boolean isVersioned()
        {
            return version > 0;
        }

        /**
         *
         * @return True iff the entry is a directory
         */
        public boolean isDirectory()
        {
            return entry.isDirectory();
        }

        /**
         * @return An input stream of the content of the versioned entry.
         * @throws IOException if something goes wrong!
         */
        public InputStream getInputStream() throws IOException
        {
            return jarFile.getInputStream(entry);
        }

        boolean isApplicable()
        {
            if (multiRelease)
               return this.version>=0 && this.version <= platform && name.length()>0;
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
