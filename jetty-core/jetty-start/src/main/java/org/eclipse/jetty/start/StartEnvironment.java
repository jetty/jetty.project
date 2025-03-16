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
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * A start environment that contains the configurations that will be used to
 * build a runtime {@code org.eclipse.jetty.util.component.Environment} via
 * {@code --env} arguments passed to {@code org.eclipse.jetty.xml.XmlConfiguration#main(java.lang.String...)}
 */
public class StartEnvironment
{
    private final String _name;
    private final BaseHome _baseHome;
    private final Props _properties = new Props();
    private final List<Path> _propertyFiles = new ArrayList<>();
    private final Classpath _libs = new Classpath();
    private final List<Path> _xmls = new ArrayList<>();
    private final List<String> _cmdLineXmls = new ArrayList<>();
    private final List<String> _cmdLinePropertyFiles = new ArrayList<>();
    private final List<String> _cmdLineLibs = new ArrayList<>();
    private final JPMSArgs _jpmsArgs = new JPMSArgs();

    StartEnvironment(String name, BaseHome baseHome)
    {
        _name = name;
        _baseHome = baseHome;
    }

    BaseHome getBaseHome()
    {
        return _baseHome;
    }

    void addCmdLineLib(String lib)
    {
        _cmdLineLibs.add(lib);
    }

    void addCmdLinePropertyFile(String arg)
    {
        // only add non-duplicates
        if (!_cmdLinePropertyFiles.contains(arg))
            _cmdLinePropertyFiles.add(arg);
    }

    private void addUniquePropertyFile(String propertyFileRef, Path propertyFile) throws IOException
    {
        if (!"Jetty".equalsIgnoreCase(getName()))
            throw new IllegalStateException("Property files not supported in environment " + getName());

        if (!FS.canReadFile(propertyFile))
            throw new IOException("Cannot read file: " + propertyFileRef);

        propertyFile = FS.toRealPath(propertyFile);
        if (!_propertyFiles.contains(propertyFile))
            _propertyFiles.add(propertyFile);
    }

    public void addUniqueXmlFile(String xmlRef, Path xmlfile) throws IOException
    {
        if (!FS.canReadFile(xmlfile))
            throw new IOException("Cannot read file: " + xmlRef);

        xmlfile = FS.toRealPath(xmlfile);
        if (!getXmlFiles().contains(xmlfile))
            getXmlFiles().add(xmlfile);
    }

    public void addCmdLineXml(String arg)
    {
        // only add non-duplicates
        if (!_cmdLineXmls.contains(arg))
            _cmdLineXmls.add(arg);
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
        out.printf("------------%s%n", "-".repeat(_name.length()));

        List<String> sortedKeys = new ArrayList<>();
        for (Props.Prop prop : _properties)
        {
            if (prop.source.equals(Props.ORIGIN_SYSPROP))
                continue; // skip
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
                try (InputStream in = Files.newInputStream(path))
                {
                    props.load(in);
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
        return _libs;
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
        resolveExtraXmls();
        resolvePropertyFiles();
    }

    /**
     * Expand any command line added {@code --libs} lib references.
     *
     * @throws IOException if unable to expand the libraries
     */
    public void resolveLibs() throws IOException
    {
        StartLog.debug("Expanding Libs");
        for (String cmdLineLib : _cmdLineLibs)
        {
            StartLog.debug("cmdLineLib = %s", cmdLineLib);
            String lib = getProperties().expand(cmdLineLib);
            StartLog.debug("expanded = %s", lib);

            // Perform path escaping (needed by windows).
            lib = lib.replaceAll("\\\\([^\\\\])", "\\\\\\\\$1");

            for (Path libPath : _baseHome.getPaths(lib))
            {
                getClasspath().addComponent(libPath);
            }
        }
    }

    private void resolveExtraXmls() throws IOException
    {
        // Find and Expand XML files
        for (String xmlRef : _cmdLineXmls)
        {
            // Straight Reference
            Path xmlfile = _baseHome.getPath(xmlRef);
            if (!FS.exists(xmlfile))
                xmlfile = _baseHome.getPath("etc/" + xmlRef);
            addUniqueXmlFile(xmlRef, xmlfile);
        }
    }

    private void resolvePropertyFiles() throws IOException
    {
        // Find and Expand property files
        for (String cmdLinePropertyFile : _cmdLinePropertyFiles)
        {
            // Straight Reference
            Path propertyFile = _baseHome.getPath(cmdLinePropertyFile);
            if (!FS.exists(propertyFile))
                propertyFile = _baseHome.getPath("etc/" + cmdLinePropertyFile);
            addUniquePropertyFile(cmdLinePropertyFile, propertyFile);
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

    void resolveJPMS(Module module) throws IOException
    {
        _jpmsArgs.collect(module, this);
    }

    JPMSArgs getJPMSArgs()
    {
        return _jpmsArgs;
    }

    @Override
    public String toString()
    {
        return "%s@%x{%s,%s,%s,%s,%s}".formatted(this.getClass().getSimpleName(), hashCode(), getName(), getClasspath(), getXmlFiles(), getProperties(), getPropertyFiles());
    }
}
