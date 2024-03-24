package org.eclipse.jetty.ee;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.ClassMatcher;
import org.eclipse.jetty.util.component.Environment;

public class WebappProtectedClasses
{
    public static final String SYSTEM_CLASSES_ATTRIBUTE = "org.eclipse.jetty.webapp.systemClasses";
    public static final String SERVER_CLASSES_ATTRIBUTE = "org.eclipse.jetty.webapp.serverClasses";

    // System classes are classes that cannot be replaced by
    // the web application, and they are *always* loaded via
    // system classloader.
    public static final ClassMatcher DEFAULT_SYSTEM_CLASSES = new ClassMatcher(
        "java.",                            // Java SE classes (per servlet spec v2.5 / SRV.9.7.2)
        "javax.",                           // Java SE classes (per servlet spec v2.5 / SRV.9.7.2)
        "jakarta.",                         // Jakarta classes (per servlet spec v5.0 / Section 15.2.1)
        "org.xml.",                         // javax.xml
        "org.w3c."                          // javax.xml
    );

    // Server classes are classes that are hidden from being
    // loaded by the web application using system classloader,
    // so if web application needs to load any of such classes,
    // it has to include them in its distribution.
    public static final ClassMatcher DEFAULT_SERVER_CLASSES = new ClassMatcher(
        "org.eclipse.jetty."                // hide jetty classes
    );

    public static ClassMatcher getSystemClasses(Server server)
    {
        return getClassMatcher(server, SYSTEM_CLASSES_ATTRIBUTE, null);
    }

    public static ClassMatcher getSystemClasses(Environment environment)
    {
        return getClassMatcher(environment, SYSTEM_CLASSES_ATTRIBUTE, null);
    }

    /**
     * Add a System Class pattern to use for all WebAppContexts.
     * @param patterns the patterns to use
     */
    public static void addSystemClasses(String... patterns)
    {
        DEFAULT_SYSTEM_CLASSES.add(patterns);
    }

    /**
     * Add a System Class pattern to use for all WebAppContexts of a given {@link Server}.
     * @param server The {@link Server} instance to add classes to
     * @param patterns the patterns to use
     */
    public static void addSystemClasses(Server server, String... patterns)
    {
        addClasses(server, SYSTEM_CLASSES_ATTRIBUTE, DEFAULT_SYSTEM_CLASSES, patterns);
    }

    /**
     * Add a System Class pattern to use for WebAppContexts of a given environment.
     * @param environment The {@link Environment} instance to add classes to
     * @param patterns the patterns to use
     */
    public static void addSystemClasses(Environment environment, String... patterns)
    {
        addClasses(environment, SYSTEM_CLASSES_ATTRIBUTE, DEFAULT_SYSTEM_CLASSES, patterns);
    }

    public static ClassMatcher getServerClasses(Server server)
    {
        return getClassMatcher(server, SERVER_CLASSES_ATTRIBUTE, DEFAULT_SERVER_CLASSES);
    }

    public static ClassMatcher getServerClasses(Environment environment)
    {
        return getClassMatcher(environment, SERVER_CLASSES_ATTRIBUTE, DEFAULT_SERVER_CLASSES);
    }

    /**
     * Add a Server Class pattern to use for all WebAppContexts of a given {@link Server}.
     * @param patterns the patterns to use
     */
    public static void addServerClasses(String... patterns)
    {
        DEFAULT_SERVER_CLASSES.add(patterns);
    }

    /**
     * Add a Server Class pattern to use for all WebAppContexts of a given {@link Server}.
     * @param server The {@link Server} instance to add classes to
     * @param patterns the patterns to use
     */
    public static void addServerClasses(Server server, String... patterns)
    {
        addClasses(server, SERVER_CLASSES_ATTRIBUTE, DEFAULT_SERVER_CLASSES, patterns);
    }

    /**
     * Add a Server Class pattern to use for all ee9 WebAppContexts.
     * @param environment The {@link Environment} instance to add classes to
     * @param patterns the patterns to use
     */
    public static void addServerClasses(Environment environment, String... patterns)
    {
        addClasses(environment, SERVER_CLASSES_ATTRIBUTE, DEFAULT_SERVER_CLASSES, patterns);
    }

    private static void addClasses(Attributes attributes, String attribute, ClassMatcher defaultPatterns, String... patterns)
    {
        ClassMatcher classMatcher = getClassMatcher(attributes, attribute, defaultPatterns);
        if (patterns != null && patterns.length > 0)
            classMatcher.add(patterns);
    }

    private static ClassMatcher getClassMatcher(Attributes attributes, String attribute, ClassMatcher defaultPatterns)
    {
        Object existing = attributes.getAttribute(attribute);
        if (existing instanceof ClassMatcher cm)
            return cm;

        ClassMatcher classMatcher = (existing instanceof String[] stringArray)
            ? new ClassMatcher(stringArray) : new ClassMatcher(defaultPatterns);
        attributes.setAttribute(attribute, classMatcher);
        return classMatcher;
    }

}
