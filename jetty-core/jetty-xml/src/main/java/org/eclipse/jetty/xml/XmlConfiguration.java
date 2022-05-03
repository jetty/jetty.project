//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.xml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Queue;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

/**
 * <p>Configures objects from XML.</p>
 * <p>This class reads an XML file conforming to the configure.dtd DTD
 * and uses it to configure and object by calling set, put or other methods on the object.</p>
 * <p>The actual XML file format may be changed (eg to spring XML) by implementing the
 * {@link ConfigurationProcessorFactory} interface to be found by the
 * {@link ServiceLoader} by using the DTD and first tag element in the file.
 * Note that DTD will be null if validation is off.</p>
 * <p>The configuration can be parameterised with properties that are looked up via the
 * Property XML element and set on the configuration via the map returned from
 * {@link #getProperties()}</p>
 * <p>The configuration can create and lookup beans by ID.  If multiple configurations are used, then it
 * is good practise to copy the entries from the {@link #getIdMap()} of a configuration to the next
 * configuration so that they can share an ID space for beans.</p>
 */
public class XmlConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(XmlConfiguration.class);
    private static final Class<?>[] PRIMITIVES =
        {
            Boolean.TYPE, Character.TYPE, Byte.TYPE, Short.TYPE, Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE, Void.TYPE
        };
    private static final Class<?>[] BOXED_PRIMITIVES =
        {
            Boolean.class, Character.class, Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class,
            Void.class
        };
    private static final Class<?>[] SUPPORTED_COLLECTIONS =
        {
            ArrayList.class, HashSet.class, Queue.class, List.class, Set.class, Collection.class
        };
    private static final List<ConfigurationProcessorFactory> PROCESSOR_FACTORIES = TypeUtil.serviceProviderStream(ServiceLoader.load(ConfigurationProcessorFactory.class))
        .flatMap(p -> Stream.of(p.get()))
        .collect(Collectors.toList());
    private static final Pool<ConfigurationParser> __parsers =
        new Pool<>(Pool.StrategyType.THREAD_ID, Math.min(8, Runtime.getRuntime().availableProcessors()));
    public static final Comparator<Executable> EXECUTABLE_COMPARATOR = (e1, e2) ->
    {
        // Favour methods with less parameters
        int count = e1.getParameterCount();
        int compare = Integer.compare(count, e2.getParameterCount());
        if (compare == 0 && count > 0)
        {
            Parameter[] p1 = e1.getParameters();
            Parameter[] p2 = e2.getParameters();

            // Favour methods without varargs
            compare = Boolean.compare(p1[count - 1].isVarArgs(), p2[count - 1].isVarArgs());
            if (compare == 0)
            {
                // Rank by differences in the parameters
                for (int i = 0; i < count; i++)
                {
                    Class<?> t1 = p1[i].getType();
                    Class<?> t2 = p2[i].getType();
                    if (t1 != t2)
                    {
                        // Favour derived type over base type
                        compare = Boolean.compare(t1.isAssignableFrom(t2), t2.isAssignableFrom(t1));
                        if (compare == 0)
                        {
                            // favour primitive type over reference
                            compare = Boolean.compare(!t1.isPrimitive(), !t2.isPrimitive());
                            if (compare == 0)
                                // Use name to avoid non determinant sorting
                                compare = t1.getName().compareTo(t2.getName());
                        }

                        // break on the first different parameter (should always be true)
                        if (compare != 0)
                            break;
                    }
                }
            }
            compare = Math.min(1, Math.max(compare, -1));
        }

        return compare;
    };

    /**
     * Set the standard IDs and properties expected in a jetty XML file:
     * <ul>
     * <li>RefId Server</li>
     * <li>Property jetty.home</li>
     * <li>Property jetty.home.uri</li>
     * <li>Property jetty.base</li>
     * <li>Property jetty.base.uri</li>
     * <li>Property jetty.webapps</li>
     * <li>Property jetty.webapps.uri</li>
     * </ul>
     *
     * @param server The Server object to set
     * @param webapp The webapps Resource
     */
    public void setJettyStandardIdsAndProperties(Object server, Resource webapp)
    {
        try
        {
            if (server != null)
                getIdMap().put("Server", server);

            Path home = Paths.get(System.getProperty("jetty.home", "."));
            getProperties().put("jetty.home", home.toString());
            getProperties().put("jetty.home.uri", normalizeURI(home.toUri().toASCIIString()));

            Path base = Paths.get(System.getProperty("jetty.base", home.toString()));
            getProperties().put("jetty.base", base.toString());
            getProperties().put("jetty.base.uri", normalizeURI(base.toUri().toASCIIString()));

            if (webapp != null)
            {
                Path webappPath = webapp.getFile().toPath().toAbsolutePath();
                getProperties().put("jetty.webapp", webappPath.toString());
                getProperties().put("jetty.webapps", webappPath.getParent().toString());
                getProperties().put("jetty.webapps.uri", normalizeURI(webappPath.getParent().toUri().toString()));
            }
        }
        catch (Exception e)
        {
            LOG.warn("Unable to get webapp file reference", e);
        }
    }

    public static String normalizeURI(String uri)
    {
        if (uri.endsWith("/"))
            return uri.substring(0, uri.length() - 1);
        return uri;
    }

    private final Map<String, Object> _idMap = new HashMap<>();
    private final Map<String, String> _propertyMap = new HashMap<>();
    private final Resource _location;
    private final String _dtd;
    private ConfigurationProcessor _processor;

    ConfigurationParser getParser()
    {
        Pool<ConfigurationParser>.Entry entry = __parsers.acquire(ConfigurationParser::new);
        if (entry == null)
            return new ConfigurationParser(null);
        return entry.getPooled();
    }

    /**
     * Reads and parses the XML configuration file.
     *
     * @param resource the Resource to the XML configuration
     * @throws IOException if the configuration could not be read
     * @throws SAXException if the configuration could not be parsed
     */
    public XmlConfiguration(Resource resource) throws SAXException, IOException
    {
        try (ConfigurationParser parser = getParser(); InputStream inputStream = resource.getInputStream())
        {
            _location = resource;
            setConfig(parser.parse(inputStream));
            _dtd = parser.getDTD();
        }
    }

    @Override
    public String toString()
    {
        if (_location == null)
        {
            return "UNKNOWN-LOCATION";
        }
        return _location.toString();
    }

    private void setConfig(XmlParser.Node config)
    {
        if ("Configure".equals(config.getTag()))
        {
            _processor = new JettyXmlConfiguration();
        }
        else if (PROCESSOR_FACTORIES != null)
        {
            for (ConfigurationProcessorFactory factory : PROCESSOR_FACTORIES)
            {
                _processor = factory.getConfigurationProcessor(_dtd, config.getTag());
                if (_processor != null)
                    break;
            }
            if (_processor == null)
                throw new IllegalStateException("Unknown configuration type: " + config.getTag() + " in " + this);
        }
        else
        {
            throw new IllegalArgumentException("Unknown XML tag:" + config.getTag());
        }
        _processor.init(_location, config, this);
    }

    /**
     * Get the map of ID String to Objects that is used to hold
     * and lookup any objects by ID.
     * <p>
     * A New, Get or Call XML element may have an
     * id attribute which will cause the resulting object to be placed into
     * this map.  A Ref XML element will lookup an object from this map.</p>
     * <p>
     * When chaining configuration files, it is good practise to copy the
     * ID entries from the ID map to the map of the next configuration, so
     * that they may share an ID space
     * </p>
     *
     * @return A modifiable map of ID strings to Objects
     */
    public Map<String, Object> getIdMap()
    {
        return _idMap;
    }

    /**
     * Get the map of properties used by the Property XML element
     * to parametrize configuration.
     *
     * @return A modifiable map of properties.
     */
    public Map<String, String> getProperties()
    {
        return _propertyMap;
    }

    /**
     * Applies the XML configuration script to the given object.
     *
     * @param obj The object to be configured, which must be of a type or super type
     * of the class attribute of the &lt;Configure&gt; element.
     * @return the configured object
     * @throws Exception if the configuration fails
     */
    public Object configure(Object obj) throws Exception
    {
        return _processor.configure(obj);
    }

    /**
     * Applies the XML configuration script.
     * If the root element of the configuration has an ID, an object is looked up by ID and its type checked
     * against the root element's type.
     * Otherwise a new object of the type specified by the root element is created.
     *
     * @return The newly created configured object.
     * @throws Exception if the configuration fails
     */
    public Object configure() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Configure {}", _location);
        return _processor.configure();
    }

    /**
     * Initialize a new Object defaults.
     * <p>This method must be called by any {@link ConfigurationProcessor} when it
     * creates a new instance of an object before configuring it, so that a derived
     * XmlConfiguration class may inject default values.
     *
     * @param object the object to initialize defaults on
     */
    public void initializeDefaults(Object object)
    {
    }

    /**
     * Utility method to resolve a provided path against a directory.
     *
     * @param dir the directory (should be a directory reference, does not have to exist)
     * @param destPath the destination path (can be relative or absolute, syntax depends on OS + FileSystem in use,
     * and does not need to exist)
     * @return String to resolved and normalized path, or null if dir or destPath is empty.
     */
    public static String resolvePath(String dir, String destPath)
    {
        if (StringUtil.isEmpty(dir) || StringUtil.isEmpty(destPath))
            return null;

        return Paths.get(dir).resolve(destPath).normalize().toString();
    }

    private static class JettyXmlConfiguration implements ConfigurationProcessor
    {
        XmlParser.Node _root;
        XmlConfiguration _configuration;

        @Override
        public void init(Resource resource, XmlParser.Node root, XmlConfiguration configuration)
        {
            _root = root;
            _configuration = configuration;
        }

        @Override
        public Object configure(Object obj) throws Exception
        {
            // Check the class of the object
            Class<?> oClass = nodeClass(_root);
            if (oClass != null && !oClass.isInstance(obj))
            {
                String loaders = (oClass.getClassLoader() == obj.getClass().getClassLoader()) ? "" : "Object Class and type Class are from different loaders.";
                throw new IllegalArgumentException("Object of class '" + obj.getClass().getCanonicalName() + "' is not of type '" + oClass.getCanonicalName() + "'. " + loaders + " in " + _configuration);
            }
            String id = _root.getAttribute("id");
            if (id != null)
                _configuration.getIdMap().put(id, obj);

            AttrOrElementNode aoeNode = new AttrOrElementNode(obj, _root, "Id", "Class", "Arg");
            // The Object already existed, if it has <Arg> nodes, warn about them not being used.
            aoeNode.getNodes("Arg")
                .forEach((node) -> LOG.warn("Ignored arg {} in {}", node, this._configuration._location));
            configure(obj, _root, aoeNode.getNext());
            return obj;
        }

        @Override
        public Object configure() throws Exception
        {
            AttrOrElementNode aoeNode = new AttrOrElementNode(_root, "Id", "Class", "Arg");
            String id = aoeNode.getString("Id");
            String clazz = aoeNode.getString("Class");
            Object obj = id == null ? null : _configuration.getIdMap().get(id);
            Class<?> oClass = clazz != null ? Loader.loadClass(clazz) : obj == null ? null : obj.getClass();

            if (LOG.isDebugEnabled())
                LOG.debug("Configure {} {}", oClass, obj);

            if (obj == null && oClass != null)
            {
                try
                {
                    obj = construct(oClass, new Args(null, oClass, aoeNode.getNodes("Arg")));
                    if (id != null)
                        _configuration.getIdMap().put(id, obj);
                }
                catch (NoSuchMethodException x)
                {
                    throw new IllegalStateException(String.format("No matching constructor %s in %s", oClass, _configuration));
                }
            }
            else
            {
                // The Object already existed, if it has <Arg> nodes, warn about them not being used.
                aoeNode.getNodes("Arg")
                    .forEach((node) -> LOG.warn("Ignored arg {} in {}", node, this._configuration._location));
            }

            _configuration.initializeDefaults(obj);
            configure(obj, _root, aoeNode.getNext());
            return obj;
        }

        private static Class<?> nodeClass(XmlParser.Node node) throws ClassNotFoundException
        {
            String className = node.getAttribute("class");
            if (className == null)
                return null;
            return Loader.loadClass(className);
        }

        /**
         * Recursive configuration routine.
         * This method applies the nested Set, Put, Call, etc. elements to the given object.
         *
         * @param obj the object to configure
         * @param cfg the XML nodes of the configuration
         * @param i the index of the XML nodes
         * @throws Exception if the configuration fails
         */
        public void configure(Object obj, XmlParser.Node cfg, int i) throws Exception
        {
            // Process real arguments
            for (; i < cfg.size(); i++)
            {
                Object o = cfg.get(i);
                if (o instanceof String)
                    continue;
                XmlParser.Node node = (XmlParser.Node)o;

                try
                {
                    String tag = node.getTag();
                    switch (tag)
                    {
                        case "Arg":
                        case "Class":
                        case "Id":
                            throw new IllegalStateException("Element '" + tag + "' not skipped");
                        case "Set":
                            set(obj, node);
                            break;
                        case "Put":
                            put(obj, node);
                            break;
                        case "Call":
                            call(obj, node);
                            break;
                        case "Get":
                            get(obj, node);
                            break;
                        case "New":
                            newObj(obj, node);
                            break;
                        case "Array":
                            newArray(obj, node);
                            break;
                        case "Map":
                            newMap(obj, node);
                            break;
                        case "Ref":
                            refObj(node);
                            break;
                        case "Property":
                            propertyObj(node);
                            break;
                        case "SystemProperty":
                            systemPropertyObj(node);
                            break;
                        case "Env":
                            envObj(node);
                            break;
                        default:
                            throw new IllegalStateException("Unknown tag: " + tag + " in " + _configuration);
                    }
                }
                catch (Exception e)
                {
                    LOG.warn("Config error {} at {} in {}", e.toString(), node, _configuration);
                    throw e;
                }
            }
        }

        /**
         * <p>Call a setter method.</p>
         * <p>This method makes a best effort to find a matching set method.
         * The type of the value is used to find a suitable set method by:</p>
         * <ol>
         * <li>Trying for a trivial type match</li>
         * <li>Looking for a native type match</li>
         * <li>Trying all correctly named methods for an auto conversion</li>
         * <li>Attempting to construct a suitable value from original value</li>
         * </ol>
         *
         * @param obj the enclosing object
         * @param node the &lt;Set&gt; XML node
         */
        private void set(Object obj, XmlParser.Node node) throws Exception
        {
            String name = node.getAttribute("name");
            String setter = "set" + name.substring(0, 1).toUpperCase(Locale.ENGLISH) + name.substring(1);
            String id = node.getAttribute("id");
            String property = node.getAttribute("property");
            String propertyValue = null;

            Class<?> oClass = nodeClass(node);
            if (oClass != null)
                obj = null;
            else
                oClass = obj.getClass();

            // Look for a property value
            if (property != null)
            {
                Map<String, String> properties = _configuration.getProperties();
                propertyValue = properties.get(property);
                // If no property value, then do not set
                if (propertyValue == null)
                {
                    // check that there is at least one setter or field that could have matched
                    if (Arrays.stream(oClass.getMethods()).noneMatch(m -> m.getName().equals(setter)) &&
                        Arrays.stream(oClass.getFields()).filter(f -> Modifier.isPublic(f.getModifiers())).noneMatch(f -> f.getName().equals(name)))
                    {
                        NoSuchMethodException e = new NoSuchMethodException(String.format("No method '%s' on %s", setter, oClass.getName()));
                        e.addSuppressed(new NoSuchFieldException(String.format("No field '%s' on %s", name, oClass.getName())));
                        throw e;
                    }
                    // otherwise it is a noop
                    return;
                }
            }

            Object value = value(obj, node);
            if (value == null)
                value = propertyValue;
            Object[] arg = {value};

            Class<?>[] vClass = {Object.class};
            if (value != null)
                vClass[0] = value.getClass();

            if (LOG.isDebugEnabled())
                LOG.debug("XML {}.{} ({})", (obj != null ? obj.toString() : oClass.getName()), setter, value);

            MultiException me = new MultiException();

            String types = null;
            Object setValue = value;
            try
            {
                // Try for trivial match
                try
                {
                    Method set = oClass.getMethod(setter, vClass);
                    invokeMethod(set, obj, arg);
                    return;
                }
                catch (IllegalArgumentException | IllegalAccessException | NoSuchMethodException e)
                {
                    LOG.trace("IGNORED", e);
                    me.add(e);
                }

                // Try for native match
                try
                {
                    Field type = vClass[0].getField("TYPE");
                    vClass[0] = (Class<?>)type.get(null);
                    Method set = oClass.getMethod(setter, vClass);
                    invokeMethod(set, obj, arg);
                    return;
                }
                catch (NoSuchFieldException | IllegalArgumentException | IllegalAccessException | NoSuchMethodException e)
                {
                    LOG.trace("IGNORED", e);
                    me.add(e);
                }

                // Try a field
                try
                {
                    Field field = oClass.getField(name);
                    if (Modifier.isPublic(field.getModifiers()))
                    {
                        try
                        {
                            setField(field, obj, value);
                            return;
                        }
                        catch (IllegalArgumentException e)
                        {
                            // try to convert String value to field value
                            if (value instanceof String)
                            {
                                try
                                {
                                    value = TypeUtil.valueOf(field.getType(), ((String)value).trim());
                                    setField(field, obj, value);
                                    return;
                                }
                                catch (Exception e2)
                                {
                                    e.addSuppressed(e2);
                                    throw e;
                                }
                            }
                        }
                    }
                }
                catch (NoSuchFieldException e)
                {
                    LOG.trace("IGNORED", e);
                    me.add(e);
                }

                // Search for a match by trying all the set methods
                Method[] sets = oClass.getMethods();
                Method set = null;
                for (Method s : sets)
                {
                    if (s.getParameterCount() != 1)
                        continue;
                    Class<?>[] paramTypes = s.getParameterTypes();
                    if (setter.equals(s.getName()))
                    {
                        types = types == null ? paramTypes[0].getName() : (types + "," + paramTypes[0].getName());
                        // lets try it
                        try
                        {
                            set = s;
                            invokeMethod(set, obj, arg);
                            return;
                        }
                        catch (IllegalArgumentException | IllegalAccessException e)
                        {
                            LOG.trace("IGNORED", e);
                            me.add(e);
                        }

                        try
                        {
                            for (Class<?> c : SUPPORTED_COLLECTIONS)
                            {
                                if (paramTypes[0].isAssignableFrom(c))
                                {
                                    setValue = convertArrayToCollection(value, c);
                                    invokeMethod(s, obj, setValue);
                                    return;
                                }
                            }
                        }
                        catch (IllegalAccessException e)
                        {
                            LOG.trace("IGNORED", e);
                            me.add(e);
                        }
                    }
                }

                // Try converting the arg to the last set found.
                if (set != null)
                {
                    try
                    {
                        Class<?> sClass = set.getParameterTypes()[0];
                        if (sClass.isPrimitive())
                        {
                            for (int t = 0; t < PRIMITIVES.length; t++)
                            {
                                if (sClass.equals(PRIMITIVES[t]))
                                {
                                    sClass = BOXED_PRIMITIVES[t];
                                    break;
                                }
                            }
                        }
                        Constructor<?> cons = sClass.getConstructor(vClass);
                        arg[0] = cons.newInstance(arg);
                        _configuration.initializeDefaults(arg[0]);
                        invokeMethod(set, obj, arg);
                        setValue = arg[0];
                        return;
                    }
                    catch (NoSuchMethodException | IllegalAccessException | InstantiationException e)
                    {
                        LOG.trace("IGNORED", e);
                        me.add(e);
                    }
                }

                setValue = null;
            }
            finally
            {
                if (id != null && setValue != null)
                    _configuration.getIdMap().put(id, setValue);
            }

            // No Joy
            String message = oClass + "." + setter + "(" + vClass[0] + ")";
            if (types != null)
                message += ". Found setters for " + types;
            NoSuchMethodException failure = new NoSuchMethodException(message);
            for (int i = 0; i < me.size(); i++)
            {
                failure.addSuppressed(me.getThrowable(i));
            }
            throw failure;
        }

        private Object invokeConstructor(Constructor<?> constructor, Object... args) throws IllegalAccessException, InvocationTargetException, InstantiationException
        {
            Object result = constructor.newInstance(args);
            if (constructor.getAnnotation(Deprecated.class) != null)
                LOG.warn("Deprecated constructor {} in {}", constructor, _configuration);
            return result;
        }

        private Object invokeMethod(Method method, Object obj, Object... args) throws IllegalAccessException, InvocationTargetException
        {
            Object result = method.invoke(obj, args);
            if (method.getAnnotation(Deprecated.class) != null)
                LOG.warn("Deprecated method {} in {}", method, _configuration);
            return result;
        }

        private Object getField(Field field, Object object) throws IllegalAccessException
        {
            Object result = field.get(object);
            if (field.getAnnotation(Deprecated.class) != null)
                LOG.warn("Deprecated field {} in {}", field, _configuration);
            return result;
        }

        private void setField(Field field, Object obj, Object arg) throws IllegalAccessException
        {
            field.set(obj, arg);
            if (field.getAnnotation(Deprecated.class) != null)
                LOG.warn("Deprecated field {} in {}", field, _configuration);
        }

        /**
         * @param array the array to convert
         * @param collectionType the desired collection type
         * @return a collection of the desired type if the array can be converted
         */
        private static Collection<?> convertArrayToCollection(Object array, Class<?> collectionType)
        {
            if (array == null)
                return null;
            Collection<?> collection = null;
            if (array.getClass().isArray())
            {
                if (collectionType.isAssignableFrom(ArrayList.class))
                    collection = convertArrayToArrayList(array);
                else if (collectionType.isAssignableFrom(HashSet.class))
                    collection = new HashSet<>(convertArrayToArrayList(array));
            }
            if (collection == null)
                throw new IllegalArgumentException("Can't convert \"" + array.getClass() + "\" to " + collectionType);
            return collection;
        }

        private static ArrayList<Object> convertArrayToArrayList(Object array)
        {
            int length = Array.getLength(array);
            ArrayList<Object> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++)
            {
                list.add(Array.get(array, i));
            }
            return list;
        }

        /**
         * <p>Calls a put method.</p>
         *
         * @param obj the enclosing map object
         * @param node the &lt;Put&gt; XML node
         */
        private void put(Object obj, XmlParser.Node node) throws Exception
        {
            if (!(obj instanceof Map))
                throw new IllegalArgumentException("Object for put is not a Map: " + obj);
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>)obj;

            String name = node.getAttribute("name");
            Object value = value(obj, node);
            map.put(name, value);
            if (LOG.isDebugEnabled())
                LOG.debug("XML {}.put({},{})", obj, name, value);
        }

        /**
         * <p>Calls a getter method.</p>
         * <p>Any object returned from the call is passed to the configure method to consume the remaining elements.</p>
         * <p>If the "class" attribute is present and its value is "class", then the class instance itself is returned.</p>
         *
         * @param obj the enclosing object
         * @param node the &lt;Get&gt; XML node
         * @return the result of the getter invocation
         */
        private Object get(Object obj, XmlParser.Node node) throws Exception
        {
            AttrOrElementNode aoeNode = new AttrOrElementNode(obj, node, "Id", "Name", "Class");
            String id = aoeNode.getString("Id");
            String name = aoeNode.getString("Name");
            String clazz = aoeNode.getString("Class");

            Class<?> oClass;
            if (clazz != null)
            {
                // static call
                oClass = Loader.loadClass(clazz);
                obj = null;
            }
            else if (obj != null)
            {
                oClass = obj.getClass();
            }
            else
            {
                throw new IllegalArgumentException(node.toString());
            }

            if (LOG.isDebugEnabled())
                LOG.debug("XML get {}", name);

            try
            {
                // Handle getClass() explicitly.
                if ("class".equalsIgnoreCase(name))
                {
                    obj = oClass;
                }
                else
                {
                    // Try calling a getXxx method.
                    Method method = oClass.getMethod("get" + name.substring(0, 1).toUpperCase(Locale.ENGLISH) + name.substring(1));
                    obj = invokeMethod(method, obj);
                }
                if (id != null)
                    _configuration.getIdMap().put(id, obj);
                configure(obj, node, aoeNode.getNext());
            }
            catch (NoSuchMethodException nsme1)
            {
                try
                {
                    // Try calling a isXxx() method.
                    Method method = oClass.getMethod("is" + name.substring(0, 1).toUpperCase(Locale.ENGLISH) + name.substring(1));
                    obj = invokeMethod(method, obj);
                    if (id != null)
                        _configuration.getIdMap().put(id, obj);
                    configure(obj, node, aoeNode.getNext());
                }
                catch (NoSuchMethodException nsme2)
                {
                    // Try the field.
                    Field field = oClass.getField(name);
                    obj = getField(field, obj);
                    if (id != null)
                        _configuration.getIdMap().put(id, obj);
                    configure(obj, node, aoeNode.getNext());
                }
            }
            return obj;
        }

        /**
         * <p>Calls a method.</p>
         * <p>A method is selected by trying all methods with matching names and number of arguments.
         * Any object returned from the call is passed to the configure method to consume the remaining elements.
         * Note that if this is a static call we consider only methods declared directly in the given class,
         * i.e. we ignore any static methods in superclasses.
         *
         * @param obj the enclosing object
         * @param node the &lt;Call&gt; XML node
         * @return the result of the method invocation
         */
        private Object call(Object obj, XmlParser.Node node) throws Exception
        {
            AttrOrElementNode aoeNode = new AttrOrElementNode(obj, node, "Id", "Name", "Class", "Arg");
            String id = aoeNode.getString("Id");
            String name = aoeNode.getString("Name");
            String clazz = aoeNode.getString("Class");

            Class<?> oClass;
            if (clazz != null)
            {
                // static call
                oClass = Loader.loadClass(clazz);
                obj = null;
            }
            else if (obj != null)
            {
                oClass = obj.getClass();
            }
            else
            {
                throw new IllegalArgumentException(node.toString());
            }

            if (LOG.isDebugEnabled())
                LOG.debug("XML call {}", name);

            try
            {
                Object nobj = call(oClass, name, obj, new Args(obj, oClass, aoeNode.getNodes("Arg")));
                if (id != null)
                    _configuration.getIdMap().put(id, nobj);
                configure(nobj, node, aoeNode.getNext());
                return nobj;
            }
            catch (NoSuchMethodException e)
            {
                throw new IllegalStateException("No Method: " + node + " on " + oClass, e);
            }
        }

        private Object call(Class<?> oClass, String methodName, Object obj, Args args) throws InvocationTargetException, NoSuchMethodException
        {
            Objects.requireNonNull(oClass, "Class cannot be null");
            Objects.requireNonNull(methodName, "Method name cannot be null");
            if (StringUtil.isBlank(methodName))
                throw new IllegalArgumentException("Method name cannot be blank");

            // Lets just try all methods for now

            Method[] methods = oClass.getMethods();
            Arrays.sort(methods, EXECUTABLE_COMPARATOR);
            for (Method method : methods)
            {
                if (!method.getName().equals(methodName))
                    continue;
                Object[] arguments = args.applyTo(method);
                if (arguments == null)
                    continue;
                if (Modifier.isStatic(method.getModifiers()) != (obj == null))
                    continue;
                if ((obj == null) && method.getDeclaringClass() != oClass)
                    continue;

                try
                {
                    return invokeMethod(method, obj, arguments);
                }
                catch (IllegalAccessException | IllegalArgumentException e)
                {
                    LOG.trace("IGNORED", e);
                }
            }

            throw new NoSuchMethodException(methodName);
        }

        /**
         * <p>Creates a new value object.</p>
         *
         * @param obj the enclosing object
         * @param node the &lt;New&gt; XML node
         * @return the result of the constructor invocation
         */
        private Object newObj(Object obj, XmlParser.Node node) throws Exception
        {
            AttrOrElementNode aoeNode = new AttrOrElementNode(obj, node, "Id", "Class", "Arg");
            String id = aoeNode.getString("Id");
            String clazz = aoeNode.getString("Class");

            if (LOG.isDebugEnabled())
                LOG.debug("XML new {}", clazz);

            Class<?> oClass = Loader.loadClass(clazz);

            Object nobj;
            try
            {
                nobj = construct(oClass, new Args(obj, oClass, aoeNode.getNodes("Arg")));
            }
            catch (NoSuchMethodException e)
            {
                throw new IllegalStateException("No suitable constructor: " + node + " on " + obj);
            }

            if (id != null)
                _configuration.getIdMap().put(id, nobj);

            _configuration.initializeDefaults(nobj);
            configure(nobj, node, aoeNode.getNext());
            return nobj;
        }

        private Object construct(Class<?> klass, Args args) throws InvocationTargetException, NoSuchMethodException
        {
            Objects.requireNonNull(klass, "Class cannot be null");
            Objects.requireNonNull(args, "Named list cannot be null");

            Constructor<?>[] constructors = klass.getConstructors();
            Arrays.sort(constructors, EXECUTABLE_COMPARATOR);
            for (Constructor<?> constructor : constructors)
            {
                try
                {
                    Object[] arguments = args.applyTo(constructor);
                    if (arguments != null)
                        return invokeConstructor(constructor, arguments);
                }
                catch (InstantiationException | IllegalAccessException | IllegalArgumentException e)
                {
                    LOG.trace("IGNORED", e);
                }
            }
            throw new NoSuchMethodException("<init>");
        }

        /**
         * <p>Returns a reference object mapped to an id.</p>
         *
         * @param node the &lt;Ref&gt; XML node
         * @return the result of the reference invocation
         */
        private Object refObj(XmlParser.Node node) throws Exception
        {
            AttrOrElementNode aoeNode = new AttrOrElementNode(node, "Id");
            String refid = aoeNode.getString("Id");
            if (refid == null)
                refid = node.getAttribute("refid");
            Object obj = _configuration.getIdMap().get(refid);
            if (obj == null && node.size() > 0)
                throw new IllegalStateException("No object for refid=" + refid);
            configure(obj, node, aoeNode.getNext());
            return obj;
        }

        /**
         * <p>Creates a new array object.</p>
         *
         * @param obj the enclosing object
         * @param node the &lt;Array&gt; XML node
         * @return the newly created array
         */
        private Object newArray(Object obj, XmlParser.Node node) throws Exception
        {
            AttrOrElementNode aoeNode = new AttrOrElementNode(obj, node, "Id", "Type", "Item");
            String id = aoeNode.getString("Id");
            String type = aoeNode.getString("Type");
            List<XmlParser.Node> items = aoeNode.getNodes("Item");

            // Get the type
            Class<?> aClass = java.lang.Object.class;
            if (type != null)
            {
                aClass = TypeUtil.fromName(type);
                if (aClass == null)
                {
                    switch (type)
                    {
                        case "String":
                            aClass = String.class;
                            break;
                        case "URL":
                            aClass = URL.class;
                            break;
                        case "InetAddress":
                            aClass = InetAddress.class;
                            break;
                        default:
                            aClass = Loader.loadClass(type);
                            break;
                    }
                }
            }

            Object al = null;
            for (XmlParser.Node item : items)
            {
                String nid = item.getAttribute("id");
                Object v = value(obj, item);
                al = LazyList.add(al, (v == null && aClass.isPrimitive()) ? 0 : v);
                if (nid != null)
                    _configuration.getIdMap().put(nid, v);
            }

            Object array = LazyList.toArray(al, aClass);
            if (id != null)
                _configuration.getIdMap().put(id, array);
            return array;
        }

        /**
         * <p>Creates a new map object.</p>
         *
         * @param obj the enclosing object
         * @param node the &lt;Map&gt; XML node
         * @return the newly created map
         */
        private Object newMap(Object obj, XmlParser.Node node) throws Exception
        {
            AttrOrElementNode aoeNode = new AttrOrElementNode(node, "Id", "Entry", "Class");
            String id = aoeNode.getString("Id");
            List<XmlParser.Node> entries = aoeNode.getNodes("Entry");
            String clazz = aoeNode.getString("Class");
            if (clazz == null)
                clazz = HashMap.class.getName();
            @SuppressWarnings("unchecked")
            Class<? extends Map<Object, Object>> oClass = Loader.loadClass(clazz);

            Map<Object, Object> map = oClass.getConstructor().newInstance();
            if (id != null)
                _configuration.getIdMap().put(id, map);

            for (XmlParser.Node entry : entries)
            {
                if (!entry.getTag().equals("Entry"))
                    throw new IllegalStateException("Not an Entry");

                XmlParser.Node key = null;
                XmlParser.Node value = null;

                for (Object object : entry)
                {
                    if (object instanceof String)
                        continue;
                    XmlParser.Node item = (XmlParser.Node)object;
                    if (!item.getTag().equals("Item"))
                        throw new IllegalStateException("Not an Item");
                    if (key == null)
                        key = item;
                    else
                        value = item;
                }

                if (key == null || value == null)
                    throw new IllegalStateException("Missing Item in Entry");
                String kid = key.getAttribute("id");
                String vid = value.getAttribute("id");

                Object k = value(obj, key);
                Object v = value(obj, value);
                map.put(k, v);

                if (kid != null)
                    _configuration.getIdMap().put(kid, k);
                if (vid != null)
                    _configuration.getIdMap().put(vid, v);
            }

            return map;
        }

        /**
         * <p>Returns the value of a property.</p>
         *
         * @param node the &lt;Property&gt; XML node
         * @return the property value
         */
        private Object propertyObj(XmlParser.Node node) throws Exception
        {
            AttrOrElementNode aoeNode = new AttrOrElementNode(node, "Id", "Name", "Deprecated", "Default");
            String id = aoeNode.getString("Id");
            String name = aoeNode.getString("Name", true);
            List<Object> deprecated = aoeNode.getList("Deprecated");
            String dftValue = aoeNode.getString("Default");

            // Look for a value
            Map<String, String> properties = _configuration.getProperties();
            String value = properties.get(name);

            // Look for a deprecated name value
            String alternate = null;
            if (!deprecated.isEmpty())
            {
                for (Object d : deprecated)
                {
                    String v = properties.get(StringUtil.valueOf(d));
                    if (v != null)
                    {
                        if (value == null)
                            LOG.warn("Property '{}' is deprecated, use '{}' instead", d, name);
                        else
                            LOG.warn("Property '{}' is deprecated, value from '{}' used", d, name);
                    }
                    if (alternate == null)
                        alternate = v;
                }
            }

            // use alternate from deprecated
            if (value == null)
                value = alternate;

            // use default value
            if (value == null)
                value = dftValue;

            // Set value if ID set
            if (id != null)
                _configuration.getIdMap().put(id, value);
            return value;
        }

        /**
         * <p>Returns the value of a system property.</p>
         *
         * @param node the &lt;SystemProperty&gt; XML node
         * @return the property value
         */
        private Object systemPropertyObj(XmlParser.Node node) throws Exception
        {
            AttrOrElementNode aoeNode = new AttrOrElementNode(node, "Id", "Name", "Deprecated", "Default");
            String id = aoeNode.getString("Id");
            String name = aoeNode.getString("Name", true);
            List<Object> deprecated = aoeNode.getList("Deprecated");
            String dftValue = aoeNode.getString("Default");

            // Look for a value
            String value = System.getProperty(name);

            // Look for a deprecated name value
            String alternate = null;
            if (!deprecated.isEmpty())
            {
                for (Object d : deprecated)
                {
                    if (d == null)
                        continue;
                    String v = System.getProperty(d.toString());
                    if (v != null)
                    {
                        if (value == null)
                            LOG.warn("SystemProperty '{}' is deprecated, use '{}' instead", d, name);
                        else
                            LOG.warn("SystemProperty '{}' is deprecated, value from '{}' used", d, name);
                    }
                    if (alternate == null)
                        alternate = v;
                }
            }

            // use alternate from deprecated
            if (value == null)
                value = alternate;

            // use default value
            if (value == null)
                value = dftValue;

            // Set value if ID set
            if (id != null)
                _configuration.getIdMap().put(id, value);

            return value;
        }

        /**
         * <p>Returns the value of an environment property.</p>
         *
         * @param node the &lt;Env&gt; XML node
         * @return the environment property value
         */
        private Object envObj(XmlParser.Node node) throws Exception
        {
            AttrOrElementNode aoeNode = new AttrOrElementNode(node, "Id", "Name", "Deprecated", "Default");
            String id = aoeNode.getString("Id");
            String name = aoeNode.getString("Name", true);
            List<Object> deprecated = aoeNode.getList("Deprecated");
            String dftValue = aoeNode.getString("Default");

            // Look for a value
            String value = System.getenv(name);

            // Look for a deprecated name value
            if (value == null && !deprecated.isEmpty())
            {
                for (Object d : deprecated)
                {
                    value = System.getenv(StringUtil.valueOf(d));
                    if (value != null)
                    {
                        LOG.warn("Property '{}' is deprecated, use '{}' instead", d, name);
                        break;
                    }
                }
            }

            // use default value
            if (value == null)
                value = dftValue;

            // Set value if ID set
            if (id != null)
                _configuration.getIdMap().put(id, value);

            return value;
        }

        /**
         * <p>Returns the scalar value of an element</p>.
         * <p>If no value type is specified, then white space is trimmed out of the value.
         * If it contains multiple value elements they are added as strings before being
         * converted to any specified type.</p>
         *
         * @param obj the enclosing object
         * @param node the XML node
         * @return the value of the XML node
         */
        private Object value(Object obj, XmlParser.Node node) throws Exception
        {
            // Get the type
            String type = node.getAttribute("type");

            // Try a ref lookup
            Object value;
            String ref = node.getAttribute("ref");
            if (ref != null)
            {
                value = _configuration.getIdMap().get(ref);
            }
            else
            {
                // handle trivial case
                if (node.size() == 0)
                {
                    if ("String".equals(type))
                        return "";
                    return null;
                }

                int first = 0;
                int last = node.size() - 1;

                // If it is String, just append all the nodes including whitespace
                if ("String".equals(type))
                {
                    if (first == last)
                        value = itemValue(obj, node.get(first));
                    else
                    {
                        StringBuilder buf = new StringBuilder();
                        for (int i = first; i <= last; i++)
                        {
                            Object item = node.get(i);
                            buf.append(itemValue(obj, item));
                        }
                        value = buf.toString();
                    }
                }
                else // Skip leading/trailing nodes that are all white space
                {
                    // Skip leading nodes that are all white space
                    while (first <= last)
                    {
                        Object item = node.get(first);
                        if (!(item instanceof String) || ((String)item).trim().length() > 0)
                            break;
                        first++;
                    }

                    // Skip trailing nodes that are all white space
                    while (first < last)
                    {
                        Object item = node.get(last);
                        if (!(item instanceof String) || ((String)item).trim().length() > 0)
                            break;
                        last--;
                    }

                    // No non whitespace nodes, so return null
                    if (first > last)
                        return null;

                    if (first == last)
                    {
                        // Single Item value
                        value = itemValue(obj, node.get(first));
                        if (value instanceof String)
                            value = ((String)value).trim();
                    }
                    else
                    {
                        // Get the multiple items as a single string
                        StringBuilder buf = new StringBuilder();
                        for (int i = first; i <= last; i++)
                        {
                            buf.append(itemValue(obj, node.get(i)));
                        }
                        value = buf.toString().trim();
                    }
                }
            }

            // No value
            if (value == null)
            {
                if ("String".equals(type))
                    return "";
                return null;
            }

            // Untyped
            if (type == null)
                return value;

            // Try to type the object
            if (isTypeMatchingClass(type, String.class))
                return value.toString();

            Class<?> pClass = TypeUtil.fromName(type);
            if (pClass != null)
                return TypeUtil.valueOf(pClass, value.toString());

            if (isTypeMatchingClass(type, URL.class))
            {
                if (value instanceof URL)
                    return value;
                try
                {
                    return new URL(value.toString());
                }
                catch (MalformedURLException e)
                {
                    throw new InvocationTargetException(e);
                }
            }

            if (isTypeMatchingClass(type, InetAddress.class))
            {
                if (value instanceof InetAddress)
                    return value;
                try
                {
                    return InetAddress.getByName(value.toString());
                }
                catch (UnknownHostException e)
                {
                    throw new InvocationTargetException(e);
                }
            }

            for (Class<?> collectionClass : SUPPORTED_COLLECTIONS)
            {
                if (isTypeMatchingClass(type, collectionClass))
                    return convertArrayToCollection(value, collectionClass);
            }

            throw new IllegalStateException("Unknown type " + type);
        }

        private static boolean isTypeMatchingClass(String type, Class<?> classToMatch)
        {
            return classToMatch.getSimpleName().equalsIgnoreCase(type) || classToMatch.getName().equals(type);
        }

        /**
         * <p>Returns recursively the value of an element.</p>
         *
         * @param obj the enclosing object
         * @param item the initial element value
         * @return the recursive value of the element
         */
        private Object itemValue(Object obj, Object item) throws Exception
        {
            // String value
            if (item instanceof String)
                return item;

            XmlParser.Node node = (XmlParser.Node)item;
            String tag = node.getTag();
            if ("Call".equals(tag))
                return call(obj, node);
            if ("Get".equals(tag))
                return get(obj, node);
            if ("New".equals(tag))
                return newObj(obj, node);
            if ("Ref".equals(tag))
                return refObj(node);
            if ("Array".equals(tag))
                return newArray(obj, node);
            if ("Map".equals(tag))
                return newMap(obj, node);
            if ("Property".equals(tag))
                return propertyObj(node);
            if ("SystemProperty".equals(tag))
                return systemPropertyObj(node);
            if ("Env".equals(tag))
                return envObj(node);

            LOG.warn("Unknown value tag: {} in {}", node,  _configuration, new Throwable());
            return null;
        }

        private class AttrOrElementNode
        {
            final Object _obj;
            final XmlParser.Node _node;
            final Set<String> _elements = new HashSet<>();
            final int _next;

            AttrOrElementNode(XmlParser.Node node, String... elements)
            {
                this(null, node, elements);
            }

            AttrOrElementNode(Object obj, XmlParser.Node node, String... elements)
            {
                _obj = obj;
                _node = node;
                Collections.addAll(_elements, elements);

                int next = 0;
                for (Object o : _node)
                {
                    if (o instanceof String)
                    {
                        if (((String)o).trim().length() == 0)
                        {
                            next++;
                            continue;
                        }
                        break;
                    }

                    if (!(o instanceof XmlParser.Node))
                        break;

                    XmlParser.Node n = (XmlParser.Node)o;
                    if (!_elements.contains(n.getTag()))
                        break;

                    next++;
                }
                _next = next;
            }

            public int getNext()
            {
                return _next;
            }

            public String getString(String elementName) throws Exception
            {
                return StringUtil.valueOf(get(elementName, false));
            }

            public String getString(String elementName, boolean mandatory) throws Exception
            {
                return StringUtil.valueOf(get(elementName, mandatory));
            }

            public Object get(String elementName, boolean mandatory) throws Exception
            {
                String attrName = StringUtil.asciiToLowerCase(elementName);
                String attr = _node.getAttribute(attrName);
                Object value = attr;

                for (int i = 0; i < _next; i++)
                {
                    Object o = _node.get(i);
                    if (!(o instanceof XmlParser.Node))
                        continue;
                    XmlParser.Node n = (XmlParser.Node)o;
                    if (elementName.equals(n.getTag()))
                    {
                        if (attr != null)
                            throw new IllegalStateException("Cannot have attr '" + attrName + "' and element '" + elementName + "'");

                        value = value(_obj, n);
                        break;
                    }
                }

                if (mandatory && value == null)
                    throw new IllegalStateException("Must have attr '" + attrName + "' or element '" + elementName + "'");

                return value;
            }

            public List<Object> getList(String elementName) throws Exception
            {
                return getList(elementName, false);
            }

            public List<Object> getList(String elementName, boolean manditory) throws Exception
            {
                String attrName = StringUtil.asciiToLowerCase(elementName);
                final List<Object> values = new ArrayList<>();

                String attr = _node.getAttribute(attrName);
                if (attr != null)
                    values.addAll(StringUtil.csvSplit(null, attr, 0, attr.length()));

                for (int i = 0; i < _next; i++)
                {
                    Object o = _node.get(i);
                    if (!(o instanceof XmlParser.Node))
                        continue;
                    XmlParser.Node n = (XmlParser.Node)o;

                    if (elementName.equals(n.getTag()))
                    {
                        if (attr != null)
                            throw new IllegalStateException("Cannot have attr '" + attrName + "' and element '" + elementName + "'");

                        values.add(value(_obj, n));
                    }
                }

                if (manditory && values.isEmpty())
                    throw new IllegalStateException("Must have attr '" + attrName + "' or element '" + elementName + "'");

                return values;
            }

            public List<XmlParser.Node> getNodes(String elementName)
            {
                return XmlConfiguration.getNodes(_node, elementName);
            }
        }

        private class Args
        {
            private final Class<?> _class;
            private final List<Object> _arguments;
            private final List<String> _names;

            private Args(Object obj, Class<?> oClass, List<XmlParser.Node> args) throws Exception
            {
                _class = oClass;
                _arguments = new ArrayList<>();
                _names = new ArrayList<>();
                for (XmlParser.Node child : args)
                {
                    _arguments.add(value(obj, child));
                    _names.add(child.getAttribute("name"));
                }
            }

            private Args(List<Object> arguments, List<String> names)
            {
                _class = null;
                _arguments = arguments;
                _names = names;
            }

            Object[] applyTo(Executable executable)
            {
                Object[] args = matchArgsToParameters(executable);
                if (args == null && _class != null)
                {
                    // Could this be an empty varargs match?
                    int count = executable.getParameterCount();
                    if (count > 0 && executable.getParameters()[count - 1].isVarArgs())
                    {
                        // There is not a no varArgs alternative so let's try a an empty varArgs match
                        args = asEmptyVarArgs(executable.getParameterTypes()[count - 1]).matchArgsToParameters(executable);
                    }
                }
                return args;
            }

            Args asEmptyVarArgs(Class<?> varArgType)
            {
                List<Object> arguments = new ArrayList<>(_arguments);
                arguments.add(Array.newInstance(varArgType.getComponentType(), 0));
                List<String> names = new ArrayList<>(_names);
                names.add(null);
                return new Args(arguments, names);
            }

            Object[] matchArgsToParameters(Executable executable)
            {
                int count = executable.getParameterCount();

                // No match of wrong number of parameters
                if (count != _arguments.size())
                    return null;

                // Handle no parameter case
                if (count == 0)
                    return new Object[0];

                // If no arg names are specified, keep the arg order
                Object[] args;
                if (_names.stream().noneMatch(Objects::nonNull))
                {
                    args = _arguments.toArray(new Object[0]);
                }
                else
                {
                    // If we don't have any parameters with names, then no match
                    Annotation[][] parameterAnnotations = executable.getParameterAnnotations();
                    if (parameterAnnotations == null || parameterAnnotations.length == 0)
                        return null;

                    // Find the position of all named parameters from the executable
                    Map<String, Integer> position = new HashMap<>();
                    int p = 0;
                    for (Annotation[] paramAnnotation : parameterAnnotations)
                    {
                        Integer pos = p++;
                        Arrays.stream(paramAnnotation)
                            .filter(Name.class::isInstance)
                            .map(Name.class::cast)
                            .findFirst().ifPresent(n -> position.put(n.value(), pos));
                    }

                    List<Object> arguments = new ArrayList<>(_arguments);
                    List<String> names = new ArrayList<>(_names);
                    // Map the actual arguments to the names
                    for (p = 0; p < count; p++)
                    {
                        String name = names.get(p);
                        if (name != null)
                        {
                            Integer pos = position.get(name);
                            if (pos == null)
                                return null;
                            if (pos != p)
                            {
                                // adjust position of parameter
                                arguments.add(pos, arguments.remove(p));
                                names.add(pos, names.remove(p));
                                p = Math.min(p, pos);
                            }
                        }
                    }
                    args = arguments.toArray(new Object[0]);
                }

                return args;
            }
        }
    }

    private static List<XmlParser.Node> getNodes(XmlParser.Node node, String elementName)
    {
        String attrName = StringUtil.asciiToLowerCase(elementName);
        final List<XmlParser.Node> values = new ArrayList<>();

        String attr = node.getAttribute(attrName);
        if (attr != null)
        {
            for (String a : StringUtil.csvSplit(null, attr, 0, attr.length()))
            {
                // create a fake node
                XmlParser.Node n = new XmlParser.Node(null, elementName, null);
                n.add(a);
                values.add(n);
            }
        }

        for (Object o : node)
        {
            if (!(o instanceof XmlParser.Node))
                continue;
            XmlParser.Node n = (XmlParser.Node)o;

            if (elementName.equals(n.getTag()))
            {
                if (attr != null)
                    throw new IllegalStateException("Cannot have attr '" + attrName + "' and element '" + elementName + "'");

                values.add(n);
            }
        }

        return values;
    }

    /**
     * Runs the XML configurations as a main application.
     * The command line is used to obtain properties files (must be named '*.properties') and XmlConfiguration files.
     * <p>
     * Any property file on the command line is added to a combined Property instance that is passed to each configuration file via
     * {@link XmlConfiguration#getProperties()}.
     * <p>
     * Each configuration file on the command line is used to create a new XmlConfiguration instance and the
     * {@link XmlConfiguration#configure()} method is used to create the configured object.
     * If the resulting object is an instance of {@link LifeCycle}, then it is started.
     * <p>
     * Any IDs created in a configuration are passed to the next configuration file on the command line using {@link #getIdMap()}.
     * This allows objects with IDs created in one config file to be referenced in subsequent config files on the command line.
     *
     * @param args array of property and xml configuration filenames or {@link Resource}s.
     * @throws Exception if the XML configurations cannot be run
     */
    public static void main(final String... args) throws Exception
    {
        try
        {
            AccessController.doPrivileged((PrivilegedExceptionAction<Void>)() ->
            {
                Properties properties = new Properties();
                properties.putAll(System.getProperties());

                // For all arguments, load properties
                if (LOG.isDebugEnabled())
                    LOG.debug("args={}", Arrays.asList(args));
                for (String arg : args)
                {
                    if (arg.indexOf('=') >= 0)
                    {
                        int i = arg.indexOf('=');
                        properties.put(arg.substring(0, i), arg.substring(i + 1));
                    }
                    else if (arg.toLowerCase(Locale.ENGLISH).endsWith(".properties"))
                    {
                        try (Resource resource = Resource.newResource(arg); InputStream inputStream = resource.getInputStream())
                        {
                            properties.load(inputStream);
                        }
                    }
                }

                // For all arguments, parse XMLs
                XmlConfiguration last = null;
                List<Object> objects = new ArrayList<>(args.length);
                for (String arg : args)
                {
                    if (!arg.toLowerCase(Locale.ENGLISH).endsWith(".properties") && (arg.indexOf('=') < 0))
                    {
                        XmlConfiguration configuration = new XmlConfiguration(Resource.newResource(arg));
                        if (last != null)
                            configuration.getIdMap().putAll(last.getIdMap());
                        if (properties.size() > 0)
                        {
                            Map<String, String> props = new HashMap<>();
                            properties.entrySet().stream()
                                .forEach(objectObjectEntry -> props.put(objectObjectEntry.getKey().toString(),
                                                                        String.valueOf(objectObjectEntry.getValue())));
                            configuration.getProperties().putAll(props);
                        }

                        Object obj = configuration.configure();
                        if (obj != null && !objects.contains(obj))
                            objects.add(obj);
                        last = configuration;
                    }
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("objects={}", objects);

                // For all objects created by XmlConfigurations, start them if they are lifecycles.
                List<LifeCycle> started = new ArrayList<>(objects.size());
                for (Object obj : objects)
                {
                    if (obj instanceof LifeCycle)
                    {
                        LifeCycle lc = (LifeCycle)obj;
                        if (!lc.isRunning())
                        {
                            lc.start();
                            if (lc.isStarted())
                                started.add(lc);
                            else
                            {
                                // Failed to start a component, so stop all started components
                                Collections.reverse(started);
                                for (LifeCycle slc : started)
                                {
                                    slc.stop();
                                }
                                break;
                            }
                        }
                    }
                }
                return null;
            });
        }
        catch (Error | Exception e)
        {
            LOG.warn("Unable to execute XmlConfiguration", e);
            throw e;
        }
    }

    private static class ConfigurationParser extends XmlParser implements AutoCloseable
    {
        private final Pool<ConfigurationParser>.Entry _entry;

        private ConfigurationParser(Pool<ConfigurationParser>.Entry entry)
        {
            _entry = entry;

            Class<?> klass = XmlConfiguration.class;
            URL config60 = klass.getResource("configure_6_0.dtd");
            URL config76 = klass.getResource("configure_7_6.dtd");
            URL config90 = klass.getResource("configure_9_0.dtd");
            URL config93 = klass.getResource("configure_9_3.dtd");
            URL config100 = klass.getResource("configure_10_0.dtd");

            redirectEntity("configure.dtd", config93);
            redirectEntity("configure_1_0.dtd", config60);
            redirectEntity("configure_1_1.dtd", config60);
            redirectEntity("configure_1_2.dtd", config60);
            redirectEntity("configure_1_3.dtd", config60);
            redirectEntity("configure_6_0.dtd", config60);
            redirectEntity("configure_7_6.dtd", config76);
            redirectEntity("configure_9_0.dtd", config90);
            redirectEntity("configure_9_3.dtd", config93);
            redirectEntity("configure_10_0.dtd", config100);

            redirectEntity("http://jetty.mortbay.org/configure.dtd", config93);
            redirectEntity("http://jetty.mortbay.org/configure_9_3.dtd", config93);
            redirectEntity("http://jetty.eclipse.org/configure.dtd", config93);
            redirectEntity("https://jetty.eclipse.org/configure.dtd", config93);
            redirectEntity("http://www.eclipse.org/jetty/configure.dtd", config93);
            redirectEntity("https://www.eclipse.org/jetty/configure.dtd", config93);
            redirectEntity("http://www.eclipse.org/jetty/configure_9_3.dtd", config93);
            redirectEntity("https://www.eclipse.org/jetty/configure_9_3.dtd", config93);
            redirectEntity("https://www.eclipse.org/jetty/configure_10_0.dtd", config100);

            redirectEntity("-//Mort Bay Consulting//DTD Configure//EN", config100);
            redirectEntity("-//Jetty//Configure//EN", config100);
        }

        @Override
        public void close()
        {
            if (_entry != null)
                __parsers.release(_entry);
        }
    }
}
