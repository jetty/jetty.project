package org.eclipse.jetty.deploy.bindings;

import java.net.URL;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.FileResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;

public class GlobalWebappConfigBinding implements AppLifeCycle.Binding
{

    private String _jettyXml;

    public String getJettyXml()
    {
        return _jettyXml;
    }

    public void setJettyXml(String jettyXml)
    {
        this._jettyXml = jettyXml;
    }

    public String[] getBindingTargets()
    {
        return new String[]  { "deploying" };
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
            WebAppContext context = (WebAppContext)handler;

            if (Log.isDebugEnabled())
            {
                Log.debug("Binding: Configuring webapp context with global settings from: " + _jettyXml);
            }

            if ( _jettyXml == null )
            {
                Log.warn("Binding: global context binding is enabled but no jetty-web.xml file has been registered");
            }
            
            Resource globalContextSettings = new FileResource(new URL(_jettyXml));

            if (globalContextSettings.exists())
            {
                XmlConfiguration jettyXmlConfig = new XmlConfiguration(globalContextSettings.getInputStream());

                jettyXmlConfig.configure(context);
            }
            else
            {
                Log.info("Binding: Unable to locate global webapp context settings: " + _jettyXml);
            }
        }
    }

}
