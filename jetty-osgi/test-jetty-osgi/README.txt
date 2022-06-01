Unit Tests with OSGi
====================

The unit tests use PaxExam https://ops4j1.jira.com/wiki/spaces/PAXEXAM4/overview
to fork a jvm to start an OSGi container (currently eclipse) and deploy the jetty
jars as osgi bundles, along with the jetty-osgi infrastructure (like jetty-osgi-boot).

To run all the tests:
   mvn test

To run a particular test:
   mvn test -Dtest=[name of test]


At the time of writing, PaxExam only works with junit-4, so you may not be
able to invoke them easily from your IDE.


Logging
-------

By default, very little log info comes out of the tests. If you wish to see more
logging information, you can control this from the command line.

There are 2 sources of logging information: 1) the pax environment  and 2) jetty logs.

To set the logging level for the pax environment use the following system property:

   mvn -Dpax.exam.LEVEL=[log level]

INFO, WARN and TRACE are known to work.

To set the logging level for the jetty logs edit the src/test/resources/jetty-logging.properties
to set the logging level you want and rerun your tests. The usual jetty logging levels apply.



General Test Diagnostics
------------------------

There are generally only ever 2 things wrong with the jetty/osgi interworking:

1. you've added or changed an existing jetty-whatever subproject to use the java ServiceLoader, but you haven't put the right entries into the jar's manifest to allow ServiceLoader to work in osgi

2. you've upgraded the jvm version and the version of Aries SpiFly that we use to provide ServiceLoader functionality in osgi does not support parsing java classes compiled with the new version


* Diagnosing problem 1:

Can be an obvious failure, because the osgi test that exercises your feature fails. Worst case is that your code substitutes the missing service with some default that isn't your new feature and it's never detected because the test still works. That's a problem with the design of the test, c'est la vie.

Other problems with misconfigured manifests are usually to do with missing or incorrect Import-Package/Export-Package statements. This usually isn't a problem because we mostly automatically generate these using the mvn bnd tool during assembly of the jar, but can become a problem if the manifest has been manually cobbled together in the pom.xml. You'll notice these failures because an osgi test will fail with messages something like the following:

Bundle [id:24, url:mvn:org.eclipse.jetty/jetty-util/11.0.0-SNAPSHOT] is not resolved

To diagnose that further, you can rerun the test, and ask it to spit out a list of the status of every jetty jar that is deployed. To do that, supply the following system property at the command line:
mvn -Dbundle.debug=true

You'll see several lines of output like the following. All of the bundles should be state 32 (active) or state 4 (resolved):

ACTIVE 32
RESOLVED 4
INSTALLED 2
0 org.eclipse.osgi System Bundle 3.15.100.v20191114-1701 32
1 org.ops4j.pax.exam file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.ops4j.pax.exam_4.13.1.jar 4.13.1 32
2 org.ops4j.pax.exam.inject file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.ops4j.pax.exam.inject_4.13.1.jar 4.13.1 32
3 org.ops4j.pax.exam.extender.service file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.ops4j.pax.exam.extender.service_4.13.1.jar 4.13.1 32
4 osgi.cmpn file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/osgi.cmpn_4.3.1.201210102024.jar 4.3.1.201210102024 32
5 org.ops4j.pax.logging.pax-logging-api file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.ops4j.pax.logging.pax-logging-api_1.10.1.jar 1.10.1 32
6 org.ops4j.base file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.ops4j.base_1.5.0.jar 1.5.0 32
7 org.ops4j.pax.swissbox.core file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.ops4j.pax.swissbox.core_1.8.2.jar 1.8.2 32
8 org.ops4j.pax.swissbox.extender file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.ops4j.pax.swissbox.extender_1.8.2.jar 1.8.2 32
9 org.ops4j.pax.swissbox.framework file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.ops4j.pax.swissbox.framework_1.8.2.jar 1.8.2 32
10 org.ops4j.pax.swissbox.lifecycle file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.ops4j.pax.swissbox.lifecycle_1.8.2.jar 1.8.2 32
11 org.ops4j.pax.swissbox.tracker file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.ops4j.pax.swissbox.tracker_1.8.2.jar 1.8.2 32
12 org.apache.geronimo.specs.geronimo-atinject_1.0_spec file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.apache.geronimo.specs.geronimo-atinject_1.0_spec_1.0.jar 1.0.0 32
13 org.ops4j.pax.tipi.junit file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.ops4j.pax.tipi.junit_4.12.0.1.jar 4.12.0.1 32
14 org.ops4j.pax.tipi.hamcrest.core file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.ops4j.pax.tipi.hamcrest.core_1.3.0.1.jar 1.3.0.1 32
15 org.ops4j.pax.exam.invoker.junit file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.ops4j.pax.exam.invoker.junit_4.13.1.jar 4.13.1 32
16 org.eclipse.jetty.servlet-api file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.servlet-api_4.0.3.jar 4.0.3 32
17 org.objectweb.asm file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.objectweb.asm_7.2.0.jar 7.2.0 32
18 org.objectweb.asm.commons file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.objectweb.asm.commons_7.2.0.jar 7.2.0 32
19 org.objectweb.asm.tree file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.objectweb.asm.tree_7.2.0.jar 7.2.0 32
20 org.apache.aries.spifly.dynamic.bundle file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.apache.aries.spifly.dynamic.bundle_1.2.3.jar 1.2.3 32
21 jakarta.annotation-api file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/jakarta.annotation-api_1.3.4.jar 1.3.4 32
22 org.apache.geronimo.specs.geronimo-jta_1.1_spec file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.apache.geronimo.specs.geronimo-jta_1.1_spec_1.1.1.jar 1.1.1 32
23 slf4j.api file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/slf4j.api_2.0.0.alpha1.jar 2.0.0.alpha1 4
24 slf4j.log4j12 file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/slf4j.log4j12_2.0.0.alpha1.jar 2.0.0.alpha1 4
25 org.eclipse.jetty.util file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.util_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
26 org.eclipse.jetty.deploy file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.deploy_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
27 org.eclipse.jetty.server file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.server_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
28 org.eclipse.jetty.servlet file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.servlet_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
29 org.eclipse.jetty.http file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.http_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
30 org.eclipse.jetty.xml file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.xml_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
31 org.eclipse.jetty.webapp file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.webapp_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
32 org.eclipse.jetty.io file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.io_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
33 org.eclipse.jetty.security file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.security_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
34 org.eclipse.jetty.servlets file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.servlets_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
35 org.eclipse.jetty.client file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.client_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
36 org.eclipse.jetty.jndi file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.jndi_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
37 org.eclipse.jetty.plus file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.plus_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
38 org.eclipse.jetty.annotations file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.annotations_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
39 org.eclipse.jetty.websocket.core file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.websocket.core_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
40 org.eclipse.jetty.websocket.servlet file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.websocket.servlet_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
41 org.eclipse.jetty.websocket.util file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.websocket.util_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
42 org.eclipse.jetty.websocket.api file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.websocket.api_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
43 org.eclipse.jetty.websocket.server file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.websocket.server_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
44 org.eclipse.jetty.websocket.client file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.websocket.client_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
45 org.eclipse.jetty.websocket.common file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.websocket.common_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
46 org.eclipse.jetty.websocket-api file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.websocket-api_1.1.2.jar 1.1.2 4
47 org.eclipse.jetty.websocket.javax.server file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.websocket.javax.server_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 4
48 org.eclipse.jetty.websocket.javax.client file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.websocket.javax.client_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 4
49 org.eclipse.jetty.websocket.javax.common file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.websocket.javax.common_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 4
50 org.eclipse.jetty.osgi.boot file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.osgi.boot_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
51 org.eclipse.jetty.alpn.java.client file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.alpn.java.client_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
52 org.eclipse.jetty.alpn.client file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.alpn.client_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
53 jakarta.servlet.jsp.jstl file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/jakarta.servlet.jsp.jstl_1.2.0.v201105211821.jar 1.2.0.v201105211821 32
54 org.mortbay.jasper.apache-el file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.mortbay.jasper.apache-el_9.0.29.jar 9.0.29 32
55 org.mortbay.jasper.apache-jsp file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.mortbay.jasper.apache-jsp_9.0.29.jar 9.0.29 32
56 org.eclipse.jetty.apache-jsp file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.apache-jsp_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
57 org.glassfish.web.jakarta.servlet.jsp.jstl file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.glassfish.web.jakarta.servlet.jsp.jstl_1.2.2.jar 1.2.2 32
58 org.eclipse.jdt.core.compiler.batch file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jdt.core.compiler.batch_3.19.0.v20190903-0936.jar 3.19.0.v20190903-0936 32
59 org.eclipse.jetty.osgi.boot.jsp file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.osgi.boot.jsp_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 4
60 org.eclipse.jetty.tests.webapp file:/home/janb/src/jetty-eclipse/jetty-10.0.x/jetty-osgi/test-jetty-osgi/target/1584628869418-0/pax-exam-downloads/org.eclipse.jetty.tests.webapp_10.0.0.SNAPSHOT.jar 10.0.0.SNAPSHOT 32
61 PAXEXAM-PROBE-d9c5a341-5c98-4084-b814-8303880cb447 local 0.0.0 32


If things didn't go so well, and some bundle that should be in state ACTIVE (32) isn't, then you'll see diagnosis like this:

Trying to start the bundle org.glassfish.web.jakarta.servlet.jsp.jstl that was supposed to be active or resolved.
org.glassfish.web.jakarta.servlet.jsp.jstl failed to start
org.osgi.framework.BundleException: Could not resolve module: org.glassfish.web.jakarta.servlet.jsp.jstl [57]
  Unresolved requirement: Import-Package: jakarta.servlet.jsp.jstl.core

The "Unresolved requirement" means either that some bundle that exports that package has not been deployed, or that it is deployed, but it's manifest is screwed up, and didn't expose that package. Check the test code for the mavenBundle() statements to ascertain if it has been deployed, and then check the manifest inside the jar for the Export-Package statements to verify the correct packages are exported.



* Diagnosing Problem 2

If you've upgraded the jetty jar and that's all you've changed, then more than likely it's a SpiFly versioning problem. SpiFly internally uses asm to parse classes to find services for the ServiceLoader, so it can be that the version of asm used by SpiFly doesn't support the higher jvm version. At the time of writing SpiFly is shipping an older version of asm baked into their jars, so very likely it won't support anything above jdk13.

Also, if you don't see any test failures with unresolved jar messages, then it's also a good indicator that the problem is with SpiFly versioning. Unfortunately, when the problem is a jvm/SpiFly versioning mismatch, the osgi paxexam environment doesn't output any good log messages. There is a java.lang.Error that is thrown from inside asm that the environment doesn't pass on in any useful fashion.

To try and catch this error, you can modify the ServletInstanceWrapper at line 163 to catch Throwable instead of Exception:
https://github.com/eclipse/jetty.project/blob/jetty-10.0.x/jetty-osgi/jetty-osgi-boot/src/main/java/org/eclipse/jetty/osgi/boot/internal/serverfactory/ServerInstanceWrapper.java#L163

When you do this, you get output like the following:

java.lang.ClassFormatError: Unexpected error from weaving hook.
at org.eclipse.osgi.internal.weaving.WeavingHookConfigurator.processClass(WeavingHookConfigurator.java:77)
at org.eclipse.osgi.internal.loader.classpath.ClasspathManager.processClass(ClasspathManager.java:736)
at org.eclipse.osgi.internal.loader.classpath.ClasspathManager.defineClass(ClasspathManager.java:707)
at org.eclipse.osgi.internal.loader.classpath.ClasspathManager.findClassImpl(ClasspathManager.java:640)
at org.eclipse.osgi.internal.loader.classpath.ClasspathManager.findLocalClassImpl(ClasspathManager.java:608)
at org.eclipse.osgi.internal.loader.classpath.ClasspathManager.findLocalClassImpl(ClasspathManager.java:588)
at org.eclipse.osgi.internal.loader.classpath.ClasspathManager.findLocalClass(ClasspathManager.java:567)
at org.eclipse.osgi.internal.loader.ModuleClassLoader.findLocalClass(ModuleClassLoader.java:346)
at org.eclipse.osgi.internal.loader.BundleLoader.findLocalClass(BundleLoader.java:398)
at org.eclipse.osgi.internal.loader.sources.SingleSourcePackage.loadClass(SingleSourcePackage.java:41)
at org.eclipse.osgi.internal.loader.BundleLoader.findClassInternal(BundleLoader.java:460)
at org.eclipse.osgi.internal.loader.BundleLoader.findClass(BundleLoader.java:425)
at org.eclipse.osgi.internal.loader.BundleLoader.findClass(BundleLoader.java:417)
at org.eclipse.osgi.internal.loader.ModuleClassLoader.loadClass(ModuleClassLoader.java:171)
at java.base/java.lang.ClassLoader.loadClass(ClassLoader.java:522)
at org.eclipse.jetty.osgi.boot.internal.serverfactory.ServerInstanceWrapper.configure(ServerInstanceWrapper.java:143)
at org.eclipse.jetty.osgi.boot.internal.serverfactory.DefaultJettyAtJettyHomeHelper.startJettyAtJettyHome(DefaultJettyAtJettyHomeHelper.java:211)
at org.eclipse.jetty.osgi.boot.JettyBootstrapActivator.start(JettyBootstrapActivator.java:98)
at org.eclipse.osgi.internal.framework.BundleContextImpl$3.run(BundleContextImpl.java:842)
at org.eclipse.osgi.internal.framework.BundleContextImpl$3.run(BundleContextImpl.java:1)
at java.base/java.security.AccessController.doPrivileged(AccessController.java:554)
at org.eclipse.osgi.internal.framework.BundleContextImpl.startActivator(BundleContextImpl.java:834)
at org.eclipse.osgi.internal.framework.BundleContextImpl.start(BundleContextImpl.java:791)
at org.eclipse.osgi.internal.framework.EquinoxBundle.startWorker0(EquinoxBundle.java:1015)
at org.eclipse.osgi.internal.framework.EquinoxBundle$EquinoxModule.startWorker(EquinoxBundle.java:365)
at org.eclipse.osgi.container.Module.doStart(Module.java:603)
at org.eclipse.osgi.container.Module.start(Module.java:467)
at org.eclipse.osgi.container.ModuleContainer$ContainerStartLevel$2.run(ModuleContainer.java:1844)
at org.eclipse.osgi.internal.framework.EquinoxContainerAdaptor$1$1.execute(EquinoxContainerAdaptor.java:136)
at org.eclipse.osgi.container.ModuleContainer$ContainerStartLevel.incStartLevel(ModuleContainer.java:1837)
at org.eclipse.osgi.container.ModuleContainer$ContainerStartLevel.incStartLevel(ModuleContainer.java:1780)
at org.eclipse.osgi.container.ModuleContainer$ContainerStartLevel.doContainerStartLevel(ModuleContainer.java:1742)
at org.eclipse.osgi.container.ModuleContainer$ContainerStartLevel.dispatchEvent(ModuleContainer.java:1664)
at org.eclipse.osgi.container.ModuleContainer$ContainerStartLevel.dispatchEvent(ModuleContainer.java:1)
at org.eclipse.osgi.framework.eventmgr.EventManager.dispatchEvent(EventManager.java:234)
at org.eclipse.osgi.framework.eventmgr.EventManager$EventThread.run(EventManager.java:345)
Caused by: java.lang.IllegalArgumentException: Unsupported class file major version 58
at org.objectweb.asm.ClassReader.(ClassReader.java:195)
at org.objectweb.asm.ClassReader.(ClassReader.java:176)
at org.objectweb.asm.ClassReader.(ClassReader.java:162)
at org.objectweb.asm.ClassReader.(ClassReader.java:283)
at org.apache.aries.spifly.dynamic.OSGiFriendlyClassWriter.getCommonSuperClass(OSGiFriendlyClassWriter.java:81)
at org.objectweb.asm.SymbolTable.addMergedType(SymbolTable.java:1200)
at org.objectweb.asm.Frame.merge(Frame.java:1299)
at org.objectweb.asm.Frame.merge(Frame.java:1207)
at org.objectweb.asm.MethodWriter.computeAllFrames(MethodWriter.java:1607)
at org.objectweb.asm.MethodWriter.visitMaxs(MethodWriter.java:1543)
at org.objectweb.asm.MethodVisitor.visitMaxs(MethodVisitor.java:762)
at org.objectweb.asm.commons.LocalVariablesSorter.visitMaxs(LocalVariablesSorter.java:147)
at org.objectweb.asm.ClassReader.readCode(ClassReader.java:2431)
at org.objectweb.asm.ClassReader.readMethod(ClassReader.java:1283)
at org.objectweb.asm.ClassReader.accept(ClassReader.java:688)
at org.objectweb.asm.ClassReader.accept(ClassReader.java:400)
at org.apache.aries.spifly.dynamic.ClientWeavingHook.weave(ClientWeavingHook.java:60)
at org.eclipse.osgi.internal.weaving.WovenClassImpl.call(WovenClassImpl.java:175)
at org.eclipse.osgi.internal.serviceregistry.ServiceRegistry.notifyHookPrivileged(ServiceRegistry.java:1343)
at org.eclipse.osgi.internal.serviceregistry.ServiceRegistry.notifyHooksPrivileged(ServiceRegistry.java:1323)
at org.eclipse.osgi.internal.weaving.WovenClassImpl.callHooks(WovenClassImpl.java:270)
at org.eclipse.osgi.internal.weaving.WeavingHookConfigurator.processClass(WeavingHookConfigurator.java:71)
... 35 more




Other Useful Things
-------------------

If you have an ordinary jar with no osgi manifest headers in it, or one with incorrect/incomplete osgi manifest headers in it, you can use the Pax Wrapped Bundle facility to add in/replace the headers so that the test will work.

See https://ops4j1.jira.com/wiki/spaces/PAXEXAM4/pages/54263890/Configuration+Options#ConfigurationOptions-wrappedBundle for more information. The wrappedBundle() itself uses an underlying Wrap Protocol mechanism, which has more details on configuration options, so also look at https://ops4j1.jira.com/wiki/spaces/paxurl/pages/3833898/Wrap+Protocol.  For an example of it in use in the tests, look at TestOSGiUtil.java line 179.
