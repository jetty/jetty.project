<html>
<%@ page session="true"%>
<body>
<jsp:useBean id='counter' scope='session' class='org.eclipse.jetty.its.jetty_start_mojo_it.Counter' type="org.eclipse.jetty.its.jetty_start_mojo_it.Counter" />

<h1>JSP1.2 Beans: 1</h1>

Counter accessed <jsp:getProperty name="counter" property="count"/> times.<br/>
Counter last accessed by <jsp:getProperty name="counter" property="last"/><br/>
<jsp:setProperty name="counter" property="last" value="<%= request.getRequestURI()%>"/>

</body>
</html>
