// ========================================================================
// Copyright (c) 2003-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.start;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.Policy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

/*-------------------------------------------*/
/**
 * Main start class. This class is intended to be the main class listed in the MANIFEST.MF of the
 * start.jar archive. It allows an application to be started with the command "java -jar
 * start.jar". The behaviour of Main is controlled by the "org/eclipse/start/start.config" file
 * obtained as a resource or file. This can be overridden with the START system property. The
 * format of each line in this file is:
 * 
 * <PRE>
 * Each line contains entry in the format:
 * 
 *  SUBJECT [ [!] CONDITION [AND|OR] ]*
 * 
 * where SUBJECT: 
 *   ends with ".class" is the Main class to run.
 *   ends with ".xml" is a configuration file for the command line
 *   ends with "/" is a directory from which to add all jar and zip files. 
 *   ends with "/*" is a directory from which to add all unconsidered jar and zip files.
 *   ends with "/**" is a directory from which to recursively add all unconsidered jar and zip files.
 *   Containing = are used to assign system properties.
 *   Containing ~= are used to assign start properties.
 *   Containing /= are used to assign a canonical path.
 *   all other subjects are treated as files to be added to the classpath.
 * 
 * ${name} is expanded to a start property
 * $(name) is expanded to either a start property or a system property. 
 * The start property ${version} is defined as the version of the start.jar
 * 
 * Files starting with "/" are considered absolute, all others are relative to
 * the home directory.
 * 
 * CONDITION is one of:
 *   always
 *   never
 *   available classname         - true if class on classpath
 *   property name               - true if set as start property
 *   system   name               - true if set as system property
 *   exists file                 - true if file/dir exists
 *   java OPERATOR version       - java version compared to literal
 *   nargs OPERATOR number       - number of command line args compared to literal
 *   OPERATOR := one of "<",">","<=",">=","==","!="
 * 
 * CONTITIONS can be combined with AND OR or !, with AND being the assume
 * operator for a list of CONDITIONS.
 * 
 * Classpath operations are evaluated on the fly, so once a class or jar is
 * added to the classpath, subsequent available conditions will see that class.
 * 
 * The configuration file may be divided into sections with option names like:
 * [ssl,default]
 * 
 * Clauses after a section header will only be included if they match one of the tags in the 
 * options property.  By default options are set to "default,*" or the OPTIONS property may
 * be used to pass in a list of tags, eg. :
 * 
 *    java -jar start.jar OPTIONS=jetty,jsp,ssl
 * 
 * The tag '*' is always appended to the options, so any section with the * tag is always 
 * applied.
 * 
 * </PRE>
 * 
 * 
 */
public class Main
{
    private static final String _version = (Main.class.getPackage()!=null && Main.class.getPackage().getImplementationVersion()!=null)
        ?Main.class.getPackage().getImplementationVersion()
        :"Unknown";
        
    public static boolean DEBUG=false;
    
    private Map<String,String> _properties = new HashMap<String,String>();
    
    
    private String _classname=null;
    private Classpath _classpath=new Classpath();
    
    private boolean _showVersions=false;
    private List<String> _xml=new ArrayList<String>();
    private Set<String> _activeOptions = new HashSet<String>();
    private Set<String> _options = new HashSet<String>();
    private Set<String> _policies = new HashSet<String>();     
    
    /*
    private String _config=System.getProperty("START","org/eclipse/jetty/start/start.config");
    */
    
    public static void main(String[] args)
    {
        try
        {
            Main main=new Main();
            List<String> arguments = new ArrayList<String>(Arrays.asList(args));
            
            for (int i=0; i<arguments.size(); i++)
            {
                String arg=arguments.get(i);
                if (arg.equalsIgnoreCase("--help"))
                {
                    usage();
                }
                
                if (arg.equalsIgnoreCase("--stop"))
                {
                    int port = Integer.parseInt(main.getProperty("STOP.PORT","-1"));
                    String key = main.getProperty("STOP.KEY", null);
                    main.stop(port,key);
                    return;
                }
                
                if (arg.equalsIgnoreCase("--version")||arg.equalsIgnoreCase("-v")||arg.equalsIgnoreCase("-info"))
                {
                    arguments.remove(i--);
                    main._showVersions=true;
                }

                if (arg.indexOf('=')>=0)
                {
                    arguments.remove(i--);
                    String[] assign=arg.split("=",2);
                    
                    if (assign.length==2)
                        main.setProperty(assign[0],assign[1]);
                    else
                        main.setProperty(assign[0],null);
                }
            }
            
            DEBUG=Boolean.parseBoolean(main.getProperty("DEBUG","false"));
            main.start(arguments.toArray(new String[arguments.size()]));        
            
        }
        catch (Exception e)
        {
            e.printStackTrace();
            usage();
        }
    }

    private String getSystemProperty(String name)
    {
        if ("version".equalsIgnoreCase(name))
            return _version;
        if (_properties.containsKey(name))
            return _properties.get(name);
        return System.getProperty(name);
    }
    
    private String getProperty(String name)
    {
        if ("version".equalsIgnoreCase(name))
            return _version;
        
        return _properties.get(name);
    }
    
    private String getProperty(String name, String dftValue)
    {
        if (_properties.containsKey(name))
            return _properties.get(name);
        return dftValue;
    }

    private void setProperty(String name, String value)
    {
        _properties.put(name,value);
    }

    private static void usage()
    {
        System.err.println("Usage: java -jar start.jar [--help|--stop|--version] [OPTIONS=option,...] [name=value ...] [config ...]");        
        System.exit(1);
    }

    static File getDirectory(String name)
    {
        try
        {
            if (name!=null)
            {
                File dir=new File(name).getCanonicalFile();
                if (dir.isDirectory())
                {
                    return dir;
                }
            }
        }
        catch (IOException e)
        {
        }
        return null;
    }

    boolean isAvailable(String classname)
    {
        try
        {
            Class.forName(classname);
            return true;
        }
        catch (NoClassDefFoundError e)
        {
            if (DEBUG)
                System.err.println(e);
        }
        catch (ClassNotFoundException e)
        {            
            if (DEBUG)
                System.err.println(e);
        }
        ClassLoader loader=_classpath.getClassLoader();
        try
        {
            loader.loadClass(classname);
            return true;
        }
        catch (NoClassDefFoundError e)
        {
            if (DEBUG)
                System.err.println(e);
        }
        catch (ClassNotFoundException e)
        {
            if (DEBUG)
                System.err.println(e);
        }
        return false;
    }

    public void invokeMain(ClassLoader classloader, String classname, String[] args) throws IllegalAccessException, InvocationTargetException,
            NoSuchMethodException, ClassNotFoundException
    {
        Class invoked_class=null;
        
        try
        {
            invoked_class=classloader.loadClass(classname);
        }
        catch(ClassNotFoundException e)
        {
            //ignored
        }
        
        if (DEBUG || _showVersions || invoked_class==null)
        {
            if (invoked_class==null)
                System.err.println("ClassNotFound: "+classname);
            else
                System.err.println(classname+" "+invoked_class.getPackage().getImplementationVersion());
            File[] elements = _classpath.getElements();
            for (int i=0;i<elements.length;i++)
                System.err.println("  "+elements[i].getAbsolutePath());
            if (_showVersions || invoked_class==null)
            {
                System.err.println("OPTIONS: "+_options);
	        usage();
            }
        }

        Class[] method_param_types=new Class[1];
        method_param_types[0]=args.getClass();
        Method main=null;
        main=invoked_class.getDeclaredMethod("main",method_param_types);
        Object[] method_params=new Object[1];
        method_params[0]=args;

        main.invoke(null,method_params);
    }

    /* ------------------------------------------------------------ */
    String expand(String s)
    {
        int i1=0;
        int i2=0;
        while (s!=null)
        {
            i1=s.indexOf("$(",i2);
            if (i1<0)
                break;
            i2=s.indexOf(")",i1+2);
            if (i2<0)
                break;
            String name=s.substring(i1+2,i2);
            String property=getSystemProperty(name);
            s=s.substring(0,i1)+property+s.substring(i2+1);
        }
        
        i1=0;
        i2=0;
        while (s!=null)
        {
            i1=s.indexOf("${",i2);
            if (i1<0)
                break;
            i2=s.indexOf("}",i1+2);
            if (i2<0)
                break;
            String name=s.substring(i1+2,i2);
            String property=getProperty(name);
            s=s.substring(0,i1)+property+s.substring(i2+1);
        }
        
        return s;
    }

    /* ------------------------------------------------------------ */
    void configure(InputStream config, int nargs) throws Exception
    {
        BufferedReader cfg=new BufferedReader(new InputStreamReader(config,"ISO-8859-1"));
        Version java_version=new Version(System.getProperty("java.version"));
        Version ver=new Version();
        // JAR's already processed
        Set<String> done=new HashSet<String>();
        
        // Initial classpath
        String classpath=System.getProperty("CLASSPATH");
        if (classpath!=null)
        {
            StringTokenizer tok=new StringTokenizer(classpath,File.pathSeparator);
            while (tok.hasMoreTokens())
                _classpath.addComponent(tok.nextToken());
        }

        List<String> section=null;
        String o=getProperty("OPTIONS","default");
        _activeOptions.addAll(Arrays.asList((o.toString()+",*").split("[ ,]")));
        List<String> unsatisfied_options = new ArrayList<String>( _activeOptions );
        
        // Handle line by line
        String line=null;
        while (true)
        {
            line=cfg.readLine();
            if (line==null)
                break;
            String trim=line.trim();
            if (trim.length()==0||trim.startsWith("#"))
                continue;
            
            // handle options
            if (trim.startsWith("[") && trim.endsWith("]"))
            {
                section = Arrays.asList(trim.substring(1,trim.length()-1).split("[ ,]"));  
                _options.addAll(section);
            }
            
            if (section!=null && Collections.disjoint(section,_activeOptions))
                continue;
            if (section!=null)
                unsatisfied_options.removeAll(section);
            try
            {
                StringTokenizer st=new StringTokenizer(line);
                String subject=st.nextToken();
                boolean expression=true;
                boolean not=false;
                String condition=null;
                // Evaluate all conditions
                while (st.hasMoreTokens())
                {
                    condition=st.nextToken();
                    if (condition.equalsIgnoreCase("!"))
                    {
                        not=true;
                        continue;
                    }
                    if (condition.equalsIgnoreCase("OR"))
                    {
                        if (expression)
                            break;
                        expression=true;
                        continue;
                    }
                    if (condition.equalsIgnoreCase("AND"))
                    {
                        if (!expression)
                            break;
                        continue;
                    }
                    boolean eval=true;
                    if (condition.equals("true")||condition.equals("always"))
                    {
                        eval=true;
                    }
                    else if (condition.equals("false")||condition.equals("never"))
                    {
                        eval=false;
                    }
                    else if (condition.equals("available"))
                    {
                        String class_to_check=st.nextToken();
                        eval=isAvailable(class_to_check);
                    }
                    else if (condition.equals("exists"))
                    {
                        try
                        {
                            eval=false;
                            File file=new File(expand(st.nextToken()));
                            eval=file.exists();
                        }
                        catch (Exception e)
                        {
                            if (DEBUG)
                                e.printStackTrace();
                        }
                    }
                    else if (condition.equals("property"))
                    {
                        String property=getProperty(st.nextToken());
                        eval=property!=null&&property.length()>0;
                    }
                    else if (condition.equals("system"))
                    {
                        String property=System.getProperty(st.nextToken());
                        eval=property!=null&&property.length()>0;
                    }
                    else if (condition.equals("java"))
                    {
                        String operator=st.nextToken();
                        String version=st.nextToken();
                        ver.parse(version);
                        eval=(operator.equals("<")&&java_version.compare(ver)<0)||(operator.equals(">")&&java_version.compare(ver)>0)
                                ||(operator.equals("<=")&&java_version.compare(ver)<=0)||(operator.equals("=<")&&java_version.compare(ver)<=0)
                                ||(operator.equals("=>")&&java_version.compare(ver)>=0)||(operator.equals(">=")&&java_version.compare(ver)>=0)
                                ||(operator.equals("==")&&java_version.compare(ver)==0)||(operator.equals("!=")&&java_version.compare(ver)!=0);
                    }
                    else if (condition.equals("nargs"))
                    {
                        String operator=st.nextToken();
                        int number=Integer.parseInt(st.nextToken());
                        eval=(operator.equals("<")&&nargs<number)||(operator.equals(">")&&nargs>number)||(operator.equals("<=")&&nargs<=number)
                                ||(operator.equals("=<")&&nargs<=number)||(operator.equals("=>")&&nargs>=number)||(operator.equals(">=")&&nargs>=number)
                                ||(operator.equals("==")&&nargs==number)||(operator.equals("!=")&&nargs!=number);
                    }
                    else
                    {
                        System.err.println("ERROR: Unknown condition: "+condition);
                        eval=false;
                    }
                    expression&=not?!eval:eval;
                    not=false;
                }
                String file=expand(subject).replace('/',File.separatorChar);
                if (DEBUG)
                    System.err.println((expression?"T ":"F ")+line);
                if (!expression)
                {
                    done.add(file);
                    continue;
                }
                
                
                // Handle the subject
                if (subject.indexOf("~=")>0)
                {
                    int i=file.indexOf("~=");
                    String property=file.substring(0,i);
                    String value=file.substring(i+2);
                    if (DEBUG)
                        System.err.println("  "+property+"~="+value);
                    setProperty(property,value);
                }
                if (subject.indexOf("/=")>0)
                {
                    int i=file.indexOf("/=");
                    String property=file.substring(0,i);
                    String value=file.substring(i+2);
                    String canonical=new File(value).getCanonicalPath();
                    if (DEBUG)
                        System.err.println("  "+property+"/="+value+"=="+canonical);
                    setProperty(property,canonical);
                }
                else if (subject.indexOf("=")>0)
                {
                    int i=file.indexOf("=");
                    String property=file.substring(0,i);
                    String value=file.substring(i+1);
                    if (DEBUG)
                        System.err.println("  "+property+"="+value);
                    System.setProperty(property,value);
                }
                else if (subject.endsWith("/*"))
                {
                    // directory of JAR files - only add jars and zips
                    // within the directory
                    File dir=new File(file.substring(0,file.length()-1));
                    addJars(dir,done,false);
                }
                else if (subject.endsWith("/**"))
                {
                    //directory hierarchy of jar files - recursively add all
                    //jars and zips in the hierarchy
                    File dir=new File(file.substring(0,file.length()-2));
                    addJars(dir,done,true);
                }
                else if (subject.endsWith("/"))
                {
                    // class directory
                    File cd=new File(file);
                    String d=cd.getCanonicalPath();
                    if (!done.contains(d))
                    {
                        done.add(d);
                        boolean added=_classpath.addComponent(d);
                        if (DEBUG)
                            System.err.println((added?"  CLASSPATH+=":"  !")+d);
                    }
                }
                else if (subject.toLowerCase().endsWith(".xml"))
                {
                    // Config file
                    File f=new File(file);
                    if (f.exists())
                        _xml.add(f.getCanonicalPath());
                    if (DEBUG)
                        System.err.println("  ARGS+="+f);
                }
                else if (subject.toLowerCase().endsWith(".class"))
                {
                    // Class
                    String cn=expand(subject.substring(0,subject.length()-6));
                    if (cn!=null&&cn.length()>0)
                    {
                        if (DEBUG)
                            System.err.println("  CLASS="+cn);
                        _classname=cn;
                    }
                }
                else if (subject.toLowerCase().endsWith(".path"))
                {
                    //classpath (jetty.class.path?) to add to runtime classpath
                    String cn=expand(subject.substring(0,subject.length()-5));
                    if (cn!=null&&cn.length()>0)
                    {
                        if (DEBUG)
                            System.err.println("  PATH="+cn);
                        _classpath.addClasspath(cn);
                    }                  
                }
                else if (subject.toLowerCase().endsWith(".policy"))
                {
                    //policy file to parse
                    String cn=expand(subject.substring(0,subject.length()));
                    if (cn!=null&&cn.length()>0)
                    {
                        if (DEBUG)
                            System.err.println("  POLICY="+cn);
                        _policies.add(cn);
                    }                  
                }
                else
                {
                    // single JAR file
                    File f=new File(file);
                    if(f.exists())
                    {
                        String d=f.getCanonicalPath();
                        if (!done.contains(d))
                        {
                            done.add(d);
                            boolean added=_classpath.addComponent(d);
                            if (!added)
                            {
                                added=_classpath.addClasspath(expand(subject));
                                if (DEBUG)
                                    System.err.println((added?"  CLASSPATH+=":"  !")+d);
                            }
                            else if (DEBUG)
                                System.err.println((added?"  CLASSPATH+=":"  !")+d);
                        }
                    }
                }
            }
            catch (Exception e)
            {
                System.err.println("on line: '"+line+"'");
                e.printStackTrace();
            }
        }

        if (unsatisfied_options!=null && unsatisfied_options.size()>0)
        {
            System.err.println("Unresolved options: "+unsatisfied_options);
        }
    }

    /* ------------------------------------------------------------ */
    public void start(String[] args)
    {
        // set up classpath:
        InputStream cpcfg=null;
        try
        {
            int port = Integer.parseInt(getProperty("STOP.PORT","-1"));
            String key = getProperty("STOP.KEY", null);
            
            Monitor.monitor(port,key);

            String config=getProperty("START","org/eclipse/jetty/start/start.config");
            if (DEBUG)
            {
                System.err.println("config="+config);
                System.err.println("properties="+_properties);
            }
            cpcfg=getClass().getClassLoader().getResourceAsStream(config);
            if (cpcfg==null)
                cpcfg=new FileInputStream(config);
            
            configure(cpcfg,args.length);
            
            String jetty_home=System.getProperty("jetty.home");
            if (jetty_home!=null)
            {
                File file=new File(jetty_home);
                String canonical=file.getCanonicalPath();
                System.setProperty("jetty.home",canonical);
            }
            
        }
        catch (Exception e)
        {
            e.printStackTrace();
            System.exit(1);
        }
        finally
        {
            try
            {
                cpcfg.close();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        // okay, classpath complete.
        System.setProperty("java.class.path",_classpath.toString());
        ClassLoader cl=_classpath.getClassLoader();
        if (DEBUG)
        {
            System.err.println("java.class.path="+System.getProperty("java.class.path"));
            System.err.println("jetty.home="+System.getProperty("jetty.home"));
            System.err.println("java.io.tmpdir="+System.getProperty("java.io.tmpdir"));
            System.err.println("java.class.path="+_classpath);
            System.err.println("classloader="+cl);
            System.err.println("classloader.parent="+cl.getParent());
        }
        // Invoke main(args) using new classloader.
        Thread.currentThread().setContextClassLoader(cl);
        // re-eval the policy now that env is set
        try
        {
        	if ( _activeOptions.contains("policy") )
        	{
        	    Class jettyPolicy = cl.loadClass( "org.eclipse.jetty.policy.JettyPolicy" );
        	    Constructor c = jettyPolicy.getConstructor( new Class[] { Set.class, Map.class } );
        	    Object policyClass = c.newInstance( _policies, _properties );
        	    
        		Policy.setPolicy( (Policy)policyClass );
        		System.setSecurityManager( new SecurityManager() );
        	}
        	else
        	{
        		Policy policy=Policy.getPolicy();
        		if (policy!=null)
        			policy.refresh();
        	}
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        try
        {
            for (int i=0; i<args.length; i++)
            {
                if (args[i]==null)
                    continue;
                _xml.add(args[i]);
            }
            args=(String[])_xml.toArray(args);
            //check for override of start class
            String mainClass=System.getProperty("jetty.server");
            if (mainClass!=null)
                _classname=mainClass;
            mainClass=System.getProperty("main.class");
            if (mainClass!=null)
                _classname=mainClass;
            if (DEBUG)
                System.err.println("main.class="+_classname);
            invokeMain(cl,_classname,args);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    /**
     * Stop a running jetty instance.
     */
    public void stop(int port,String key)
    {
        int _port=port;
        String _key=key;

        try
        {
            if (_port<=0)
                System.err.println("STOP.PORT system property must be specified");
            if (_key==null)
            {
                _key="";
                System.err.println("STOP.KEY system property must be specified");
                System.err.println("Using empty key");
            }

            Socket s=new Socket(InetAddress.getByName("127.0.0.1"),_port);
            OutputStream out=s.getOutputStream();
            out.write((_key+"\r\nstop\r\n").getBytes());
            out.flush();
            s.close();
        }
        catch (ConnectException e)
        {
            System.err.println("ERROR: Not running!");
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void addJars(File dir, Set<String> table, boolean recurse) throws IOException
    {
        File[] entries=dir.listFiles();

        for (int i=0; entries!=null&&i<entries.length; i++)
        {
            File entry=entries[i];

            if (entry.isDirectory()&&recurse)
                addJars(entry,table,recurse);
            else
            {
                String name=entry.getName().toLowerCase();
                if (name.endsWith(".jar")||name.endsWith(".zip"))
                {
                    String jar=entry.getCanonicalPath();
                    if (!table.contains(jar))
                    {
                        table.add(jar);
                        boolean added=_classpath.addComponent(jar);
                        if (DEBUG)
                            System.err.println((added?"  CLASSPATH+=":"  !")+jar);
                    }
                }
            }
        }
    }
}
