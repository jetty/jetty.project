package org.eclipse.jetty.deploy.bindings;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * This binding allows a user to manage a server wide policy of server
 * and system classes as they exist for the webapp context.  These settings
 * can alter and override any that might have been set during context handler 
 * creation.
 * 
 */
public class WebappClasspathPatternBinding implements AppLifeCycle.Binding
{
    private List<String> _serverClasses = new ArrayList<String>();
    private List<String> _systemClasses = new ArrayList<String>();
    
    /**
     * if true, this binding will replace server and system classes instead
     * of merging with whatever is in the ContextHandler when processBinding
     * is invoked.
     */
    private boolean _override = false;
    
    public void setOverride( boolean override )
    {
        _override = override;
    }
    
    public void setServerClasses(String[] serverClasses)
    {
        for ( String entry : serverClasses )
        {
            _serverClasses.add(entry);
        }
    }
    
    public void setSystemClasses(String[] systemClasses)
    {
        for (String entry : systemClasses)
        {
            _systemClasses.add(entry);
        }
    }
    
    public String[] getBindingTargets()
    {
        return new String[] { "deploying" };
    }

    public void processBinding(Node node, App app) throws Exception
    {
        ContextHandler handler = app.getContextHandler();
        if (handler == null)
        {
            throw new NullPointerException("No Handler created for App: " + app);
        }

        if (handler instanceof WebAppContext)
        {
            WebAppContext webapp = (WebAppContext)handler;

            if ( !_override )
            {
                for ( String entry : webapp.getServerClasses() )
                {
                    _serverClasses.add(entry);
                }
                for ( String entry : webapp.getSystemClasses() )
                {
                    _systemClasses.add(entry);
                }
            }        
           
            webapp.setServerClasses( _serverClasses.toArray(new String[_serverClasses.size()]) );
            webapp.setSystemClasses( _systemClasses.toArray(new String[_systemClasses.size()]) );
        }
    }
    
}
