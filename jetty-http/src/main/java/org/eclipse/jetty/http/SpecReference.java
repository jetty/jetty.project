package org.eclipse.jetty.http;

/**
 * The reference to a specific place in the Spec
 */
public interface SpecReference
{
    /**
     * Request attribute name for storing encountered compliance violations
     */
    String VIOLATIONS_ATTR = "org.eclipse.jetty.spec.violations";

    /**
     * The unique name (to Jetty) for this specific reference
     * @return the unique name for this reference
     */
    String getName();

    /**
     * The URL to the spec (and section, if possible)
     * @return the url to the spec
     */
    String getUrl();

    /**
     * The spec description
     * @return the description of the spec detail that this reference is about
     */
    String getDescription();
}
