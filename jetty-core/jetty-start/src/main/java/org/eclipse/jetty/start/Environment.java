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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class Environment
{
    private final BaseHome _baseHome;

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
     * List of all active [jpms] sections for enabled modules
     */
    private final Set<String> _jmodAdds = new LinkedHashSet<>();
    private final Map<String, Set<String>> _jmodPatch = new LinkedHashMap<>();
    private final Map<String, Set<String>> _jmodOpens = new LinkedHashMap<>();
    private final Map<String, Set<String>> _jmodExports = new LinkedHashMap<>();
    private final Map<String, Set<String>> _jmodReads = new LinkedHashMap<>();

    /**
     * List of all xml references found directly on command line or start.ini
     */

    private final List<String> _xmlRefs = new ArrayList<>();

    /**
     * List of all property references found directly on command line or start.ini
     */
    private final List<String> _propertyFileRefs = new ArrayList<>();

    private final List<String> _libRefs = new ArrayList<>();

    Environment(BaseHome baseHome)
    {
        this._baseHome = baseHome;
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
        out.println("Jetty Active XMLs:");
        out.println("------------------");
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
        out.println("Properties:");
        out.println("-----------");

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

    public Props getProperties()
    {
        return _properties;
    }

    public List<Path> getXmlFiles()
    {
        return _xmls;
    }

    public void resolve(List<Module> activeModules) throws IOException
    {
        // 6) Resolve Extra XMLs
        resolveExtraXmls();

        // 7) JPMS Expansion
        resolveJPMS(activeModules);

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

    protected void generateJpmsArgs(CommandLineBuilder cmd)
    {
        if (!_jmodAdds.isEmpty())
        {
            cmd.addRawArg("--add-modules");
            cmd.addRawArg(String.join(",", _jmodAdds));
        }
        for (Map.Entry<String, Set<String>> entry : _jmodPatch.entrySet())
        {
            cmd.addRawArg("--patch-module");
            cmd.addRawArg(entry.getKey() + "=" + String.join(File.pathSeparator, entry.getValue()));
        }
        for (Map.Entry<String, Set<String>> entry : _jmodOpens.entrySet())
        {
            cmd.addRawArg("--add-opens");
            cmd.addRawArg(entry.getKey() + "=" + String.join(",", entry.getValue()));
        }
        for (Map.Entry<String, Set<String>> entry : _jmodExports.entrySet())
        {
            cmd.addRawArg("--add-exports");
            cmd.addRawArg(entry.getKey() + "=" + String.join(",", entry.getValue()));
        }
        for (Map.Entry<String, Set<String>> entry : _jmodReads.entrySet())
        {
            cmd.addRawArg("--add-reads");
            cmd.addRawArg(entry.getKey() + "=" + String.join(",", entry.getValue()));
        }
    }

    protected List<Path> getPropertyFiles()
    {
        return _propertyFiles;
    }

    private void resolveJPMS(List<Module> activeModules) throws IOException
    {
        for (Module module : activeModules)
        {
            for (String line : module.getJPMS())
            {
                line = _properties.expand(line);
                String directive;
                if (line.startsWith(directive = "add-modules:"))
                {
                    String[] names = line.substring(directive.length()).split(",");
                    Arrays.stream(names).map(String::trim).collect(Collectors.toCollection(() -> _jmodAdds));
                }
                else if (line.startsWith(directive = "patch-module:"))
                {
                    parseJPMSKeyValue(module, line, directive, true, _jmodPatch);
                }
                else if (line.startsWith(directive = "add-opens:"))
                {
                    parseJPMSKeyValue(module, line, directive, false, _jmodOpens);
                }
                else if (line.startsWith(directive = "add-exports:"))
                {
                    parseJPMSKeyValue(module, line, directive, false, _jmodExports);
                }
                else if (line.startsWith(directive = "add-reads:"))
                {
                    parseJPMSKeyValue(module, line, directive, false, _jmodReads);
                }
                else
                {
                    throw new IllegalArgumentException("Invalid [jpms] directive " + directive + " in module " + module.getName() + ": " + line);
                }
            }
        }
        _jmodAdds.add("ALL-MODULE-PATH");
        StartLog.debug("Expanded JPMS directives:%n  add-modules: %s%n  patch-modules: %s%n  add-opens: %s%n  add-exports: %s%n  add-reads: %s",
            _jmodAdds, _jmodPatch, _jmodOpens, _jmodExports, _jmodReads);
    }

    private void parseJPMSKeyValue(Module module, String line, String directive, boolean valueIsFile, Map<String, Set<String>> output) throws IOException
    {
        String valueString = line.substring(directive.length());
        int equals = valueString.indexOf('=');
        if (equals <= 0)
            throw new IllegalArgumentException("Invalid [jpms] directive " + directive + " in module " + module.getName() + ": " + line);
        String delimiter = valueIsFile ? File.pathSeparator : ",";
        String key = valueString.substring(0, equals).trim();
        String[] values = valueString.substring(equals + 1).split(delimiter);
        Set<String> result = output.computeIfAbsent(key, k -> new LinkedHashSet<>());
        for (String value : values)
        {
            value = value.trim();
            if (valueIsFile)
            {
                List<Path> paths = _baseHome.getPaths(value);
                paths.stream().map(Path::toAbsolutePath).map(Path::toString).collect(Collectors.toCollection(() -> result));
            }
            else
            {
                result.add(value);
            }
        }
    }
}
