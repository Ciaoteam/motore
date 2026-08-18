package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.get;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import ai.timefold.solver.core.api.solver.SolverStatus;

import org.acme.employeescheduling.domain.EmployeeSchedule;
import org.acme.employeescheduling.domain.Shift;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class EmployeeScheduleResourceTest {

    @Test
    @Timeout(600_000)
    void solveDemoDataUntilFeasible() {

        EmployeeSchedule testSchedule = given()
                .when().get("/demo-data/SMALL")
                .then()
                .statusCode(200)
                .extract()
                .as(EmployeeSchedule.class);

        String jobId = given()
                .contentType(ContentType.JSON)
                .body(testSchedule)
                .expect().contentType(ContentType.TEXT)
                .when().post("/schedules")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        await()
                .atMost(Duration.ofMinutes(5))
                .pollInterval(Duration.ofMillis(500L))
                .until(() -> SolverStatus.NOT_SOLVING.name().equals(
                        get("/schedules/" + jobId + "/status")
                                .jsonPath().get("solverStatus")));

        EmployeeSchedule solution = get("/schedules/" + jobId).then().extract().as(EmployeeSchedule.class);
        assertEquals(SolverStatus.NOT_SOLVING, solution.getSolverStatus());
        assertNotNull(solution.getEmployees());
        assertNotNull(solution.getShifts());
        assertFalse(solution.getShifts().isEmpty());
        for (Shift shift : solution.getShifts()) {
            assertNotNull(shift.getEmployee());
        }
        assertTrue(solution.getScore().isFeasible());
    }

    @Test
    void analyzeCompletedScheduleWithoutLicenseReturnsActionableError() {
        assumeFalse(isTimefoldLicenseConfigured(), "This test covers the unlicensed score-analysis path.");

        EmployeeSchedule testSchedule = given()
                .when().get("/demo-data/SMALL")
                .then()
                .statusCode(200)
                .extract()
                .as(EmployeeSchedule.class);

        String jobId = given()
                .contentType(ContentType.JSON)
                .body(testSchedule)
                .expect().contentType(ContentType.TEXT)
                .when().post("/schedules")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        await()
                .atMost(Duration.ofMinutes(5))
                .pollInterval(Duration.ofMillis(500L))
                .until(() -> SolverStatus.NOT_SOLVING.name().equals(
                        get("/schedules/" + jobId + "/status")
                                .jsonPath().get("solverStatus")));

        EmployeeSchedule solution = get("/schedules/" + jobId).then().extract().as(EmployeeSchedule.class);
        assertThat(solution.getScore()).isNotNull();

        String analysisError = given()
                .accept(ContentType.JSON)
                .when()
                .get("/schedules/" + jobId + "/analysis")
                .then()
                .statusCode(500)
                .extract()
                .asString();
        assertThat(analysisError)
                .contains("Score analysis requires Timefold Solver Enterprise Edition with a valid license.");

        String shallowAnalysisError = given()
                .queryParam("fetchPolicy", "FETCH_SHALLOW")
                .accept(ContentType.JSON)
                .when()
                .get("/schedules/" + jobId + "/analysis")
                .then()
                .statusCode(500)
                .extract()
                .asString();
        assertThat(shallowAnalysisError)
                .contains("Score analysis requires Timefold Solver Enterprise Edition with a valid license.");
    }

    private static boolean isTimefoldLicenseConfigured() {
        if (hasValue(System.getenv("TIMEFOLD_LICENSE")) || hasValue(System.getenv("TIMEFOLD_LICENSE_PATH"))) {
            return true;
        }
        if (Files.exists(Path.of(System.getProperty("user.home"), "timefold-license.pem"))) {
            return true;
        }
        return Thread.currentThread().getContextClassLoader().getResource("timefold-license.pem") != null;
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }
}
