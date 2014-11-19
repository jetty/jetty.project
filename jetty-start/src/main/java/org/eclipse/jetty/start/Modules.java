//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.start.graph.Graph;
import org.eclipse.jetty.start.graph.GraphException;
import org.eclipse.jetty.start.graph.NodeDepthComparator;
import org.eclipse.jetty.start.graph.OnlyTransitivePredicate;
import org.eclipse.jetty.start.graph.Selection;

/**
 * Access for all modules declared, as well as what is enabled.
 */
public class Modules extends Graph<Module>
{
    private final BaseHome baseHome;
    private final StartArgs args;

    //    /*
    //     * modules that may appear in the resolved graph but are undefined in the module system
    //     * 
    //     * ex: modules/npn/npn-1.7.0_01.mod (property expansion resolves to non-existent file)
    //     */
    //    private Set<String> missingModules = new HashSet<String>();

    public Modules(BaseHome basehome, StartArgs args)
    {
        this.baseHome = basehome;
        this.args = args;
        this.setSelectionTerm("enable");
        this.setNodeTerm("module");
    }

    //    public void clearMissing()
    //    {
    //        missingModules.clear();
    //    }

    public void dump()
    {
        List<Module> ordered = new ArrayList<>();
        ordered.addAll(getNodes());
        Collections.sort(ordered,new Module.NameComparator());

        List<Module> active = getEnabled();

        for (Module module : ordered)
        {
            boolean activated = active.contains(module);
            boolean selected = module.isSelected();
            boolean transitive = selected && module.matches(OnlyTransitivePredicate.INSTANCE);

            String status = "[ ]";
            if (transitive)
            {
                status = "[t]";
            }
            else if (selected)
            {
                status = "[x]";
            }

            System.out.printf("%n %s Module: %s%n",status,module.getName());
            if (!module.getName().equals(module.getFilesystemRef()))
            {
                System.out.printf("        Ref: %s%n",module.getFilesystemRef());
            }
            for (String parent : module.getParentNames())
            {
                System.out.printf("     Depend: %s%n",parent);
            }
            for (String lib : module.getLibs())
            {
                System.out.printf("        LIB: %s%n",lib);
            }
            for (String xml : module.getXmls())
            {
                System.out.printf("        XML: %s%n",xml);
            }
            if (StartLog.isDebugEnabled())
            {
                System.out.printf("      depth: %d%n",module.getDepth());
            }
            if (activated)
            {
                for (Selection selection : module.getSelections())
                {
                    System.out.printf("    Enabled: <via> %s%n",selection);
                }
            }
            else
            {
                System.out.printf("    Enabled: <not enabled in this configuration>%n");
            }
        }
    }

    //    public int enableAll(List<String> names, String source) throws IOException
    //    {
    //        if ((names == null) || (names.isEmpty()))
    //        {
    //            // nothing to do
    //            return 0;
    //        }
    //
    //        List<String> sources = Collections.singletonList(source);
    //
    //        int count = 0;
    //        for (String name : names)
    //        {
    //            count += enable(name,sources);
    //        }
    //        return count;
    //    }

    //    public int enable(String name, List<String> sources) throws IOException
    //    {
    //        int count = 0;
    //
    //        if (name.contains("*"))
    //        {
    //            // A regex!
    //            List<Module> matching = getMatching(new RegexNamePredicate(name));
    //
    //            // enable them
    //            for (Module module : matching)
    //            {
    //                count += enableModule(module,sources);
    //            }
    //        }
    //        else
    //        {
    //            Module module = get(name);
    //            if (module == null)
    //            {
    //                System.err.printf("WARNING: Cannot enable requested module [%s]: not a valid module name.%n",name);
    //                return count;
    //            }
    //            count += enableModule(module,sources);
    //        }
    //        return count;
    //    }

    //    private int enableModule(Module module, List<String> sources) throws IOException
    //    {
    //        int count = 0;
    //        if (sources == null)
    //        {
    //            // We use source for tagging how a node was selected, it should
    //            // always be required
    //            throw new RuntimeException("sources should never be empty");
    //        }
    //
    //        module.addSources(sources);
    //        String via = Utils.join(sources,", ");
    //
    //        // If already enabled, nothing else to do
    //        if (module.isEnabled())
    //        {
    //            StartLog.debug("Enabled module: %s (via %s)",module.getName(),via);
    //            return count;
    //        }
    //
    //        StartLog.debug("Enabling module: %s (via %s)",module.getName(),via);
    //        module.setEnabled(true);
    //        count++;
    //        args.parseModule(module);
    //        module.expandProperties(args.getProperties());
    //
    //        // enable any parents that haven't been enabled (yet)
    //        Set<String> parentNames = new HashSet<>();
    //        parentNames.addAll(module.getParentNames());
    //        for (String name : parentNames)
    //        {
    //            StartLog.debug("Enable parent '%s' of module: %s",name,module.getName());
    //            Module parent = get(name);
    //            if (parent == null)
    //            {
    //                // parent module doesn't exist, yet
    //                Path file = baseHome.getPath("modules/" + name + ".mod");
    //                if (FS.canReadFile(file))
    //                {
    //                    parent = registerModule(file);
    //                    parent.expandProperties(args.getProperties());
    //                    updateParentReferencesTo(parent);
    //                }
    //                else
    //                {
    //                    if (!Props.hasPropertyKey(name))
    //                    {
    //                        StartLog.debug("Missing module definition: [ Mod: %s | File: %s ]",name,file);
    //                        missingModules.add(name);
    //                    }
    //                }
    //            }
    //            if (parent != null)
    //            {
    //                count += enableModule(parent,sources);
    //            }
    //        }
    //        return count;
    //    }

    @Override
    public Module resolveNode(String name)
    {
        String expandedName = args.getProperties().expand(name);

        if (Props.hasPropertyKey(expandedName))
        {
            throw new GraphException("Unable to expand property in name: " + name);
        }

        Path file = baseHome.getPath("modules/" + expandedName + ".mod");
        if (FS.canReadFile(file))
        {
            Module parent = registerModule(file);
            parent.expandProperties(args.getProperties());
            updateParentReferencesTo(parent);
            return parent;
        }
        else
        {
            if (!Props.hasPropertyKey(name))
            {
                StartLog.debug("Missing module definition: [ Mod: %s | File: %s ]",name,file);
            }
            return null;
        }
    }
    
    @Override
    public void onNodeSelected(Module module)
    {
        args.parseModule(module);
        module.expandProperties(args.getProperties());
    }

    public List<String> normalizeLibs(List<Module> active)
    {
        List<String> libs = new ArrayList<>();
        for (Module module : active)
        {
            for (String lib : module.getLibs())
            {
                if (!libs.contains(lib))
                {
                    libs.add(lib);
                }
            }
        }
        return libs;
    }

    public List<String> normalizeXmls(List<Module> active)
    {
        List<String> xmls = new ArrayList<>();
        for (Module module : active)
        {
            for (String xml : module.getXmls())
            {
                if (!xmls.contains(xml))
                {
                    xmls.add(xml);
                }
            }
        }
        return xmls;
    }

    public void registerParentsIfMissing(Module module) throws IOException
    {
        Set<String> parents = new HashSet<>(module.getParentNames());
        for (String name : parents)
        {
            if (!containsNode(name))
            {
                Path file = baseHome.getPath("modules/" + name + ".mod");
                if (FS.canReadFile(file))
                {
                    Module parent = registerModule(file);
                    updateParentReferencesTo(parent);
                    registerParentsIfMissing(parent);
                }
            }
        }
    }

    public void registerAll() throws IOException
    {
        for (Path path : baseHome.getPaths("modules/*.mod"))
        {
            registerModule(path);
        }
    }

    // load missing post-expanded dependent modules
    public void normalizeDependencies() throws IOException
    {
        Set<String> expandedModules = new HashSet<>();
        boolean done = false;
        while (!done)
        {
            done = true;
            Set<String> missingParents = new HashSet<>();

            for (Module m : getNodes())
            {
                for (String parent : m.getParentNames())
                {
                    String expanded = args.getProperties().expand(parent);
                    if (containsNode(expanded) || expandedModules.contains(parent))
                    {
                        continue; // found. skip it.
                    }
                    done = false;
                    StartLog.debug("Missing parent module %s == %s for %s",parent,expanded,m);
                    missingParents.add(parent);
                }
            }

            for (String missingParent : missingParents)
            {
                String expanded = args.getProperties().expand(missingParent);
                Path file = baseHome.getPath("modules/" + expanded + ".mod");
                if (FS.canReadFile(file))
                {
                    Module module = registerModule(file);
                    updateParentReferencesTo(module);
                    if (!expanded.equals(missingParent))
                    {
                        expandedModules.add(missingParent);
                    }
                }
                else
                {
                    if (Props.hasPropertyKey(expanded))
                    {
                        StartLog.debug("Module property not expandable (yet) [%s]",expanded);
                        expandedModules.add(missingParent);
                    }
                    else
                    {
                        StartLog.debug("Missing module definition: %s expanded to %s",missingParent,expanded);
                        //                        missingModules.add(missingParent);
                    }
                }
            }
        }
    }

    private Module registerModule(Path file)
    {
        if (!FS.canReadFile(file))
        {
            throw new GraphException("Cannot read file: " + file);
        }
        String shortName = baseHome.toShortForm(file);
        try
        {
            StartLog.debug("Registering Module: %s",shortName);
            Module module = new Module(baseHome,file);
            return register(module);
        }
        catch (Throwable t)
        {
            throw new GraphException("Unable to register module: " + shortName,t);
        }
    }

    /**
     * Resolve the execution order of the enabled modules, and all dependent modules, based on depth first transitive
     * reduction.
     * 
     * @return the list of active modules (plus dependent modules), in execution order.
     * @deprecated use {@link #getEnabled()} and {@link #assertModulesValid(Collection)} instead.
     */
    @Deprecated
    public List<Module> resolveEnabled()
    {
        Map<String, Module> active = new HashMap<String, Module>();

        for (Module module : getNodes())
        {
            if (module.isEnabled())
            {
                findParents(module,active);
            }
        }

        assertModulesValid(active.values());

        List<Module> ordered = new ArrayList<>();
        ordered.addAll(active.values());
        Collections.sort(ordered,new NodeDepthComparator());
        return ordered;
    }

    public void assertModulesValid(Collection<Module> active)
    {
        //        /*
        //         * check against the missing modules
        //         * 
        //         * Ex: npn should match anything under npn/
        //         */
        //        for (String missing : missingModules)
        //        {
        //            for (Module module : active)
        //            {
        //                if (missing.startsWith(module.getName()))
        //                {
        //                    StartLog.warn("** Unable to continue, required dependency missing. [%s]",missing);
        //                    StartLog.warn("** As configured, Jetty is unable to start due to a missing enabled module dependency.");
        //                    StartLog.warn("** This may be due to a transitive dependency akin to spdy on npn, which resolves based on the JDK in use.");
        //                    throw new UsageException(UsageException.ERR_BAD_ARG,"Missing referenced dependency: " + missing);
        //                }
        //            }
        //        }
    }

    /**
     * Modules can have a different logical name than to their filesystem reference. This updates existing references to
     * the filesystem form to use the logical
     * name form.
     * 
     * @param module
     *            the module that might have other modules referring to it.
     */
    private void updateParentReferencesTo(Module module)
    {
        if (module.getName().equals(module.getFilesystemRef()))
        {
            // nothing to do, its sane already
            return;
        }

        for (Module m : getNodes())
        {
            Set<String> resolvedParents = new HashSet<>();
            for (String parent : m.getParentNames())
            {
                if (parent.equals(module.getFilesystemRef()))
                {
                    // use logical name instead
                    resolvedParents.add(module.getName());
                }
                else
                {
                    // use name as-is
                    resolvedParents.add(parent);
                }
            }
            m.setParentNames(resolvedParents);
        }
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append("Modules[");
        str.append("count=").append(count());
        str.append(",<");
        boolean delim = false;
        for (String name : getNodeNames())
        {
            if (delim)
            {
                str.append(',');
            }
            str.append(name);
            delim = true;
        }
        str.append(">");
        str.append("]");
        return str.toString();
    }

}
