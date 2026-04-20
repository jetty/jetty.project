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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.util.StringUtil;

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
 * <li>License details for using the module</li>
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
    public static final String ENVIRONMENT_JETTY = "jetty";
    private static final String ENVIRONMENT_INHERITED = "<inherit>";
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
     * List of enabled environments for this Module, mapped to the string of where/how it was enabled.
     */
    private final Map<String, String> _enabledEnvironments = new HashMap<>();

    /**
     * List of provides for this Module, mapped to if this is flagged as the default provider or not.
     */
    private final Map<String, Boolean> _provides = new HashMap<>();

    /**
     * List of tags for this Module
     */
    private final List<String> _tags = new ArrayList<>();

    /**
     * Boolean true if directly enabled, false if enabled via transitive reference
     */
    private boolean _enabledDirectly = false;

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

    /**
     * The specified environment name that this module belongs to.
     */
    private String _environment = ENVIRONMENT_JETTY;

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
        StringBuilder n = new StringBuilder(path.getName(m + 1).toString());
        for (int i = m + 2; i < path.getNameCount(); i++)
        {
            n.append("/").append(path.getName(i));
        }
        Matcher matcher = MOD_NAME.matcher(n.toString());
        if (!matcher.matches())
            throw new IllegalArgumentException("Module filename must have .mod extension: " + basehome.toShortForm(path));
        _name = matcher.group(1);

        // _provides.add(_name); // TODO: do we need this still? it pollutes the provides namespace.
        _dynamic = isDynamicDependency(_name);

        process(basehome);
    }

    public static boolean isDynamicDependency(String depends)
    {
        return depends.contains("/");
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

    /**
     * True if this environment is inherited based on directly enabled modules
     * and it's dependencies.
     *
     * <p>
     * Inherited modules can exist in one or more environments
     * (that are not the default {@code Jetty} environment)
     * </p>
     *
     * @return True if the environment is inherited
     */
    public boolean isEnvironmentInherited()
    {
        return _environment.equalsIgnoreCase(ENVIRONMENT_INHERITED);
    }

    /**
     * Get the environment name that this module belongs to.
     *
     * <p>
     * Important note: Check {@link #isEnvironmentInherited()}
     * before using this environment name, as inherited
     * environments act special.
     * </p>
     *
     * @return the name of the environment
     */
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
        Function<String, String> expander = props::expand;

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
        return !_license.isEmpty();
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

    public void process(BaseHome basehome) throws IOException
    {
        Pattern section = Pattern.compile("\\s*\\[([^]]*)\\]\\s*");

        if (!FS.canReadFile(_path))
        {
            StartLog.debug("Skipping read of missing file: %s", basehome.toShortForm(_path));
            return;
        }

        String providedSection = null;

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
                    if ((line.isEmpty()) || line.startsWith("#"))
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
                            case "" ->
                            {
                                // ignore (this would be entries before first section)
                            }
                            case "DESCRIPTION" -> _description.add(line);
                            case "DEPEND", "DEPENDS" ->
                            {
                                if (!_depends.contains(line))
                                    _depends.add(line);
                            }
                            case "DEPRECATED" -> _deprecated.add(line);
                            case "ENV", "ENVIRONMENT" ->
                            {
                                if (line.equalsIgnoreCase(ENVIRONMENT_INHERITED))
                                {
                                    _environment = ENVIRONMENT_INHERITED;
                                }
                                else if (StringUtil.isBlank(line))
                                {
                                    _environment = ENVIRONMENT_JETTY;
                                }
                                else
                                {
                                    _environment = line.trim().toLowerCase(Locale.ROOT);
                                }
                            }
                            case "FILE", "FILES" -> _files.add(line);
                            case "TAG", "TAGS" -> _tags.add(line);
                            case "DEFAULTS", // old name introduced in 9.2.x
                                 "INI" -> // new name for 9.3+
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
                            }
                            case "INI-TEMPLATE" -> _iniTemplate.add(line);
                            case "LIB", "LIBS" -> _libs.add(line);
                            case "JPMS" -> _jpms.add(line);
                            case "LICENSE", "LICENSES", "LICENCE", "LICENCES" -> _license.add(line);
                            case "NAME" ->
                            {
                                StartLog.warn("Deprecated section called [name] used in %s", basehome.toShortForm(_path));
                                addProvides(line);
                            }
                            case "PROVIDE", "PROVIDES" -> addProvides(line);
                            case "BEFORE" -> _before.add(line);
                            case "OPTIONAL", "AFTER" -> _after.add(line);
                            case "EXEC" -> _jvmArgs.add(line);
                            case "VERSION" ->
                            {
                                if (version != null)
                                {
                                    throw new IOException("[version] already specified");
                                }
                                version = new Version(line);
                            }
                            case "XML" -> _xmls.add(line);
                            default -> throw new IOException("Unrecognized module section: [" + sectionType + "]");
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

    private void addProvides(String rawName)
    {
        // Syntax can be :
        // "<name>" - for a simple provider reference
        // "<name>|default" - for a provider that is also the default implementation
        String name = rawName;
        boolean isDefaultProvider = false;
        int idx = name.indexOf('|');
        if (idx > 0)
        {
            name = rawName.substring(0, idx);
            isDefaultProvider = rawName.substring(idx + 1).equalsIgnoreCase("default");
        }

        Boolean previous = _provides.putIfAbsent(name, isDefaultProvider);
        if (previous != null && previous != isDefaultProvider)
        {
            throw new IllegalStateException("Unable to reset default state of provider [%s]".formatted(name));
        }
    }

    public void setSkipFilesValidation(boolean skipFilesValidation)
    {
        this._skipFilesValidation = skipFilesValidation;
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append(getName()).append('{');
        boolean delim = false;
        if (isDynamic())
        {
            str.append("dynamic");
            delim = true;
        }
        if (!getEnabledEnvironments().isEmpty())
        {
            if (delim)
                str.append(',');
            str.append("envs=[");
            str.append(String.join(",", getEnabledEnvironments()));
            str.append("]");
            delim = true;
        }
        if (delim)
            str.append(',');
        if (isTransitive())
        {
            str.append("transitive");
        }
        else
        {
            str.append("directly-enabled");
        }

        str.append('}');
        return str.toString();
    }

    public List<String> getDepends()
    {
        return new ArrayList<>(_depends);
    }

    public boolean isDeprecated()
    {
        return !_deprecated.isEmpty();
    }

    public List<String> getDeprecated()
    {
        return List.copyOf(_deprecated);
    }

    public Set<String> getProvides()
    {
        return new HashSet<>(_provides.keySet());
    }

    public boolean isProvidesDefault(String name)
    {
        Boolean defaultProvider = _provides.get(name);
        if (defaultProvider == null)
            return false;
        return defaultProvider;
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

    /**
     * @deprecated use {@link #isEnabledIn(String)} instead.
     */
    @Deprecated(since = "13.0.0", forRemoval = true)
    public boolean isEnabled()
    {
        return !_enabledEnvironments.isEmpty();
    }

    public boolean isEnabledInAny()
    {
        return !_enabledEnvironments.isEmpty();
    }

    public boolean isEnabledIn(String environmentName)
    {
        return _enabledEnvironments.containsKey(environmentName.toLowerCase(Locale.ROOT));
    }

    public Set<String> getEnabledEnvironments()
    {
        return _enabledEnvironments.keySet();
    }

    /**
     * No longer used.
     *
     * @deprecated use {@link #getEnabledEnvironments()} instead.
     */
    @Deprecated(since = "13.0.0", forRemoval = true)
    public Set<String> getEnableSources()
    {
        return _enabledEnvironments.keySet();
    }

    public List<String> getEnabledFromAll()
    {
        return _enabledEnvironments.entrySet()
            .stream()
            .map(e ->
            {
                if (e.getKey().equals(ENVIRONMENT_JETTY))
                    return e.getValue();
                else
                    return "%s:%s".formatted(e.getKey(), e.getValue());
            })
            .toList();
    }

    public String getEnabledFrom(String environmentName)
    {
        return _enabledEnvironments.get(environmentName);
    }

    /**
     * Enable this module under the environment name context.
     *
     * <p>
     * The list of enabled environments can be updated based
     * on the module's own environment name, or if inherited, the provided environment name.
     * </p>
     *
     * @param environmentScope The environment name scope this module was enabled in.
     * @param enabledFrom the source of this enablement (used for help / listing / debug reasons)
     * @param transitive True if the enable is transitive
     * @return {@code true} if this module was not already enabled for the specified environment name.
     * @see #getEnabledEnvironments()
     */
    public boolean enable(String environmentScope, String enabledFrom, boolean transitive)
    {
        Objects.requireNonNull(enabledFrom, "Source cannot be null");
        String environmentName = isEnvironmentInherited() ? environmentScope.toLowerCase(Locale.ROOT) : getEnvironment();
        if (!transitive)
            _enabledDirectly = true;
        return _enabledEnvironments.putIfAbsent(environmentName, enabledFrom) == null;
    }

    public boolean isTransitive()
    {
        return !_enabledDirectly;
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
        out.println("--modules=" + getName());
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
