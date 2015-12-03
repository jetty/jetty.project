//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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

public class Configurations 
{        
    private static final Logger LOG = Log.getLogger(Configurations.class);
    
    private static final Map<String,Configuration> __known = new HashMap<>();
    static
    {
        ServiceLoader<Configuration> configs = ServiceLoader.load(Configuration.class);
        for (Configuration configuration : configs)
            __known.put(configuration.getClass().getName(),configuration);
    }

    public static Collection<Configuration> getKnown()
    {
        return Collections.unmodifiableCollection(__known.values());
    }
    
    protected List<String> _configurations = new ArrayList<>();
    
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
        Configurations cl=server.getBean(Configurations.class);
        if (cl!=null)
            return cl;
        cl=serverDefault(server);
        server.addBean(cl);
        server.setAttribute(Configuration.ATTR,null);
        return cl;
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
        Configurations cl=null;
        if (server!=null)
        {
            cl= server.getBean(Configurations.class);
            if (cl!=null)
                return new Configurations(cl);
            Object attr = server.getAttribute(Configuration.ATTR);
            if (attr instanceof Configurations)
                return new Configurations((Configurations)attr);
            if (attr instanceof String[])
                return new Configurations((String[])attr);
        }
        return new Configurations();
    }
    
    public Configurations()
    {
        this(WebAppContext.DEFAULT_CONFIGURATION_CLASSES);
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
            __known.put(configuration.getClass().getName(),configuration);
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
    
    public void add(@Name("configClass")String... configClass)
    {   
        loop: for (String c : configClass)
        {
            Configuration configuration = getConfiguration(c);
            
            // Do we need to replace any existing configuration?
            Class<? extends Configuration> replaces = configuration.replaces();
            if (replaces!=null)
            {
                for (ListIterator<String> i=_configurations.listIterator();i.hasNext();)
                {
                    if (i.next().equals(replaces.getName()))
                    {
                        i.set(c);
                        continue loop;
                    }
                }
            }

            if (!_configurations.contains(c))
                _configurations.add(c);
        }
    }
    
    
    public int size()
    {
        return _configurations.size();
    }

    public String[] toArray(String[] asArray)
    {
        return _configurations.toArray(new String[_configurations.size()]);
    }

    public List<Configuration> getConfigurations()
    {
        // instantiate configurations list
        List<Configuration> configurations = _configurations.stream().map(n->{return __known.get(n);}).collect(Collectors.toList());
        
        // Sort the configurations
        Map<String,Configuration> map = new HashMap<>();
        TopologicalSort<Configuration> sort = new TopologicalSort<>();
        
        for (Configuration c:configurations)
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
        sort.sort(configurations);
        if (LOG.isDebugEnabled())
            LOG.debug("{} configurations {}",configurations);
        return configurations;
    }

    @Override
    public String toString()
    {
        return getConfigurations().toString();
    }

}