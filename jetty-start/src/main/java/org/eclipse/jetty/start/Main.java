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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.Policy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
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
 * 
 * SUBJECT [ [!] CONDITION [AND|OR] ]*
 * 
 * </PRE>
 * 
 * where SUBJECT:
 * 
 * <PRE>
 * ends with ".class" is the Main class to run.
 * ends with ".xml" is a configuration file for the command line
 * ends with "/" is a directory from which add all jar and zip files from.
 * ends with "/*" is a directory from which add all unconsidered jar and zip files from.
 * Containing = are used to assign system properties.
 * all other subjects are treated as files to be added to the classpath.
 * </PRE>
 * 
 * Subjects may include system properties with $(propertyname) syntax. The $(version) property is
 * defined as the maven version of the start.jar. File subjects starting with
 * "/" are considered absolute, all others are relative to the home directory.
 * <P>
 * CONDITION is one of:
 * 
 * <PRE>
 * 
 * always
 * never
 * available package.class 
 * java OPERATOR n.n 
 * nargs OPERATOR n
 * OPERATOR := one of "<",">"," <=",">=","==","!="
 * 
 * </PRE>
 * 
 * CONTITIONS can be combined with AND OR or !, with AND being the assume operator for a list of
 * CONDITIONS. Classpath operations are evaluated on the fly, so once a class or jar is added to
 * the classpath, subsequent available conditions will see that class. The system parameter
 * CLASSPATH, if set is given to the start classloader before any paths from the configuration
 * file. Programs started with start.jar may be stopped with the stop.jar, which connects via a
 * local port to stop the server. The default port can be set with the STOP.PORT system property (a
 * port of < 0 disables the stop mechanism). If the STOP.KEY system property is set, then a random
 * key is generated and written to stdout. This key must be passed to the stop.jar.
 * <p>
 * The configuration file may be divided into sections with option names like:
 * <pre>
 * [ssl,default]
 * </pre>
 * Clauses after a section header will only be included if they match one of the tags in the 
 * options property.  By default options are set to "default,*" or the OPTIONS property may
 * be used to pass in a list of tags, eg. :
 * <pre>
 *  java -DOPTIONS=jetty,jsp,ssl -jar start.jar
 * </pre>
 * The tag '*' is always appended to the options, so any section with the * tag is always 
 * applied.
 * 
 */
public class Main
{
    private static final String _version = (Main.class.getPackage()!=null && Main.class.getPackage().getImplementationVersion()!=null)
        ?Main.class.getPackage().getImplementationVersion()
        :"Unknown";
        
    static boolean _debug=System.getProperty("DEBUG",null)!=null;
    private String _classname=null;
    private Classpath _classpath=new Classpath();
    private String _config=System.getProperty("START","org/eclipse/jetty/start/start.config");
    private ArrayList _xml=new ArrayList();
    private boolean _showVersions=false;
    private Set _options = new HashSet();

    public static void main(String[] args)
    {
        try
        {
            if (args.length>0&&args[0].equalsIgnoreCase("--help"))
            {
                usage();
            }
            else if (args.length>0&&args[0].equalsIgnoreCase("--stop"))
            {
                new Main().stop();
            }
            else if (args.length>0&&(args[0].equalsIgnoreCase("--version")||args[0].equalsIgnoreCase("--info")))
            {
                String[] nargs=new String[args.length-1];
                System.arraycopy(args,1,nargs,0,nargs.length);
                Main main=new Main();
                main._showVersions=true;
                main.start(nargs);
            }
            else
            {
                new Main().start(args);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            usage();
        }
    }
    
    private static void usage()
    {
        System.err.println("Usage: java [-DDEBUG] [-DSTART=start.config] [-DOPTIONS=opts] [-Dmain.class=org.MyMain] -jar start.jar [--help|--stop|--version|--info] [config ...]");        
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
            if (_debug)
                System.err.println(e);
        }
        catch (ClassNotFoundException e)
        {            
            if (_debug)
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
            if (_debug)
                System.err.println(e);
        }
        catch (ClassNotFoundException e)
        {
            if (_debug)
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
        
        if (_debug || _showVersions || invoked_class==null)
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
                List opts = new ArrayList(_options);
                Collections.sort(opts);
                System.err.println("OPTIONS: "+opts);
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
            String property;
            if ("version".equalsIgnoreCase(name))
                property=_version;
            else
                property=System.getProperty(s.substring(i1+2,i2),"");
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
        Hashtable done=new Hashtable();
        // Initial classpath
        String classpath=System.getProperty("CLASSPATH");
        if (classpath!=null)
        {
            StringTokenizer tok=new StringTokenizer(classpath,File.pathSeparator);
            while (tok.hasMoreTokens())
                _classpath.addComponent(tok.nextToken());
        }

        List section=null;
        List options=null;
        String o=System.getProperty("OPTIONS");
        if (o==null)
            o="default";
        options=Arrays.asList((o.toString()+",*").split("[ ,]")); 
        ArrayList unsatisfiedOptions = new ArrayList(options);
        
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
            
            if (section!=null && Collections.disjoint(section,options))
                continue;
            if (section!=null)
                unsatisfiedOptions.removeAll(section);
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
                            if (_debug)
                                e.printStackTrace();
                        }
                    }
                    else if (condition.equals("property"))
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
                if (_debug)
                    System.err.println((expression?"T ":"F ")+line);
                if (!expression)
                {
                    done.put(file,file);
                    continue;
                }
                // Handle the subject
                if (subject.indexOf("=")>0)
                {
                    int i=file.indexOf("=");
                    String property=file.substring(0,i);
                    String value=file.substring(i+1);
                    if (_debug)
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
                    if (!done.containsKey(d))
                    {
                        done.put(d,d);
                        boolean added=_classpath.addComponent(d);
                        if (_debug)
                            System.err.println((added?"  CLASSPATH+=":"  !")+d);
                    }
                }
                else if (subject.toLowerCase().endsWith(".xml"))
                {
                    // Config file
                    File f=new File(file);
                    if (f.exists())
                        _xml.add(f.getCanonicalPath());
                    if (_debug)
                        System.err.println("  ARGS+="+f);
                }
                else if (subject.toLowerCase().endsWith(".class"))
                {
                    // Class
                    String cn=expand(subject.substring(0,subject.length()-6));
                    if (cn!=null&&cn.length()>0)
                    {
                        if (_debug)
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
                        if (_debug)
                            System.err.println("  PATH="+cn);
                        _classpath.addClasspath(cn);
                    }                  
                }
                else
                {
                    // single JAR file
                    File f=new File(file);
                    if(f.exists())
                    {
                        String d=f.getCanonicalPath();
                        if (!done.containsKey(d))
                        {
                            done.put(d,d);
                            boolean added=_classpath.addComponent(d);
                            if (!added)
                            {
                                added=_classpath.addClasspath(expand(subject));
                                if (_debug)
                                    System.err.println((added?"  CLASSPATH+=":"  !")+d);
                            }
                            else if (_debug)
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

        if (unsatisfiedOptions!=null && unsatisfiedOptions.size()>0)
        {
            String home = System.getProperty("jetty.home");
            String lib = System.getProperty("jetty.lib");
            File libDir = null;
            if (lib!=null)
            {
                libDir = new File (lib);
            }
            else if (home != null)
            {
                libDir = new File (home, "lib");
            }

            for (int i=0; i< unsatisfiedOptions.size(); i++)
            {   
                if (libDir != null)
                {
                    File dir = new File (libDir, (String)unsatisfiedOptions.get(i));
                    if (dir.exists())
                        addJars(dir,done,true);
                    else
                        System.err.println("Unsatisfied option:"+unsatisfiedOptions.get(i));
                }
                else
                    System.err.println("Unsatisfied option:"+unsatisfiedOptions.get(i));
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void start(String[] args)
    {
        ArrayList al=new ArrayList();
        for (int i=0; i<args.length; i++)
        {
            if (args[i]==null)
                continue;
            else
                al.add(args[i]);
        }
        args=(String[])al.toArray(new String[al.size()]);
        // set up classpath:
        InputStream cpcfg=null;
        try
        {
            Monitor.monitor();

            cpcfg=getClass().getClassLoader().getResourceAsStream(_config);
            if (_debug)
                System.err.println("config="+_config);
            if (cpcfg==null)
                cpcfg=new FileInputStream(_config);
            configure(cpcfg,args.length);
            File file=new File(System.getProperty("jetty.home"));
            String canonical=file.getCanonicalPath();
            System.setProperty("jetty.home",canonical);
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
        if (_debug)
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
            Policy policy=Policy.getPolicy();
            if (policy!=null)
                policy.refresh();
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
            if (_debug)
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
    public void stop()
    {
        int _port=Integer.getInteger("STOP.PORT",-1).intValue();
        String _key=System.getProperty("STOP.KEY",null);

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

    private void addJars(File dir, Hashtable table, boolean recurse) throws IOException
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
                    if (!table.containsKey(jar))
                    {
                        table.put(jar,jar);
                        boolean added=_classpath.addComponent(jar);
                        if (_debug)
                            System.err.println((added?"  CLASSPATH+=":"  !")+jar);
                    }
                }
            }
        }
    }
}
