//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.start.graph.Graph;
import org.eclipse.jetty.start.graph.GraphException;
import org.eclipse.jetty.start.graph.OnlyTransitivePredicate;
import org.eclipse.jetty.start.graph.Selection;

/**
 * Access for all modules declared, as well as what is enabled.
 */
public class Modules extends Graph<Module>
{
    private final BaseHome baseHome;
    private final StartArgs args;

    public Modules(BaseHome basehome, StartArgs args)
    {
        this.baseHome = basehome;
        this.args = args;
        this.setSelectionTerm("enable");
        this.setNodeTerm("module");
        
        String java_version = System.getProperty("java.version");
        if (java_version!=null)
        {
            args.setProperty("java.version",java_version,"<internal>",false);
        }        
    }

    public void dump()
    {
        List<Module> ordered = new ArrayList<>();
        ordered.addAll(getNodes());
        Collections.sort(ordered,new Module.NameComparator());

        List<Module> active = getSelected();

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

    @Override
    public Module resolveNode(String name)
    {
        String expandedName = args.getProperties().expand(name);

        if (Props.hasPropertyKey(expandedName))
        {
            StartLog.debug("Not yet able to expand property in: %s",name);
            return null;
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
        StartLog.debug("on node selected: [%s] (%s.mod)",module.getName(),module.getFilesystemRef());
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

    public void registerAll() throws IOException
    {
        for (Path path : baseHome.getPaths("modules/*.mod"))
        {
            registerModule(path);
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
            List<String> resolvedParents = new ArrayList<>();
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
