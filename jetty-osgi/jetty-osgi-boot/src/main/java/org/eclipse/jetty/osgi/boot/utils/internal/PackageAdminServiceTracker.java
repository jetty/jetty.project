//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.osgi.boot.utils.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.service.startlevel.StartLevel;

/**
 * PackageAdminServiceTracker
 * <p>
 * When the PackageAdmin service is activated we can look for the fragments
 * attached to this bundle and do a fake "activate" on them.
 * <p>
 * See particularly the jetty-osgi-boot-jsp fragment bundle that uses this
 * facility.
 */
public class PackageAdminServiceTracker implements ServiceListener
{
    private BundleContext _context;

    private List<BundleActivator> _activatedFragments = new ArrayList<>();

    private boolean _fragmentsWereActivated = false;

    // Use the deprecated StartLevel to stay compatible with older versions of
    // OSGi.
    private StartLevel _startLevel;

    private int _maxStartLevel = 6;

    public static PackageAdminServiceTracker INSTANCE = null;

    public PackageAdminServiceTracker(BundleContext context)
    {
        INSTANCE = this;
        _context = context;
        if (!setup())
        {
            try
            {
                _context.addServiceListener(this, "(objectclass=" + PackageAdmin.class.getName() + ")");
            }
            catch (InvalidSyntaxException e)
            {
                e.printStackTrace(); // won't happen
            }
        }
    }

    /**
     * @return true if the fragments were activated by this method.
     */
    private boolean setup()
    {
        ServiceReference sr = _context.getServiceReference(PackageAdmin.class.getName());
        _fragmentsWereActivated = sr != null;
        if (sr != null)
            invokeFragmentActivators(sr);

        sr = _context.getServiceReference(StartLevel.class.getName());
        if (sr != null)
        {
            _startLevel = (StartLevel)_context.getService(sr);
            try
            {
                _maxStartLevel = Integer.parseInt(System.getProperty("osgi.startLevel", "6"));
            }
            catch (Exception e)
            {
                // nevermind default on the usual.
                _maxStartLevel = 6;
            }
        }
        return _fragmentsWereActivated;
    }

    /**
     * Invokes the optional BundleActivator in each fragment. By convention the
     * bundle activator for a fragment must be in the package that is defined by
     * the symbolic name of the fragment and the name of the class must be
     * 'FragmentActivator'.
     *
     * @param event The <code>ServiceEvent</code> object.
     */
    @Override
    public void serviceChanged(ServiceEvent event)
    {
        if (event.getType() == ServiceEvent.REGISTERED)
        {
            invokeFragmentActivators(event.getServiceReference());
        }
    }

    /**
     * Helper to access the PackageAdmin and return the fragments hosted by a
     * bundle. when we drop the support for the older versions of OSGi, we will
     * stop using the PackageAdmin service.
     *
     * @param bundle the bundle
     * @return the bundle fragment list
     */
    public Bundle[] getFragments(Bundle bundle)
    {
        ServiceReference sr = _context.getServiceReference(PackageAdmin.class.getName());
        if (sr == null)
        {
            // we should never be here really.
            return null;
        }
        PackageAdmin admin = (PackageAdmin)_context.getService(sr);
        return admin.getFragments(bundle);
    }

    /**
     * Returns the fragments and the required-bundles of a bundle. Recursively
     * collect the required-bundles and fragment when the directive
     * visibility:=reexport is added to a required-bundle.
     *
     * @param bundle the bundle
     * @return the bundle fragment and required list
     */
    public Bundle[] getFragmentsAndRequiredBundles(Bundle bundle)
    {
        ServiceReference sr = _context.getServiceReference(PackageAdmin.class.getName());
        if (sr == null)
        {
            // we should never be here really.
            return null;
        }
        PackageAdmin admin = (PackageAdmin)_context.getService(sr);
        LinkedHashMap<String, Bundle> deps = new LinkedHashMap<>();
        collectFragmentsAndRequiredBundles(bundle, admin, deps, false);
        return deps.values().toArray(new Bundle[deps.size()]);
    }

    /**
     * Returns the fragments and the required-bundles. Collects them
     * transitively when the directive 'visibility:=reexport' is added to a
     * required-bundle.
     *
     * @param bundle the bundle
     * @param admin the admin package
     * @param deps The map of fragment and required bundles associated to the value of the
     * jetty-web attribute.
     * @param onlyReexport true to collect resources and web-fragments
     * transitively if and only if the directive visibility is
     * reexport.
     */
    protected void collectFragmentsAndRequiredBundles(Bundle bundle, PackageAdmin admin, Map<String, Bundle> deps, boolean onlyReexport)
    {
        Bundle[] fragments = admin.getFragments(bundle);
        if (fragments != null)
        {
            // Also add the bundles required by the fragments.
            // this way we can inject onto an existing web-bundle a set of
            // bundles that extend it
            for (Bundle f : fragments)
            {
                if (!deps.keySet().contains(f.getSymbolicName()))
                {
                    deps.put(f.getSymbolicName(), f);
                    collectRequiredBundles(f, admin, deps, onlyReexport);
                }
            }
        }
        collectRequiredBundles(bundle, admin, deps, onlyReexport);
    }

    /**
     * A simplistic but good enough parser for the Require-Bundle header. Parses
     * the version range attribute and the visibility directive.
     *
     * @param bundle the bundle
     * @param admin the admin package
     * @param deps The map of required bundles associated to the value of the
     * jetty-web attribute.
     * @param onlyReexport true to collect resources and web-fragments
     * transitively if and only if the directive visibility is
     * reexport.
     */
    protected void collectRequiredBundles(Bundle bundle, PackageAdmin admin, Map<String, Bundle> deps, boolean onlyReexport)
    {
        String requiredBundleHeader = (String)bundle.getHeaders().get("Require-Bundle");
        if (requiredBundleHeader == null)
        {
            return;
        }
        StringTokenizer tokenizer = new ManifestTokenizer(requiredBundleHeader);
        while (tokenizer.hasMoreTokens())
        {
            String tok = tokenizer.nextToken().trim();
            StringTokenizer tokenizer2 = new StringTokenizer(tok, ";");
            String symbolicName = tokenizer2.nextToken().trim();
            if (deps.keySet().contains(symbolicName))
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
            Bundle[] reqBundles = admin.getBundles(symbolicName, versionRange);
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
                collectFragmentsAndRequiredBundles(reqBundle, admin, deps, true);
            }
        }
    }

    private void invokeFragmentActivators(ServiceReference sr)
    {
        PackageAdmin admin = (PackageAdmin)_context.getService(sr);
        Bundle[] fragments = admin.getFragments(_context.getBundle());
        if (fragments == null)
        {
            return;
        }
        for (Bundle frag : fragments)
        {
            // find a convention to look for a class inside the fragment.
            try
            {
                String fragmentActivator = frag.getSymbolicName() + ".FragmentActivator";
                Class<?> c = Class.forName(fragmentActivator);
                if (c != null)
                {
                    BundleActivator bActivator = (BundleActivator)c.getDeclaredConstructor().newInstance();
                    bActivator.start(_context);
                    _activatedFragments.add(bActivator);
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    public void stop()
    {
        INSTANCE = null;
        for (BundleActivator fragAct : _activatedFragments)
        {
            try
            {
                fragAct.stop(_context);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    /**
     * @return true if the framework has completed all the start levels.
     */
    public boolean frameworkHasCompletedAutostarts()
    {
        return _startLevel == null || _startLevel.getStartLevel() >= _maxStartLevel;
    }

    private static class ManifestTokenizer extends StringTokenizer
    {

        public ManifestTokenizer(String header)
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

