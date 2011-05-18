// ========================================================================
// Copyright (c) 2009 Intalio, Inc.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.osgi.boot.utils.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.service.packageadmin.PackageAdmin;

/**
 * When the PackageAdmin service is activated we can look for the fragments
 * attached to this bundle and "activate" them.
 * 
 */
public class PackageAdminServiceTracker implements ServiceListener
{
    private BundleContext _context;

    private List<BundleActivator> _activatedFragments = new ArrayList<BundleActivator>();
    private boolean _fragmentsWereActivated = false;
    public static PackageAdminServiceTracker INSTANCE = null;

    public PackageAdminServiceTracker(BundleContext context)
    {
        INSTANCE = this;
        _context = context;
        if (!setup())
        {
            try
            {
                _context.addServiceListener(this,"(objectclass=" + PackageAdmin.class.getName() + ")");
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
        return _fragmentsWereActivated;
    }

    /**
     * Invokes the optional BundleActivator in each fragment. By convention the
     * bundle activator for a fragment must be in the package that is defined by
     * the symbolic name of the fragment and the name of the class must be
     * 'FragmentActivator'.
     * 
     * @param event
     *            The <code>ServiceEvent</code> object.
     */
    public void serviceChanged(ServiceEvent event)
    {
        if (event.getType() == ServiceEvent.REGISTERED)
        {
            invokeFragmentActivators(event.getServiceReference());
        }
    }
    
    /**
     * Helper to access the PackageAdmin and return the fragments hosted by a bundle.
     * when we drop the support for the older versions of OSGi, we will stop using the PackageAdmin
     * service.
     * @param bundle
     * @return
     */
    public Bundle[] getFragments(Bundle bundle)
    {
        ServiceReference sr = _context.getServiceReference(PackageAdmin.class.getName());
        if (sr == null)
        {//we should never be here really.
            return null;
        }
        PackageAdmin admin = (PackageAdmin)_context.getService(sr);
        return admin.getFragments(bundle);
    }
    
    /**
     * Returns the fragments and the required-bundles that have a jetty-web annotation attribute
     * compatible with the webFragOrAnnotationOrResources.
     * @param bundle
     * @param webFragOrAnnotationOrResources
     * @return
     */
    public Bundle[] getFragmentsAndRequiredBundles(Bundle bundle)
    {
        ServiceReference sr = _context.getServiceReference(PackageAdmin.class.getName());
        if (sr == null)
        {//we should never be here really.
            return null;
        }
        PackageAdmin admin = (PackageAdmin)_context.getService(sr);
        Bundle[] fragments = admin.getFragments(bundle);
        //get the required bundles. we can't use the org.osgi.framework.wiring package
        //just yet: it is not supported by enough osgi implementations.
        List<Bundle> requiredBundles = getRequiredBundles(bundle, admin);
        if (fragments != null)
        {
        	Set<String> already = new HashSet<String>();
        	for (Bundle b : requiredBundles)
        	{
        		already.add(b.getSymbolicName());
        	}
	        //Also add the bundles required by the fragments.
	        //this way we can inject onto an existing web-bundle a set of bundles that extend it
        	for (Bundle f : fragments)
        	{
        		List<Bundle> requiredBundlesByFragment = getRequiredBundles(f, admin);
        		for (Bundle b : requiredBundlesByFragment)
        		{
        			if (already.add(b.getSymbolicName()))
        			{
        				requiredBundles.add(b);
        			}
        		}
        	}
        }
        ArrayList<Bundle> bundles = new ArrayList<Bundle>(
        		(fragments != null ? fragments.length : 0) +
        		(requiredBundles != null ? requiredBundles.size() : 0));
        if (fragments != null)
        {
        	for (Bundle f : fragments)
        	{
        		bundles.add(f);
        	}
        }
        if (requiredBundles != null)
        {
        	bundles.addAll(requiredBundles);
        }
        return bundles.toArray(new Bundle[bundles.size()]);
    }
    
    /**
     * A simplistic but good enough parser for the Require-Bundle header.
     * @param bundle
     * @return The map of required bundles associated to the value of the jetty-web attribute.
     */
    protected List<Bundle> getRequiredBundles(Bundle bundle, PackageAdmin admin)
    {
    	List<Bundle> res = new ArrayList<Bundle>();
    	String requiredBundleHeader = bundle.getHeaders().get("Require-Bundle");
    	if (requiredBundleHeader == null)
    	{
    		return res;
    	}
    	StringTokenizer tokenizer = new StringTokenizer(requiredBundleHeader, ",");
    	while (tokenizer.hasMoreTokens())
    	{
    		String tok = tokenizer.nextToken().trim();
    		StringTokenizer tokenizer2 = new StringTokenizer(tok, ";");
    		String symbolicName = tokenizer2.nextToken().trim();
    		String versionRange = null;
    		while (tokenizer2.hasMoreTokens())
    		{
    			String next = tokenizer2.nextToken().trim();
    			if (next.startsWith("bundle-version="))
    			{
    				if (next.startsWith("bundle-version=\"") || next.startsWith("bundle-version='"))
    				{
    					versionRange = next.substring("bundle-version=\"".length(), next.length()-1);
    				}
    				else
    				{
    					versionRange = next.substring("bundle-version=".length());
    				}
    			}
    		}
    		Bundle[] reqBundles = admin.getBundles(symbolicName, versionRange);
    		if (reqBundles != null)
    		{
	    		for (Bundle b : reqBundles)
	    		{
	    			if (b.getState() == Bundle.ACTIVE || b.getState() == Bundle.STARTING)
	    			{
	    				res.add(b);
	    				break;
	    			}
	    		}
    		}
    	}
    	return res;
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
                    BundleActivator bActivator = (BundleActivator)c.newInstance();
                    bActivator.start(_context);
                    _activatedFragments.add(bActivator);
                }
            }
            catch (NullPointerException e)
            {
                // e.printStackTrace();
            }
            catch (InstantiationException e)
            {
                // e.printStackTrace();
            }
            catch (IllegalAccessException e)
            {
                // e.printStackTrace();
            }
            catch (ClassNotFoundException e)
            {
                // e.printStackTrace();
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

}
