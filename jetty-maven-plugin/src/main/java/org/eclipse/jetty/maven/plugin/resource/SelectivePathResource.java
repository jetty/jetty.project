//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.maven.plugin.resource;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.Lists;
import org.eclipse.jetty.maven.plugin.utils.PathPatternUtils;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;

public class SelectivePathResource extends PathResource
{
    private final static Collection<String> DEFAULT_INCLUSION_PATTERN = Collections.singletonList("**");
    private final static Collection<String> DEFAULT_EXCLUSION_PATTERN = Collections.emptyList();

    private final static String ROOT_PATH = "";

    private final Set<String> included = new LinkedHashSet<>();
    private final Set<String> excluded = new LinkedHashSet<>();
    private final String rootPath;

    public static SelectivePathResourceBuilder withResource(Resource resource)
    {
        return new SelectivePathResourceBuilder(resource);
    }

    private SelectivePathResource(SelectivePathResourceBuilder builder) throws IOException
    {
        super(builder.resource.getURI());
        this.rootPath = builder.rootPath;
        this.included.addAll(builder.inclusionPattern);
        this.excluded.addAll(builder.exclusionPattern);
    }

    @Override
    public Resource addPath(String path) throws IOException
    {
        return withResource(super.addPath(path))
            .rootPath(createRootPath(rootPath, path))
            .included(included).excluded(excluded)
            .build();
    }

    @Override
    public File getFile() throws IOException
    {
        return super.getFile();
    }

    @Override
    public String[] list()
    {
        String[] paths = super.list();
        if (paths == null)
        {
            return null;
        }
        List<String> list = Lists.newArrayList(paths);
        list.removeIf(e ->
        {
            String rootPath = createRootPath(this.rootPath, e);
            return !PathPatternUtils.isPermitted(rootPath, included, excluded);
        });
        return list.toArray(new String[0]);
    }

    @Override
    public Collection<Resource> getAllResources()
    {
        Collection<Resource> resources = super.getAllResources();
        resources.removeIf(e ->
        {
            String rootPath = createRootPath(this.rootPath, getFilename(e));
            return !PathPatternUtils.isPermitted(rootPath, included, excluded);
        });
        return resources;
    }

    @Override
    public Resource getResource(String path)
    {
        return withResource(super.getResource(path))
            .rootPath(createRootPath(this.rootPath, path))
            .included(included).excluded(excluded)
            .build();
    }

    @Override
    public boolean exists()
    {
        if (ROOT_PATH.equals(rootPath))
        {
            return super.exists();
        }
        return super.exists() && PathPatternUtils.isPermitted(rootPath, included, excluded);
    }

    private static String createRootPath(String rootPath, String path)
    {
        if (!rootPath.endsWith("/"))
        {
            rootPath += ROOT_PATH;
        }
        if (path.startsWith("/"))
        {
            path = path.substring(1);
        }
        return rootPath + path;
    }

    private static String getFilename(Resource resource)
    {
        String path = resource.getName();
        int idx = path.lastIndexOf(File.separator);
        if (idx == path.length() - 1)
        {
            idx = path.lastIndexOf(File.separator, idx - 1);
        }
        String fileName;
        if (idx >= 0)
        {
            fileName = path.substring(idx + 1);
        }
        else
        {
            fileName = path; // entire name
        }
        return fileName;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (obj instanceof SelectivePathResource)
        {
            SelectivePathResource other = (SelectivePathResource)obj;
            return Objects.equals(this.getPath(), other.getPath())
                && Objects.deepEquals(this.excluded, other.excluded)
                && Objects.deepEquals(this.included, other.included);
        }
        if (obj instanceof PathResource)
        {
            PathResource other = (PathResource)obj;
            return Objects.equals(this.getPath(), other.getPath())
                && Objects.deepEquals(this.excluded, DEFAULT_EXCLUSION_PATTERN)
                && Objects.deepEquals(this.included, DEFAULT_INCLUSION_PATTERN);
        }
        return super.equals(obj);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(super.hashCode(), included, excluded, rootPath);
    }

    /**
     * builder
     */
    public static final class SelectivePathResourceBuilder
    {

        private final Resource resource;
        private Collection<String> inclusionPattern = DEFAULT_INCLUSION_PATTERN;
        private Collection<String> exclusionPattern = DEFAULT_EXCLUSION_PATTERN;
        private String rootPath = ROOT_PATH;

        private SelectivePathResourceBuilder(Resource resource)
        {
            this.resource = resource;
        }

        public SelectivePathResourceBuilder included(Collection<String> inclusionPattern)
        {
            if (inclusionPattern != null)
            {
                this.inclusionPattern = inclusionPattern;
            }
            return this;
        }

        public SelectivePathResourceBuilder excluded(Collection<String> exclusionPattern)
        {
            if (exclusionPattern != null)
            {
                this.exclusionPattern = exclusionPattern;
            }
            return this;
        }

        public SelectivePathResourceBuilder rootPath(String rootPath)
        {
            this.rootPath = rootPath;
            return this;
        }

        public SelectivePathResource build()
        {
            try
            {
                return new SelectivePathResource(this);
            }
            catch (IOException e)
            {
                throw new IllegalArgumentException(e);
            }
        }
    }
}
