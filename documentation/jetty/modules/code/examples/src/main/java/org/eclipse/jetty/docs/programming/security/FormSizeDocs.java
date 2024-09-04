package org.eclipse.jetty.docs.programming.security;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;

public class FormSizeDocs
{
    public void example()
    {
        ServletContextHandler servletContextHandler = new ServletContextHandler();
        int maxSizeInBytes = 1024;
        int formKeys = 100;
        // tag::formSizeConfig[]
        servletContextHandler.setMaxFormContentSize(maxSizeInBytes);
        servletContextHandler.setMaxFormKeys(formKeys);
        // end::formSizeConfig[]
    }
}
