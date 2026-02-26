<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>

<c:catch var="tossable">
  <jsp:doBody />
</c:catch>
<c:if test="${tossable != null}">
[jtest:errorhandler] exception : ${tossable}
[jtest:errorhandler] exception.message : ${tossable.message}
</c:if>
<c:if test="${tossable == null}">
[jtest:errorhandler] exception is null
</c:if>
