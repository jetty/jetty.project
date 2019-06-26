package org.eclipse.jetty.plus.webapp;

import org.eclipse.jetty.webapp.AbstractConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;

public class DecoratingConfiguration extends AbstractConfiguration
{
    private final String _attributeName;

    public DecoratingConfiguration()
    {
        this("org.eclipse.jetty.plus.webapp.Decorator");
    }

    public DecoratingConfiguration(String attributeName)
    {
        super(true);
        _attributeName = attributeName;
    }

    @Override
    public void preConfigure(WebAppContext context)
    {
        context.addEventListener(new DecoratingListener(context, _attributeName));
    }
}
