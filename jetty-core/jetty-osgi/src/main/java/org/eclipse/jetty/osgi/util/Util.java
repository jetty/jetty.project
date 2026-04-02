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

package org.eclipse.jetty.osgi.util;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringTokenizer;

import org.eclipse.jetty.osgi.BundleMetadata;
import org.eclipse.jetty.osgi.OSGiServerConstants;
import org.eclipse.jetty.osgi.OSGiWebappConstants;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.Version;
import org.osgi.service.packageadmin.PackageAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods.
 */
public class Util
{
    private static final Logger LOG = LoggerFactory.getLogger(Util.class);

    private static final String REGISTRATION = "org.eclipse.jetty.osgi.registration";

    /**
     * Create an osgi filter for the given classname and server name.
     *
     * @param bundleContext the {@link BundleContext} instance to use
     * @param classname the class to match on the filter
     * @param managedServerName the name of the jetty server instance
     * @return a new filter
     * @throws InvalidSyntaxException If the filter contains an invalid string that cannot be parsed.
     */
    public static Filter createFilter(BundleContext bundleContext, String classname, String managedServerName) throws InvalidSyntaxException
    {
        if (StringUtil.isBlank(managedServerName) || managedServerName.equals(OSGiServerConstants.MANAGED_JETTY_SERVER_DEFAULT_NAME))
        {
            return bundleContext.createFilter("(&(objectclass=" + classname + ")(|(managedServerName=" + managedServerName + ")(!(managedServerName=*))))");
        }
        else
        {
            return bundleContext.createFilter("(&(objectclass=" + classname + ")(managedServerName=" + managedServerName + "))");
        }
    }

    public static void deregisterAsOSGiService(ContextHandler contextHandler)
    {
        ServiceRegistration<?> serviceRegistration = (ServiceRegistration<?>)contextHandler.getAttribute(REGISTRATION);
        if (serviceRegistration == null)
            return;

        serviceRegistration.unregister();
        contextHandler.removeAttribute(REGISTRATION);
    }

    /**
     * Treating the string as a separated list of filenames,
     * convert and return the list of urls.
     *
     * @param val the separated list of filenames
     * @param delims the separators (default is <code>,;</code>)
     * @return the list of URLs found in the input list
     * @throws Exception if unable to convert entry to a URL
     */
    public static List<URL> fileNamesAsURLs(String val, String delims)
        throws Exception
    {
        String separators = StringUtil.DEFAULT_DELIMS;
        if (delims == null)
            delims = separators;

        StringTokenizer tokenizer = new StringTokenizer(val, delims, false);
        List<URL> urls = new ArrayList<>();
        while (tokenizer.hasMoreTokens())
        {
            urls.add(BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(new URL(tokenizer.nextToken())));
        }
        return urls;
    }

    /**
     * Get the install location of a Bundle as a Path
     *
     * @param bundle the Bundle whose location to return
     * @return the installed location of the Bundle as a Path
     */
    public static Path getBundlePath(Bundle bundle) throws Exception
    {
        String bundleOverrideLocation = bundle.getHeaders().get(OSGiWebappConstants.JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE);
        File bundleLocation = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);
        return (bundleOverrideLocation == null ? bundleLocation.toPath() : Path.of(bundleOverrideLocation));
    }

    /**
     * Convert a bundle installed location into a Resource, taking account of
     * any locations that are actually packed jars, but without a ".jar" extension, eg
     * as found on equinox. Eg file:///a/b/c/org.eclipse.osgi/89/0/bundleFile
     *
     * @param bundle the Bundle to convert
     * @param resourceFactory the ResourceFactory to create the new Resource in
     * @return a new Resource representing the bundle's installed location
     */
    public static Resource newBundleResource(Bundle bundle, ResourceFactory resourceFactory) throws Exception
    {
        String bundleOverrideLocation = bundle.getHeaders().get(OSGiWebappConstants.JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE);
        File bundleLocation = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);
        Path root = (bundleOverrideLocation == null ? bundleLocation.toPath() : Path.of(bundleOverrideLocation));
        // Fix some osgiPaths.get( locations which point to an archive, but that doesn't end in .jar
        URL url = BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(root.toUri().toURL());
        return resourceFactory.newResource(url);
    }

    /**
     * Get or create a contextPath from bundle headers and information
     *
     * @param bundle the bundle
     * @return a contextPath
     */
    public static String getContextPath(Bundle bundle)
    {
        Dictionary<?, ?> headers = bundle.getHeaders();
        String contextPath = (String)headers.get(OSGiWebappConstants.RFC66_WEB_CONTEXTPATH);
        if (contextPath == null)
        {
            // extract from the last token of the bundle's location:
            // (really ?could consider processing the symbolic name as an alternative
            // the location will often reflect the version.
            // maybe this is relevant when the file is a war)
            String location = bundle.getLocation();
            String[] toks = StringUtil.replace(location, '\\', '/').split("/");
            contextPath = toks[toks.length - 1];
            // remove .jar, .war etc:
            int lastDot = contextPath.lastIndexOf('.');
            if (lastDot != -1)
                contextPath = contextPath.substring(0, lastDot);
        }
        if (!contextPath.startsWith("/"))
            contextPath = "/" + contextPath;

        return contextPath;
    }

    public static URL getLocalURL(URL url)
        throws Exception
    {
        if (url == null)
            return null;

        return BundleFileLocatorHelper.DEFAULT.getLocalURL(url);
    }

    public static URL getLocalizedEntry(String file, Bundle bundle)
        throws Exception
    {
        if (file == null || bundle == null)
            return null;

        URL url = bundle.getEntry(file);
        if (url == null)
            return null;

        return BundleFileLocatorHelper.DEFAULT.getLocalURL(url);
    }

    /**
     * Get the value of a manifest header.
     *
     * @param name the name of the header
     * @param altName an alternative name for the header (useful for deprecated names)
     * @param manifest the dictionary
     * @return the value from the manifest
     */
    public static String getManifestHeaderValue(String name, String altName, Dictionary<String, String> manifest)
    {
        if (manifest == null)
            return null;
        if (name == null && altName == null)
            return null;
        if (name != null)
            return (String)manifest.get(name);
        return (String)manifest.get(altName);
    }

    /**
     * Get the value of a manifest header.
     *
     * @param name the name of the header
     * @param manifest the dictionary
     * @return the value from the manifest
     */
    public static String getManifestHeaderValue(String name, Dictionary<String, String> manifest)
    {
        return getManifestHeaderValue(name, null, manifest);
    }

    /**
     * Resolve the file system paths to bundles identified by their symbolic names.
     *
     * @param bundleSymbolicNames comma separated list of symbolic bundle names
     * @param bundleContext the bundle on whose behalf to resolve
     * @return List of resolved Paths matching the bundle symbolic names
     */
    public static List<Path> getPathsToBundlesBySymbolicNames(String bundleSymbolicNames, BundleContext bundleContext)
        throws Exception
    {
        if (bundleSymbolicNames == null)
            return Collections.emptyList();

        Objects.requireNonNull(bundleContext);

        ServiceReference ref = bundleContext.getServiceReference(org.osgi.service.packageadmin.PackageAdmin.class.getName());
        PackageAdmin packageAdmin = (ref == null) ? null : (PackageAdmin)bundleContext.getService(ref);
        if (packageAdmin == null)
            throw new IllegalStateException("Unable to get PackageAdmin reference to locate required Tld bundles");

        List<Path> paths = new ArrayList<>();
        String[] symbNames = bundleSymbolicNames.split("[, ]");

        for (String symbName : symbNames)
        {
            Bundle[] bs = packageAdmin.getBundles(symbName, null);
            if (bs == null || bs.length == 0)
            {
                throw new IllegalArgumentException("Unable to locate the bundle '" + symbName + "' specified in manifest of " +
                    bundleContext.getBundle().getSymbolicName());
            }

            File f = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bs[0]);
            paths.add(f.toPath());
        }

        return paths;
    }

    /**
     * Register the Jetty deployed context/webapp as a service, as
     * according to the OSGi Web Application Specification.
     */
    public static void registerAsOSGiService(ContextHandler contextHandler)
    {
        ServiceRegistration<?> serviceRegistration = (ServiceRegistration<?>)contextHandler.getAttribute(REGISTRATION);
        if (serviceRegistration == null)
        {
            Dictionary<String, String> properties = new Hashtable<>();
            properties.put(OSGiWebappConstants.WATERMARK, OSGiWebappConstants.WATERMARK);

            Bundle bundle = (Bundle)contextHandler.getAttribute(BundleMetadata.BUNDLE);
            if (bundle != null)
            {
                String bundleSymbolicName = bundle.getSymbolicName();
                if (StringUtil.isNotBlank(bundleSymbolicName))
                    properties.put(OSGiWebappConstants.OSGI_WEB_SYMBOLICNAME, bundleSymbolicName);

                Version version = bundle.getVersion();
                if (version != null)
                {
                    String bundleVersion = version.toString();
                    if (StringUtil.isNotBlank(bundleVersion))
                        properties.put(OSGiWebappConstants.OSGI_WEB_VERSION, bundleVersion);
                }
            }

            properties.put(OSGiWebappConstants.OSGI_WEB_CONTEXTPATH, contextHandler.getContextPath());

            serviceRegistration = FrameworkUtil.getBundle(BundleMetadata.class).getBundleContext().registerService(ContextHandler.class.getName(), contextHandler, properties);
            contextHandler.setAttribute(REGISTRATION, serviceRegistration);
        }
    }

    /**
     * Resolve a path either absolutely or against the bundle install location, or
     * against jetty home.
     *
     * @param path the path to resolve
     * @param bundle the bundle
     * @param jettyHome the path to jetty home
     * @return the URI resolved either absolutely or against the bundle install location or jetty home.
     */
    public static URI resolvePathAsLocalizedURI(String path, Bundle bundle, Path jettyHome)
        throws Exception
    {
        if (StringUtil.isBlank(path))
            return null;

        if (path.startsWith("file:/"))
            return URIUtil.correctURI(new URI(path));

        if (path.startsWith("/") && File.separatorChar != '/')
            return new URI("file:" + path);

        try
        {
            Path p = FileSystems.getDefault().getPath(path);
            if (p.isAbsolute())
                return p.toUri();
        }
        catch (InvalidPathException x)
        {
            //ignore and try via the jetty bundle instead
            if (LOG.isTraceEnabled())
                LOG.trace("IGNORED", x);
        }

        //relative location
        //try inside the bundle first
        if (bundle != null)
        {
            URL url = bundle.getEntry(path);
            if (url != null)
            {
                return BundleFileLocatorHelper.DEFAULT.getLocalURL(url).toURI();
            }
        }

        //try resolving against jetty.home
        if (jettyHome != null)
        {
            Path p = jettyHome.resolve(path);
            if (Files.exists(p))
                return p.toUri();
        }

        return null;
    }

    /**
     * recursively substitute the <code>${sysprop}</code> by their actual system property.
     * <code>${sysprop,defaultvalue}</code> will use <code>'defaultvalue'</code> as the value if no
     * sysprop is defined. Not the most efficient code but we are shooting for
     * simplicity and speed of development here.
     *
     * @param value the input string
     * @return the string with replaced properties
     */
    public static String resolvePropertyValue(String value)
    {
        int ind = value.indexOf("${");
        if (ind == -1)
        {
            return value;
        }
        int ind2 = value.indexOf('}', ind);
        if (ind2 == -1)
        {
            return value;
        }
        String sysprop = value.substring(ind + 2, ind2);
        String defaultValue = null;
        int comma = sysprop.indexOf(',');
        if (comma != -1 && comma + 1 != sysprop.length())
        {
            defaultValue = sysprop.substring(comma + 1);
            defaultValue = resolvePropertyValue(defaultValue);
            sysprop = sysprop.substring(0, comma);
        }
        else
        {
            defaultValue = "${" + sysprop + "}";
        }

        String v = System.getProperty(sysprop);

        String reminder = value.length() > ind2 + 1 ? value.substring(ind2 + 1) : "";
        reminder = resolvePropertyValue(reminder);
        if (v != null)
        {
            return value.substring(0, ind) + v + reminder;
        }
        else
        {
            return value.substring(0, ind) + defaultValue + reminder;
        }
    }

    public static void setProperty(Dictionary<String, Object> properties, String key, Object value)
    {
        if (value != null)
        {
            properties.put(key, value);
        }
    }

    /**
     * Returns the fragments and the required-bundles of a bundle. Recursively
     * collect the required-bundles and fragment when the directive
     * visibility:=reexport is added to a required-bundle.
     *
     * @param bootBundleContext BundleContext associated with the jetty boot bundle
     * @param targetBundle the bundle whose fragments and required bundles should be collected
     * @return the flattened tree of all fragments and required bundles associated with the target bundle
     */
    public static Bundle[] getFragmentsAndRequiredBundles(BundleContext bootBundleContext, Bundle targetBundle)
    {
        if (bootBundleContext == null)
            return null;

        ServiceReference<PackageAdmin> sr = bootBundleContext.getServiceReference(PackageAdmin.class);
        if (sr == null)
            return null;
        
        PackageAdmin packageAdmin = bootBundleContext.getService(sr);
        LinkedHashMap<String, Bundle> deps = new LinkedHashMap<>();
        collectFragmentsAndRequiredBundles(packageAdmin, targetBundle, deps, false);
        return deps.values().toArray(new Bundle[0]);
    }

    /**
     * Returns the fragments and the required-bundles. Collects them
     * transitively when the directive 'visibility:=reexport' is added to a
     * required-bundle.
     *
     * @param packageAdmin the PackageAdmin service to use
     * @param bundle the bundle
     * @param deps The map of fragment and required bundles associated to the value of the
     * jetty-web attribute.
     * @param onlyReexport true to collect resources and web-fragments
     * transitively if and only if the directive visibility is
     * reexport.
     */
    private static void collectFragmentsAndRequiredBundles(PackageAdmin packageAdmin, Bundle bundle, Map<String, Bundle> deps, boolean onlyReexport)
    {
        if (packageAdmin == null)
            return;

        Bundle[] fragments = packageAdmin.getFragments(bundle);
        if (fragments != null)
        {
            // Also add the bundles required by the fragments.
            // this way we can inject onto an existing web-bundle a set of
            // bundles that extend it
            for (Bundle f : fragments)
            {
                if (!deps.containsKey(f.getSymbolicName()))
                {
                    deps.put(f.getSymbolicName(), f);
                    collectRequiredBundles(packageAdmin, f, deps, onlyReexport);
                }
            }
        }
        collectRequiredBundles(packageAdmin, bundle, deps, onlyReexport);
    }

    /**
     * Parse the Require-Bundle header on the given bundle, and then collect
     * the fragments and required bundles of those bundles.
     *
     * @param packageAdmin the PackageAdmin service to use
     * @param bundle the bundle
     * @param deps The map of required and fragment bundles
     * @param onlyReexport true to collect resources and web-fragments transitively if and only if the directive visibility is
     * reexport.
     */
    private static void collectRequiredBundles(PackageAdmin packageAdmin, Bundle bundle, Map<String, Bundle> deps, boolean onlyReexport)
    {
        String requiredBundleHeader = bundle.getHeaders().get("Require-Bundle");
        if (requiredBundleHeader == null)
        {
            return;
        }
        StringTokenizer tokenizer = new ManifestHeaderTokenizer(requiredBundleHeader);
        while (tokenizer.hasMoreTokens())
        {
            String tok = tokenizer.nextToken().trim();
            StringTokenizer tokenizer2 = new StringTokenizer(tok, ";");
            String symbolicName = tokenizer2.nextToken().trim();
            if (deps.containsKey(symbolicName))
            {
                // was already added. 2 dependencies pointing at the same
                // bundle.
                continue;
            }
            String versionRange = null;
            boolean reexport = false;
            while (tokenizer2.hasMoreTokens())
            {
                String next = tokenizer2.nextToken().trim();
                if (next.startsWith("bundle-version="))
                {
                    if (next.startsWith("bundle-version=\"") || next.startsWith("bundle-version='"))
                    {
                        versionRange = next.substring("bundle-version=\"".length(), next.length() - 1);
                    }
                    else
                    {
                        versionRange = next.substring("bundle-version=".length());
                    }
                }
                else if (next.equals("visibility:=reexport"))
                {
                    reexport = true;
                }
            }
            if (!reexport && onlyReexport)
            {
                return;
            }
            Bundle[] reqBundles = packageAdmin.getBundles(symbolicName, versionRange);
            if (reqBundles != null && reqBundles.length != 0)
            {
                Bundle reqBundle = null;
                for (Bundle b : reqBundles)
                {
                    if (b.getState() == Bundle.ACTIVE || b.getState() == Bundle.STARTING)
                    {
                        reqBundle = b;
                        break;
                    }
                }
                if (reqBundle == null)
                {
                    // strange? in OSGi with Require-Bundle,
                    // the dependent bundle is supposed to be active already
                    reqBundle = reqBundles[0];
                }
                deps.put(reqBundle.getSymbolicName(), reqBundle);
                collectFragmentsAndRequiredBundles(packageAdmin, reqBundle, deps, true);
            }
        }
    }

    private static class ManifestHeaderTokenizer extends StringTokenizer
    {
        public ManifestHeaderTokenizer(String header)
        {
            super(header, ",");
        }

        @Override
        public String nextToken()
        {
            String token = super.nextToken();

            while (hasOpenQuote(token) && hasMoreTokens())
            {
                token += "," + super.nextToken();
            }
            return token;
        }

        private boolean hasOpenQuote(String token)
        {
            int i = -1;
            do
            {
                int quote = getQuote(token, i + 1);
                if (quote < 0)
                {
                    return false;
                }

                i = token.indexOf(quote, i + 1);
                i = token.indexOf(quote, i + 1);
            }
            while (i >= 0);
            return true;
        }

        private int getQuote(String token, int offset)
        {
            int i = token.indexOf('"', offset);
            int j = token.indexOf('\'', offset);
            if (i < 0)
            {
                if (j < 0)
                {
                    return -1;
                }
                else
                {
                    return '\'';
                }
            }
            if (j < 0)
            {
                return '"';
            }
            if (i < j)
            {
                return '"';
            }
            return '\'';
        }
    }
}
