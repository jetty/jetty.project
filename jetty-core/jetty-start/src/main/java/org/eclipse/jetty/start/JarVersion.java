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

package org.eclipse.jetty.start;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attempt to determine the version of the Jar File based on common version locations.
 */
public class JarVersion
{
    private static JarEntry findEntry(JarFile jar, String regex)
    {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher;
        Enumeration<JarEntry> en = jar.entries();
        while (en.hasMoreElements())
        {
            JarEntry entry = en.nextElement();
            matcher = pattern.matcher(entry.getName());
            if (matcher.matches())
            {
                return entry;
            }
        }

        return null;
    }

    private static String getBundleVersion(Manifest manifest)
    {
        Attributes attribs = manifest.getMainAttributes();
        if (attribs == null)
        {
            return null;
        }

        String version = attribs.getValue("Bundle-Version");
        if (version == null)
        {
            return null;
        }

        return stripV(version);
    }

    private static String getMainManifestImplVersion(Manifest manifest)
    {
        Attributes attribs = manifest.getMainAttributes();
        if (attribs == null)
        {
            return null;
        }

        String version = attribs.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        if (version == null)
        {
            return null;
        }

        return stripV(version);
    }

    private static String getMavenVersion(JarFile jar) throws IOException
    {
        JarEntry pomProp = findEntry(jar, "META-INF/maven/.*/pom\\.properties$");
        if (pomProp == null)
        {
            return null;
        }

        InputStream stream = null;

        try
        {
            stream = jar.getInputStream(pomProp);
            Properties props = new Properties();
            props.load(stream);

            String version = props.getProperty("version");
            if (version == null)
            {
                return null;
            }

            return stripV(version);
        }
        finally
        {
            FS.close(stream);
        }
    }

    private static String getSubManifestImplVersion(Manifest manifest)
    {
        Map<String, Attributes> entries = manifest.getEntries();

        for (Attributes attribs : entries.values())
        {
            if (attribs == null)
            {
                continue; // skip entry
            }

            String version = attribs.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            if (version == null)
            {
                continue; // empty, no value, skip it
            }

            return stripV(version);
        }

        return null; // no valid impl version entries found
    }

    public static String getVersion(Path file)
    {
        try (JarFile jar = new JarFile(file.toFile()))
        {
            String version = null;

            Manifest manifest = jar.getManifest();

            if (manifest == null)
            {
                return "(none specified)";
            }

            version = getMainManifestImplVersion(manifest);
            if (version != null)
            {
                return version;
            }

            version = getSubManifestImplVersion(manifest);
            if (version != null)
            {
                return version;
            }

            version = getBundleVersion(manifest);
            if (version != null)
            {
                return version;
            }

            version = getMavenVersion(jar);
            if (version != null)
            {
                return version;
            }

            return "(none specified)";
        }
        catch (IOException e)
        {
            return "(error: " + e.getClass().getSimpleName() + " " + e.getMessage() + ")";
        }
    }

    private static String stripV(String version)
    {
        if (version.charAt(0) == 'v')
        {
            return version.substring(1);
        }

        return version;
    }
}
