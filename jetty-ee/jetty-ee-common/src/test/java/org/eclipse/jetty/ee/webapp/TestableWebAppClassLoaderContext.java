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

package org.eclipse.jetty.ee.webapp;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.util.ClassMatcher;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;

public class TestableWebAppClassLoaderContext implements WebAppClassLoader.Context
{
    private final ResourceFactory resourceFactory;
    private boolean parentLoaderPriority = false;
    private ClassMatcher protectedClassMatcher;
    private ClassMatcher hiddenClassMatcher;
    private List<Resource> extraClasspath;

    public TestableWebAppClassLoaderContext(ResourceFactory resourceFactory, String environmentName)
    {
        this.resourceFactory = resourceFactory;
        Environment environment = Environment.create(environmentName, null);
        environment.setAttribute(WebAppClassLoading.PROTECTED_CLASSES_ATTRIBUTE, "");
        environment.setAttribute(WebAppClassLoading.HIDDEN_CLASSES_ATTRIBUTE, "");

        this.protectedClassMatcher = new ClassMatcher(WebAppClassLoading.getProtectedClasses(environment));
        this.hiddenClassMatcher = new ClassMatcher(WebAppClassLoading.getHiddenClasses(environment));
    }

    @Override
    public Resource newResource(String urlOrPath) throws IOException
    {
        return resourceFactory.newResource(urlOrPath);
    }

    @Override
    public boolean isParentLoaderPriority()
    {
        return parentLoaderPriority;
    }

    @Override
    public List<Resource> getExtraClasspath()
    {
        return Objects.requireNonNullElse(extraClasspath, Collections.emptyList());
    }

    @Override
    public ClassMatcher getHiddenClassMatcher()
    {
        return hiddenClassMatcher;
    }

    public ClassMatcher getProtectedClassMatcher()
    {
        return protectedClassMatcher;
    }

    public void setExtraClasspath(String extraClasspath)
    {
        setExtraClasspath(resourceFactory.split(extraClasspath));
    }

    public void setExtraClasspath(List<Resource> extraClasspath)
    {
        this.extraClasspath = extraClasspath;
    }

    public void setHiddenClassMatcher(ClassMatcher classMatcher)
    {
        this.hiddenClassMatcher = classMatcher;
    }

    public void setParentLoaderPriority(boolean flag)
    {
        this.parentLoaderPriority = flag;
    }

    public void setProtectedClassMatcher(ClassMatcher classMatcher)
    {
        this.protectedClassMatcher = classMatcher;
    }
}
