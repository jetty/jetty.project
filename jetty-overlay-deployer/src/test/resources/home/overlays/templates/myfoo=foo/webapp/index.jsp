
<%@page import="java.io.BufferedReader"%>
<%@page import="java.io.InputStreamReader"%>
<%@page import="java.util.Enumeration"%>
<h1><%=application.getServletContextName()%></h1>
<img src=logo.png></img>

<p>
<a href="/red">Red</a>,
<a href="/blue">Blue</a>,
<a href="/green">Green</a>
<p>

<h3>Overlays</h3>
webapp=<%=application.getInitParameter("webapp")%><br/>
template=<%=application.getInitParameter("template")%><br/>
node=<%=application.getInitParameter("node")%><br/>
instance=<%=application.getInitParameter("instance")%><br/>

<h3>Init Parameters</h3>
<%
Enumeration e=application.getInitParameterNames();
while (e.hasMoreElements())
{
    String name=e.nextElement().toString();
    String value=application.getInitParameter(name);
    out.println(name+": "+value+"<br/>");
}
%>
<h3>Attributes</h3>
<%
e=application.getAttributeNames();
while (e.hasMoreElements())
{
    String name=e.nextElement().toString();
    String value=String.valueOf(application.getAttribute(name));
    out.println(name+": "+value+"<br/>");
}
%>
<h3>Resources</h3>
<%
ClassLoader loader = Thread.currentThread().getContextClassLoader();
%>
resourceA.txt=<%=new BufferedReader(new InputStreamReader(loader.getResource("resourceA.txt").openStream())).readLine()%><br/>
resourceB.txt=<%=new BufferedReader(new InputStreamReader(loader.getResource("resourceB.txt").openStream())).readLine()%><br/>
resourceC.txt=<%=new BufferedReader(new InputStreamReader(loader.getResource("resourceC.txt").openStream())).readLine()%><br/>
resourceD.txt=<%=new BufferedReader(new InputStreamReader(loader.getResource("resourceD.txt").openStream())).readLine()%><br/>
resourceE.txt=<%=new BufferedReader(new InputStreamReader(loader.getResource("resourceE.txt").openStream())).readLine()%><br/>
resourceF.txt=<%=new BufferedReader(new InputStreamReader(loader.getResource("resourceF.txt").openStream())).readLine()%><br/>
resourceG.txt=<%=new BufferedReader(new InputStreamReader(loader.getResource("resourceG.txt").openStream())).readLine()%><br/>
resourceH.txt=<%=new BufferedReader(new InputStreamReader(loader.getResource("resourceH.txt").openStream())).readLine()%><br/>
resourceI.txt=<%=new BufferedReader(new InputStreamReader(loader.getResource("resourceI.txt").openStream())).readLine()%><br/>
resourceJ.txt=<%=new BufferedReader(new InputStreamReader(loader.getResource("resourceJ.txt").openStream())).readLine()%><br/>
resourceK.txt=<%=new BufferedReader(new InputStreamReader(loader.getResource("resourceK.txt").openStream())).readLine()%><br/>
