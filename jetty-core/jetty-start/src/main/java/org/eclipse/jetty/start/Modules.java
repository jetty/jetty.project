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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.util.TopologicalSort;

/**
 * Access for all modules declared, as well as what is enabled.
 */
public class Modules implements Iterable<Module>
{
    // List of ALL modules in BaseHome.
    private final List<Module> _modules = new ArrayList<>();
    // Map of Modules by name.
    private final Map<String, Module> _names = new HashMap<>();
    // Map of provided modules by name.
    private final Map<String, Set<Module>> _provided = new HashMap<>();
    // Map of provided default module names.
    private final Map<String, String> _providedDefaults = new HashMap<>();
    private final BaseHome _baseHome;
    private final StartArgs _args;
    private final Properties _deprecated = new Properties();

    public Modules(BaseHome basehome, StartArgs args)
    {
        this._baseHome = basehome;
        this._args = args;

        // Allow override mostly for testing
        if (!args.getJettyEnvironment().getProperties().containsKey("java.version"))
        {
            String javaVersion = System.getProperty("java.version");
            if (javaVersion != null)
            {
                args.setProperty(null, "java.version", javaVersion, "<internal>");
            }
        }

        try
        {
            Path deprecatedPath = _baseHome.getPath("modules/deprecated.properties");
            if (deprecatedPath != null && FS.exists(deprecatedPath))
            {
                try (InputStream inputStream = Files.newInputStream(deprecatedPath))
                {
                    _deprecated.load(inputStream);
                }
            }
        }
        catch (IOException e)
        {
            StartLog.debug(e);
        }
    }

    public void showModules(PrintStream out, List<String> modules)
    {
        Stream<Module> stream = (modules.contains("*") || modules.isEmpty())
            ? _modules.stream().sorted()
            : modules.stream().map(this::get);

        stream.forEach(module ->
        {
            if (module == null)
                return;

            String label;
            Set<String> provides = module.getProvides();
            provides.remove(module.getName());
            out.printf("%n     Module: %s %s%n", module.getName(), !provides.isEmpty() ? provides : "");
            for (String description : module.getDescription())
            {
                out.printf("           : %s%n", description);
            }
            if (module.isEnvironmentInherited())
            {
                out.println("Environment: " + Module.ENVIRONMENT_INHERITED);
            }
            else
            {
                out.printf("Environment: %s%n", module.getEnvironment());
            }
            if (!module.getTags().isEmpty())
            {
                label = "       Tags: %s";
                for (String t : module.getTags())
                {
                    out.printf(label, t);
                    label = ", %s";
                }
                out.println();
            }
            if (!module.getDepends().isEmpty())
            {
                label = "     Depend: %s";
                for (String parent : module.getDepends())
                {
                    parent = Module.normalizeModuleName(parent);
                    out.printf(label, parent);
                    if (Module.isConditionalDependency(parent))
                        out.print(" [conditional]");
                    label = ", %s";
                }
                out.println();
            }
            if (!module.getBefore().isEmpty())
            {
                label = "     Before: %s";
                for (String before : module.getBefore())
                {
                    out.printf(label, before);
                    label = ", %s";
                }
                out.println();
            }
            if (!module.getAfter().isEmpty())
            {
                label = "      After: %s";
                for (String after : module.getAfter())
                {
                    out.printf(label, after);
                    label = ", %s";
                }
                out.println();
            }
            for (String lib : module.getLibs())
            {
                out.printf("        LIB: %s%n", lib);
            }
            for (String xml : module.getXmls())
            {
                out.printf("        XML: %s%n", xml);
            }
            for (String jpms : module.getJPMS())
            {
                out.printf("        JPMS: %s%n", jpms);
            }
            for (String jvm : module.getJvmArgs())
            {
                out.printf("        JVM: %s%n", jvm);
            }
            for (String environmentName : module.getEnabledEnvironments())
            {
                out.printf("    Enabled: %s%n", environmentName);
            }
        });
    }

    public void listModules(PrintStream out, List<String> tags)
    {
        if (tags.contains("-*"))
            return;

        tags = new ArrayList<>(tags);

        boolean wild = tags.remove("*");
        boolean showDeprecated = tags.remove("deprecated") || wild;

        Set<String> included = new HashSet<>();
        if (!wild)
            tags.stream().filter(t -> !t.startsWith("-")).forEach(included::add);

        Set<String> excluded = new HashSet<>();
        tags.stream().filter(t -> t.startsWith("-")).map(t -> t.substring(1)).forEach(excluded::add);
        if (!included.contains("internal"))
            excluded.add("internal");

        Predicate<Module> filter = m -> (included.isEmpty() || m.getTags().stream().anyMatch(included::contains)) &&
            m.getTags().stream().noneMatch(excluded::contains);

        Optional<Integer> max = _modules.stream().filter(filter).map(Module::getName).map(String::length).max(Integer::compareTo);
        if (max.isEmpty())
            return;
        String format = "  %-" + max.get() + "s - %s%s%n";

        Comparator<Module> comparator = wild ? Comparator.comparing(Module::getName) : Module::compareTo;
        AtomicReference<String> tag = new AtomicReference<>();
        _modules.stream().filter(filter).sorted(comparator).forEach(module ->
        {
            if (module.isDeprecated() && !showDeprecated)
                return;
            if (module.isDynamic())
                return;

            if (!wild && !module.getPrimaryTag().equals(tag.get()))
            {
                tag.set(module.getPrimaryTag());
                out.printf("%n%s modules:", module.getPrimaryTag());
                out.printf("%n%s---------%n", "-".repeat(module.getPrimaryTag().length()));
            }

            List<String> description = module.getDescription();
            out.printf(format, module.getName(), module.isDeprecated() ? "DEPRECATED " : "", description != null && !description.isEmpty() ? description.get(0) : "");
        });
    }

    public void listEnabled(PrintStream out)
    {
        out.println();
        out.println("Enabled Modules:");
        out.println("----------------");

        int i = 0;
        List<Module> enabled = getEnabled();
        for (Module module : enabled)
        {
            String index = (i++) + ")";
            String name = module.getName();
            if (module.isDeprecated())
                name += " (deprecated)";
            for (String envName : module.getEnabledEnvironments())
            {
                out.printf("%4s %-25s %s %s%n", index, name, envName, module.isEnvironmentInherited() ? "<inherit>" : "");
                index = "";
                name = "";
            }
        }
    }

    public void registerAll() throws IOException
    {
        for (Path path : _baseHome.getPaths("modules/*.mod"))
        {
            registerModule(path);
        }
    }

    private Module registerModule(Path file)
    {
        if (!FS.canReadFile(file))
        {
            throw new IllegalStateException("Cannot read file: " + file);
        }
        String shortName = _baseHome.toShortForm(file);
        try
        {
            StartLog.debug("Registering Module: %s", shortName);
            Module module = new Module(_baseHome, file);
            _modules.add(module);
            _names.put(module.getName(), module);
            module.getProvides().forEach(name ->
            {
                _provided.computeIfAbsent(name, k -> new HashSet<>()).add(module);
                if (module.isProvidesDefault(name))
                    _providedDefaults.putIfAbsent(name, module.getName());
            });

            return module;
        }
        catch (Error | RuntimeException t)
        {
            throw t;
        }
        catch (Throwable t)
        {
            throw new IllegalStateException("Unable to register module: " + shortName, t);
        }
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append("Modules[");
        str.append("count=").append(_modules.size());
        str.append(",<");
        final AtomicBoolean delim = new AtomicBoolean(false);
        _modules.forEach(m ->
        {
            if (delim.get())
                str.append(',');
            str.append(m.getName());
            if (m.isEnvironmentInherited())
                str.append("<env:inherit>");
            else
                str.append("(env:").append(m.getEnvironment()).append(")");
            delim.set(true);
        });
        str.append(">");
        str.append("]");
        return str.toString();
    }

    /**
     * Get a List of ANY enabled Modules.
     *
     * @return the List
     */
    public List<Module> getEnabled()
    {
        List<Module> enabled = _modules.stream()
            .filter(Module::isEnabledInAnyEnvironment)
            .collect(Collectors.toList());

        TopologicalSort<Module> sort = new TopologicalSort<>();
        for (Module module : enabled)
        {
            Consumer<String> add = name ->
            {
                Module dependency = _names.get(name);
                if (dependency != null && dependency.isEnabledInAnyEnvironment())
                    sort.addDependency(module, dependency);

                Set<Module> provided = _provided.get(name);
                if (provided != null)
                {
                    for (Module p : provided)
                    {
                        if (p.isEnabledInAnyEnvironment())
                            sort.addDependency(module, p);
                    }
                }
            };
            module.getDepends().forEach(add);
            module.getAfter().forEach(add);
            module.getBefore().forEach(name ->
            {
                Module before = _names.get(name);
                if (before != null && before.isEnabledInAnyEnvironment())
                    sort.addDependency(before, module);
            });
        }

        sort.sort(enabled);
        return enabled;
    }

    /**
     * Get a List of enabled Modules that belong to the specified environment name.
     *
     * @param environmentName the environment name to limit results to.
     * @return a List of {@link Module} objects that are enabled in the specified environment name.
     */
    public List<Module> getEnabled(String environmentName)
    {
        List<Module> enabled = _modules.stream()
            .filter(m -> m.isEnabledInEnvironment(environmentName))
            .filter(m ->
            {
                if (m.isEnvironmentInherited())
                    return true;
                else
                    return m.getEnvironment().equalsIgnoreCase(environmentName);
            })
            .collect(Collectors.toList());

        TopologicalSort<Module> sort = new TopologicalSort<>();
        for (Module module : enabled)
        {
            Consumer<String> add = name ->
            {
                Module dependency = _names.get(name);
                if (dependency != null && dependency.isEnabledInEnvironment(environmentName))
                    sort.addDependency(module, dependency);

                Set<Module> provided = _provided.get(name);
                if (provided != null)
                {
                    for (Module p : provided)
                    {
                        if (p.isEnabledInEnvironment(environmentName))
                            sort.addDependency(module, p);
                    }
                }
            };
            module.getDepends().forEach(add);
            module.getAfter().forEach(add);
            module.getBefore().forEach(name ->
            {
                Module before = _names.get(name);
                if (before != null && before.isEnabledInEnvironment(environmentName))
                    sort.addDependency(before, module);
            });
        }

        sort.sort(enabled);
        return enabled;
    }

    public List<Module> getSortedAll()
    {
        List<Module> all = new ArrayList<>(_modules);

        TopologicalSort<Module> sort = new TopologicalSort<>();
        for (Module module : all)
        {
            Consumer<String> add = name ->
            {
                Module dependency = _names.get(name);
                if (dependency != null)
                    sort.addDependency(module, dependency);

                Set<Module> provided = _provided.get(name);
                if (provided != null)
                {
                    for (Module p : provided)
                    {
                        sort.addDependency(module, p);
                    }
                }
            };
            module.getDepends().forEach(add);
            module.getAfter().forEach(add);
            module.getBefore().forEach(name ->
            {
                Module before = _names.get(name);
                if (before != null)
                    sort.addDependency(before, module);
            });
        }

        sort.sort(all);
        return all;
    }

    public List<String> getSortedNames(Set<String> enabledModules)
    {
        return getSortedAll().stream()
            .map(Module::getName)
            .filter(enabledModules::contains)
            .collect(Collectors.toList());
    }

    /**
     * Enable a module
     *
     * @param name The name of the module to enable
     * @param enabledFrom The source the module was enabled from
     * @return The set of modules newly enabled
     */
    public Set<String> enable(String name, String enabledFrom)
    {
        Module module = get(name);
        if (module == null)
            throw new UsageException(UsageException.ERR_UNKNOWN, "Unknown module='%s'. List available with --list-modules", name);

        if (module.isEnvironmentInherited())
            throw new UsageException(UsageException.ERR_UNKNOWN, "Unable to directly select module='%s' as it only works when used as a dependency from a module that has an environment");

        Set<String> enabled = new HashSet<>();
        enable(enabled, module, module.getEnvironment(), enabledFrom, false);
        return enabled;
    }

    /**
     * Enable module, and then walk the transitive dependencies to enable other required modules.
     *
     * @param newlyEnabled the list of newly enabled modules.
     * @param module the module to enable
     * @param environmentNameScope the environment name scope (for the initial non-transitive enablement, used for modules with inherited environments)
     * @param enabledFrom how the module was enabled
     * @param transitive true if this enablement comes from a transitive walk of dependencies
     */
    private void enable(Set<String> newlyEnabled, Module module, String environmentNameScope, String enabledFrom, boolean transitive)
    {
        if (newlyEnabled.contains(module.getName()))
        {
            if (module.isEnvironmentInherited())
            {
                // Trigger inherited enablement within (possibly new) environment name scope
                module.enable(environmentNameScope, enabledFrom, transitive);
            }
            else
            {
                StartLog.info("%s already enabled by [%s]", module.getName(), module.getEnabledFromEnvironment(module.getEnvironment()));
            }
            return;
        }

        StartLog.debug("Enable [%s] in env [%s] from [%s] transitive=%b", module, environmentNameScope, enabledFrom, transitive);

        if (module.isDeprecated())
        {
            String reason = module.getDeprecated().stream().collect(Collectors.joining(System.lineSeparator()));
            StartLog.warn(reason);
        }

        // Check the "provides" list to ensure that it is only provided once.
        for (String name : module.getProvides())
        {
            Set<Module> providers = _provided.get(name);
            if (providers != null)
            {
                for (Module p : providers)
                {
                    if (p.equals(module))
                        continue; // skip self
                    if (p.isEnabledInAnyEnvironment())
                    {
                        // If the already enabled module is transitive and this enable is not,
                        // allow the explicit module to replace the transitive default provider.
                        // (Ported from jetty-12.1.x Modules.java)
                        if (p.isTransitive() && !transitive)
                            p.clearTransitiveEnablement();
                        else
                            throw new UsageException("Module %s provides %s, which is already provided by %s enabled in %s", module.getName(), name, p.getName(), p.getEnabledFromAllEnvironments());
                    }
                }
            }
        }

        // Enable the module
        if (module.enable(environmentNameScope, enabledFrom, transitive))
        {
            StartLog.debug("Enabled [%s]", module.getName());
            newlyEnabled.add(module.getName());

            // Expand module properties
            module.expandDependencies(_args.getJettyEnvironment().getProperties());

            // Apply default configuration
            if (module.hasDefaultConfig())
            {
                String source = module.getName() + "[ini]";
                StartEnvironment jettyEnvironment = _args.getJettyEnvironment();
                jettyEnvironment = _args.parse(jettyEnvironment, "--module=" + module.getName(), source);

                for (String line : module.getIniSection())
                    jettyEnvironment = _args.parse(jettyEnvironment, line, source);

                for (Module m : _modules)
                    m.expandDependencies(jettyEnvironment.getProperties());
            }
        }

        // Process module dependencies (always processed as may be dynamic)
        StartLog.debug("Enabled module [%s] depends on %s", module.getName(), module.getDepends());
        for (String dependsOnRaw : module.getDepends())
        {
            boolean isConditional = Module.isConditionalDependency(dependsOnRaw);
            // Final to allow lambda's below to use name
            final String dependentModule = Module.normalizeModuleName(dependsOnRaw);

            // Figure out if the referenced dependency is ...
            //  1. A module that is listed in a [provided] somewhere
            //  2. A registered real module.
            //  3. A dynamic module reference. (only if dependency has a '/' character)

            // Is it a dynamic module?
            if (Module.isDynamicDependency(dependentModule))
            {
                Path file = _baseHome.getPath("modules/" + dependentModule + ".mod");
                if (!isConditional || Files.exists(file))
                {
                    Module dynamic = get(dependentModule);
                    if (dynamic == null)
                    {
                        dynamic = registerModule(file);
                        dynamic.expandDependencies(_args.getJettyEnvironment().getProperties());
                    }
                    enable(newlyEnabled, dynamic, environmentNameScope, "dynamic dependency of " + module.getName(), true);
                }
            }
            else
            {
                // Reference as a named provider.
                Set<Module> providers = getAvailableProviders(dependentModule);

                if (providers.isEmpty())
                {
                    // Reference as a previously registered module.
                    Module dependent = get(dependentModule);

                    if (dependent == null)
                    {
                        // is this a conditional module
                        if (isConditional)
                        {
                            StartLog.debug("Skipping conditional module [%s]: it does not exist", dependentModule);
                            continue;
                        }

                        // throw an exception (not a dynamic module and a required dependency)
                        throw new UsageException("No module found for dependency %s from %s", dependentModule, module);
                    }

                    // Process real dependency.
                    enable(newlyEnabled, dependent, environmentNameScope, "transitive dependency " + dependentModule + " from " + module.getName(), true);
                }
                else
                {
                    // Process as a module with a named provider
                    StartLog.debug("Module [%s] depends on [%s] provided by %s", module, dependentModule, providers);

                    // If a provider is already enabled, then add a transitive enable
                    if (providers.stream().anyMatch(Module::isEnabledInAnyEnvironment))
                    {
                        providers.stream()
                            .filter(m -> m.isEnabledInAnyEnvironment() && !m.equals(module))
                            .forEach(m -> enable(newlyEnabled, m, environmentNameScope, "transitive provider of " + dependentModule + " for " + module.getName(), true));
                    }
                    else
                    {
                        Optional<Module> dftProvider = findDefaultProvider(providers, dependentModule);

                        if (dftProvider.isPresent())
                        {
                            StartLog.debug("Using [%s] provider as default for [%s]", dftProvider.get(), dependentModule);
                            enable(newlyEnabled, dftProvider.get(), environmentNameScope, "transitive provider of " + dependentModule + " for " + module.getName(), true);
                        }
                    }
                }
            }
        }
    }

    private Optional<Module> findDefaultProvider(Set<Module> providers, String dependsOn)
    {
        // Is it obvious?
        if (providers.size() == 1)
            return providers.stream().findFirst();

        // If more than one provider impl, is there one specified as "default"?
        if (providers.size() > 1)
        {
            // Was it specified with [provides] "name|default" ?
            String defaultProviderName = _providedDefaults.get(dependsOn);
            if (defaultProviderName != null)
            {
                return providers.stream().filter(m -> m.getName().equals(defaultProviderName)).findFirst();
            }

            // Or does a module exist with the same name as the [provides] "name"
            return providers.stream().filter(m -> m.getName().equals(dependsOn)).findFirst();
        }

        // No default provider
        return Optional.empty();
    }

    private Set<Module> getAvailableProviders(String name)
    {
        // Get all available providers 
        Set<Module> providers = _provided.get(name);
        StartLog.debug("Providers of [%s] are %s", name, providers);
        if (providers == null || providers.isEmpty())
            return Set.of();

        StartLog.debug("Available providers of [%s] are %s", name, providers);
        return providers;
    }

    public Module get(String name)
    {
        Module module = _names.get(name);
        if (module == null)
        {
            String reason = _deprecated.getProperty(name);
            if (reason != null)
                StartLog.warn("Module %s is no longer available: %s", name, reason);
        }
        return module;
    }

    @Override
    public Iterator<Module> iterator()
    {
        return _modules.iterator();
    }

    public Stream<Module> stream()
    {
        return _modules.stream();
    }

    public List<String> getEnabledEnvironments()
    {
        return _modules.stream()
            .filter(Module::isEnabledInAnyEnvironment)
            .flatMap(m -> m.getEnabledEnvironments().stream())
            .distinct()
            .sorted()
            .toList();
    }

    public void checkEnabledModules()
    {
        List<String> enabledEnvironments = getEnabledEnvironments();

        for (String environmentName: enabledEnvironments)
        {
            checkEnabledModules(environmentName);
        }
    }

    public void checkEnabledModules(String environmentName)
    {
        StringBuilder unsatisfied = new StringBuilder();
        _modules.stream()
            .filter(m -> m.isEnabledInEnvironment(environmentName))
            .forEach(m ->
        {
            // Check dependencies
            m.getDepends().stream()
                .filter(depends -> !Module.isConditionalDependency(depends))
                // filter unsatisfied dependencies (likely from a provided or dynamic)
                .filter(depends -> _names.get(depends) == null)
                .forEach(d ->
                {
                    // Ensure referenced dependency is satisfied via a provided name
                    Set<Module> providers = getAvailableProviders(d);
                    if (providers.stream().noneMatch(Module::isEnabledInAnyEnvironment))
                    {
                        if (!unsatisfied.isEmpty())
                            unsatisfied.append(',');
                        unsatisfied.append(m.getName());
                        StartLog.error("Module [%s] requires a module providing [%s] from one of %s%n", m.getName(), d, providers);
                    }
                });
        });

        if (!unsatisfied.isEmpty())
            throw new UsageException(-1, "Unsatisfied module dependencies: " + unsatisfied);
    }
}
