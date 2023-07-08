//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.webapp;

import java.io.IOException;
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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.TopologicalSort;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class Configurations extends AbstractList<Configuration> implements Dumpable
{
    public static final String SERVER_DEFAULT_ATTR = "org.eclipse.jetty.webapp.configurations";
    private static final Logger LOG = LoggerFactory.getLogger(Configurations.class);
    private static final AutoLock __lock = new AutoLock();
    private static final List<Configuration> __known = new ArrayList<>();
    private static final List<Configuration> __unavailable = new ArrayList<>();
    private static final Set<String> __knownByClassName = new HashSet<>();

    public static List<Configuration> getKnown()
    {
        try (AutoLock ignored = __lock.lock())
        {
            if (__known.isEmpty())
            {
                TypeUtil.serviceProviderStream(ServiceLoader.load(Configuration.class)).forEach(provider ->
                {
                    try
                    {
                        Configuration configuration = provider.get();
                        if (!configuration.isAvailable())
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Configuration unavailable: {}", configuration);
                            __unavailable.add(configuration);
                            return;
                        }
                        __known.add(configuration);
                        __knownByClassName.add(configuration.getClass().getName());
                    }
                    catch (Throwable e)
                    {
                        LOG.warn("Unable to get known Configuration", e);
                    }
                });
                sort(__known);
                if (LOG.isDebugEnabled())
                {
                    for (Configuration c : __known)
                    {
                        LOG.debug("known {}", c);
                    }
                    LOG.debug("Known Configurations {}", __knownByClassName);
                }
            }
            return __known;
        }
    }

    public static void setKnown(String... classes)
    {
        try (AutoLock ignored = __lock.lock())
        {
            if (!__known.isEmpty())
                throw new IllegalStateException("Known configuration classes already set");

            for (String c : classes)
            {
                try
                {
                    Class<? extends Configuration> clazz = Loader.loadClass(c);
                    Configuration configuration = clazz.getConstructor().newInstance();
                    if (!configuration.isAvailable())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.warn("Configuration unavailable: {}", configuration);
                        __unavailable.add(configuration);
                        continue;
                    }
                    __known.add(clazz.getConstructor().newInstance());
                    __knownByClassName.add(c);
                }
                catch (Exception e)
                {
                    LOG.warn("Problem loading known class", e);
                }
            }
            sort(__known);
            if (LOG.isDebugEnabled())
            {
                for (Configuration c : __known)
                {
                    LOG.debug("known {}", c);
                }
                LOG.debug("Known Configurations {}", __knownByClassName);
            }
        }
    }

    // Only used by tests.
    static void cleanKnown()
    {
        try (AutoLock ignored = __lock.lock())
        {
            __known.clear();
            __unavailable.clear();
        }
    }

    /**
     * Get/Set/Create the server default Configuration ClassList.
     * <p>Get the class list from: a Server bean; or the attribute (which can
     * either be a ClassList instance or an String[] of class names); or a new instance
     * with default configuration classes.</p>
     * <p>This method also adds the obtained ClassList instance as a dependent bean
     * on the server and clears the attribute</p>
     *
     * @param server The server the default is for
     * @return the server default ClassList instance of the configuration classes for this server.
     * Changes to this list will change the server default instance.
     */
    public static Configurations setServerDefault(Server server)
    {
        Configurations configurations = server.getBean(Configurations.class);
        if (configurations != null)
            return configurations;
        configurations = getServerDefault(server);
        server.addBean(configurations);
        server.setAttribute(SERVER_DEFAULT_ATTR, null);
        return configurations;
    }

    /**
     * Get/Create the server default Configuration ClassList.
     * <p>Get the class list from: a Server bean; or the attribute (which can
     * either be a ClassList instance or an String[] of class names); or a new instance
     * with default configuration classes.
     *
     * @param server The server the default is for
     * @return A copy of the server default ClassList instance of the configuration classes for this server.
     * Changes to the returned list will not change the server default.
     */
    public static Configurations getServerDefault(Server server)
    {
        Configurations configurations = null;
        if (server != null)
        {
            configurations = server.getBean(Configurations.class);
            if (configurations != null)
                configurations = new Configurations(configurations);
            else
            {
                Object attr = server.getAttribute(SERVER_DEFAULT_ATTR);
                LOG.debug("{} attr({})= {}", server, SERVER_DEFAULT_ATTR, attr);
                if (attr instanceof Configurations)
                    configurations = new Configurations((Configurations)attr);
                else if (attr instanceof String[])
                    configurations = new Configurations((String[])attr);
            }
        }

        if (configurations == null)
        {
            configurations = new Configurations(Configurations.getKnown().stream()
                .filter(Configuration::isEnabledByDefault)
                .toList());
        }

        if (LOG.isDebugEnabled())
            LOG.debug("default configurations for {}: {}", server, configurations);

        return configurations;
    }

    private final List<Configuration> _configurations;

    public Configurations()
    {
        this(Collections.emptyList());
    }

    public Configurations(List<Configuration> configurations)
    {
        _configurations = new ArrayList<>();
        //ensure replacements are processed
        if (configurations != null)
            configurations.forEach(this::add);
    }

    public Configurations(Configuration... configurations)
    {
        this(Arrays.asList(configurations));
    }

    public Configurations(String... configurationClassNames)
    {
        this(Arrays.stream(configurationClassNames).map(Configurations::newConfiguration).toList());
    }

    protected static Configuration newConfiguration(String classname)
    {
        if (LOG.isDebugEnabled())
        {
            if (!__knownByClassName.contains(classname))
                LOG.warn("Unknown configuration {}. Not declared for ServiceLoader!", classname);
        }

        try
        {
            @SuppressWarnings("unchecked")
            Class<Configuration> clazz = Loader.loadClass(classname);
            return clazz.getConstructor().newInstance();
        }
        catch (Throwable e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean add(Configuration configuration)
    {
        return addConfiguration(configuration);
    }

    public void add(Configuration... configurations)
    {
        for (Configuration configuration : configurations)
        {
            addConfiguration(configuration);
        }
    }

    public void add(@Name("configClass") String... configClass)
    {
        for (String name : configClass)
        {
            addConfiguration(newConfiguration(name));
        }
    }

    public <T> T get(Class<? extends T> configClass)
    {
        for (Configuration configuration : _configurations)
        {
            if (configClass.isAssignableFrom(configuration.getClass()))
                return (T)configuration;
        }
        return null;
    }

    public <T> List<T> getConfigurations(Class<? extends T> configClass)
    {
        List<T> list = new ArrayList<>();
        for (Configuration configuration : _configurations)
        {
            if (configClass.isAssignableFrom(configuration.getClass()))
                list.add((T)configuration);
        }
        return list;
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

    public void set(@Name("configClass") String... configClass)
    {
        clear();
        add(configClass);
    }

    public void remove(Configuration... configurations)
    {
        List<String> names = Arrays.stream(configurations).map(c -> c.getClass().getName()).toList();
        _configurations.removeIf(configuration -> names.contains(configuration.getClass().getName()));
    }

    @SafeVarargs
    public final void remove(Class<? extends Configuration>... configClass)
    {
        List<String> names = Arrays.stream(configClass).map(Class::getName).toList();
        _configurations.removeIf(configuration -> names.contains(configuration.getClass().getName()));
    }

    public void remove(@Name("configClass") String... configClass)
    {
        List<String> names = Arrays.asList(configClass);
        _configurations.removeIf(configuration -> names.contains(configuration.getClass().getName()));
    }

    public int size()
    {
        return _configurations.size();
    }

    public Configuration[] toArray()
    {
        return _configurations.toArray(new Configuration[0]);
    }

    public String[] toStringArray()
    {
        return _configurations.stream().map(Configuration::getClass).map(Class::getName).toArray(String[]::new);
    }

    public void sort()
    {
        sort(_configurations);
        if (LOG.isDebugEnabled())
        {
            for (Configuration c : _configurations)
            {
                LOG.debug("sorted {}", c);
            }
        }
    }

    public static void sort(List<Configuration> configurations)
    {
        // Sort the configurations
        Map<String, Configuration> byName = new HashMap<>();
        Map<String, List<Configuration>> replacedBy = new HashMap<>();
        TopologicalSort<Configuration> sort = new TopologicalSort<>();

        for (Configuration c : configurations)
        {
            byName.put(c.getClass().getName(), c);
            if (c.replaces() != null)
                replacedBy.computeIfAbsent(c.replaces().getName(), key -> new ArrayList<>()).add(c);
        }

        for (Configuration c : configurations)
        {
            for (String b : c.getDependencies())
            {
                Configuration before = byName.get(b);
                if (before != null)
                    sort.addBeforeAfter(before, c);
                if (replacedBy.containsKey(b))
                    replacedBy.get(b).forEach(bc -> sort.addBeforeAfter(bc, c));
            }
            for (String a : c.getDependents())
            {
                Configuration after = byName.get(a);
                if (after != null)
                    sort.addBeforeAfter(c, after);
                if (replacedBy.containsKey(a))
                    replacedBy.get(a).forEach(ac -> sort.addBeforeAfter(c, ac));
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

    private boolean addConfiguration(Configuration configuration)
    {
        String name = configuration.getClass().getName();
        // Is this configuration known?
        if (LOG.isDebugEnabled())
        {
            if (!__knownByClassName.contains(name))
                LOG.warn("Unknown configuration {}. Not declared for ServiceLoader!", name);
        }

        // Do we need to replace any existing configuration?
        Class<? extends Configuration> replaces = configuration.replaces();
        if (replaces != null)
        {
            for (ListIterator<Configuration> i = _configurations.listIterator(); i.hasNext(); )
            {
                Configuration c = i.next();
                if (c.getClass().getName().equals(replaces.getName()) ||
                    c.replaces() != null && c.replaces().getName().equals(replaces.getName()))
                {
                    i.remove();
                    break;
                }
            }
        }

        //check if any existing configurations replace the one we're adding
        for (Configuration c : _configurations)
        {
            Class<? extends Configuration> r = c.replaces();
            if (r != null)
            {
                if (r.getName().equals(configuration.getClass().getName()))
                    return false; //skip the addition, a replacement is already present
            }

            if (c.getClass().getName().equals(configuration.getClass().getName()))
                return false; //don't add same one twice
        }

        return _configurations.add(configuration);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x%s", this.getClass(), this.hashCode(), _configurations);
    }

    public void preConfigure(WebAppContext webapp) throws Exception
    {
        // TODO currently we iterate with index to allows changes to the Configurations
        //      during calls to preConfiguration. Would be better to have a cleaner way of
        //      mutating the configurations
        for (int i = 0; i < _configurations.size(); i++)
        {
            Configuration configuration = _configurations.get(i);
            LOG.debug("preConfigure with {}", configuration);
            configuration.preConfigure(webapp);

            if (_configurations.get(i) != configuration)
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
            LOG.debug("configure {}", configuration);
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
            LOG.debug("postConfigure {}", configuration);
            configuration.postConfigure(webapp);
        }
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this,
            new DumpableCollection("Known", Configurations.getKnown()),
            new DumpableCollection("Unavailable", Configurations.__unavailable));
    }
}
