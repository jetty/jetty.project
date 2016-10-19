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

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Access for all modules declared, as well as what is enabled.
 */
public class Modules implements Iterable<Module>
{
    private final List<Module> _modules = new ArrayList<>();
    private final Map<String,Module> _names = new HashMap<>();
    private final Map<String,Set<Module>> _provided = new HashMap<>();
    private final BaseHome _baseHome;
    private final StartArgs _args;
    private final Properties _deprecated = new Properties();

    public Modules(BaseHome basehome, StartArgs args)
    {
        this._baseHome = basehome;
        this._args = args;
        
        // Allow override mostly for testing
        if (!args.getProperties().containsKey("java.version"))
        {
            String java_version = System.getProperty("java.version");
            if (java_version!=null)
            {
                args.setProperty("java.version",java_version,"<internal>");
            }   
        }
        
        try
        {
            Path deprecated_path = _baseHome.getPath("modules/deprecated.properties");
            if (deprecated_path!=null && FS.exists(deprecated_path))
            {
                _deprecated.load(new FileInputStream(deprecated_path.toFile()));
            }
        }
        catch (IOException e)
        {
            StartLog.debug(e);
        }
    }

    public void dump(List<String> tags)
    {
        Set<String> exclude = tags.stream().filter(t->t.startsWith("-")).map(t->t.substring(1)).collect(Collectors.toSet());
        Set<String> include = tags.stream().filter(t->!t.startsWith("-")).collect(Collectors.toSet());
        boolean all = include.contains("*") || include.isEmpty();
        AtomicReference<String> tag = new AtomicReference<>();
        
        _modules.stream()
            .filter(m->
            {
                boolean included = all || m.getTags().stream().anyMatch(t->include.contains(t));
                boolean excluded = m.getTags().stream().anyMatch(t->exclude.contains(t));
                return included && !excluded;
            })
            .sorted()
            .forEach(module->
            {
                if (!module.getPrimaryTag().equals(tag.get()))
                {
                    tag.set(module.getPrimaryTag());
                    System.out.printf("%nModules for tag '%s':%n",module.getPrimaryTag());
                    System.out.print("-------------------");
                    for (int i=module.getPrimaryTag().length();i-->0;)
                        System.out.print("-");
                    System.out.println();
                    
                }

                String label;
                Set<String> provides = module.getProvides();
                provides.remove(module.getName());
                System.out.printf("%n     Module: %s %s%n",module.getName(),provides.size()>0?provides:"");
                for (String description : module.getDescription())
                {
                    System.out.printf("           : %s%n",description);
                }
                if (!module.getTags().isEmpty())
                {
                    label="       Tags: %s";
                    for (String t : module.getTags())
                    {
                        System.out.printf(label,t);
                        label=", %s";
                    }
                    System.out.println();
                }
                if (!module.getDepends().isEmpty())
                {
                    label="     Depend: %s";
                    for (String parent : module.getDepends())
                    {
                        System.out.printf(label,parent);
                        label=", %s";
                    }
                    System.out.println();
                }
                if (!module.getOptional().isEmpty())
                {
                    label="   Optional: %s";
                    for (String parent : module.getOptional())
                    {
                        System.out.printf(label,parent);
                        label=", %s";
                    }
                    System.out.println();
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
                if (module.isEnabled())
                {
                    for (String selection : module.getEnableSources())
                    {
                        System.out.printf("    Enabled: %s%n",selection);
                    }
                }
            });
    }

    public void dumpEnabled()
    {
        int i=0;
        List<Module> enabled = getEnabled();
        for (Module module:enabled)
        {
            String name=module.getName();
            String index=(i++)+")";
            for (String s:module.getEnableSources())
            {
                System.out.printf("  %4s %-15s %s%n",index,name,s);
                index="";
                name="";
            }
            if (module.isTransitive() && module.hasIniTemplate())
                System.out.printf("                       init template available with --add-to-start=%s%n",module.getName());
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
            StartLog.debug("Registering Module: %s",shortName);
            Module module = new Module(_baseHome,file);
            _modules.add(module);
            _names.put(module.getName(),module);
            module.getProvides().forEach(n->{
                _provided.computeIfAbsent(n,k->new HashSet<Module>()).add(module);
            });
            
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
        str.append("count=").append(_modules.size());
        str.append(",<");
        final AtomicBoolean delim = new AtomicBoolean(false);
        _modules.forEach(m->
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
        List<Module> enabled = _modules.stream().filter(m->{return m.isEnabled();}).collect(Collectors.toList());

        TopologicalSort<Module> sort = new TopologicalSort<>();
        for (Module module: enabled)
        {
            Consumer<String> add = name ->
            {
                Module dependency = _names.get(name);
                if (dependency!=null && dependency.isEnabled())
                    sort.addDependency(module,dependency);
                
                Set<Module> provided = _provided.get(name);
                if (provided!=null)
                    for (Module p : provided)
                        if (p.isEnabled())
                            sort.addDependency(module,p);
            };
            module.getDepends().forEach(add);
            module.getOptional().forEach(add);
        }

        sort.sort(enabled);
        return enabled;
    }

    /** Enable a module
     * @param name The name of the module to enable
     * @param enabledFrom The source the module was enabled from
     * @return The set of modules newly enabled
     */
    public Set<String> enable(String name, String enabledFrom)
    {
        Module module = get(name);
        if (module==null)
            throw new UsageException(UsageException.ERR_UNKNOWN,"Unknown module='%s'. List available with --list-modules",name);

        Set<String> enabled = new HashSet<>();
        enable(enabled,module,enabledFrom,false);
        return enabled;
    }

    private void enable(Set<String> newlyEnabled, Module module, String enabledFrom, boolean transitive)
    {
        StartLog.debug("enable %s from %s transitive=%b",module,enabledFrom,transitive);
        
        if (newlyEnabled.contains(module.getName()))
        {
            StartLog.debug("Cycle at %s",module);
            return;
        }
        
        // Check that this is not already provided by another module!
        for (String name:module.getProvides())
        {
            Set<Module> providers = _provided.get(name);
            if (providers!=null)
            {
                for (Module p:providers)
                { 
                    if (p!=module && p.isEnabled())
                    {
                        // If the already enabled module is transitive and this enable is not
                        if (p.isTransitive() && !transitive)
                            p.clearTransitiveEnable();
                        else
                            throw new UsageException("Module %s provides %s, which is already provided by %s enabled in %s",module.getName(),name,p.getName(),p.getEnableSources());
                    }
                };
            }   
        }
      
        // Enable the  module
        if (module.enable(enabledFrom,transitive))
        {
            StartLog.debug("enabled %s",module.getName());
            newlyEnabled.add(module.getName());
            
            // Expand module properties
            module.expandProperties(_args.getProperties());
            
            // Apply default configuration
            if (module.hasDefaultConfig())
            {
                for(String line:module.getDefaultConfig())
                    _args.parse(line,module.getName()+"[ini]");
                for (Module m:_modules)
                    m.expandProperties(_args.getProperties());
            }
        }
        
        // Process module dependencies (always processed as may be dynamic)
        for(String dependsOn:module.getDepends())
        {
            // Look for modules that provide that dependency
            Set<Module> providers = getAvailableProviders(dependsOn);
                
            StartLog.debug("Module %s depends on %s provided by ",module,dependsOn,providers);
            
            // If there are no known providers of the module
            if (providers.isEmpty())
            {
                // look for a dynamic module
                if (dependsOn.contains("/"))
                {
                    Path file = _baseHome.getPath("modules/" + dependsOn + ".mod");
                    registerModule(file).expandProperties(_args.getProperties());
                    providers = _provided.get(dependsOn);
                    if (providers==null || providers.isEmpty())
                        throw new UsageException("Module %s does not provide %s",_baseHome.toShortForm(file),dependsOn);

                    enable(newlyEnabled,providers.stream().findFirst().get(),"dynamic dependency of "+module.getName(),true);
                    continue;
                }
                throw new UsageException("No module found to provide %s for %s",dependsOn,module);
            }
            
            // If a provider is already enabled, then add a transitive enable
            if (providers.stream().filter(Module::isEnabled).count()!=0)
                providers.stream().filter(m->m.isEnabled()&&m!=module).forEach(m->enable(newlyEnabled,m,"transitive provider of "+dependsOn+" for "+module.getName(),true));
            else
            {
                // Is there an obvious default?
                Optional<Module> dftProvider = (providers.size()==1)
                    ?providers.stream().findFirst()
                    :providers.stream().filter(m->m.getName().equals(dependsOn)).findFirst();

                if (dftProvider.isPresent())
                    enable(newlyEnabled,dftProvider.get(),"transitive provider of "+dependsOn+" for "+module.getName(),true);
                else if (StartLog.isDebugEnabled())
                    StartLog.debug("Module %s requires a %s implementation from one of %s",module,dependsOn,providers);
            }
        }
    }
    
    private Set<Module> getAvailableProviders(String name)
    {
        // Get all available providers 
        
        Set<Module> providers = _provided.get(name);
        if (providers==null || providers.isEmpty())
            return Collections.emptySet();
        
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
        for (Iterator<Module> i = providers.iterator(); i.hasNext();)
        {
            Module provider = i.next();
            if (!provider.isEnabled())
            {    
                for (String p : provider.getProvides())
                {
                    if (provided.contains(p))
                    {
                        i.remove();
                        break;
                    }
                }
            }
        }
        
        return providers;
    }

    public Module get(String name)
    {
        Module module = _names.get(name);
        if (module==null)
        {
            String reason = _deprecated.getProperty(name);
            if (reason!=null)
                StartLog.warn("Module %s is no longer available: %s",name,reason);
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
        StringBuilder unsatisfied=new StringBuilder();
        _modules.stream().filter(Module::isEnabled).forEach(m->
        {
            // Check dependencies
            m.getDepends().forEach(d->
            {
                Set<Module> providers = getAvailableProviders(d);
                if (providers.stream().filter(Module::isEnabled).count()==0)
                { 
                    if (unsatisfied.length()>0)
                        unsatisfied.append(',');
                    unsatisfied.append(m.getName());
                    StartLog.error("Module %s requires a module providing %s from one of %s%n",m.getName(),d,providers);
                }
            });
        });
        
        if (unsatisfied.length()>0)
            throw new UsageException(-1,"Unsatisfied module dependencies: "+unsatisfied);
    }
    
}
