//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.io.File;
import java.io.IOException;
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

/**
 * Access for all modules declared, as well as what is enabled.
 */
public class Modules implements Iterable<Module>
{
    private Map<String, Module> modules = new HashMap<>();
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
                    System.err.printf("WARNING: module not found [%s]%n",parentName);
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
                    System.err.printf("WARNING: module not found [%s]%n",optionalParentName);
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

        for (Module module : ordered)
        {
            System.out.printf("%nModule: %s%n",module.getName());
            for (String lib : module.getLibs())
            {
                System.out.printf("      LIB: %s%n",lib);
            }
            for (String xml : module.getXmls())
            {
                System.out.printf("      XML: %s%n",xml);
            }
            System.out.printf("  depends: [%s]%n",Main.join(module.getParentNames(),", "));
            if (StartLog.isDebugEnabled())
            {
                System.out.printf("    depth: %d%n",module.getDepth());
            }
            for (String source : module.getSources())
            {
                System.out.printf("  enabled: %s%n",source);
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
        Module module = modules.get(name);
        if (module == null)
        {
            System.err.printf("WARNING: Cannot enable requested module [%s]: not a valid module name.%n",name);
            return;
        }
        StartLog.debug("Enabling module: %s (via %s)",name,Main.join(sources,", "));
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

    private void findParents(Module module, Set<Module> ret)
    {
        ret.add(module);
        for (Module parent : module.getParentEdges())
        {
            ret.add(parent);
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

    public void register(Module module)
    {
        modules.put(module.getName(),module);
    }

    public void registerAll(BaseHome basehome) throws IOException
    {
        for (File file : basehome.listFiles("modules",new FS.FilenameRegexFilter("^.*\\.mod$")))
        {
            register(new Module(file));
        }
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
        Set<Module> active = new HashSet<Module>();

        for (Module module : modules.values())
        {
            if (module.isEnabled())
            {
                findParents(module,active);
            }
        }

        List<Module> ordered = new ArrayList<>();
        ordered.addAll(active);
        Collections.sort(ordered,new Module.DepthComparator());
        return ordered;
    }

    public Set<String> resolveParentModulesOf(String moduleName)
    {
        Set<Module> ret = new HashSet<>();
        Module module = get(moduleName);
        findParents(module,ret);
        return asNameSet(ret);
    }

    private String toIndent(int depth)
    {
        char indent[] = new char[depth * 2];
        Arrays.fill(indent,' ');
        return new String(indent);
    }
}
