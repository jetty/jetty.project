//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.util.TopologicalSort;

/**
 * Access for all modules declared, as well as what is enabled.
 */
public class Modules implements Iterable<Module>
{
    private final List<Module> modules = new ArrayList<>();
    private final Map<String,Module> names = new HashMap<>();
    private final BaseHome baseHome;
    private final StartArgs args;

    public Modules(BaseHome basehome, StartArgs args)
    {
        this.baseHome = basehome;
        this.args = args;
        
        String java_version = System.getProperty("java.version");
        if (java_version!=null)
        {
            args.setProperty("java.version",java_version,"<internal>",false);
        }        
    }

    public void dump()
    {
        List<String> ordered = modules.stream().map(m->{return m.getName();}).collect(Collectors.toList());
        Collections.sort(ordered);
        ordered.stream().map(n->{return get(n);}).forEach(module->
        {
            String status = "[ ]";
            if (module.isTransitive())
            {
                status = "[t]";
            }
            else if (module.isSelected())
            {
                status = "[x]";
            }

            System.out.printf("%n %s Module: %s%n",status,module.getName());
            if (!module.getName().equals(module.getFilesystemRef()))
            {
                System.out.printf("        Ref: %s%n",module.getFilesystemRef());
            }
            for (String description : module.getDescription())
            {
                System.out.printf("           : %s%n",description);
            }
            for (String parent : module.getDepends())
            {
                System.out.printf("     Depend: %s%n",parent);
            }
            for (String optional : module.getOptional())
            {
                System.out.printf("   Optional: %s%n",optional);
            }
            for (String lib : module.getLibs())
            {
                System.out.printf("        LIB: %s%n",lib);
            }
            for (String xml : module.getXmls())
            {
                System.out.printf("        XML: %s%n",xml);
            }
            for (String jvm : module.getJvmArgs())
            {
                System.out.printf("        JVM: %s%n",jvm);
            }
            if (module.isSelected())
            {
                for (String selection : module.getSelections())
                {
                    System.out.printf("    Enabled: %s%n",selection);
                }
            }
        });
    }

    public void dumpSelected()
    {
        int i=0;
        for (Module module:getSelected())
        {
            String name=module.getName();
            String index=(i++)+")";
            for (String s:module.getSelections())
            {
                System.out.printf("  %4s %-15s %s%n",index,name,s);
                index="";
                name="";
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

    private Module registerModule(Path file)
    {
        if (!FS.canReadFile(file))
        {
            throw new IllegalStateException("Cannot read file: " + file);
        }
        String shortName = baseHome.toShortForm(file);
        try
        {
            StartLog.debug("Registering Module: %s",shortName);
            Module module = new Module(baseHome,file);
            modules.add(module);
            names.put(module.getName(),module);
            if (module.isDynamic())
                names.put(module.getFilesystemRef(),module);
            return module;
        }
        catch (Error|RuntimeException t)
        {
            throw t;
        }
        catch (Throwable t)
        {
            throw new IllegalStateException("Unable to register module: " + shortName,t);
        }
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append("Modules[");
        str.append("count=").append(modules.size());
        str.append(",<");
        final AtomicBoolean delim = new AtomicBoolean(false);
        modules.forEach(m->
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

    public void sort()
    {
        TopologicalSort<Module> sort = new TopologicalSort<>();
        for (Module module: modules)
        {
            Consumer<String> add = name ->
            {
                Module dependency = names.get(name);
                if (dependency!=null)
                    sort.addDependency(module,dependency);
            };
            module.getDepends().forEach(add);
            module.getOptional().forEach(add);
        }
        sort.sort(modules);
    }

    public List<Module> getSelected()
    {
        return modules.stream().filter(m->{return m.isSelected();}).collect(Collectors.toList());
    }

    public Set<String> select(String name, String enabledFrom)
    {
        Module module = get(name);
        if (module==null)
            throw new UsageException(UsageException.ERR_UNKNOWN,"Unknown module='%s'",name);

        Set<String> enabled = new HashSet<>();
        enable(enabled,module,enabledFrom,false);
        return enabled;
    }

    private void enable(Set<String> enabled,Module module, String enabledFrom, boolean transitive)
    {
        StartLog.debug("enable %s from %s transitive=%b",module,enabledFrom,transitive);
        if (module.addSelection(enabledFrom,transitive))
        {
            StartLog.debug("enabled %s",module.getName());
            enabled.add(module.getName());
            module.expandProperties(args.getProperties());
            if (module.hasDefaultConfig())
            {
                for(String line:module.getDefaultConfig())
                    args.parse(line,module.getFilesystemRef(),false);
                for (Module m:modules)
                    m.expandProperties(args.getProperties());
            }
        }
        else if (module.isTransitive() && module.hasIniTemplate())
            enabled.add(module.getName());
        
        for(String name:module.getDepends())
        {
            Module depends = names.get(name);
            StartLog.debug("%s depends on %s/%s",module,name,depends);
            if (depends==null)
            {
                Path file = baseHome.getPath("modules/" + name + ".mod");
                depends = registerModule(file);
                depends.expandProperties(args.getProperties());
            }
            
            if (depends!=null)
                enable(enabled,depends,"transitive from "+module.getName(),true);
        }
    }
    
    public Module get(String name)
    {
        return names.get(name);
    }

    @Override
    public Iterator<Module> iterator()
    {
        return modules.iterator();
    }

    public Stream<Module> stream()
    {
        return modules.stream();
    }
    
}
