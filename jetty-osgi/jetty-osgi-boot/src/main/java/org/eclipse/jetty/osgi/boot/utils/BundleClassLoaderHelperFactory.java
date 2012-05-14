package org.eclipse.jetty.osgi.boot.utils;


import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * BundleClassLoaderHelperFactory
 *
 * Get a class loader helper adapted for the particular osgi environment.
 */
public class BundleClassLoaderHelperFactory
{
    private static final Logger LOG = Log.getLogger(BundleClassLoaderHelperFactory.class);
    
    private static BundleClassLoaderHelperFactory _instance = new BundleClassLoaderHelperFactory();
    
    public static BundleClassLoaderHelperFactory getFactory()
    {
        return _instance;
    }
    
    private BundleClassLoaderHelperFactory()
    {
    }
    
    public BundleClassLoaderHelper getHelper()
    {
        //use the default
        BundleClassLoaderHelper helper = BundleClassLoaderHelper.DEFAULT;
        try
        {
            //if a fragment has not provided their own impl
            helper = (BundleClassLoaderHelper) Class.forName(BundleClassLoaderHelper.CLASS_NAME).newInstance();
        }
        catch (Throwable t)
        {
            LOG.ignore(t);
        }
        
        return helper;
    }

}
