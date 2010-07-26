package org.eclipse.jetty.webapp;


public class CloneConfiguration extends AbstractConfiguration
{
    final WebAppContext _template;
    
    CloneConfiguration(WebAppContext template)
    {
        _template=template;
    }
    
    @Override
    public void configure(WebAppContext context) throws Exception
    {
        for (Configuration configuration : _template.getConfigurations())
            configuration.cloneConfigure(_template,context);
    }


    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
        for (Configuration configuration : _template.getConfigurations())
            configuration.deconfigure(context);
    }
}
