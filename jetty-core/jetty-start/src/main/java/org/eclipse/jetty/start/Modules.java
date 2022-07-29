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
    private final List<Module> _modules = new ArrayList<>();
    private final Map<String, Module> _names = new HashMap<>();
    private final Map<String, Set<Module>> _provided = new HashMap<>();
    private final Map<String, String> _providedDefaults = new HashMap<>();
    private final BaseHome _baseHome;
    private final StartArgs _args;
    private final Properties _deprecated = new Properties();

    public Modules(BaseHome basehome, StartArgs args)
    {
        this._baseHome = basehome;
        this._args = args;

        // Allow override mostly for testing
        if (!args.getCoreEnvironment().getProperties().containsKey("java.version"))
        {
            String javaVersion = System.getProperty("java.version");
            if (javaVersion != null)
            {
                // TODO environment
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
            out.printf("%n     Module: %s %s%n", module.getName(), provides.size() > 0 ? provides : "");
            for (String description : module.getDescription())
            {
                out.printf("           : %s%n", description);
            }
            if (module.getEnvironment() != null)
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
            if (module.isEnabled())
            {
                for (String selection : module.getEnableSources())
                {
                    out.printf("    Enabled: %s%n", selection);
                }
            }
        });
    }

    public void listModules(PrintStream out, List<String> tags)
    {
        if (tags.contains("-*"))
            return;

        tags = new ArrayList<>(tags);

        boolean wild = tags.contains("*");
        Set<String> included = new HashSet<>();
        if (wild)
            tags.remove("*");
        else
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
        String format = "%" + max.get() + "s - %s%n";

        Comparator<Module> comparator = wild ? Comparator.comparing(Module::getName) : Module::compareTo;
        AtomicReference<String> tag = new AtomicReference<>();
        _modules.stream().filter(filter).sorted(comparator).forEach(module ->
        {
            if (!wild && !module.getPrimaryTag().equals(tag.get()))
            {
                tag.set(module.getPrimaryTag());
                out.printf("%n%s modules:", module.getPrimaryTag());
                out.printf("%n%s---------%n", "-".repeat(module.getPrimaryTag().length()));
            }

            List<String> description = module.getDescription();
            out.printf(format, module.getName(), description != null && description.size() > 0 ? description.get(0) : "");
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
            if (!module.getDeprecated().isEmpty())
                name += " (deprecated)";
            for (String s : module.getEnableSources())
            {
                out.printf("%4s %-25s %s%n", index, name, s);
                index = "";
                name = "";
            }
            if (module.isTransitive() && module.hasIniTemplate())
                out.printf(" ".repeat(31) + "ini template available with --add-module=%s%n", module.getName());
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
            module.getProvides().forEach(n ->
            {
                // Syntax can be :
                // "<name>" - for a simple provider reference
                // "<name>|default" - for a provider that is also the default implementation
                String name = n;
                boolean isDefaultProvider = false;
                int idx = n.indexOf('|');
                if (idx > 0)
                {
                    name = n.substring(0, idx);
                    isDefaultProvider = n.substring(idx + 1).equalsIgnoreCase("default");
                }
                _provided.computeIfAbsent(name, k -> new HashSet<>()).add(module);
                if (isDefaultProvider)
                {
                    _providedDefaults.computeIfAbsent(name, k -> module.getName());
                }
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
            delim.set(true);
        });
        str.append(">");
        str.append("]");
        return str.toString();
    }

    public List<Module> getEnabled()
    {
        List<Module> enabled = _modules.stream().filter(Module::isEnabled).collect(Collectors.toList());

        TopologicalSort<Module> sort = new TopologicalSort<>();
        for (Module module : enabled)
        {
            Consumer<String> add = name ->
            {
                Module dependency = _names.get(name);
                if (dependency != null && dependency.isEnabled())
                    sort.addDependency(module, dependency);

                Set<Module> provided = _provided.get(name);
                if (provided != null)
                {
                    for (Module p : provided)
                    {
                        if (p.isEnabled())
                            sort.addDependency(module, p);
                    }
                }
            };
            module.getDepends().forEach(add);
            module.getAfter().forEach(add);
            module.getBefore().forEach(name ->
            {
                Module before = _names.get(name);
                if (before != null && before.isEnabled())
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

        Set<String> enabled = new HashSet<>();
        enable(enabled, module, enabledFrom, false);
        return enabled;
    }

    private void enable(Set<String> newlyEnabled, Module module, String enabledFrom, boolean transitive)
    {
        StartLog.debug("Enable [%s] from [%s] transitive=%b", module, enabledFrom, transitive);

        if (newlyEnabled.contains(module.getName()))
        {
            StartLog.debug("Already enabled [%s] from %s", module.getName(), module.getEnableSources());
            return;
        }

        List<String> deprecated = module.getDeprecated();
        if (!deprecated.isEmpty())
        {
            String reason = deprecated.stream().collect(Collectors.joining(System.lineSeparator()));
            StartLog.warn(reason);
        }

        // Check that this is not already provided by another module!
        for (String name : module.getProvides())
        {
            Set<Module> providers = _provided.get(name);
            if (providers != null)
            {
                for (Module p : providers)
                {
                    if (!p.equals(module) && p.isEnabled())
                    {
                        // If the already enabled module is transitive and this enable is not
                        if (p.isTransitive() && !transitive)
                            p.clearTransitiveEnable();
                        else
                            throw new UsageException("Module %s provides %s, which is already provided by %s enabled in %s", module.getName(), name, p.getName(), p.getEnableSources());
                    }
                }
            }
        }

        // Enable the module
        if (module.enable(enabledFrom, transitive))
        {
            StartLog.debug("Enabled [%s]", module.getName());
            newlyEnabled.add(module.getName());

            // Expand module properties
            module.expandDependencies(_args.getCoreEnvironment().getProperties());

            // Apply default configuration
            if (module.hasDefaultConfig())
            {
                String source = module.getName() + "[ini]";
                Environment environment = _args.getCoreEnvironment();
                environment = _args.parse(environment, "--module=" + module.getName(), source);

                for (String line : module.getIniSection())
                    environment = _args.parse(environment, line, source);

                for (Module m : _modules)
                    m.expandDependencies(environment.getProperties());
            }
        }

        // Process module dependencies (always processed as may be dynamic)
        StartLog.debug("Enabled module [%s] depends on %s", module.getName(), module.getDepends());
        for (String dependsOnRaw : module.getDepends())
        {
            boolean isConditional = Module.isConditionalDependency(dependsOnRaw);
            // Final to allow lambda's below to use name
            final String dependentModule = Module.normalizeModuleName(dependsOnRaw);

            // Look for modules that provide that dependency
            Set<Module> providers = getAvailableProviders(dependentModule);

            StartLog.debug("Module [%s] depends on [%s] provided by %s", module, dependentModule, providers);

            // If there are no known providers of the module
            if (providers.isEmpty())
            {
                // look for a dynamic module
                if (dependentModule.contains("/"))
                {
                    Path file = _baseHome.getPath("modules/" + dependentModule + ".mod");
                    if (!isConditional || Files.exists(file))
                    {
                        registerModule(file).expandDependencies(_args.getCoreEnvironment().getProperties());
                        providers = _provided.get(dependentModule);
                        if (providers == null || providers.isEmpty())
                            throw new UsageException("Module %s does not provide %s", _baseHome.toShortForm(file), dependentModule);

                        enable(newlyEnabled, providers.stream().findFirst().get(), "dynamic dependency of " + module.getName(), true);
                        continue;
                    }
                }
                // is this a conditional module
                if (isConditional)
                {
                    StartLog.debug("Skipping conditional module [%s]: it does not exist", dependentModule);
                    continue;
                }
                // throw an exception (not a dynamic module and a required dependency)
                throw new UsageException("No module found to provide %s for %s", dependentModule, module);
            }

            // If a provider is already enabled, then add a transitive enable
            if (providers.stream().anyMatch(Module::isEnabled))
                providers.stream().filter(m -> m.isEnabled() && !m.equals(module)).forEach(m -> enable(newlyEnabled, m, "transitive provider of " + dependentModule + " for " + module.getName(), true));
            else
            {
                Optional<Module> dftProvider = findDefaultProvider(providers, dependentModule);

                if (dftProvider.isPresent())
                {
                    StartLog.debug("Using [%s] provider as default for [%s]", dftProvider.get(), dependentModule);
                    enable(newlyEnabled, dftProvider.get(), "transitive provider of " + dependentModule + " for " + module.getName(), true);
                }
            }
        }
    }

    private Optional<Module> findDefaultProvider(Set<Module> providers, String dependsOn)
    {
        // Is it obvious?
        if (providers.size() == 1)
            return providers.stream().findFirst();

        // If more then one provider impl, is there one specified as "default"?
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

        providers = new HashSet<>(providers);

        // find all currently provided names by other modules
        Set<String> provided = new HashSet<>();
        for (Module m : _modules)
        {
            if (m.isEnabled())
            {
                provided.add(m.getName());
                provided.addAll(m.getProvides());
            }
        }

        // Remove any that cannot be selected
        for (Iterator<Module> i = providers.iterator(); i.hasNext(); )
        {
            Module provider = i.next();
            if (!provider.isEnabled())
            {
                for (String p : provider.getProvides())
                {
                    if (provided.contains(p))
                    {
                        StartLog.debug("Removing provider %s because %s already enabled", provider, p);
                        i.remove();
                        break;
                    }
                }
            }
        }

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

    public void checkEnabledModules()
    {
        StringBuilder unsatisfied = new StringBuilder();
        _modules.stream().filter(Module::isEnabled).forEach(m ->
        {
            // Check dependencies
            m.getDepends().stream()
                .filter(depends -> !Module.isConditionalDependency(depends))
                .forEach(d ->
                {
                    Set<Module> providers = getAvailableProviders(d);
                    if (providers.stream().noneMatch(Module::isEnabled))
                    {
                        if (unsatisfied.length() > 0)
                            unsatisfied.append(',');
                        unsatisfied.append(m.getName());
                        StartLog.error("Module [%s] requires a module providing [%s] from one of %s%n", m.getName(), d, providers);
                    }
                });
        });

        if (unsatisfied.length() > 0)
            throw new UsageException(-1, "Unsatisfied module dependencies: " + unsatisfied);
    }
}
