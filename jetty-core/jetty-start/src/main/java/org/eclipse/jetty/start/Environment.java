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

package org.eclipse.jetty.start;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public class Environment
{
    private final BaseHome _baseHome;
    private final String _name;
    private final Props _properties = new Props();

    /**
     * List of all property files
     */
    private final List<Path> _propertyFiles = new ArrayList<>();

    /**
     * List of all active [lib] sections from enabled modules
     */
    private final Classpath _classpath = new Classpath();

    /**
     * List of all active [xml] sections from enabled modules
     */
    private final List<Path> _xmls = new ArrayList<>();

    /**
     * List of all xml references found directly on command line or start.ini
     */

    private final List<String> _xmlRefs = new ArrayList<>();

    /**
     * List of all property references found directly on command line or start.ini
     */
    private final List<String> _propertyFileRefs = new ArrayList<>();

    private final List<String> _libRefs = new ArrayList<>();

    Environment(String name, BaseHome baseHome)
    {
        _name = name;
        _baseHome = baseHome;
    }

    public void addLibRef(String lib)
    {
        _libRefs.add(lib);
    }

    public void addPropertyFileRef(String arg)
    {
        // only add non-duplicates
        if (!_propertyFileRefs.contains(arg))
        {
            _propertyFileRefs.add(arg);
        }
    }

    public void addUniquePropertyFile(String propertyFileRef, Path propertyFile) throws IOException
    {
        if (!FS.canReadFile(propertyFile))
        {
            throw new IOException("Cannot read file: " + propertyFileRef);
        }
        propertyFile = FS.toRealPath(propertyFile);
        if (!_propertyFiles.contains(propertyFile))
        {
            _propertyFiles.add(propertyFile);
        }
    }

    public void addUniqueXmlFile(String xmlRef, Path xmlfile) throws IOException
    {
        if (!FS.canReadFile(xmlfile))
        {
            throw new IOException("Cannot read file: " + xmlRef);
        }
        xmlfile = FS.toRealPath(xmlfile);
        if (!getXmlFiles().contains(xmlfile))
        {
            getXmlFiles().add(xmlfile);
        }
    }

    public void addXmlRef(String arg)
    {
        // only add non-duplicates
        if (!_xmlRefs.contains(arg))
        {
            _xmlRefs.add(arg);
        }
    }

    public void dumpActiveXmls(PrintStream out)
    {
        out.println();
        out.printf("Active XMLs: %s%n", _name);
        out.printf("-------------%s%n", "-".repeat(_name.length()));
        if (getXmlFiles().isEmpty())
        {
            out.println(" (no xml files specified)");
            return;
        }

        for (Path xml : getXmlFiles())
        {
            out.printf(" %s%n", _baseHome.toShortForm(xml.toAbsolutePath()));
        }
    }

    public void dumpProperties(PrintStream out)
    {
        out.println();
        out.printf("Properties: %s%n", _name);
        out.printf("--------------%n", "-".repeat(_name.length()));

        List<String> sortedKeys = new ArrayList<>();
        for (Props.Prop prop : _properties)
        {
            if (prop.source.equals(Props.ORIGIN_SYSPROP))
            {
                continue; // skip
            }
            sortedKeys.add(prop.key);
        }

        if (sortedKeys.isEmpty())
        {
            out.println(" (no properties specified)");
            return;
        }

        Collections.sort(sortedKeys);

        for (String key : sortedKeys)
        {
            dumpProperty(out, key);
        }

        for (Path path : _propertyFiles)
        {
            String p = _baseHome.toShortForm(path);
            if (Files.isReadable(path))
            {
                Properties props = new Properties();
                try
                {
                    props.load(new FileInputStream(path.toFile()));
                    for (Object key : props.keySet())
                    {
                        out.printf(" %s:%s = %s%n", p, key, props.getProperty(String.valueOf(key)));
                    }
                }
                catch (Throwable th)
                {
                    out.printf(" %s NOT READABLE!%n", p);
                }
            }
            else
            {
                out.printf(" %s NOT READABLE!%n", p);
            }
        }
    }

    public Classpath getClasspath()
    {
        return _classpath;
    }

    public String getName()
    {
        return _name;
    }

    public Props getProperties()
    {
        return _properties;
    }

    public List<Path> getXmlFiles()
    {
        return _xmls;
    }

    public void resolve() throws IOException
    {
        // 6) Resolve Extra XMLs
        resolveExtraXmls();

        // 8) Resolve Property Files
        resolvePropertyFiles();
    }

    /**
     * Expand any command line added {@code --lib} lib references.
     *
     * @throws IOException if unable to expand the libraries
     */
    public void resolveLibs() throws IOException
    {
        StartLog.debug("Expanding Libs");
        for (String rawlibref : _libRefs)
        {
            StartLog.debug("rawlibref = " + rawlibref);
            String libref = getProperties().expand(rawlibref);
            StartLog.debug("expanded = " + libref);

            // perform path escaping (needed by windows)
            libref = libref.replaceAll("\\\\([^\\\\])", "\\\\\\\\$1");

            for (Path libpath : _baseHome.getPaths(libref))
            {
                getClasspath().addComponent(libpath.toFile());
            }
        }
    }

    private void resolveExtraXmls() throws IOException
    {
        // Find and Expand XML files
        for (String xmlRef : _xmlRefs)
        {
            // Straight Reference
            Path xmlfile = _baseHome.getPath(xmlRef);
            if (!FS.exists(xmlfile))
            {
                xmlfile = _baseHome.getPath("etc/" + xmlRef);
            }
            addUniqueXmlFile(xmlRef, xmlfile);
        }
    }

    private void resolvePropertyFiles() throws IOException
    {
        // Find and Expand property files
        for (String propertyFileRef : _propertyFileRefs)
        {
            // Straight Reference
            Path propertyFile = _baseHome.getPath(propertyFileRef);
            if (!FS.exists(propertyFile))
            {
                propertyFile = _baseHome.getPath("etc/" + propertyFileRef);
            }
            addUniquePropertyFile(propertyFileRef, propertyFile);
        }
    }

    protected void dumpProperty(PrintStream out, String key)
    {
        Props.Prop prop = _properties.getProp(key);
        if (prop == null)
        {
            out.printf(" %s (not defined)%n", key);
        }
        else
        {
            out.printf(" %s = %s%n", key, prop.value);
            if (StartLog.isDebugEnabled())
                out.printf("   origin: %s%n", prop.source);
        }
    }

    protected List<Path> getPropertyFiles()
    {
        return _propertyFiles;
    }

    @Override
    public String toString()
    {
        return "%s@%x{%s,%s,%s,%s,%s}".formatted(this.getClass().getSimpleName(), hashCode(), getName(), getClasspath(), getXmlFiles(), getProperties(), getPropertyFiles());
    }
}
