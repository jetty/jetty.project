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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Access for all modules declared, as well as what is enabled.
 */
public class Modules implements Iterable<Module>
{
    private Map<String, Module> modules = new HashMap<>();

    private void bfsCalculateDepth(final Module module, final int depthNow)
    {
        int depth = depthNow + 1;

        // Set depth on every child first
        for (Module child : module.getChildEdges())
        {
            child.setDepth(Math.max(depth,child.getDepth()));
        }

        // Dive down
        for (Module child : module.getChildEdges())
        {
            bfsCalculateDepth(child,depth);
        }
    }

    /**
     * Using the provided dependenies, build the module graph
     */
    public void buildGraph()
    {
        // TODO: Validate / Enforce Directed Acyclic Graph

        // Connect edges
        for (Module module : modules.values())
        {
            for (String parentName : module.getParentNames())
            {
                Module parent = get(parentName);
                if (parent != null)
                {
                    module.addParentEdge(parent);
                    parent.addChildEdge(module);
                }
            }
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
        Collections.sort(ordered,Collections.reverseOrder(new Module.DepthComparator()));

        for (Module module : ordered)
        {
            System.out.printf("Module: %s%n",module.getName());
            System.out.printf("  depth: %d%n",module.getDepth());
            System.out.printf("  parents: [%s]%n",join(module.getParentNames(),','));
            for (String xml : module.getXmls())
            {
                System.out.printf("  xml: %s%n",xml);
            }
        }
    }

    public void enable(String name)
    {
        Module module = modules.get(name);
        if (module == null)
        {
            System.err.printf("WARNING: Cannot enable requested module [%s]: not a valid module name.%n",name);
            return;
        }
        module.setEnabled(true);
    }

    private void findParents(Module module, Set<Module> active)
    {
        active.add(module);
        for (Module parent : module.getParentEdges())
        {
            active.add(parent);
            findParents(parent,active);
        }
    }

    public Module get(String name)
    {
        Module module = modules.get(name);
        if (module == null)
        {
            System.err.printf("WARNING: module not found [%s]%n",name);
        }
        return module;
    }

    @Override
    public Iterator<Module> iterator()
    {
        return modules.values().iterator();
    }

    private String join(Collection<?> objs, char delim)
    {
        StringBuilder str = new StringBuilder();
        boolean needDelim = false;
        for (Object obj : objs)
        {
            if (needDelim)
            {
                str.append(delim);
            }
            str.append(obj);
            needDelim = true;
        }
        return str.toString();
    }

    // TODO: Resolve LIB names to actual java.io.File references via HomeBase
    // TODO: Handle ${jetty.version} references here
    // TODO: Handle *.jar filesystem glob style here
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

    // TODO: Resolve XML names to actual java.io.File references via HomeBase
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
}
