package org.eclipse.jetty.servlet.api;

import java.util.Collection;
import java.util.EnumSet;

import org.eclipse.jetty.server.DispatcherType;

/**
 * FilterRegistration
 * 
 * Mimics the javax.servlet.FilterRegistration class to ease
 * jetty-7/jetty-8 compatibility
 */
public interface FilterRegistration
{
    public void addMappingForServletNames(EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter, String... servletNames);

    public Collection<String> getServletNameMappings();

    public void addMappingForUrlPatterns(EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter, String... urlPatterns);

    public Collection<String> getUrlPatternMappings();

    interface Dynamic extends FilterRegistration, Registration.Dynamic
    {
    }
}
