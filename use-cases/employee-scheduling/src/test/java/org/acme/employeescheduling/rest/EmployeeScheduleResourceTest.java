package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.get;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import ai.timefold.solver.core.api.solver.SolverStatus;

import org.acme.employeescheduling.domain.EmployeeSchedule;
import org.acme.employeescheduling.domain.Shift;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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

    @EnabledIfSystemProperty(named = "enterprise", matches = ".*")
    @Test
    void analyzeCompletedSchedule() {
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

        String analysis = given()
                .expect().contentType(ContentType.JSON)
                .when()
                .get("/schedules/" + jobId + "/analysis")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(analysis).isNotNull();

        String shallowAnalysis = given()
                .queryParam("fetchPolicy", "FETCH_SHALLOW")
                .expect().contentType(ContentType.JSON)
                .when()
                .get("/schedules/" + jobId + "/analysis")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(shallowAnalysis).isNotNull();
    }
}
