<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>

<c:catch var="error">
  <jsp:doBody />
</c:catch>

<c:if test="${error != null}">
[jtest:errorhandler] exception : ${error}
[jtest:errorhandler] exception.message : ${error.message}
</c:if>
<c:if test="${error == null}">
[jtest:errorhandler] exception is null
</c:if>
