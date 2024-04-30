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

package org.eclipse.jetty.ee9.webapp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.ClassMatcher;

public class AbstractConfiguration implements Configuration
{
    private final boolean _enabledByDefault;
    private final List<String> _after = new ArrayList<>();
    private final List<String> _beforeThis = new ArrayList<>();
    private final ClassMatcher _protected = new ClassMatcher();
    private final ClassMatcher _hidden = new ClassMatcher();

    protected AbstractConfiguration()
    {
        this(true);
    }

    protected AbstractConfiguration(boolean enabledByDefault)
    {
        _enabledByDefault = enabledByDefault;
    }

    /**
     * Add configuration classes that come before this configuration
     *
     * @param classes Classname or package name
     */
    protected void addDependencies(String... classes)
    {
        for (String c : classes)
        {
            _beforeThis.add(c);
        }
    }

    /**
     * Add configuration classes that come before this configuration
     *
     * @param classes Classes
     */
    protected void addDependencies(Class<? extends Configuration>... classes)
    {
        addDependencies(Arrays.asList(classes).stream().map(Class::getName).collect(Collectors.toList()).toArray(new String[classes.length]));
    }

    /**
     * Add configuration classes that come after this configuration
     *
     * @param classes Classname or package name
     */
    protected void addDependents(String... classes)
    {
        for (String c : classes)
        {
            _after.add(c);
        }
    }

    /**
     * Add configuration classes that come after this configuration
     *
     * @param classes Class
     */
    protected void addDependents(Class<?>... classes)
    {
        addDependents(Arrays.asList(classes).stream().map(Class::getName).collect(Collectors.toList()).toArray(new String[classes.length]));
    }

    /**
     * Protect classes from modification by the web application by adding them
     * to the {@link WebAppConfiguration#getProtectedClasses()}
     *
     * @param classes classname or package pattern
     */
    protected void protect(String... classes)
    {
        _protected.add(classes);
    }

    /**
     * Hide classes from the web application by adding them
     * to the {@link WebAppConfiguration#getHiddenClasses()}
     *
     * @param classes classname or package pattern
     */
    protected void hide(String... classes)
    {
        _hidden.add(classes);
    }

    /**
     * Expose classes to the web application by adding them
     * as exclusions to the {@link WebAppConfiguration#getHiddenClasses()}
     *
     * @param classes classname or package pattern
     */
    protected void expose(String... classes)
    {
        for (String c : classes)
        {
            if (c.startsWith("-"))
                throw new IllegalArgumentException();
            _hidden.add("-" + c);
        }
    }

    /**
     * Protect classes from modification by the web application by adding them
     * to the {@link WebAppConfiguration#getProtectedClasses()} and
     * expose them to the web application by adding them
     * as exclusions to the {@link WebAppConfiguration#getHiddenClasses()}
     *
     * @param classes classname or package pattern
     */
    protected void protectAndExpose(String... classes)
    {
        for (String c : classes)
        {
            if (c.startsWith("-"))
                throw new IllegalArgumentException();

            _protected.add(c);
            _hidden.add("-" + c);
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
    public ClassMatcher getProtectedClasses()
    {
        return _protected;
    }

    @Override
    public ClassMatcher getHiddenClasses()
    {
        return _hidden;
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
    public boolean isEnabledByDefault()
    {
        return _enabledByDefault;
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
