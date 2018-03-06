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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class AbstractConfiguration implements Configuration
{
    protected static final boolean ENABLE_BY_DEFAULT = true;
    private final boolean _disabledByDefault;
    private final List<String> _after=new ArrayList<>();
    private final List<String> _beforeThis=new ArrayList<>();
    private final ClasspathPattern _system=new ClasspathPattern();
    private final ClasspathPattern _server=new ClasspathPattern();

    protected AbstractConfiguration()
    {
        this(false);
    }

    protected AbstractConfiguration(boolean disabledByDefault)
    {
        _disabledByDefault=disabledByDefault;
    }

    /**
     * Add configuration classes that come before this configuration
     * @param classes Classname or package name
     */
    protected void addDependencies(String... classes)
    {
        for (String c:classes)
            _beforeThis.add(c);
    }

    /**
     * Add configuration classes that come before this configuration
     * @param classes Classes
     */
    protected void addDependencies(Class<? extends Configuration>... classes)
    {
        addDependencies(Arrays.asList(classes).stream().map(Class::getName).collect(Collectors.toList()).toArray(new String[classes.length]));
    }

    /**
     * Add configuration classes that come after this configuration
     * @param classes Classname or package name
     */
    protected void addDependents(String... classes)
    {
        for (String c:classes)
            _after.add(c);
    }

    /**
     * Add configuration classes that come after this configuration
     * @param classes Class
     */
    protected void addDependents(Class<?>... classes)
    {
        addDependents(Arrays.asList(classes).stream().map(Class::getName).collect(Collectors.toList()).toArray(new String[classes.length]));
    }

    /**
     * Protect classes from modification by the web application by adding them
     * to the {@link WebAppConfiguration#getSystemClasses()}
     * @param classes classname or package pattern
     */
    protected void protect(String... classes)
    {
        _system.add(classes);
    }

    /**
     * Hide classes from the web application by adding them
     * to the {@link WebAppConfiguration#getServerClasses()}
     * @param classes classname or package pattern
     */
    protected void hide(String... classes)
    {
        _server.add(classes);
    }

    /**
     * Expose classes to the web application by adding them
     * as exclusions to the {@link WebAppConfiguration#getServerClasses()}
     * @param classes classname or package pattern
     */
    protected void expose(String... classes)
    {
        for (String c:classes)
        {
            if (c.startsWith("-"))
                throw new IllegalArgumentException();
            _server.add("-"+c);
        }
    }

    /**
     * Protect classes from modification by the web application by adding them
     * to the {@link WebAppConfiguration#getSystemClasses()} and
     * expose them to the web application by adding them
     * as exclusions to the {@link WebAppConfiguration#getServerClasses()}
     * @param classes classname or package pattern
     */
    protected void protectAndExpose(String... classes)
    {
        for (String c:classes)
        {
            if (c.startsWith("-"))
                throw new IllegalArgumentException();

            _system.add(c);
            _server.add("-"+c);
        }
    }


    @Override
    public Collection<String> getDependents()
    {
        return _after;
    }

    @Override
    public Collection<String> getDependencies()
    {
        return _beforeThis;
    }

    @Override
    public ClasspathPattern getSystemClasses()
    {
        return _system;
    }

    @Override
    public ClasspathPattern getServerClasses()
    {
        return _server;
    }

    @Override
    public void preConfigure(WebAppContext context) throws Exception
    {
    }

    @Override
    public void configure(WebAppContext context) throws Exception
    {
    }

    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
    }

    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
    }

    @Override
    public void destroy(WebAppContext context) throws Exception
    {
    }

    @Override
    public boolean isDisabledByDefault()
    {
        return _disabledByDefault;
    }

    @Override
    public boolean abort(WebAppContext context)
    {
        return false;
    }

    public void cloneConfigure(WebAppContext template, WebAppContext context) throws Exception
    {
    }
}
