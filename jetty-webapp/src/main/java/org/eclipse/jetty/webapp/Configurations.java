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

package org.eclipse.jetty.webapp;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.TopologicalSort;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An ordered list of {@link Configuration} instances.
 * <p>
 * The ordering of Configurations will initially be the order in which they
 * are added.  The {@link #sort()} method can be used to apply a 
 * {@link TopologicalSort} to the ordering as defined by the 
 * {@link Configuration#getDependencies()} and
 * {@link Configuration#getDependents()} methods.
 * Instances that do not have ordering dependencies will maintain 
 * their add order, as will additions/insertions made after the 
 * the sort.
 * </p>
 * <p>
 * If an added {@link Configuration} returns a value for 
 * {@link Configuration#replaces()} then the added instance will replace
 * any existing instance of that type or that has already replaced that
 * type.
 * </p>
 */
public class Configurations extends AbstractList<Configuration>
{        
    private static final Logger LOG = Log.getLogger(Configurations.class);
    
    private static final List<Configuration> __known = new ArrayList<>();
    private static final Set<String> __knownByClassName = new HashSet<>();


    /* ------------------------------------------------------------ */
    public static synchronized List<Configuration> getKnown()
    {
        if (__known.isEmpty())
        {
            ServiceLoader<Configuration> configs = ServiceLoader.load(Configuration.class);
            for (Configuration configuration : configs)
            {
                __known.add(configuration);
                __knownByClassName.add(configuration.getClass().getName());
            }
            sort(__known);
            if (LOG.isDebugEnabled())
            {
                for (Configuration c: __known)
                    LOG.debug("known {}",c);
            }

            LOG.debug("Known Configurations {}",__knownByClassName);
        }
        return __known;
    }
    
    

    /* ------------------------------------------------------------ */
    public static synchronized void setKnown (String ... classes)
    {
        if (!__known.isEmpty())
            throw new IllegalStateException("Known configuration classes already set");

        for (String c:classes)
        {
            try
            {
                Class<?> clazz = Loader.loadClass(c);
                __known.add((Configuration)clazz.newInstance());
                __knownByClassName.add(c);
            }
            catch (Exception e)
            {
                LOG.warn("Problem loading known class",e);
            }
        }
        sort(__known);
        if (LOG.isDebugEnabled())
        {
            for (Configuration c: __known)
                LOG.debug("known {}",c);
        }

        LOG.debug("Known Configurations {}",__knownByClassName);
    }

    /* ------------------------------------------------------------ */
    static synchronized void cleanKnown()
    {
        __known.clear();
    }
    
    /* ------------------------------------------------------------ */
    /** Get/Set/Create the server default Configuration ClassList.
     * <p>Get the class list from: a Server bean; or the attribute (which can
     * either be a ClassList instance or an String[] of class names); or a new instance
     * with default configuration classes.</p>
     * <p>This method also adds the obtained ClassList instance as a dependent bean
     * on the server and clears the attribute</p>
     * @param server The server the default is for
     * @return the server default ClassList instance of the configuration classes for this server. 
     * Changes to this list will change the server default instance.
     */
    public static Configurations setServerDefault(Server server)
    {
        Configurations configurations=server.getBean(Configurations.class);
        if (configurations!=null)
            return configurations;
        configurations=getServerDefault(server);
        server.addBean(configurations);
        server.setAttribute(Configuration.ATTR,null);
        return configurations;
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public static Configurations serverDefault(Server server)
    {
        return getServerDefault(server);
    }
    
    /* ------------------------------------------------------------ */
    /** Get/Create the server default Configuration ClassList.
     * <p>Get the class list from: a Server bean; or the attribute (which can
     * either be a ClassList instance or an String[] of class names); or a new instance
     * with default configuration classes.
     * @param server The server the default is for
     * @return A copy of the server default ClassList instance of the configuration classes for this server. 
     * Changes to the returned list will not change the server default.
     */
    public static Configurations getServerDefault(Server server)
    {
        Configurations configurations=null;
        if (server!=null)
        {
            configurations= server.getBean(Configurations.class);
            if (configurations!=null)
                configurations= new Configurations(configurations);
            else 
            {
                Object attr = server.getAttribute(Configuration.ATTR);
                LOG.debug("{} attr({})= {}",server,Configuration.ATTR,attr);
                if (attr instanceof Configurations)
                    configurations = new Configurations((Configurations)attr);
                else if (attr instanceof String[])
                    configurations = new Configurations((String[])attr);
            }
        }    
        
        if (configurations==null)
        {
            configurations=new Configurations(Configurations.getKnown().stream()
                    .filter(c->!c.isDisabledByDefault())
                    .map(c->c.getClass().getName())
                    .toArray(String[]::new));
        }

        if (LOG.isDebugEnabled())
            LOG.debug("default configurations for {}: {}",server,configurations);
        
        return configurations;
    }
    

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
 
    protected List<Configuration> _configurations = new ArrayList<>();
    
    public Configurations()
    {
    }

    protected static Configuration newConfiguration(String classname)
    {
        if (LOG.isDebugEnabled())
        {
            if (!__knownByClassName.contains(classname))
                LOG.warn("Unknown configuration {}. Not declared for ServiceLoader!",classname);
        }

        try
        {
            @SuppressWarnings("unchecked")
            Class<Configuration> clazz = Loader.loadClass(classname);
            return clazz.newInstance();
        }
        catch (ClassNotFoundException | InstantiationException | IllegalAccessException e)
        {
            throw new RuntimeException(e);
        }
    }
    
    public Configurations(String... classes)
    {
        add(classes);
    }

    public Configurations(List<String> classes)
    {
        add(classes.toArray(new String[classes.size()]));
    }

    public Configurations(Configurations classlist)
    {
        this(classlist._configurations.stream()
             .map(c->c.getClass().getName())
             .toArray(String[]::new));
    }

    public void add(Configuration... configurations)
    {   
        for (Configuration configuration : configurations)
            addConfiguration(configuration);
    }
    
    public void add(@Name("configClass")String... configClass)
    {   
        for (String name : configClass)
            addConfiguration(newConfiguration(name));
    }
    
    public void clear()
    {
        _configurations.clear();
    }

    public void set(Configuration... configurations)
    {   
        clear();
        add(configurations);
    }
    
    public void set(@Name("configClass")String... configClass)
    {   
        clear();
        add(configClass);
    }

    public void remove(Configuration... configurations)
    {
        List<String> names = Arrays.asList(configurations).stream().map(c->c.getClass().getName()).collect(Collectors.toList());
        for (ListIterator<Configuration> i=_configurations.listIterator();i.hasNext();)
        {
            Configuration configuration=i.next();
            if (names.contains(configuration.getClass().getName()))
                i.remove();
        }
    }
    
    public void remove(Class<? extends Configuration>... configClass)
    {
        List<String> names = Arrays.asList(configClass).stream().map(c->c.getName()).collect(Collectors.toList());
        for (ListIterator<Configuration> i=_configurations.listIterator();i.hasNext();)
        {
            Configuration configuration=i.next();
            if (names.contains(configuration.getClass().getName()))
                i.remove();
        }
    }

    public void remove(@Name("configClass")String... configClass)
    {
        List<String> names = Arrays.asList(configClass);
        for (ListIterator<Configuration> i=_configurations.listIterator();i.hasNext();)
        {
            Configuration configuration=i.next();
            if (names.contains(configuration.getClass().getName()))
                i.remove();
        }
    }
    
    public int size()
    {
        return _configurations.size();
    }

    public String[] toArray()
    {
        return _configurations.stream().map(c->c.getClass().getName()).toArray(String[]::new);
    }

    public void sort()
    {
        sort(_configurations);
        if (LOG.isDebugEnabled())
        {
            for (Configuration c: _configurations)
                LOG.debug("sorted {}",c);
        }
    }
    
    public static void sort(List<Configuration> configurations)
    {
        // Sort the configurations
        Map<String,Configuration> by_name = new HashMap<>();
        Map<String,List<Configuration>> replaced_by = new HashMap<>();
        TopologicalSort<Configuration> sort = new TopologicalSort<>();

        for (Configuration c:configurations)
        {
            by_name.put(c.getClass().getName(),c);
            if (c.replaces()!=null)
                replaced_by.computeIfAbsent(c.replaces().getName(),key->new ArrayList<>()).add(c);
        }
        
        for (Configuration c:configurations)
        {
            for (String b:c.getDependencies())
            {
                Configuration before=by_name.get(b);
                if (before!=null)
                    sort.addBeforeAfter(before,c);
                if (replaced_by.containsKey(b))
                    replaced_by.get(b).forEach(bc->sort.addBeforeAfter(bc,c));
            }
            for (String a:c.getDependents())
            {
                Configuration after=by_name.get(a);
                if (after!=null)
                    sort.addBeforeAfter(c,after);
                if (replaced_by.containsKey(a))
                    replaced_by.get(a).forEach(ac->sort.addBeforeAfter(c,ac));
            }
        }
        sort.sort(configurations);
    }
    
    public List<Configuration> getConfigurations()
    {
        return Collections.unmodifiableList(_configurations);
    }

    @Override
    public Configuration get(int index)
    {
        return _configurations.get(index);
    }
    
    @Override
    public Iterator<Configuration> iterator()
    {
        return getConfigurations().iterator();
    }
    
    private void addConfiguration(Configuration configuration)
    {
        String name=configuration.getClass().getName();
        // Is this configuration known?
        if (LOG.isDebugEnabled())
        {
            if (!__knownByClassName.contains(name))
                LOG.warn("Unknown configuration {}. Not declared for ServiceLoader!",name);        
        }

        // Do we need to replace any existing configuration?
        Class<? extends Configuration> replaces = configuration.replaces();
        if (replaces!=null)
        {
            for (ListIterator<Configuration> i=_configurations.listIterator();i.hasNext();)
            {
                Configuration c=i.next();
                if(c.getClass().getName().equals(replaces.getName()) 
                || c.replaces()!=null && c.replaces().getName().equals(replaces.getName()))
                {
                    i.set(configuration);
                    return;
                }
            }

            _configurations.add(configuration);
            return;
        }

        if (!_configurations.stream().map(c->c.getClass().getName()).anyMatch(n->{return name.equals(n);}))
            _configurations.add(configuration);
    }

    @Override
    public String toString()
    {
        return getConfigurations().toString();
    }

    public void preConfigure(WebAppContext webapp) throws Exception
    {
        // Configure webapp
        // iterate with index to allows changes to the Configurations
        // during calls to preConfiguration.
        for (int i=0; i<_configurations.size() ;i++)
        {
            Configuration configuration=_configurations.get(i);
            LOG.debug("preConfigure with {}",configuration);
            configuration.preConfigure(webapp);
            
            if (_configurations.get(i)!=configuration)
                throw new ConcurrentModificationException("Cannot change prior configuration");
        }
    }
    
    /**
     * @param webapp The webapp to configure
     * @return false if a {@link Configuration#abort(WebAppContext)} returns true, true otherwise
     * @throws Exception Thrown by {@link Configuration#configure(WebAppContext)}
     */
    public boolean configure(WebAppContext webapp) throws Exception
    {
        // Configure webapp
        for (Configuration configuration : _configurations)
        {
            LOG.debug("configure {}",configuration);
            configuration.configure(webapp);
            if (configuration.abort(webapp))
                return false;
        }
        return true;
    }


    public void postConfigure(WebAppContext webapp) throws Exception
    {
        // Configure webapp
        for (Configuration configuration : _configurations)
        {
            LOG.debug("postConfigure {}",configuration);
            configuration.postConfigure(webapp);
        }
    }
    
}
