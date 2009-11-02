package $packageName$;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class $activator$ implements BundleActivator {

    /*
     * (non-Javadoc)
     * @see org.osgi.framework.BundleActivator#start(org.osgi.framework.BundleContext)
     */
    public void start(BundleContext context) throws Exception {
        System.out.println("$startMessage$");
    }
    
    /*
     * (non-Javadoc)
     * @see org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
     */
    public void stop(BundleContext context) throws Exception {
        System.out.println("$stopMessage$");
    }

}
