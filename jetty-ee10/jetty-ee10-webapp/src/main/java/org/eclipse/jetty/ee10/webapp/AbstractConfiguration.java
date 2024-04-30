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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.util.ClassMatcher;

public class AbstractConfiguration implements Configuration
{
    private final boolean _enabledByDefault;
    private final List<String> _after;
    private final List<String> _before;
    private final ClassMatcher _protected;
    private final ClassMatcher _hidden;

    public static class Builder
    {
        private boolean _enabledByDefault = true;
        private final List<String> _after = new ArrayList<>();
        private final List<String> _before = new ArrayList<>();
        private final ClassMatcher _system = new ClassMatcher();
        private final ClassMatcher _server = new ClassMatcher();

        public Builder enabledByDefault(boolean enabledByDefault)
        {
            _enabledByDefault = enabledByDefault;
            return this;
        }

        /**
         * Add configuration classes that come before this configuration
         *
         * @param classes Classname or package name
         */
        public Builder addDependencies(String... classes)
        {
            Collections.addAll(_before, classes);
            return this;
        }

        /**
         * Add configuration classes that come before this configuration
         *
         * @param classes Classes
         */
        @SafeVarargs
        public final Builder addDependencies(Class<? extends Configuration>... classes)
        {
            addDependencies(Arrays.stream(classes).map(Class::getName).toList().toArray(new String[classes.length]));
            return this;
        }

        /**
         * Add configuration classes that come after this configuration
         *
         * @param classes Classname or package name
         */
        public Builder addDependents(String... classes)
        {
            _after.addAll(Arrays.asList(classes));
            return this;
        }

        /**
         * Add configuration classes that come after this configuration
         *
         * @param classes Class
         */
        public Builder addDependents(Class<?>... classes)
        {
            addDependents(Arrays.stream(classes).map(Class::getName).toList().toArray(new String[classes.length]));
            return this;
        }

        /**
         * Protect classes from modification by the web application by adding them
         * to the {@link WebAppConfiguration#getProtectedClasses()}
         *
         * @param classes classname or package pattern
         */
        public Builder protect(String... classes)
        {
            _system.add(classes);
            return this;
        }

        /**
         * Hide classes from the web application by adding them
         * to the {@link WebAppConfiguration#getHiddenClasses()}
         *
         * @param classes classname or package pattern
         */
        public Builder hide(String... classes)
        {
            _server.add(classes);
            return this;
        }

        /**
         * Expose classes to the web application by adding them
         * as exclusions to the {@link WebAppConfiguration#getHiddenClasses()}
         *
         * @param classes classname or package pattern
         */
        public Builder expose(String... classes)
        {
            for (String c : classes)
            {
                if (c.startsWith("-"))
                    throw new IllegalArgumentException();
                _server.add("-" + c);
            }
            return this;
        }

        /**
         * Protect classes from modification by the web application by adding them
         * to the {@link WebAppConfiguration#getProtectedClasses()} and
         * expose them to the web application by adding them
         * as exclusions to the {@link WebAppConfiguration#getHiddenClasses()}
         *
         * @param classes classname or package pattern
         */
        public Builder protectAndExpose(String... classes)
        {
            for (String c : classes)
            {
                if (c.startsWith("-"))
                    throw new IllegalArgumentException();

                _system.add(c);
                _server.add("-" + c);
            }
            return this;
        }
    }

    protected AbstractConfiguration(Builder builder)
    {
        _enabledByDefault = builder._enabledByDefault;
        _after = List.copyOf(builder._after);
        _before = List.copyOf(builder._before);
        _protected = new ClassMatcher(builder._system).asImmutable();
        _hidden = new ClassMatcher(builder._server).asImmutable();
    }

    @Override
    public Collection<String> getDependents()
    {
        return _after;
    }

    @Override
    public Collection<String> getDependencies()
    {
        return _before;
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
}
