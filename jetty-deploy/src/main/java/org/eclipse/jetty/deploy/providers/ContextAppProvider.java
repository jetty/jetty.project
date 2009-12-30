package org.eclipse.jetty.deploy.providers;

import org.eclipse.jetty.deploy.ContextDeployer;


/* ------------------------------------------------------------ */
/** Context directory App Provider.
 * <p>This specialisation of {@link MonitoredDirAppProvider} is the
 * replacement for {@link ContextDeployer} and it will scan a directory
 * only for context.xml files.
 * @see ContextDeployer
 */
public class ContextAppProvider extends MonitoredDirAppProvider
{
    public ContextAppProvider()
    {
        super(true,false,false);
        setRecursive(false);
    }
    
}
