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

package org.eclipse.jetty.webapp;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.TopologicalSort;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class Configurations extends AbstractList<Configuration>
{        
    private static final Logger LOG = Log.getLogger(Configurations.class);
    
    private static final Map<String,Configuration> __known = new HashMap<>();
    static
    {
        ServiceLoader<Configuration> configs = ServiceLoader.load(Configuration.class);
        for (Configuration configuration : configs)
            __known.put(configuration.getName(),configuration);
        
        LOG.debug("Known Configurations {}",__known.keySet());
    }

    public static Collection<Configuration> getKnown()
    {
        return Collections.unmodifiableCollection(__known.values());
    }
    
    /* ------------------------------------------------------------ */
    /** Get/Set/Create the server default Configuration ClassList.
     * <p>Get the class list from: a Server bean; or the attribute (which can
     * either be a ClassList instance or an String[] of class names); or a new instance
     * with default configuration classes.</p>
     * <p>This method also adds the obtained ClassList instance as a dependent bean
     * on the server and clears the attribute</p>
     * @param server The server the default is for
     * @return the server default ClassList instance of the configuration classes for this server. Changes to this list will change the server default instance.
     */
    public static Configurations setServerDefault(Server server) throws ClassNotFoundException, InstantiationException, IllegalAccessException
    {
        Configurations configurations=server.getBean(Configurations.class);
        if (configurations!=null)
            return configurations;
        configurations=serverDefault(server);
        server.addBean(configurations);
        server.setAttribute(Configuration.ATTR,null);
        return configurations;
    }

    /* ------------------------------------------------------------ */
    /** Get/Create the server default Configuration ClassList.
     * <p>Get the class list from: a Server bean; or the attribute (which can
     * either be a ClassList instance or an String[] of class names); or a new instance
     * with default configuration classes.
     * @param server The server the default is for
     * @return A copy of the server default ClassList instance of the configuration classes for this server. Changes to the returned list will not change the server default.
     */
    public static Configurations serverDefault(Server server)
    {
        Configurations configurations;
        if (server==null)
            configurations=new Configurations(WebAppContext.DEFAULT_CONFIGURATION_CLASSES);
        else
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
                else 
                    configurations=new Configurations(WebAppContext.DEFAULT_CONFIGURATION_CLASSES);
            }
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

    protected static Configuration getConfiguration(String classname)
    {
        Configuration configuration = __known.get(classname);
        if (configuration==null)
        {
            try
            {
                @SuppressWarnings("unchecked")
                Class<Configuration> clazz = Loader.loadClass(classname);
                configuration = clazz.newInstance();
            }
            catch (ClassNotFoundException | InstantiationException | IllegalAccessException e)
            {
                throw new RuntimeException(e);
            }
            
            LOG.info("Unknown configuration {}",classname);
        }
        return configuration;
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
        _configurations.addAll(classlist._configurations);
    }

    public void add(Configuration... configurations)
    {   
        for (Configuration configuration : configurations)
            add(configuration.getName(),configuration);
    }
    
    public void add(@Name("configClass")String... configClass)
    {   
        for (String name : configClass)
            add(name,getConfiguration(name));
    }
    
    public void clear()
    {
        _configurations.clear();
    }

    public void set(Configuration... configurations)
    {   
        clear();
        for (Configuration configuration : configurations)
            add(configuration.getName(),configuration);
    }
    
    public void set(@Name("configClass")String... configClass)
    {   
        clear();
        for (String name : configClass)
            add(name,getConfiguration(name));
    }

    public void remove(Configuration... configurations)
    {
        List<String> names = Arrays.asList(configurations).stream().map(Configuration::getName).collect(Collectors.toList());
        for (ListIterator<Configuration> i=_configurations.listIterator();i.hasNext();)
        {
            Configuration configuration=i.next();
            if (names.contains(configuration.getName()))
                i.remove();
        }
    }

    public void remove(@Name("configClass")String... configClass)
    {
        List<String> names = Arrays.asList(configClass);
        for (ListIterator<Configuration> i=_configurations.listIterator();i.hasNext();)
        {
            Configuration configuration=i.next();
            if (names.contains(configuration.getName()))
                i.remove();
        }
    }
    
    public int size()
    {
        return _configurations.size();
    }

    public String[] toArray()
    {
        return _configurations.stream().map(Configuration::getName).toArray(String[]::new);
    }

    public void sort()
    {
        // Sort the configurations
        Map<String,Configuration> map = new HashMap<>();
        TopologicalSort<Configuration> sort = new TopologicalSort<>();

        for (Configuration c:_configurations)
            map.put(c.getName(),c);
        for (Configuration c:_configurations)
        {
            for (String b:c.getBeforeThis())
            {
                Configuration before=map.get(b);
                if (before!=null)
                    sort.addBeforeAfter(before,c);
            }
            for (String a:c.getAfterThis())
            {
                Configuration after=map.get(a);
                if (after!=null)
                    sort.addBeforeAfter(c,after);
            }
        }
        
        sort.sort(_configurations);
        if (LOG.isDebugEnabled())
            LOG.debug("sorted {}",_configurations);
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

    private void add(String name,Configuration configuration)
    {
        // Is this configuration known?
        if (!__known.containsKey(name))
            LOG.info("Unknown configuration {}",name);            

        // Do we need to replace any existing configuration?
        Class<? extends Configuration> replaces = configuration.replaces();
        if (replaces!=null)
        {
            for (ListIterator<Configuration> i=_configurations.listIterator();i.hasNext();)
            {
                if (i.next().getName().equals(replaces.getName()))
                {
                    i.set(configuration);
                    return;
                }
            }

            _configurations.add(configuration);
            return;
        }

        if (!_configurations.stream().map(Configuration::getName).anyMatch(n->{return name.equals(n);}))
            _configurations.add(configuration);
    }

    @Override
    public String toString()
    {
        return getConfigurations().toString();
    }

}
