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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jetty.start.Props.Prop;

/**
 * Represents a Module metadata, as defined in Jetty.
 *
 * <p>A module consists of:
 * <ul>
 * <li>A set of jar files, directories and/or jar file patterns to be added to the classpath</li>
 * <li>A list of XML configuration files</li>
 * <li>Properties set either directly or via a file of properties</li>
 * <li>A set of modules names (or capability names) that this module depends on.</li>
 * <li>A set of capability names that this module provides (including it's own name).</li>
 * <li>Licence details for using the module</li>
 * </ul>
 * Modules are discovered in the <code>${jetty.home}/modules</code> and
 * <code>${jetty.home}/modules</code> directories. A module may refer to
 * non-discovered dynamic module in a subdirectory, using a property as part or
 * all of the name.
 * A module may be enabled, either directly by name or transiently via a dependency
 * from another module by name or provided capability.
 */
public class Module implements Comparable<Module>
{
    private static final String VERSION_UNSPECIFIED = "0.0";
    static Pattern MOD_NAME = Pattern.compile("^(.*)\\.mod", Pattern.CASE_INSENSITIVE);
    static Pattern SET_PROPERTY = Pattern.compile("^(#?)\\s*([^=\\s]+)=(.*)$");

    /**
     * The file of the module
     */
    private final Path _path;

    /**
     * The name of the module
     */
    private final String _name;

    /**
     * Is the module dynamic - ie referenced rather than discovered
     */
    private final boolean _dynamic;

    /**
     * The version of Jetty the module supports
     */
    private Version version;

    /**
     * The module description
     */
    private final List<String> _description = new ArrayList<>();

    /**
     * List of xml configurations for this Module
     */
    private final List<String> _xmls = new ArrayList<>();

    /**
     * List of ini template lines
     */
    private final List<String> _iniTemplate = new ArrayList<>();

    /**
     * List of default config
     */
    private final List<String> _ini = new ArrayList<>();

    /**
     * List of library options for this Module
     */
    private final List<String> _libs = new ArrayList<>();

    /**
     * List of JPMS options for this Module
     */
    private final List<String> _jpms = new ArrayList<>();

    /**
     * List of files for this Module
     */
    private final List<String> _files = new ArrayList<>();

    /**
     * List of selections for this Module
     */
    private final Set<String> _enables = new HashSet<>();

    /**
     * List of provides for this Module
     */
    private final Set<String> _provides = new HashSet<>();

    /**
     * List of tags for this Module
     */
    private final List<String> _tags = new ArrayList<>();

    /**
     * Boolean true if directly enabled, false if all selections are transitive
     */
    private boolean _notTransitive;

    /**
     * Skip File Validation (default: false)
     */
    private boolean _skipFilesValidation = false;

    /**
     * List of jvm Args
     */
    private final List<String> _jvmArgs = new ArrayList<>();

    /**
     * License lines
     */
    private final List<String> _license = new ArrayList<>();

    /**
     * Dependencies from {@code [depends]} section
     */
    private final List<String> _depends = new ArrayList<>();

    /**
     * Text from {@code [deprecated]} section
     */
    private final List<String> _deprecated = new ArrayList<>();

    /**
     * Module names from {@code [before]} section
     */
    private final Set<String> _before = new HashSet<>();

    /**
     * Module names from {@code [after]} section
     */
    private final Set<String> _after = new HashSet<>();
    private String _environment;

    public Module(BaseHome basehome, Path path) throws IOException
    {
        super();
        _path = path;

        // Module name is the / separated path below the modules directory
        int m = -1;
        for (int i = path.getNameCount(); i-- > 0; )
        {
            if ("modules".equals(path.getName(i).toString()))
            {
                m = i;
                break;
            }
        }
        if (m < 0)
            throw new IllegalArgumentException("Module not contained within modules directory: " + basehome.toShortForm(path));
        String n = path.getName(m + 1).toString();
        for (int i = m + 2; i < path.getNameCount(); i++)
        {
            n = n + "/" + path.getName(i).toString();
        }
        Matcher matcher = MOD_NAME.matcher(n);
        if (!matcher.matches())
            throw new IllegalArgumentException("Module filename must have .mod extension: " + basehome.toShortForm(path));
        _name = matcher.group(1);

        _provides.add(_name);
        _dynamic = _name.contains("/");

        process(basehome);
    }

    public static boolean isConditionalDependency(String depends)
    {
        return (depends != null) && (depends.charAt(0) == '?');
    }

    public static String normalizeModuleName(String name)
    {
        if (isConditionalDependency(name))
            return name.substring(1);
        return name;
    }

    public String getEnvironment()
    {
        return _environment;
    }

    public String getName()
    {
        return _name;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        Module other = (Module)obj;
        if (_path == null)
            return other._path == null;

        return _path.equals(other._path);
    }

    public void expandDependencies(Props props)
    {
        Function<String, String> expander = d -> props.expand(d);

        List<String> tmp = _depends.stream().map(expander).collect(Collectors.toList());
        _depends.clear();
        _depends.addAll(tmp);
        tmp = _after.stream().map(expander).collect(Collectors.toList());
        _after.clear();
        _after.addAll(tmp);
        tmp = _before.stream().map(expander).collect(Collectors.toList());
        _before.clear();
        _before.addAll(tmp);
    }

    public List<String> getIniSection()
    {
        return _ini;
    }

    public List<String> getIniTemplate()
    {
        return _iniTemplate;
    }

    public List<String> getFiles()
    {
        return _files;
    }

    public boolean isSkipFilesValidation()
    {
        return _skipFilesValidation;
    }

    public List<String> getJvmArgs()
    {
        return _jvmArgs;
    }

    public List<String> getLibs()
    {
        return _libs;
    }

    public List<String> getLicense()
    {
        return _license;
    }

    public List<String> getXmls()
    {
        return _xmls;
    }

    public List<String> getJPMS()
    {
        return _jpms;
    }

    public Version getVersion()
    {
        return version;
    }

    public boolean hasDefaultConfig()
    {
        return !_ini.isEmpty();
    }

    public boolean hasIniTemplate()
    {
        return !_iniTemplate.isEmpty();
    }

    @Override
    public int hashCode()
    {
        return _name.hashCode();
    }

    public boolean hasLicense()
    {
        return (_license != null) && (_license.size() > 0);
    }

    /**
     * Indicates a module that is dynamic in nature
     *
     * @return a module where the name is not in the top level of the modules directory
     */
    public boolean isDynamic()
    {
        return _dynamic;
    }

    public boolean hasFiles(BaseHome baseHome, Props props)
    {
        for (String ref : getFiles())
        {
            FileArg farg = new FileArg(this, props.expand(ref));
            Path refPath = baseHome.getBasePath(farg.location);
            if (!Files.exists(refPath))
            {
                return false;
            }
        }
        return true;
    }

    public void process(BaseHome basehome) throws FileNotFoundException, IOException
    {
        Pattern section = Pattern.compile("\\s*\\[([^]]*)\\]\\s*");

        if (!FS.canReadFile(_path))
        {
            StartLog.debug("Skipping read of missing file: %s", basehome.toShortForm(_path));
            return;
        }

        try (BufferedReader buf = Files.newBufferedReader(_path, StandardCharsets.UTF_8))
        {
            String sectionType = "";
            String line;
            while ((line = buf.readLine()) != null)
            {
                line = line.trim();

                Matcher sectionMatcher = section.matcher(line);

                if (sectionMatcher.matches())
                {
                    sectionType = sectionMatcher.group(1).trim().toUpperCase(Locale.ENGLISH);
                }
                else
                {
                    // blank lines and comments are valid for ini-template section
                    if ((line.length() == 0) || line.startsWith("#"))
                    {
                        // Remember ini comments and whitespace (empty lines)
                        // for the [ini-template] section
                        if ("INI-TEMPLATE".equals(sectionType))
                        {
                            // Exclude asciidoc tag lines used in documentation.
                            if (!line.contains("tag::") && !line.contains("end::"))
                                _iniTemplate.add(line);
                        }
                    }
                    else
                    {
                        switch (sectionType)
                        {
                            case "":
                                // ignore (this would be entries before first section)
                                break;
                            case "DESCRIPTION":
                                _description.add(line);
                                break;
                            case "DEPEND":
                            case "DEPENDS":
                                if (!_depends.contains(line))
                                    _depends.add(line);
                                break;
                            case "DEPRECATED":
                                _deprecated.add(line);
                                break;
                            case "ENV":
                            case "ENVIRONMENT":
                                _environment = line;
                                break;
                            case "FILE":
                            case "FILES":
                                _files.add(line);
                                break;
                            case "TAG":
                            case "TAGS":
                                _tags.add(line);
                                break;
                            case "DEFAULTS": // old name introduced in 9.2.x
                            case "INI": // new name for 9.3+
                            {
                                // If a property is specified as `<k>=<v>` it is to be treated as `<k>?=<v>`.
                                // All other property usages are left as-is (eg: `<k>+=<v>`)
                                int idx = line.indexOf('=');
                                if (idx > 0)
                                {
                                    String key = line.substring(0, idx);
                                    String value = line.substring(idx + 1);
                                    if (key.endsWith("?") || key.endsWith("+"))
                                    {
                                        // already the correct way
                                        _ini.add(line);
                                    }
                                    else
                                    {
                                        _ini.add(String.format("%s?=%s", key, value));
                                    }
                                }
                                else
                                {
                                    _ini.add(line);
                                }
                                break;
                            }
                            case "INI-TEMPLATE":
                                _iniTemplate.add(line);
                                break;
                            case "LIB":
                            case "LIBS":
                                _libs.add(line);
                                break;
                            case "JPMS":
                                _jpms.add(line);
                                break;
                            case "LICENSE":
                            case "LICENSES":
                            case "LICENCE":
                            case "LICENCES":
                                _license.add(line);
                                break;
                            case "NAME":
                                StartLog.warn("Deprecated [name] used in %s", basehome.toShortForm(_path));
                                _provides.add(line);
                                break;
                            case "PROVIDE":
                            case "PROVIDES":
                                _provides.add(line);
                                break;
                            case "BEFORE":
                                _before.add(line);
                                break;
                            case "OPTIONAL":
                            case "AFTER":
                                _after.add(line);
                                break;
                            case "EXEC":
                                _jvmArgs.add(line);
                                break;
                            case "VERSION":
                                if (version != null)
                                {
                                    throw new IOException("[version] already specified");
                                }
                                version = new Version(line);
                                break;
                            case "XML":
                                _xmls.add(line);
                                break;
                            default:
                                throw new IOException("Unrecognized module section: [" + sectionType + "]");
                        }
                    }
                }
            }
        }

        if (version == null)
        {
            version = new Version(VERSION_UNSPECIFIED);
        }
    }

    public boolean clearTransitiveEnable()
    {
        if (_notTransitive)
            throw new IllegalStateException("Not Transitive");
        if (isEnabled())
        {
            _enables.clear();
            return true;
        }
        return false;
    }

    public void setSkipFilesValidation(boolean skipFilesValidation)
    {
        this._skipFilesValidation = skipFilesValidation;
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append(getName());
        char sep = '{';
        if (isDynamic())
        {
            str.append(sep).append("dynamic");
            sep = ',';
        }
        if (isEnabled())
        {
            str.append(sep).append("enabled");
            sep = ',';
        }
        if (isTransitive())
        {
            str.append(sep).append("transitive");
            sep = ',';
        }
        if (sep != '{')
            str.append('}');
        return str.toString();
    }

    public List<String> getDepends()
    {
        return new ArrayList<>(_depends);
    }

    public List<String> getDeprecated()
    {
        return List.copyOf(_deprecated);
    }

    public Set<String> getProvides()
    {
        return new HashSet<>(_provides);
    }

    public Set<String> getBefore()
    {
        return Set.copyOf(_before);
    }

    public Set<String> getAfter()
    {
        return Set.copyOf(_after);
    }

    /**
     * @return the module names in the [after] section
     * @deprecated use {@link #getAfter()} instead
     */
    @Deprecated
    public Set<String> getOptional()
    {
        return getAfter();
    }

    public List<String> getDescription()
    {
        return _description;
    }

    public List<String> getTags()
    {
        return _tags;
    }

    public String getPrimaryTag()
    {
        return _tags.isEmpty() ? "untagged" : _tags.get(0);
    }

    public boolean isEnabled()
    {
        return !_enables.isEmpty();
    }

    public Set<String> getEnableSources()
    {
        return new HashSet<>(_enables);
    }

    /**
     * @param source String describing where the module was enabled from
     * @param transitive True if the enable is transitive
     * @return true if the module was not previously enabled
     */
    public boolean enable(String source, boolean transitive)
    {
        boolean updated = _enables.isEmpty();
        if (transitive)
        {
            // Ignore transitive selections if explicitly enabled
            if (!_notTransitive)
                _enables.add(source);
        }
        else
        {
            if (!_notTransitive)
            {
                // Ignore transitive selections if explicitly enabled
                updated = true;
                _enables.clear(); // clear any transitive enabling
            }
            _notTransitive = true;
            _enables.add(source);
        }
        return updated;
    }

    public boolean isTransitive()
    {
        return isEnabled() && !_notTransitive;
    }

    public void writeIniSection(BufferedWriter writer, Props props)
    {
        PrintWriter out = new PrintWriter(writer);
        out.println("# --------------------------------------- ");
        out.println("# Module: " + getName());
        for (String line : getDescription())
        {
            out.append("# ").println(line);
        }
        out.println("# --------------------------------------- ");
        out.println("--module=" + getName());
        out.println();
        for (String line : getIniTemplate())
        {
            Matcher m = SET_PROPERTY.matcher(line);
            if (m.matches() && m.groupCount() == 3)
            {
                String name = m.group(2);
                String value = m.group(3);
                Prop p = props.getProp(name);

                if (p != null && (p.source == null || !p.source.endsWith("?=")) && ("#".equals(m.group(1)) || !value.equals(p.value)))
                {
                    System.err.printf("%s == %s :: %s%n", name, value, p.source);
                    StartLog.info("%-15s property set %s=%s", this._name, name, p.value);
                    out.printf("%s=%s%n", name, p.value);
                }
                else
                    out.println(line);
            }
            else
                out.println(line);
        }
        out.println();
        out.flush();
    }

    @Override
    public int compareTo(Module m)
    {
        int byTag = getPrimaryTag().compareTo(m.getPrimaryTag());
        if (byTag != 0)
            return byTag;
        return getName().compareTo(m.getName());
    }
}
