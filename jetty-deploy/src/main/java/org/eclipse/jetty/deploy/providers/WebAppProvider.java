package org.eclipse.jetty.deploy.providers;

import org.eclipse.jetty.deploy.ConfigurationManager;
import org.eclipse.jetty.deploy.WebAppDeployer;


/* ------------------------------------------------------------ */
/** Context directory App Provider.
 * <p>This specialisation of {@link MonitoredDirAppProvider} is the
 * replacement for {@link WebAppDeployer} and it will scan a directory
 * only for warfiles or directories files.
 * @see WebAppDeployer
 */
public class WebAppProvider extends MonitoredDirAppProvider
{
    public WebAppProvider()
    {
        super(false,true,true);
        setRecursive(false);
        setScanInterval(0);
    }

    /* ------------------------------------------------------------ */
    /**
     * Configuration Managers are not supported for WebAppProvider, so this
     * methods throws an {@link UnsupportedOperationException}.
     */
    @Override
    public void setConfigurationManager(ConfigurationManager configurationManager)
    {
        throw new UnsupportedOperationException();
    }
    
    
}
