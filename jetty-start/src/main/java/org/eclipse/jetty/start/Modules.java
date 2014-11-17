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
import java.util.Collection;
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
    public static interface Matcher
    {
        public boolean match(Module module);
    }
    
    public static class AllMatcher implements Matcher
    {
        @Override
        public boolean match(Module module)
        {
            return true;
        }
    }
    
    public static class AndMatcher implements Matcher
    {
        private final Matcher matchers[];
        
        public AndMatcher(Matcher ... matchers)
        {
            this.matchers = matchers;
        }
        
        @Override
        public boolean match(Module module)
        {
            for (Matcher matcher : this.matchers)
            {
                if (!matcher.match(module))
                {
                    return false;
                }
            }

            return true;
        }
    }
    
    public static class EnabledMatcher implements Matcher
    {
        @Override
        public boolean match(Module module)
        {
            return module.isEnabled();
        }
    }
    
    public static class RegexNameMatcher implements Matcher
    {
        private final Pattern pat;
        
        public RegexNameMatcher(String regex)
        {
            this.pat = Pattern.compile(regex);
        }
        
        @Override
        public boolean match(Module module)
        {
            return pat.matcher(module.getName()).matches();
        }
    }
    
    public static class UniqueSourceMatcher implements Matcher
    {
        private final Pattern pat;
        
        public UniqueSourceMatcher(String sourceRegex)
        {
            this.pat = Pattern.compile(sourceRegex);
        }
        
        @Override
        public boolean match(Module module)
        {
            if (module.getSources().size() != 1)
            {
                // Not unique
                return false;
            }

            String sourceId = module.getSources().iterator().next();
            return pat.matcher(sourceId).matches();
        }
    }
    
    public static class SourceSetMatcher implements Matcher
    {
        private final Set<String> nameSet;
        
        public SourceSetMatcher(String... names)
        {
            this.nameSet = new HashSet<>();

            for (String name : names)
            {
                this.nameSet.add(name);
            }
        }

        @Override
        public boolean match(Module module)
        {
            Set<String> sources = module.getSources();
            if (sources == null)
            {
                // empty sources list
                return false;
            }

            if (sources.size() != nameSet.size())
            {
                // non-equal sized set
                return false;
            }

            for (String source : module.getSources())
            {
                if (!this.nameSet.contains(source))
                {
                    return false;
                }
            }
            return true;
        }
        
    }
    
    private final BaseHome baseHome;
    private final StartArgs args;
    
    private Map<String, Module> modules = new HashMap<>();
    /*
     * modules that may appear in the resolved graph but are undefined in the module system
     * 
     * ex: modules/npn/npn-1.7.0_01.mod (property expansion resolves to non-existent file)
     */
    private Set<String> missingModules = new HashSet<String>();

    private int maxDepth = -1;
    
    public Modules(BaseHome basehome, StartArgs args)
    {
        this.baseHome = basehome;
        this.args = args;
    }

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
    public void buildGraph() throws FileNotFoundException, IOException
    {
        normalizeDependencies();
        
        // Connect edges
        for (Module module : modules.values())
        {
            for (String parentName : module.getParentNames())
            {
                Module parent = get(parentName);

                if (parent == null)
                {
                    if (Props.hasPropertyKey(parentName))
                    {
                        StartLog.debug("Module property not expandable (yet) [%s]",parentName);
                    }
                    else
                    {
                        StartLog.warn("Module not found [%s]",parentName);
                    }
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
                    StartLog.debug("Optional module not found [%s]",optionalParentName);
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

    public void clearMissing()
    {
        missingModules.clear();
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

        List<Module> active = getEnabled();

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

    public void enable(String name) throws IOException
    {
        List<String> empty = Collections.emptyList();
        enable(name,empty);
    }

    
    public int enableAll(List<String> names, String source) throws IOException
    {
        if ((names == null) || (names.isEmpty()))
        {
            // nothing to do
            return 0;
        }

        List<String> sources = Collections.singletonList(source);

        int count = 0;
        for (String name : names)
        {
            count += enable(name,sources);
        }
        return count;
    }
    
    public int enable(String name, List<String> sources) throws IOException
    {
        int count = 0;
        
        if (name.contains("*"))
        {
            // A regex!
            List<Module> matching = getMatching(new RegexNameMatcher(name));
            
            // enable them
            for (Module module : matching)
            {
                count += enableModule(module,sources);
            }
        }
        else
        {
            Module module = modules.get(name);
            if (module == null)
            {
                System.err.printf("WARNING: Cannot enable requested module [%s]: not a valid module name.%n",name);
                return count;
            }
            count += enableModule(module,sources);
        }
        return count;
    }

    private int enableModule(Module module, List<String> sources) throws IOException
    {
        int count = 0;
        if(sources == null)
        {
            // We use source for tagging how a node was selected, it should
            // always be required
            throw new RuntimeException("sources should never be empty");
        }
        
        module.addSources(sources);
        String via = Utils.join(sources, ", ");
        
        // If already enabled, nothing else to do
        if (module.isEnabled())
        {
            StartLog.debug("Enabled module: %s (via %s)",module.getName(),via);
            return count;
        }
        
        StartLog.debug("Enabling module: %s (via %s)",module.getName(),via);
        module.setEnabled(true);
        count++;
        args.parseModule(module);
        module.expandProperties(args.getProperties());
        
        // enable any parents that haven't been enabled (yet)
        Set<String> parentNames = new HashSet<>();
        parentNames.addAll(module.getParentNames());
        for(String name: parentNames)
        {
            StartLog.debug("Enable parent '%s' of module: %s",name,module.getName());
            Module parent = modules.get(name);
            if (parent == null)
            {
                // parent module doesn't exist, yet
                Path file = baseHome.getPath("modules/" + name + ".mod");
                if (FS.canReadFile(file))
                {
                    parent = registerModule(file);
                    parent.expandProperties(args.getProperties());
                    updateParentReferencesTo(parent);
                }
                else
                {
                    if (!Props.hasPropertyKey(name))
                    {
                        StartLog.debug("Missing module definition: [ Mod: %s | File: %s ]",name,file);
                        missingModules.add(name);
                    }
                }
            }
            if (parent != null)
            {
                count += enableModule(parent,sources);
            }
        }
        return count;
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

    public void registerParentsIfMissing(Module module) throws IOException
    {
        Set<String> parents = new HashSet<>(module.getParentNames());
        for (String name : parents)
        {
            if (!modules.containsKey(name))
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
    private void normalizeDependencies() throws FileNotFoundException, IOException
    {
        Set<String> expandedModules = new HashSet<>();
        boolean done = false;
        while (!done)
        {
            done = true;
            Set<String> missingParents = new HashSet<>();

            for (Module m : modules.values())
            {
                for (String parent : m.getParentNames())
                {
                    String expanded = args.getProperties().expand(parent);
                    if (modules.containsKey(expanded) || missingModules.contains(parent) || expandedModules.contains(parent))
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
                        missingModules.add(missingParent);
                    }
                }
            }
        }
    }

    private Module registerModule(Path file) throws FileNotFoundException, IOException
    {
        if (!FS.canReadFile(file))
        {
            throw new IOException("Cannot read file: " + file);
        }
        StartLog.debug("Registering Module: %s",baseHome.toShortForm(file));
        Module module = new Module(baseHome,file);
        return register(module);
    }

    public Set<String> resolveChildModulesOf(String moduleName)
    {
        Set<Module> ret = new HashSet<>();
        Module module = get(moduleName);
        findChildren(module,ret);
        return asNameSet(ret);
    }
    
    public List<Module> getEnabled()
    {
        return getMatching(new EnabledMatcher());
    }

    /**
     * Get the modules from the tree that match the provided matcher.  
     * 
     * @param matcher the matcher to use for matches
     * @return the list of matching modules in execution order.
     */
    public List<Module> getMatching(Matcher matcher)
    {
        List<Module> selected = new ArrayList<Module>();

        for (Module module : modules.values())
        {
            if (matcher.match(module))
            {
                selected.add(module);
            }
        }
        
        Collections.sort(selected,new Module.DepthComparator());
        return selected;
    }

    /**
     * Resolve the execution order of the enabled modules, and all dependent modules, based on depth first transitive reduction.
     * 
     * @return the list of active modules (plus dependent modules), in execution order.
     */
    @Deprecated
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

        assertModulesValid(active.values());

        List<Module> ordered = new ArrayList<>();
        ordered.addAll(active.values());
        Collections.sort(ordered,new Module.DepthComparator());
        return ordered;
    }

    public void assertModulesValid(Collection<Module> active)
    {
        /*
         * check against the missing modules
         * 
         * Ex: npn should match anything under npn/
         */
        for (String missing : missingModules)
        {
            for (Module module : active)
            {
                if (missing.startsWith(module.getName()))
                {
                    StartLog.warn("** Unable to continue, required dependency missing. [%s]",missing);
                    StartLog.warn("** As configured, Jetty is unable to start due to a missing enabled module dependency.");
                    StartLog.warn("** This may be due to a transitive dependency akin to spdy on npn, which resolves based on the JDK in use.");
                    throw new UsageException(UsageException.ERR_BAD_ARG, "Missing referenced dependency: " + missing);
                }
            }
        }
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
