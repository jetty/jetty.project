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

package org.eclipse.jetty.xml;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Builder of {@link Environment}s intended to be used in XML
 * files processed by {@code start.jar}.
 */
public class EnvironmentBuilder
{
    private static final Logger LOG = LoggerFactory.getLogger(EnvironmentBuilder.class);

    private final String _name;
    private final List<Path> _classPath = new ArrayList<>();
    private final List<Path> _modulePath = new ArrayList<>();
    private final List<String> _jpmsAdds = new ArrayList<>();
    private final List<String> _jpmsOpens = new ArrayList<>();
    private final List<String> _jpmsExports = new ArrayList<>();
    private final List<String> _jpmsReads = new ArrayList<>();

    public EnvironmentBuilder(@Name("name") String name)
    {
        _name = name;
    }

    public void addClassPath(String... classPaths)
    {
        for (String classPath : classPaths)
        {
            _classPath.add(Paths.get(classPath));
        }
    }

    public void addModulePath(String modulePath)
    {
        _modulePath.add(Paths.get(modulePath));
    }

    void addModules(String modules)
    {
        _jpmsAdds.addAll(List.of(modules.split(",")));
    }

    void patchModule(String patch)
    {
        // Not supported by the ModuleLayer.Controller APIs.
    }

    void addOpens(String opens)
    {
        _jpmsOpens.add(opens);
    }

    void addExports(String exports)
    {
        _jpmsExports.add(exports);
    }

    void addReads(String reads)
    {
        _jpmsReads.add(reads);
    }

    public Environment build() throws Exception
    {
        if (_modulePath.isEmpty())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Building environment {} with class-path {}", _name, _classPath);
            return new Environment.Named(_name, new URLClassLoader(toURLs(_classPath), Environment.class.getClassLoader()));
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Building environment {} with module-path {} and class-path {}", _name, _modulePath, _classPath);
        Module parentModule = Environment.class.getModule();
        ModuleLayer parentModuleLayer = parentModule.getLayer();
        ModuleFinder moduleFinder = ModuleFinder.of(_modulePath.toArray(Path[]::new));
        // Collect all module names to resolve them.
        // This is equivalent to the command-line option
        // --add-modules ALL-MODULE-PATH, but for this ModuleLayer only.
        Set<String> roots = moduleFinder.findAll().stream()
            .map(moduleReference -> moduleReference.descriptor().name())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        // Add the module names to resolve provided by the configuration,
        // typically from the [jpms] section of *.mod files.
        roots.addAll(_jpmsAdds);
        Configuration configuration = parentModuleLayer.configuration().resolve(moduleFinder, ModuleFinder.of(), roots);
        ModuleLayer.Controller controller = ModuleLayer.defineModulesWithOneLoader(configuration, List.of(parentModuleLayer), parentModule.getClassLoader());
        addOpens(controller, _jpmsOpens);
        addExports(controller, _jpmsExports);
        addReads(controller, _jpmsReads);
        ClassLoader moduleLayerClassLoader = controller.layer().modules().stream().findAny().orElseThrow().getClassLoader();
        ClassLoader environmentClassLoader = _classPath.isEmpty() ? moduleLayerClassLoader : new URLClassLoader(toURLs(_classPath), moduleLayerClassLoader);
        return new Environment.Named(_name, environmentClassLoader);
    }

    private static URL[] toURLs(List<Path> paths)
    {
        return paths.stream().map(EnvironmentBuilder::toURL).toArray(URL[]::new);
    }

    private static URL toURL(Path path)
    {
        try
        {
            return path.toUri().toURL();
        }
        catch (MalformedURLException e)
        {
            throw new RuntimeException(e);
        }
    }

    private void addOpens(ModuleLayer.Controller controller, List<String> opens)
    {
        for (String open : opens)
        {
            SourcePackageTargets spt = SourcePackageTargets.from(open);
            Module sourceModule = controller.layer().findModule(spt.sourceModuleName()).orElseThrow();
            spt.targetModuleNames().forEach(targetModuleName ->
            {
                Module targetModule = controller.layer().findModule(targetModuleName).orElse(null);
                if (targetModule != null)
                    controller.addOpens(sourceModule, spt.packageName(), targetModule);
            });
        }
    }

    private void addExports(ModuleLayer.Controller controller, List<String> exports)
    {
        for (String export : exports)
        {
            SourcePackageTargets spt = SourcePackageTargets.from(export);
            Module sourceModule = controller.layer().findModule(spt.sourceModuleName()).orElseThrow();
            spt.targetModuleNames().forEach(targetModuleName ->
            {
                Module targetModule = controller.layer().findModule(targetModuleName).orElse(null);
                if (targetModule != null)
                    controller.addExports(sourceModule, spt.packageName(), targetModule);
            });
        }
    }

    private void addReads(ModuleLayer.Controller controller, List<String> reads)
    {
        for (String read : reads)
        {
            int equal = read.indexOf('=');
            String sourceModuleName = read.substring(0, equal);
            String targetModuleName = read.substring(equal + 1);
            Module sourceModule = controller.layer().findModule(sourceModuleName).orElseThrow();
            Module targetModule = controller.layer().findModule(targetModuleName).orElse(null);
            if (targetModule != null)
                controller.addReads(sourceModule, targetModule);
        }
    }

    private record SourcePackageTargets(String sourceModuleName, String packageName, List<String> targetModuleNames)
    {
        public static SourcePackageTargets from(String option)
        {
            int slash = option.indexOf('/');
            String sourceModuleName = option.substring(0, slash);
            int equal = option.indexOf('=', slash + 1);
            String packageName = option.substring(slash + 1, equal);
            List<String> targetModuleNames = List.of(option.substring(equal + 1).split(","));
            return new SourcePackageTargets(sourceModuleName, packageName, targetModuleNames);
        }
    }
}
