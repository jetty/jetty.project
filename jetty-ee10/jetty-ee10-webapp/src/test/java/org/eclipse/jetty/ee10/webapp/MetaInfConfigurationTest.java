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
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollators;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
@Isolated("Access static method of FileSystemPool")
public class MetaInfConfigurationTest
{

    @BeforeEach
    public void beforeEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void tearDown()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    /**
     * Test of a MetaInf scan of a Servlet 2.5 webapp, where
     * {@link WebAppContext#setConfigurationDiscovered(boolean)} set to {@code false},
     * thus not performing any Servlet 3.0+ discovery steps for {@code META-INF/web-fragment.xml}.
     * Scanning for {@code META-INF/resources} is unaffected by configuration.
     */
    @Test
    public void testScanServlet25ConfigurationDiscoveredOff(WorkDir workDir) throws Exception
    {
        Path webappDir = workDir.getEmptyPathDir();
        Path webinf = webappDir.resolve("WEB-INF");
        FS.ensureDirExists(webinf);
        Path webxml = webinf.resolve("web.xml");

        String web25 = """
            <?xml version="1.0" encoding="ISO-8859-1"?>
            <web-app xmlns="http://java.sun.com/xml/ns/javaee" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
               xsi:schemaLocation="http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd"
               version="2.5">
              <display-name>Test 2.5 WebApp</display-name>
            </web-app>
            """;

        Files.writeString(webxml, web25, StandardCharsets.UTF_8);
        Path libDir = webinf.resolve("lib");
        FS.ensureDirExists(libDir);
        Path fooFragmentJar = libDir.resolve("foo-fragment.jar");
        try (FileSystem jarfs = createNewJarFileSystem(fooFragmentJar))
        {
            Path webfragment = jarfs.getPath("/META-INF/web-fragment.xml");
            FS.ensureDirExists(webfragment.getParent());
            Files.writeString(webfragment, "<web-fragment />", StandardCharsets.UTF_8);
        }

        Path barResourceJar = libDir.resolve("bar-resources.jar");
        try (FileSystem jarfs = createNewJarFileSystem(barResourceJar))
        {
            Path resourcesDir = jarfs.getPath("/META-INF/resources");
            Files.createDirectories(resourcesDir);
            Path testTxt = resourcesDir.resolve("test.txt");
            Files.writeString(testTxt, "Test", StandardCharsets.UTF_8);
        }

        Path zedTldJar = libDir.resolve("zed-tlds.jar");
        try (FileSystem jarfs = createNewJarFileSystem(zedTldJar))
        {
            Path tldFile = jarfs.getPath("/META-INF/zed.tld");
            Files.createDirectory(tldFile.getParent());
            Files.writeString(tldFile, "<taglib />", StandardCharsets.UTF_8);
        }

        WebAppContext context = new WebAppContext();
        context.setServer(new Server());
        try
        {
            context.setBaseResource(context.getResourceFactory().newResource(webappDir));
            context.getMetaData().setWebDescriptor(new WebDescriptor(context.getResourceFactory().newResource(webxml)));
            context.setConfigurationDiscovered(false); // don't allow discovery of servlet 3.0+ features
            context.getContext().getServletContext().setEffectiveMajorVersion(2);
            context.getContext().getServletContext().setEffectiveMinorVersion(5);

            MetaInfConfiguration metaInfConfiguration = new MetaInfConfiguration();
            metaInfConfiguration.preConfigure(context);

            List<String> discoveredWebInfResources = context.getMetaData().getWebInfResources(false)
                .stream()
                .sorted(ResourceCollators.byName(true))
                .map(Resource::getURI)
                .map(URI::toASCIIString)
                .toList();
            String[] expectedWebInfResources = {
                fooFragmentJar.toUri().toASCIIString(),
                barResourceJar.toUri().toASCIIString(),
                zedTldJar.toUri().toASCIIString()
            };
            assertThat("Discovered WEB-INF resources", discoveredWebInfResources, hasItems(expectedWebInfResources));

            // Since this is Servlet 2.5, and we have configuration-discovered turned off, we shouldn't see any web fragments
            Map<Resource, Resource> fragmentMap = getDiscoveredMetaInfFragments(context);
            assertThat("META-INF/web-fragment.xml discovered (servlet 2.5 and configuration-discovered turned off)", fragmentMap.size(), is(0));

            // Even on Servlet 2.5, when we have configuration-discovered turned off, we should still see the META-INF/resources/
            Set<Resource> resourceSet = getDiscoveredMetaInfResource(context);
            assertThat(resourceSet.size(), is(1));
            List<String> discoveredResources = resourceSet
                .stream()
                .map(Resource::getURI)
                .map(URI::toASCIIString)
                .toList();
            String[] expectedResources = {
                URIUtil.toJarFileUri(barResourceJar.toUri()).toASCIIString() + "META-INF/resources/"
            };
            assertThat("META-INF/resources discovered (servlet 2.5 and configuration-discovered turned off)", discoveredResources, hasItems(expectedResources));

            // TLDs discovered
            Set<URL> tldSet = getDiscoveredMetaInfTlds(context);
            assertThat(tldSet.size(), is(1));
            List<String> discoveredTlds = tldSet
                .stream()
                .map(URL::toExternalForm)
                .toList();
            String[] expectedTlds = {
                URIUtil.toJarFileUri(zedTldJar.toUri()).toASCIIString() + "META-INF/zed.tld"
            };
            assertThat("Discovered TLDs", discoveredTlds, hasItems(expectedTlds));
        }
        finally
        {
            LifeCycle.stop(context.getResourceFactory());
        }
    }

    /**
     * Test of a MetaInf scan of a Servlet 2.5 webapp, where
     * {@link WebAppContext#setConfigurationDiscovered(boolean)} is left at default (@{code true})
     * allowing the performing of Servlet 3.0+ discovery steps for {@code META-INF/web-fragment.xml} and {@code META-INF/resources}
     */
    @Test
    public void testScanServlet25ConfigurationDiscoveredDefault(WorkDir workDir) throws Exception
    {
        Path webappDir = workDir.getEmptyPathDir();
        Path webinf = webappDir.resolve("WEB-INF");
        FS.ensureDirExists(webinf);
        Path webxml = webinf.resolve("web.xml");

        String web25 = """
            <?xml version="1.0" encoding="ISO-8859-1"?>
            <web-app xmlns="http://java.sun.com/xml/ns/javaee" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
               xsi:schemaLocation="http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd"
               version="2.5">
              <display-name>Test 2.5 WebApp</display-name>
            </web-app>
            """;

        Files.writeString(webxml, web25, StandardCharsets.UTF_8);
        Path libDir = webinf.resolve("lib");
        FS.ensureDirExists(libDir);
        Path fooFragmentJar = libDir.resolve("foo-fragment.jar");
        try (FileSystem jarfs = createNewJarFileSystem(fooFragmentJar))
        {
            Path webfragment = jarfs.getPath("/META-INF/web-fragment.xml");
            FS.ensureDirExists(webfragment.getParent());
            Files.writeString(webfragment, "<web-fragment />", StandardCharsets.UTF_8);
        }

        Path barResourceJar = libDir.resolve("bar-resources.jar");
        try (FileSystem jarfs = createNewJarFileSystem(barResourceJar))
        {
            Path resourcesDir = jarfs.getPath("/META-INF/resources");
            Files.createDirectories(resourcesDir);
            Path testTxt = resourcesDir.resolve("test.txt");
            Files.writeString(testTxt, "Test", StandardCharsets.UTF_8);
        }

        Path zedTldJar = libDir.resolve("zed-tlds.jar");
        try (FileSystem jarfs = createNewJarFileSystem(zedTldJar))
        {
            Path tldFile = jarfs.getPath("/META-INF/zed.tld");
            Files.createDirectory(tldFile.getParent());
            Files.writeString(tldFile, "<taglib />", StandardCharsets.UTF_8);
        }

        WebAppContext context = new WebAppContext();
        context.setServer(new Server());
        try
        {
            context.setBaseResource(context.getResourceFactory().newResource(webappDir));
            context.getMetaData().setWebDescriptor(new WebDescriptor(context.getResourceFactory().newResource(webxml)));
            // context25.setConfigurationDiscovered(true); // The default value
            context.getContext().getServletContext().setEffectiveMajorVersion(2);
            context.getContext().getServletContext().setEffectiveMinorVersion(5);

            MetaInfConfiguration metaInfConfiguration = new MetaInfConfiguration();
            metaInfConfiguration.preConfigure(context);

            List<String> discoveredWebInfResources = context.getMetaData().getWebInfResources(false)
                .stream()
                .sorted(ResourceCollators.byName(true))
                .map(Resource::getURI)
                .map(URI::toASCIIString)
                .toList();
            String[] expectedWebInfResources = {
                fooFragmentJar.toUri().toASCIIString(),
                barResourceJar.toUri().toASCIIString(),
                zedTldJar.toUri().toASCIIString()
            };
            assertThat("Discovered WEB-INF resources", discoveredWebInfResources, hasItems(expectedWebInfResources));

            // Since this is Servlet 2.5, and we have configuration-discovered turned on, we should see the META-INF/web-fragment.xml entries
            Map<Resource, Resource> fragmentMap = getDiscoveredMetaInfFragments(context);
            assertThat(fragmentMap.size(), is(1));
            List<String> discoveredFragments = fragmentMap.entrySet()
                .stream()
                .map(e -> e.getValue().getURI().toASCIIString())
                .toList();
            String[] expectedFragments = {
                URIUtil.toJarFileUri(fooFragmentJar.toUri()).toASCIIString() + "META-INF/web-fragment.xml"
            };
            assertThat("META-INF/web-fragment.xml discovered (servlet 2.5 and configuration-discovered=true)", discoveredFragments, hasItems(expectedFragments));

            // Since this is Servlet 2.5, and we have configuration-discovered turned on, we should see the META-INF/resources/
            Set<Resource> resourceSet = getDiscoveredMetaInfResource(context);
            assertThat(resourceSet.size(), is(1));
            List<String> discoveredResources = resourceSet
                .stream()
                .map(Resource::getURI)
                .map(URI::toASCIIString)
                .toList();
            String[] expectedResources = {
                URIUtil.toJarFileUri(barResourceJar.toUri()).toASCIIString() + "META-INF/resources/"
            };
            assertThat("META-INF/resources discovered (servlet 2.5 and configuration-discovered=true)", discoveredResources, hasItems(expectedResources));

            // TLDs discovered
            Set<URL> tldSet = getDiscoveredMetaInfTlds(context);
            assertThat(tldSet.size(), is(1));
            List<String> discoveredTlds = tldSet
                .stream()
                .map(URL::toExternalForm)
                .toList();
            String[] expectedTlds = {
                URIUtil.toJarFileUri(zedTldJar.toUri()).toASCIIString() + "META-INF/zed.tld"
            };
            assertThat("Discovered TLDs", discoveredTlds, hasItems(expectedTlds));
        }
        finally
        {
            LifeCycle.stop(context.getResourceFactory());
        }
    }

    /**
     * Test of a MetaInf scan of a Servlet 3.0 webapp, metadata-complete is set to {@code false}
     */
    @Test
    public void testScanServlet30MetadataCompleteFalse(WorkDir workDir) throws Exception
    {
        Path webappDir = workDir.getEmptyPathDir();
        Path webinf = webappDir.resolve("WEB-INF");
        FS.ensureDirExists(webinf);
        Path webxml = webinf.resolve("web.xml");

        String web30 = """
            <?xml version="1.0" encoding="ISO-8859-1"?>
            <web-app xmlns="http://java.sun.com/xml/ns/javaee" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd"
                 metadata-complete="false"
                 version="3.0">
              <display-name>Test 3.0 WebApp</display-name>
            </web-app>
            """;

        Files.writeString(webxml, web30, StandardCharsets.UTF_8);
        Path libDir = webinf.resolve("lib");
        FS.ensureDirExists(libDir);
        Path fooFragmentJar = libDir.resolve("foo-fragment.jar");
        try (FileSystem jarfs = createNewJarFileSystem(fooFragmentJar))
        {
            Path webfragment = jarfs.getPath("/META-INF/web-fragment.xml");
            FS.ensureDirExists(webfragment.getParent());
            Files.writeString(webfragment, "<web-fragment />", StandardCharsets.UTF_8);
        }

        Path barResourceJar = libDir.resolve("bar-resources.jar");
        try (FileSystem jarfs = createNewJarFileSystem(barResourceJar))
        {
            Path resourcesDir = jarfs.getPath("/META-INF/resources");
            Files.createDirectories(resourcesDir);
            Path testTxt = resourcesDir.resolve("test.txt");
            Files.writeString(testTxt, "Test", StandardCharsets.UTF_8);
        }

        Path zedTldJar = libDir.resolve("zed-tlds.jar");
        try (FileSystem jarfs = createNewJarFileSystem(zedTldJar))
        {
            Path tldFile = jarfs.getPath("/META-INF/zed.tld");
            Files.createDirectory(tldFile.getParent());
            Files.writeString(tldFile, "<taglib />", StandardCharsets.UTF_8);
        }

        WebAppContext context = new WebAppContext();
        context.setServer(new Server());
        try
        {
            context.setBaseResource(context.getResourceFactory().newResource(webappDir));
            context.getMetaData().setWebDescriptor(new WebDescriptor(context.getResourceFactory().newResource(webxml)));
            // context25.setConfigurationDiscovered(true); // The default value
            context.getContext().getServletContext().setEffectiveMajorVersion(3);
            context.getContext().getServletContext().setEffectiveMinorVersion(0);

            MetaInfConfiguration metaInfConfiguration = new MetaInfConfiguration();
            metaInfConfiguration.preConfigure(context);

            List<String> discoveredWebInfResources = context.getMetaData().getWebInfResources(false)
                .stream()
                .sorted(ResourceCollators.byName(true))
                .map(Resource::getURI)
                .map(URI::toASCIIString)
                .toList();
            String[] expectedWebInfResources = {
                fooFragmentJar.toUri().toASCIIString(),
                barResourceJar.toUri().toASCIIString(),
                zedTldJar.toUri().toASCIIString()
            };
            assertThat("Discovered WEB-INF resources", discoveredWebInfResources, hasItems(expectedWebInfResources));

            // Since this is Servlet 3.0, and we have configuration-discovered turned on, we should see the META-INF/web-fragment.xml entries
            Map<Resource, Resource> fragmentMap = getDiscoveredMetaInfFragments(context);
            assertThat(fragmentMap.size(), is(1));
            List<String> discoveredFragments = fragmentMap.entrySet()
                .stream()
                .map(e -> e.getValue().getURI().toASCIIString())
                .toList();
            String[] expectedFragments = {
                URIUtil.toJarFileUri(fooFragmentJar.toUri()).toASCIIString() + "META-INF/web-fragment.xml"
            };
            assertThat("META-INF/web-fragment.xml discovered (servlet 3.0, and metadata-complete=false, and configuration-discovered=true)", discoveredFragments, hasItems(expectedFragments));

            // Since this is Servlet 3.0, and we have configuration-discovered turned on, we should see the META-INF/resources/
            Set<Resource> resourceSet = getDiscoveredMetaInfResource(context);
            assertThat(resourceSet.size(), is(1));
            List<String> discoveredResources = resourceSet
                .stream()
                .map(Resource::getURI)
                .map(URI::toASCIIString)
                .toList();
            String[] expectedResources = {
                URIUtil.toJarFileUri(barResourceJar.toUri()).toASCIIString() + "META-INF/resources/"
            };
            assertThat("META-INF/resources discovered (servlet 3.0, and metadata-complete=false, and configuration-discovered=true)", discoveredResources, hasItems(expectedResources));

            // TLDs discovered
            Set<URL> tldSet = getDiscoveredMetaInfTlds(context);
            assertThat(tldSet.size(), is(1));
            List<String> discoveredTlds = tldSet
                .stream()
                .map(URL::toExternalForm)
                .toList();
            String[] expectedTlds = {
                URIUtil.toJarFileUri(zedTldJar.toUri()).toASCIIString() + "META-INF/zed.tld"
            };
            assertThat("Discovered TLDs", discoveredTlds, hasItems(expectedTlds));
        }
        finally
        {
            LifeCycle.stop(context.getResourceFactory());
        }
    }

    /**
     * Test of a MetaInf scan of a Servlet 3.1 webapp, metadata-complete is set to {@code true}
     */
    @Test
    public void testScanServlet31MetadataCompleteTrue(WorkDir workDir) throws Exception
    {
        Path webappDir = workDir.getEmptyPathDir();
        Path webinf = webappDir.resolve("WEB-INF");
        FS.ensureDirExists(webinf);
        Path webxml = webinf.resolve("web.xml");

        String web31 = """
            <?xml version="1.0" encoding="ISO-8859-1"?>
            <web-app xmlns="http://java.sun.com/xml/ns/javaee" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/web-app_3_1.xsd"
                 metadata-complete="true"
                 version="3.1">
              <display-name>Test 3.1 WebApp</display-name>
            </web-app>
            """;

        Files.writeString(webxml, web31, StandardCharsets.UTF_8);
        Path libDir = webinf.resolve("lib");
        FS.ensureDirExists(libDir);
        Path fooFragmentJar = libDir.resolve("foo-fragment.jar");
        try (FileSystem jarfs = createNewJarFileSystem(fooFragmentJar))
        {
            Path webfragment = jarfs.getPath("/META-INF/web-fragment.xml");
            FS.ensureDirExists(webfragment.getParent());
            Files.writeString(webfragment, "<web-fragment />", StandardCharsets.UTF_8);
        }

        Path barResourceJar = libDir.resolve("bar-resources.jar");
        try (FileSystem jarfs = createNewJarFileSystem(barResourceJar))
        {
            Path resourcesDir = jarfs.getPath("/META-INF/resources");
            Files.createDirectories(resourcesDir);
            Path testTxt = resourcesDir.resolve("test.txt");
            Files.writeString(testTxt, "Test", StandardCharsets.UTF_8);
        }

        Path zedTldJar = libDir.resolve("zed-tlds.jar");
        try (FileSystem jarfs = createNewJarFileSystem(zedTldJar))
        {
            Path tldFile = jarfs.getPath("/META-INF/zed.tld");
            Files.createDirectory(tldFile.getParent());
            Files.writeString(tldFile, "<taglib />", StandardCharsets.UTF_8);
        }

        WebAppContext context = new WebAppContext();
        context.setServer(new Server());
        try
        {
            context.setBaseResource(context.getResourceFactory().newResource(webappDir));
            context.getMetaData().setWebDescriptor(new WebDescriptor(context.getResourceFactory().newResource(webxml)));
            context.getContext().getServletContext().setEffectiveMajorVersion(3);
            context.getContext().getServletContext().setEffectiveMinorVersion(1);

            MetaInfConfiguration metaInfConfiguration = new MetaInfConfiguration();
            metaInfConfiguration.preConfigure(context);

            List<String> discoveredWebInfResources = context.getMetaData().getWebInfResources(false)
                .stream()
                .sorted(ResourceCollators.byName(true))
                .map(Resource::getURI)
                .map(URI::toASCIIString)
                .toList();
            String[] expectedWebInfResources = {
                fooFragmentJar.toUri().toASCIIString(),
                barResourceJar.toUri().toASCIIString(),
                zedTldJar.toUri().toASCIIString()
            };
            assertThat("Discovered WEB-INF resources", discoveredWebInfResources, hasItems(expectedWebInfResources));

            // Since this is Servlet 3.1, and we have metadata-complete=true, we should see no fragments
            Map<Resource, Resource> fragmentMap = getDiscoveredMetaInfFragments(context);
            assertThat("META-INF/web-fragment.xml discovered (servlet 3.1, and metadata-complete=true)", fragmentMap.size(), is(0));

            // Even on Servlet 3.1, with metadata-complete=true, we should still see the META-INF/resources/
            Set<Resource> resourceSet = getDiscoveredMetaInfResource(context);
            assertThat(resourceSet.size(), is(1));
            List<String> discoveredResources = resourceSet
                .stream()
                .map(Resource::getURI)
                .map(URI::toASCIIString)
                .toList();
            String[] expectedResources = {
                URIUtil.toJarFileUri(barResourceJar.toUri()).toASCIIString() + "META-INF/resources/"
            };
            assertThat("META-INF/resources discovered (servlet 3.1 and metadata-complete=true)", discoveredResources, hasItems(expectedResources));

            // TLDs discovered
            Set<URL> tldSet = getDiscoveredMetaInfTlds(context);
            assertThat(tldSet.size(), is(1));
            List<String> discoveredTlds = tldSet
                .stream()
                .map(URL::toExternalForm)
                .toList();
            String[] expectedTlds = {
                URIUtil.toJarFileUri(zedTldJar.toUri()).toASCIIString() + "META-INF/zed.tld"
            };
            assertThat("Discovered TLDs", discoveredTlds, hasItems(expectedTlds));
        }
        finally
        {
            LifeCycle.stop(context.getResourceFactory());
        }
    }

    private FileSystem createNewJarFileSystem(Path jarFile) throws IOException
    {
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        URI jarUri = URIUtil.uriJarPrefix(jarFile.toUri(), "!/");
        return FileSystems.newFileSystem(jarUri, env);
    }

    /**
     * This test examines both the classpath and the module path to find container resources.
     * This test looks {@code foo-bar.janb.jar} on the classpath (it was added there by the surefire configuration
     * present in the {@code pom.xml}), and the {@code servlet-api} from the module path.
     */
    @Test
    public void testGetContainerPathsWithModuleSystem() throws Exception
    {
        MetaInfConfiguration config = new MetaInfConfiguration();
        WebAppContext context = new WebAppContext();
        context.setServer(new Server());
        try
        {
            context.setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN, ".*servlet-api-[^/]*\\.jar$|.*/foo-bar-janb.jar");
            WebAppClassLoader loader = new WebAppClassLoader(context);
            context.setClassLoader(loader);
            config.preConfigure(context);

            Class janbClazz = Class.forName("foo.bar.janb.What", false, loader);
            URI janbUri = TypeUtil.getLocationOfClass(janbClazz);
            Class servletClazz = Class.forName("jakarta.servlet.Servlet", false, loader);
            URI servletUri = TypeUtil.getLocationOfClass(servletClazz);

            List<String> discoveredContainerResources = context.getMetaData().getContainerResources()
                .stream()
                .sorted(ResourceCollators.byName(true))
                .map(Resource::getURI)
                .map(URI::toASCIIString)
                .toList();
            // we "correct" the bad file URLs that come from the ClassLoader
            // to be the same as what comes from every non-classloader URL/URI.
            String[] expectedContainerResources = {
                URIUtil.correctURI(janbUri).toASCIIString(),
                URIUtil.correctURI(servletUri).toASCIIString()
            };
            assertThat("Discovered Container resources", discoveredContainerResources, hasItems(expectedContainerResources));
        }
        finally
        {
            config.postConfigure(context);
            // manually stop ResourceFactory.
            // normally this would be done via WebAppContext.stop(), but we didn't start the context.
            LifeCycle.stop(context.getResourceFactory());
        }
    }

    private Map<Resource, Resource> getDiscoveredMetaInfFragments(WebAppContext context)
    {
        return (Map<Resource, Resource>)context.getAttribute(MetaInfConfiguration.METAINF_FRAGMENTS);
    }

    private Set<Resource> getDiscoveredMetaInfResource(WebAppContext context)
    {
        return (Set<Resource>)context.getAttribute(MetaInfConfiguration.METAINF_RESOURCES);
    }

    private Set<URL> getDiscoveredMetaInfTlds(WebAppContext context)
    {
        return (Set<URL>)context.getAttribute(MetaInfConfiguration.METAINF_TLDS);
    }
}
