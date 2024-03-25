//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.webapp;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;

/**
 * A matcher for classes based on package and/or location and/or module/
 * <p>
 * Performs pattern matching of a class against a set of pattern entries.
 * A class pattern is a string of one of the forms:<ul>
 * <li>'org.package.SomeClass' will match a specific class
 * <li>'org.package.' will match a specific package hierarchy
 * <li>'org.package.SomeClass$NestedClass ' will match a nested class exactly otherwise.
 * Nested classes are matched by their containing class. (eg. org.example.MyClass
 * matches org.example.MyClass$AnyNestedClass)
 * <li>'file:///some/location/' - A file system directory from which
 * the class was loaded
 * <li>'file:///some/location.jar' - The URI of a jar file from which
 * the class was loaded
 * <li>'jrt:/modulename' - A Java9 module name</li>
 * <li>Any of the above patterns preceded by '-' will exclude rather than include the match.
 * </ul>
 * When class is initialized from a classpath pattern string, entries
 * in this string should be separated by ':' (semicolon) or ',' (comma).
 */

public class ClassMatcher extends AbstractSet<String>
{
    public static class Entry
    {
        private final String _pattern;
        private final String _name;
        private final boolean _inclusive;

        protected Entry(String name, boolean inclusive)
        {
            _name = name;
            _inclusive = inclusive;
            _pattern = inclusive ? _name : ("-" + _name);
        }

        public String getPattern()
        {
            return _pattern;
        }

        public String getName()
        {
            return _name;
        }

        @Override
        public String toString()
        {
            return _pattern;
        }

        @Override
        public int hashCode()
        {
            return _pattern.hashCode();
        }

        @Override
        public boolean equals(Object o)
        {
            return (o instanceof Entry) && _pattern.equals(((Entry)o)._pattern);
        }

        public boolean isInclusive()
        {
            return _inclusive;
        }
    }

    private static class PackageEntry extends Entry
    {
        protected PackageEntry(String name, boolean inclusive)
        {
            super(name, inclusive);
        }
    }

    private static class ClassEntry extends Entry
    {
        protected ClassEntry(String name, boolean inclusive)
        {
            super(name, inclusive);
        }
    }

    private static class LocationEntry extends Entry
    {
        private final Path _path;

        protected LocationEntry(String name, boolean inclusive)
        {
            super(name, inclusive);
            URI uri = URI.create(name);
            if (!uri.isAbsolute() && !"file".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalArgumentException("Not a valid file URI: " + name);

            _path = Paths.get(uri);
        }

        public Path getPath()
        {
            return _path;
        }
    }

    private static class ModuleEntry extends Entry
    {
        private final String _module;

        protected ModuleEntry(String name, boolean inclusive)
        {
            super(name, inclusive);
            if (!getName().startsWith("jrt:"))
                throw new IllegalArgumentException(name);
            _module = getName().split("/")[1];
        }

        public String getModule()
        {
            return _module;
        }
    }

    public static class ByPackage extends AbstractSet<Entry> implements Predicate<String>
    {
        private final Index.Mutable<Entry> _entries = new Index.Builder<Entry>()
            .caseSensitive(true)
            .mutable()
            .build();

        @Override
        public boolean test(String name)
        {
            return _entries.getBest(name) != null;
        }

        @Override
        public Iterator<Entry> iterator()
        {
            return _entries.keySet().stream().map(_entries::get).iterator();
        }

        @Override
        public int size()
        {
            return _entries.size();
        }

        @Override
        public boolean isEmpty()
        {
            return _entries.isEmpty();
        }

        @Override
        public boolean add(Entry entry)
        {
            String name = entry.getName();
            if (entry instanceof ClassEntry)
                name += "$";
            else if (!(entry instanceof PackageEntry))
                throw new IllegalArgumentException(entry.toString());
            else if (".".equals(name))
                name = "";

            if (_entries.get(name) != null)
                return false;

            return _entries.put(name, entry);
        }

        @Override
        public boolean remove(Object entry)
        {
            if (!(entry instanceof Entry))
                return false;

            return _entries.remove(((Entry)entry).getName()) != null;
        }

        @Override
        public void clear()
        {
            _entries.clear();
        }
    }

    @SuppressWarnings("serial")
    public static class ByClass extends HashSet<Entry> implements Predicate<String>
    {
        private final Map<String, Entry> _entries = new HashMap<>();

        @Override
        public boolean test(String name)
        {
            return _entries.containsKey(name);
        }

        @Override
        public Iterator<Entry> iterator()
        {
            return _entries.values().iterator();
        }

        @Override
        public int size()
        {
            return _entries.size();
        }

        @Override
        public boolean add(Entry entry)
        {
            if (!(entry instanceof ClassEntry))
                throw new IllegalArgumentException(entry.toString());
            return _entries.put(entry.getName(), entry) == null;
        }

        @Override
        public boolean remove(Object entry)
        {
            if (!(entry instanceof Entry))
                return false;

            return _entries.remove(((Entry)entry).getName()) != null;
        }
    }

    public static class ByPackageOrName extends AbstractSet<Entry> implements Predicate<String>
    {
        private final ByClass _byClass = new ByClass();
        private final ByPackage _byPackage = new ByPackage();

        @Override
        public boolean test(String name)
        {
            return _byPackage.test(name) || _byClass.test(name);
        }

        @Override
        public Iterator<Entry> iterator()
        {
            // by package contains all entries (classes are also $ packages).
            return _byPackage.iterator();
        }

        @Override
        public int size()
        {
            return _byPackage.size();
        }

        @Override
        public boolean add(Entry entry)
        {
            if (entry instanceof PackageEntry)
                return _byPackage.add(entry);

            if (entry instanceof ClassEntry)
            {
                // Add class name to packages also as classes act
                // as packages for nested classes.
                boolean added = _byPackage.add(entry);
                added = _byClass.add(entry) || added;
                return added;
            }

            throw new IllegalArgumentException();
        }

        @Override
        public boolean remove(Object o)
        {
            if (!(o instanceof Entry))
                return false;

            boolean removedPackage = _byPackage.remove(o);
            boolean removedClass = _byClass.remove(o);

            return removedPackage || removedClass;
        }

        @Override
        public void clear()
        {
            _byPackage.clear();
            _byClass.clear();
        }
    }

    @SuppressWarnings("serial")
    public static class ByLocation extends HashSet<Entry> implements Predicate<URI>
    {
        @Override
        public boolean test(URI uri)
        {
            if ((uri == null) || (!uri.isAbsolute()))
                return false;
            if (!uri.getScheme().equals("file"))
                return false;
            Path path = Paths.get(uri);

            for (Entry entry : this)
            {
                if (!(entry instanceof LocationEntry))
                    throw new IllegalStateException();

                Path entryPath = ((LocationEntry)entry).getPath();

                if (Files.isDirectory(entryPath))
                {
                    if (path.startsWith(entryPath))
                    {
                        return true;
                    }
                }
                else
                {
                    try
                    {
                        if (Files.isSameFile(path, entryPath))
                        {
                            return true;
                        }
                    }
                    catch (IOException ignore)
                    {
                        // this means there is a FileSystem issue preventing comparison.
                        // Use old technique
                        if (path.equals(entryPath))
                        {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    @SuppressWarnings("serial")
    public static class ByModule extends HashSet<Entry> implements Predicate<URI>
    {
        private final Index.Mutable<Entry> _entries = new Index.Builder<Entry>()
            .caseSensitive(true)
            .mutable()
            .build();

        @Override
        public boolean test(URI uri)
        {
            if ((uri == null) || (!uri.isAbsolute()))
                return false;
            if (!uri.getScheme().equalsIgnoreCase("jrt"))
                return false;
            String module = uri.getPath();
            int end = module.indexOf('/', 1);
            if (end < 1)
                end = module.length();
            return _entries.get(module, 1, end - 1) != null;
        }

        @Override
        public Iterator<Entry> iterator()
        {
            return _entries.keySet().stream().map(_entries::get).iterator();
        }

        @Override
        public int size()
        {
            return _entries.size();
        }

        @Override
        public boolean add(Entry entry)
        {
            if (!(entry instanceof ModuleEntry))
                throw new IllegalArgumentException(entry.toString());
            String module = ((ModuleEntry)entry).getModule();

            if (_entries.get(module) != null)
                return false;
            _entries.put(module, entry);
            return true;
        }

        @Override
        public boolean remove(Object entry)
        {
            if (!(entry instanceof Entry))
                return false;

            return _entries.remove(((Entry)entry).getName()) != null;
        }
    }

    public static class ByLocationOrModule extends AbstractSet<Entry> implements Predicate<URI>
    {
        private final ByLocation _byLocation = new ByLocation();
        private final ByModule _byModule = new ByModule();

        @Override
        public boolean test(URI name)
        {
            if ((name == null) || (!name.isAbsolute()))
                return false;
            return _byLocation.test(name) || _byModule.test(name);
        }

        @Override
        public Iterator<Entry> iterator()
        {
            Set<Entry> entries = new HashSet<>();
            entries.addAll(_byLocation);
            entries.addAll(_byModule);
            return entries.iterator();
        }

        @Override
        public int size()
        {
            return _byLocation.size() + _byModule.size();
        }

        @Override
        public boolean add(Entry entry)
        {
            if (entry instanceof LocationEntry)
                return _byLocation.add(entry);
            if (entry instanceof ModuleEntry)
                return _byModule.add(entry);

            throw new IllegalArgumentException(entry.toString());
        }

        @Override
        public boolean remove(Object o)
        {
            if (o instanceof LocationEntry)
                return _byLocation.remove(o);
            if (o instanceof ModuleEntry)
                return _byModule.remove(o);
            return false;
        }

        @Override
        public void clear()
        {
            _byLocation.clear();
            _byModule.clear();
        }
    }

    Map<String, Entry> _entries = new HashMap<>();
    IncludeExcludeSet<Entry, String> _patterns = new IncludeExcludeSet<>(ByPackageOrName.class);
    IncludeExcludeSet<Entry, URI> _locations = new IncludeExcludeSet<>(ByLocationOrModule.class);

    public ClassMatcher()
    {
    }

    @SuppressWarnings("CopyConstructorMissesField")
    public ClassMatcher(ClassMatcher patterns)
    {
        if (patterns != null)
            setAll(patterns.getPatterns());
    }

    public ClassMatcher(String... patterns)
    {
        if (patterns != null && patterns.length > 0)
            setAll(patterns);
    }

    public ClassMatcher(String pattern)
    {
        add(pattern);
    }

    public boolean include(String name)
    {
        if (name == null)
            return false;
        return add(newEntry(name, true));
    }

    public boolean include(String... name)
    {
        boolean added = false;
        for (String n : name)
        {
            if (n != null)
                added = add(newEntry(n, true)) || added;
        }
        return added;
    }

    public boolean exclude(String name)
    {
        if (name == null)
            return false;
        return add(newEntry(name, false));
    }

    public boolean exclude(String... name)
    {
        boolean added = false;
        for (String n : name)
        {
            if (n != null)
                added = add(newEntry(n, false)) || added;
        }
        return added;
    }

    @Override
    public boolean add(String pattern)
    {
        if (pattern == null)
            return false;
        return add(newEntry(pattern));
    }

    public boolean add(String... pattern)
    {
        boolean added = false;
        for (String p : pattern)
        {
            if (p != null)
                added = add(newEntry(p)) || added;
        }
        return added;
    }

    protected boolean add(Entry entry)
    {
        if (_entries.containsKey(entry.getPattern()))
            return false;
        _entries.put(entry.getPattern(), entry);

        if (entry instanceof LocationEntry || entry instanceof ModuleEntry)
        {
            if (entry.isInclusive())
                _locations.include(entry);
            else
                _locations.exclude(entry);
        }
        else
        {
            if (entry.isInclusive())
                _patterns.include(entry);
            else
                _patterns.exclude(entry);
        }
        return true;
    }

    protected Entry newEntry(String pattern)
    {
        if (pattern.startsWith("-"))
            return newEntry(pattern.substring(1), false);
        return newEntry(pattern, true);
    }

    protected Entry newEntry(String name, boolean inclusive)
    {
        if (name.startsWith("-"))
            throw new IllegalStateException(name);
        if (name.startsWith("file:"))
            return new LocationEntry(name, inclusive);
        if (name.startsWith("jrt:"))
            return new ModuleEntry(name, inclusive);
        if (name.endsWith("."))
            return new PackageEntry(name, inclusive);
        return new ClassEntry(name, inclusive);
    }

    @Override
    public boolean remove(Object o)
    {
        if (!(o instanceof String))
            return false;
        String pattern = (String)o;

        Entry entry = _entries.remove(pattern);
        if (entry == null)
            return false;

        List<Entry> saved = new ArrayList<>(_entries.values());
        clear();
        for (Entry e : saved)
        {
            add(e);
        }
        return true;
    }

    @Override
    public void clear()
    {
        _entries.clear();
        _patterns.clear();
        _locations.clear();
    }

    @Override
    public Iterator<String> iterator()
    {
        return _entries.keySet().iterator();
    }

    @Override
    public int size()
    {
        return _entries.size();
    }

    /**
     * Initialize the matcher by parsing each classpath pattern in an array
     *
     * @param classes array of classpath patterns
     */
    private void setAll(String[] classes)
    {
        _entries.clear();
        addAll(classes);
    }

    /**
     * Add array of classpath patterns.
     * @param classes array of classpath patterns
     */
    private void addAll(String[] classes)
    {
        if (classes != null)
            addAll(Arrays.asList(classes));
    }

    /**
     * @return array of classpath patterns
     */
    public String[] getPatterns()
    {
        return toArray(new String[0]);
    }

    /**
     * @return array of inclusive classpath patterns
     */
    public String[] getInclusions()
    {
        return _entries.values().stream().filter(Entry::isInclusive).map(Entry::getName).toArray(String[]::new);
    }

    /**
     * @return array of excluded classpath patterns (without '-' prefix)
     */
    public String[] getExclusions()
    {
        return _entries.values().stream().filter(e -> !e.isInclusive()).map(Entry::getName).toArray(String[]::new);
    }

    /**
     * Match the class name against the pattern
     *
     * @param name name of the class to match
     * @return true if class matches the pattern
     */
    public boolean match(String name)
    {
        return _patterns.test(name);
    }

    /**
     * Match the class name against the pattern
     *
     * @param clazz A class to try to match
     * @return true if class matches the pattern
     */
    public boolean match(Class<?> clazz)
    {
        try
        {
            return combine(_patterns, clazz.getName(), _locations, () -> TypeUtil.getLocationOfClass(clazz));
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    public boolean match(String name, URL url)
    {
        if (url == null)
            return false;

        // Strip class suffix for name matching
        if (name.endsWith(".class"))
            name = name.substring(0, name.length() - 6);

        // Treat path elements as packages for name matching
        name = StringUtil.replace(name, '/', '.');

        return combine(_patterns, name, _locations, () ->
        {
            try
            {
                return URIUtil.unwrapContainer(url.toURI());
            }
            catch (URISyntaxException ignored)
            {
                return null;
            }
        });
    }

    /**
     * Match a class against inclusions and exclusions by name and location.
     * Name based checks are performed before location checks. For a class to match,
     * it must not be excluded by either name or location, and must either be explicitly
     * included, or for there to be no inclusions. In the case where the location
     * of the class is null, it will match if it is included by name, or
     * if there are no location exclusions.
     * 
     * @param names configured inclusions and exclusions by name
     * @param name the name to check
     * @param locations configured inclusions and exclusions by location
     * @param location the location of the class (can be null)
     * @return true if the class is not excluded but is included, or there are
     * no inclusions. False otherwise.
     */
    static boolean combine(IncludeExcludeSet<Entry, String> names, String name, IncludeExcludeSet<Entry, URI> locations, Supplier<URI> location)
    {
        // check the name set
        Boolean byName = names.isIncludedAndNotExcluded(name);

        // If we excluded by name, then no match
        if (Boolean.FALSE == byName)
            return false;

        // check the location set
        URI uri = location.get();
        Boolean byLocation = uri == null ? null : locations.isIncludedAndNotExcluded(uri);

        // If we excluded by location or couldn't check location exclusion, then no match
        if (Boolean.FALSE == byLocation || (locations.hasExcludes() && uri == null))
            return false;

        // If there are includes, then we must be included to match.
        if (names.hasIncludes() || locations.hasIncludes())
            return byName == Boolean.TRUE || byLocation == Boolean.TRUE;

        // Otherwise there are no includes and it was not excluded, so match
        return true;
    }
}
