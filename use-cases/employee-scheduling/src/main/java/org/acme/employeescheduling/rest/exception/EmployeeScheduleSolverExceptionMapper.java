package org.acme.employeescheduling.rest.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class EmployeeScheduleSolverExceptionMapper implements ExceptionMapper<EmployeeScheduleSolverException> {

    @Override
    public Response toResponse(EmployeeScheduleSolverException exception) {
        ErrorInfo errorInfo = exception.getJobId() == null
                ? new ErrorInfo(exception.getMessage())
                : new ErrorInfo(exception.getJobId(), exception.getMessage());
        return Response
                .status(exception.getStatus())
                .type(MediaType.APPLICATION_JSON)
                .entity(errorInfo)
                .build();
    }
}
