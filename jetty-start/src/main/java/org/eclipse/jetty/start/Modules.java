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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Pattern;

/**
 * Access for all modules declared, as well as what is enabled.
 */
public class Modules implements Iterable<Module>
{
    private Map<String, Module> modules = new HashMap<>();
    /*
     * modules that may appear in the resolved graph but are undefined in the module system
     * 
     * ex: modules/npn/npn-1.7.0_01.mod (property expansion resolves to non-existent file)
     */
    private Set<String> missingModules = new HashSet<String>();

    private int maxDepth = -1;

    private Set<String> asNameSet(Set<Module> moduleSet)
    {
        Set<String> ret = new HashSet<>();
        for (Module module : moduleSet)
        {
            ret.add(module.getName());
        }
        return ret;
    }

    private void assertNoCycle(Module module, Stack<String> refs)
    {
        for (Module parent : module.getParentEdges())
        {
            if (refs.contains(parent.getName()))
            {
                // Cycle detected.
                StringBuilder err = new StringBuilder();
                err.append("A cyclic reference in the modules has been detected: ");
                for (int i = 0; i < refs.size(); i++)
                {
                    if (i > 0)
                    {
                        err.append(" -> ");
                    }
                    err.append(refs.get(i));
                }
                err.append(" -> ").append(parent.getName());
                throw new IllegalStateException(err.toString());
            }

            refs.push(parent.getName());
            assertNoCycle(parent,refs);
            refs.pop();
        }
    }

    private void bfsCalculateDepth(final Module module, final int depthNow)
    {
        int depth = depthNow + 1;

        // Set depth on every child first
        for (Module child : module.getChildEdges())
        {
            child.setDepth(Math.max(depth,child.getDepth()));
            this.maxDepth = Math.max(this.maxDepth,child.getDepth());
        }

        // Dive down
        for (Module child : module.getChildEdges())
        {
            bfsCalculateDepth(child,depth);
        }
    }

    /**
     * Using the provided dependencies, build the module graph
     */
    public void buildGraph()
    {
        // Connect edges
        for (Module module : modules.values())
        {
            for (String parentName : module.getParentNames())
            {
                Module parent = get(parentName);

                if (parent == null)
                {
                    if (parentName.contains("${"))
                        StartLog.debug("module not found [%s]%n",parentName);
                    else
                        StartLog.warn("module not found [%s]%n",parentName);
                }
                else
                {
                    module.addParentEdge(parent);
                    parent.addChildEdge(module);
                }
            }

            for (String optionalParentName : module.getOptionalParentNames())
            {
                Module optional = get(optionalParentName);
                if (optional == null)
                {
                    StartLog.debug("optional module not found [%s]%n",optionalParentName);
                }
                else if (optional.isEnabled())
                {
                    module.addParentEdge(optional);
                    optional.addChildEdge(module);
                }
            }
        }

        // Verify there is no cyclic references
        Stack<String> refs = new Stack<>();
        for (Module module : modules.values())
        {
            refs.push(module.getName());
            assertNoCycle(module,refs);
            refs.pop();
        }

        // Calculate depth of all modules for sorting later
        for (Module module : modules.values())
        {
            if (module.getParentEdges().isEmpty())
            {
                bfsCalculateDepth(module,0);
            }
        }
    }

    public Integer count()
    {
        return modules.size();
    }

    public void dump()
    {
        List<Module> ordered = new ArrayList<>();
        ordered.addAll(modules.values());
        Collections.sort(ordered,new Module.NameComparator());

        List<Module> active = resolveEnabled();

        for (Module module : ordered)
        {
            boolean activated = active.contains(module);
            boolean enabled = module.isEnabled();
            boolean transitive = activated && !enabled;

            char status = '-';
            if (enabled)
            {
                status = '*';
            }
            else if (transitive)
            {
                status = '+';
            }

            System.out.printf("%n %s Module: %s%n",status,module.getName());
            if (!module.getName().equals(module.getFilesystemRef()))
            {
                System.out.printf("      Ref: %s%n",module.getFilesystemRef());
            }
            for (String parent : module.getParentNames())
            {
                System.out.printf("   Depend: %s%n",parent);
            }
            for (String lib : module.getLibs())
            {
                System.out.printf("      LIB: %s%n",lib);
            }
            for (String xml : module.getXmls())
            {
                System.out.printf("      XML: %s%n",xml);
            }
            if (StartLog.isDebugEnabled())
            {
                System.out.printf("    depth: %d%n",module.getDepth());
            }
            if (activated)
            {
                for (String source : module.getSources())
                {
                    System.out.printf("  Enabled: <via> %s%n",source);
                }
                if (transitive)
                {
                    System.out.printf("  Enabled: <via transitive reference>%n");
                }
            }
            else
            {
                System.out.printf("  Enabled: <not enabled in this configuration>%n");
            }
        }
    }

    public void dumpEnabledTree()
    {
        List<Module> ordered = new ArrayList<>();
        ordered.addAll(modules.values());
        Collections.sort(ordered,new Module.DepthComparator());

        List<Module> active = resolveEnabled();

        for (Module module : ordered)
        {
            if (active.contains(module))
            {
                // Show module name
                String indent = toIndent(module.getDepth());
                System.out.printf("%s + Module: %s [%s]%n",indent,module.getName(),module.isEnabled()?"enabled":"transitive");
            }
        }
    }

    public void enable(String name, List<String> sources)
    {
        if (name.contains("*"))
        {
            // A regex!
            Pattern pat = Pattern.compile(name);
            for (Map.Entry<String, Module> entry : modules.entrySet())
            {
                if (pat.matcher(entry.getKey()).matches())
                {
                    enableModule(entry.getValue(),sources);
                }
            }
        }
        else
        {
            Module module = modules.get(name);
            if (module == null)
            {
                System.err.printf("WARNING: Cannot enable requested module [%s]: not a valid module name.%n",name);
                return;
            }
            enableModule(module,sources);
        }
    }

    private void enableModule(Module module, List<String> sources)
    {
        StartLog.debug("Enabling module: %s (via %s)",module.getName(),Main.join(sources,", "));
        module.setEnabled(true);
        if (sources != null)
        {
            module.addSources(sources);
        }
    }

    private void findChildren(Module module, Set<Module> ret)
    {
        ret.add(module);
        for (Module child : module.getChildEdges())
        {
            ret.add(child);
        }
    }

    private void findParents(Module module, Map<String, Module> ret)
    {
        ret.put(module.getName(),module);
        for (Module parent : module.getParentEdges())
        {
            ret.put(parent.getName(),parent);
            findParents(parent,ret);
        }
    }

    public Module get(String name)
    {
        return modules.get(name);
    }

    public int getMaxDepth()
    {
        return maxDepth;
    }

    public Set<Module> getModulesAtDepth(int depth)
    {
        Set<Module> ret = new HashSet<>();
        for (Module module : modules.values())
        {
            if (module.getDepth() == depth)
            {
                ret.add(module);
            }
        }
        return ret;
    }

    @Override
    public Iterator<Module> iterator()
    {
        return modules.values().iterator();
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

    public Module register(Module module)
    {
        modules.put(module.getName(),module);
        return module;
    }

    public void registerParentsIfMissing(BaseHome basehome, StartArgs args, Module module) throws IOException
    {
        Set<String> parents = new HashSet<>(module.getParentNames());
        for (String name : parents)
        {
            if (!modules.containsKey(name))
            {
                Path file = basehome.getPath("modules/" + name + ".mod");
                if (FS.canReadFile(file))
                {
                    Module parent = registerModule(basehome,args,file);
                    updateParentReferencesTo(parent);
                    registerParentsIfMissing(basehome, args, parent);
                }
            }
        }
    }
    
    public void registerAll(BaseHome basehome, StartArgs args) throws IOException
    {
        for (Path path : basehome.getPaths("modules/*.mod"))
        {
            registerModule(basehome,args,path);
        }

        // load missing post-expanded dependent modules
        boolean done = false;
        while (!done)
        {
            done = true;
            Set<String> missingParents = new HashSet<>();

            for (Module m : modules.values())
            {
                for (String parent : m.getParentNames())
                {
                    if (modules.containsKey(parent) || missingModules.contains(parent))
                    {
                        continue; // found. skip it.
                    }
                    done = false;
                    missingParents.add(parent);
                }
            }

            for (String missingParent : missingParents)
            {
                Path file = basehome.getPath("modules/" + missingParent + ".mod");
                if (FS.canReadFile(file))
                {
                    Module module = registerModule(basehome,args,file);
                    updateParentReferencesTo(module);
                }
                else
                {
                    StartLog.debug("Missing module definition: [ Mod: %s | File: %s]",missingParent,file);
                    missingModules.add(missingParent);
                }
            }
        }
    }

    private Module registerModule(BaseHome basehome, StartArgs args, Path file) throws FileNotFoundException, IOException
    {
        if (!FS.canReadFile(file))
        {
            throw new IOException("Cannot read file: " + file);
        }
        StartLog.debug("Registering Module: %s",basehome.toShortForm(file));
        Module module = new Module(basehome,file);
        module.expandProperties(args.getProperties());
        return register(module);
    }

    public Set<String> resolveChildModulesOf(String moduleName)
    {
        Set<Module> ret = new HashSet<>();
        Module module = get(moduleName);
        findChildren(module,ret);
        return asNameSet(ret);
    }

    /**
     * Resolve the execution order of the enabled modules, and all dependant modules, based on depth first transitive reduction.
     * 
     * @return the list of active modules (plus dependant modules), in execution order.
     */
    public List<Module> resolveEnabled()
    {
        Map<String, Module> active = new HashMap<String, Module>();

        for (Module module : modules.values())
        {
            if (module.isEnabled())
            {
                findParents(module,active);
            }
        }

        /*
         * check against the missing modules
         * 
         * Ex: npn should match anything under npn/
         */
        for (String missing : missingModules)
        {
            for (String activeModule : active.keySet())
            {
                if (missing.startsWith(activeModule))
                {
                    StartLog.warn("** Unable to continue, required dependency missing. [%s]",missing);
                    StartLog.warn("** As configured, Jetty is unable to start due to a missing enabled module dependency.");
                    StartLog.warn("** This may be due to a transitive dependency akin to spdy on npn, which resolves based on the JDK in use.");
                    return Collections.emptyList();
                }
            }
        }

        List<Module> ordered = new ArrayList<>();
        ordered.addAll(active.values());
        Collections.sort(ordered,new Module.DepthComparator());
        return ordered;
    }

    public Set<String> resolveParentModulesOf(String moduleName)
    {
        Map<String, Module> ret = new HashMap<>();
        Module module = get(moduleName);
        findParents(module,ret);
        return ret.keySet();
    }

    private String toIndent(int depth)
    {
        char indent[] = new char[depth * 2];
        Arrays.fill(indent,' ');
        return new String(indent);
    }

    /**
     * Modules can have a different logical name than to their filesystem reference. This updates existing references to the filesystem form to use the logical
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

        for (Module m : modules.values())
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
        str.append("count=").append(modules.size());
        str.append(",<");
        boolean delim = false;
        for (String name : modules.keySet())
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
