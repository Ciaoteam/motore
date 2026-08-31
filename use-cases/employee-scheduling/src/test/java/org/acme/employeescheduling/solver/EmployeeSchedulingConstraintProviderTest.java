package org.acme.employeescheduling.solver;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Set;

import jakarta.inject.Inject;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;

import org.acme.employeescheduling.domain.ConcurrentSkillRequirement;
import org.acme.employeescheduling.domain.ConstraintConfiguration;
import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.EmployeeSchedule;
import org.acme.employeescheduling.domain.MustWorkTogether;
import org.acme.employeescheduling.domain.Shift;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class EmployeeSchedulingConstraintProviderTest {
    private static final LocalDate DAY_1 = LocalDate.of(2021, 2, 1);
    private static final LocalDate DAY_3 = LocalDate.of(2021, 2, 3);

    private static final LocalDateTime DAY_START_TIME = DAY_1.atTime(LocalTime.of(9, 0));
    private static final LocalDateTime DAY_END_TIME = DAY_1.atTime(LocalTime.of(17, 0));
    private static final LocalDateTime AFTERNOON_START_TIME = DAY_1.atTime(LocalTime.of(13, 0));
    private static final LocalDateTime AFTERNOON_END_TIME = DAY_1.atTime(LocalTime.of(21, 0));

    @Inject
    ConstraintVerifier<EmployeeSchedulingConstraintProvider, EmployeeSchedule> constraintVerifier;

    @Test
    void requiredSkill() {
        Employee employee = new Employee("Amy", Set.of(), null, null, null);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::requiredSkill)
                .given(employee,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee))
                .penalizes(1);

        employee = new Employee("Beth", Set.of("Skill"), null, null, null);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::requiredSkill)
                .given(employee,
                        new Shift("2", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee))
                .penalizes(0);
    }

    @Test
    void overlappingShifts() {
        Employee employee1 = new Employee("Amy", null, null, null, null);
        Employee employee2 = new Employee("Beth", null, null, null, null);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::noOverlappingShifts)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", DAY_START_TIME, DAY_END_TIME, "Location 2", "Skill", employee1))
                .penalizesBy((int) Duration.ofHours(8).toMinutes());

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::noOverlappingShifts)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", DAY_START_TIME, DAY_END_TIME, "Location 2", "Skill", employee2))
                .penalizes(0);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::noOverlappingShifts)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", AFTERNOON_START_TIME, AFTERNOON_END_TIME, "Location 2", "Skill", employee1))
                .penalizesBy((int) Duration.ofHours(4).toMinutes());
    }

    @Test
    void oneShiftPerDay() {
        Employee employee1 = new Employee("Amy", null, null, null, null);
        Employee employee2 = new Employee("Beth", null, null, null, null);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::oneShiftPerDay)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", DAY_START_TIME, DAY_END_TIME, "Location 2", "Skill", employee1))
                .penalizes(1);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::oneShiftPerDay)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", DAY_START_TIME, DAY_END_TIME, "Location 2", "Skill", employee2))
                .penalizes(0);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::oneShiftPerDay)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", AFTERNOON_START_TIME, AFTERNOON_END_TIME, "Location 2", "Skill", employee1))
                .penalizes(1);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::oneShiftPerDay)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", DAY_START_TIME.plusDays(1), DAY_END_TIME.plusDays(1), "Location 2", "Skill", employee1))
                .penalizes(0);
    }

    @Test
    void atLeast10HoursBetweenConsecutiveShifts() {
        Employee employee1 = new Employee("Amy", null, null, null, null);
        Employee employee2 = new Employee("Beth", null, null, null, null);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast10HoursBetweenTwoShifts)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", AFTERNOON_END_TIME, DAY_START_TIME.plusDays(1), "Location 2", "Skill", employee1))
                .penalizesBy(360);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast10HoursBetweenTwoShifts)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", DAY_END_TIME, DAY_START_TIME.plusDays(1), "Location 2", "Skill", employee1))
                .penalizesBy(600);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast10HoursBetweenTwoShifts)
                .given(employee1, employee2,
                        new Shift("1", DAY_END_TIME, DAY_START_TIME.plusDays(1), "Location", "Skill", employee1),
                        new Shift("2", DAY_START_TIME, DAY_END_TIME, "Location 2", "Skill", employee1))
                .penalizesBy(600);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast10HoursBetweenTwoShifts)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", DAY_END_TIME.plusHours(10), DAY_START_TIME.plusDays(1), "Location 2", "Skill",
                                employee1))
                .penalizes(0);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::atLeast10HoursBetweenTwoShifts)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", AFTERNOON_END_TIME, DAY_START_TIME.plusDays(1), "Location 2", "Skill", employee2))
                .penalizes(0);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::noOverlappingShifts)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", DAY_START_TIME.plusDays(1), DAY_END_TIME.plusDays(1), "Location 2", "Skill", employee1))
                .penalizes(0);
    }

    @Test
    void unavailableEmployee() {
        Employee employee1 = new Employee("Amy", null, Set.of(DAY_1, DAY_3), null, null);
        Employee employee2 = new Employee("Beth", null, Set.of(), null, null);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::unavailableEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1))
                .penalizesBy((int) Duration.ofHours(8).toMinutes());
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::unavailableEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME.minusDays(1), DAY_END_TIME, "Location", "Skill", employee1))
                .penalizesBy((int) Duration.ofHours(17).toMinutes());
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::unavailableEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME.plusDays(1), DAY_END_TIME.plusDays(1), "Location", "Skill", employee1))
                .penalizes(0);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::unavailableEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee2))
                .penalizes(0);
    }

    @Test
    void undesiredDayForEmployee() {
        Employee employee1 = new Employee("Amy", null, null, Set.of(DAY_1, DAY_3), null);
        Employee employee2 = new Employee("Beth", null, null, Set.of(), null);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::undesiredDayForEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1))
                .penalizesBy((int) Duration.ofHours(8).toMinutes());
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::undesiredDayForEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME.minusDays(1), DAY_END_TIME, "Location", "Skill", employee1))
                .penalizesBy((int) Duration.ofHours(17).toMinutes());
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::undesiredDayForEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME.plusDays(1), DAY_END_TIME.plusDays(1), "Location", "Skill", employee1))
                .penalizes(0);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::undesiredDayForEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee2))
                .penalizes(0);
    }

    @Test
    void desiredDayForEmployee() {
        Employee employee1 = new Employee("Amy", null, null, null, Set.of(DAY_1, DAY_3));
        Employee employee2 = new Employee("Beth", null, null, null, Set.of());
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::desiredDayForEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1))
                .rewardsWith((int) Duration.ofHours(8).toMinutes());
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::desiredDayForEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME.minusDays(1), DAY_END_TIME, "Location", "Skill", employee1))
                .rewardsWith((int) Duration.ofHours(17).toMinutes());
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::desiredDayForEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME.plusDays(1), DAY_END_TIME.plusDays(1), "Location", "Skill", employee1))
                .rewards(0);
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::desiredDayForEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee2))
                .rewards(0);
    }

    @Test
    void balanceEmployeeShiftAssignments() {
        Employee employee1 = new Employee("Amy", null, null, null, Collections.emptySet());
        Employee employee2 = new Employee("Beth", null, null, null, Collections.emptySet());
        // No employees have shifts assigned; the schedule is perfectly balanced.
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::balanceEmployeeShiftAssignments)
                .given(employee1, employee2)
                .penalizesBy(0);
        // Only one employee has shifts assigned; the schedule is less balanced.
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::balanceEmployeeShiftAssignments)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME.minusDays(1), DAY_END_TIME, "Location", "Skill", employee1))
                .penalizesByMoreThan(0);
        // Every employee has a shift assigned; the schedule is once again perfectly balanced.
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::balanceEmployeeShiftAssignments)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME.minusDays(1), DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", DAY_START_TIME.minusDays(1), DAY_END_TIME, "Location", "Skill", employee2))
                .penalizesBy(0);

    }

    @Test
    void varyShiftStartTimesForEmployee() {
        Employee employee1 = new Employee("Amy", null, null, null, null);
        Employee employee2 = new Employee("Beth", null, null, null, null);

        // Same employee, same week, same start time on two different days: penalized.
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::varyShiftStartTimesForEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", DAY_START_TIME.plusDays(1), DAY_END_TIME.plusDays(1), "Location", "Skill",
                                employee1))
                .penalizes(1);

        // Same employee, same week, different start times: not penalized.
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::varyShiftStartTimesForEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", AFTERNOON_START_TIME.plusDays(1), AFTERNOON_END_TIME.plusDays(1), "Location", "Skill",
                                employee1))
                .penalizes(0);

        // Same start time, but different employees: not penalized.
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::varyShiftStartTimesForEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", DAY_START_TIME.plusDays(1), DAY_END_TIME.plusDays(1), "Location", "Skill",
                                employee2))
                .penalizes(0);

        // Same employee, same start time, but different (non-overlapping ISO) weeks: not penalized.
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::varyShiftStartTimesForEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", DAY_START_TIME.plusWeeks(1), DAY_END_TIME.plusWeeks(1), "Location", "Skill",
                                employee1))
                .penalizes(0);

        // Three same-employee, same-week, same-start-time shifts: penalizes each of the 3 unique pairs.
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::varyShiftStartTimesForEmployee)
                .given(employee1, employee2,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee1),
                        new Shift("2", DAY_START_TIME.plusDays(1), DAY_END_TIME.plusDays(1), "Location", "Skill",
                                employee1),
                        new Shift("3", DAY_START_TIME.plusDays(2), DAY_END_TIME.plusDays(2), "Location", "Skill",
                                employee1))
                .penalizes(3);
    }

    @Test
    void minShiftsTogetherPerWeekHard_penalizesShortfall() {
        Employee amy = new Employee("Amy", null, null, null, null);
        Employee beth = new Employee("Beth", null, null, null, null);

        // Pair requires at least 3 shared shifts per week (HARD)
        MustWorkTogether mw = new MustWorkTogether(amy, beth);
        mw.setMinShiftsTogetherPerWeek(3);
        mw.setMinShiftsTogetherPerWeekSeverity("HARD");

        // Week of 2021-02-01: Amy and Beth share 2 overlapping shifts → shortfall of 1
        LocalDateTime mon9 = LocalDate.of(2021, 2, 1).atTime(9, 0);
        LocalDateTime mon17 = LocalDate.of(2021, 2, 1).atTime(17, 0);
        LocalDateTime tue9 = LocalDate.of(2021, 2, 2).atTime(9, 0);
        LocalDateTime tue17 = LocalDate.of(2021, 2, 2).atTime(17, 0);

        Shift amyShift1 = new Shift("a1", mon9, mon17, "Loc", "Skill", amy);
        Shift amyShift2 = new Shift("a2", tue9, tue17, "Loc", "Skill", amy);
        Shift bethShift1 = new Shift("b1", mon9, mon17, "Loc", "Skill", beth);
        Shift bethShift2 = new Shift("b2", tue9, tue17, "Loc", "Skill", beth);

        // 2 shared shifts, need 3 → penalize by 1
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::minShiftsTogetherPerWeekHard)
                .given(amy, beth, mw, amyShift1, amyShift2, bethShift1, bethShift2)
                .penalizesBy(1);
    }

    @Test
    void minShiftsTogetherPerWeekHard_noViolationWhenMetOrExceeded() {
        Employee amy = new Employee("Amy", null, null, null, null);
        Employee beth = new Employee("Beth", null, null, null, null);

        MustWorkTogether mw = new MustWorkTogether(amy, beth);
        mw.setMinShiftsTogetherPerWeek(2);
        mw.setMinShiftsTogetherPerWeekSeverity("HARD");

        LocalDateTime mon9 = LocalDate.of(2021, 2, 1).atTime(9, 0);
        LocalDateTime mon17 = LocalDate.of(2021, 2, 1).atTime(17, 0);
        LocalDateTime tue9 = LocalDate.of(2021, 2, 2).atTime(9, 0);
        LocalDateTime tue17 = LocalDate.of(2021, 2, 2).atTime(17, 0);

        Shift amyShift1 = new Shift("a1", mon9, mon17, "Loc", "Skill", amy);
        Shift amyShift2 = new Shift("a2", tue9, tue17, "Loc", "Skill", amy);
        Shift bethShift1 = new Shift("b1", mon9, mon17, "Loc", "Skill", beth);
        Shift bethShift2 = new Shift("b2", tue9, tue17, "Loc", "Skill", beth);

        // 2 shared shifts, need 2 → no violation
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::minShiftsTogetherPerWeekHard)
                .given(amy, beth, mw, amyShift1, amyShift2, bethShift1, bethShift2)
                .penalizes(0);
    }

    @Test
    void minShiftsTogetherPerWeekHard_penalizesNoOverlap() {
        Employee amy = new Employee("Amy", null, null, null, null);
        Employee beth = new Employee("Beth", null, null, null, null);
        MustWorkTogether requirement = new MustWorkTogether(amy, beth);
        requirement.setMinShiftsTogetherPerWeek(2);
        requirement.setMinShiftsTogetherPerWeekSeverity("HARD");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::minShiftsTogetherPerWeekHard)
                .given(amy, beth, requirement,
                        new Shift("a", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", amy),
                        new Shift("b", AFTERNOON_END_TIME, AFTERNOON_END_TIME.plusHours(8), "Location", "Skill", beth))
                .penalizesBy(2);
    }

    @Test
    void minShiftsTogetherPerWeekSoft_penalizesShortfall() {
        Employee amy = new Employee("Amy", null, null, null, null);
        Employee beth = new Employee("Beth", null, null, null, null);

        MustWorkTogether mw = new MustWorkTogether(amy, beth);
        mw.setMinShiftsTogetherPerWeek(3);
        mw.setMinShiftsTogetherPerWeekSeverity("SOFT");

        LocalDateTime mon9 = LocalDate.of(2021, 2, 1).atTime(9, 0);
        LocalDateTime mon17 = LocalDate.of(2021, 2, 1).atTime(17, 0);
        Shift amyShift1 = new Shift("a1", mon9, mon17, "Loc", "Skill", amy);
        Shift bethShift1 = new Shift("b1", mon9, mon17, "Loc", "Skill", beth);

        // 1 shared shift, need 3 → penalize by 2
        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::minShiftsTogetherPerWeekSoft)
                .given(amy, beth, mw, amyShift1, bethShift1)
                .penalizesBy(2);
    }

    @Test
    void mustWorkTogetherMedium_penalizesMissingPartner() {
        Employee amy = new Employee("Amy", Set.of("Skill"), null, null, null);
        Employee beth = new Employee("Beth", Set.of("Skill"), null, null, null);
        MustWorkTogether mustWorkTogether = new MustWorkTogether(amy, beth);
        ConstraintConfiguration configuration = new ConstraintConfiguration();
        configuration.setMustWorkTogetherSeverity(ConstraintConfiguration.Severity.MEDIUM);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::mustWorkTogetherMedium)
                .given(amy, beth, mustWorkTogether, configuration,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", amy))
                .penalizesBy(1);
    }

    @Test
    void minShiftsTogetherPerWeekMedium_penalizesShortfall() {
        Employee amy = new Employee("Amy", null, null, null, null);
        Employee beth = new Employee("Beth", null, null, null, null);

        MustWorkTogether mw = new MustWorkTogether(amy, beth);
        mw.setMinShiftsTogetherPerWeek(3);
        mw.setMinShiftsTogetherPerWeekSeverity("MEDIUM");

        LocalDateTime mon9 = LocalDate.of(2021, 2, 1).atTime(9, 0);
        LocalDateTime mon17 = LocalDate.of(2021, 2, 1).atTime(17, 0);
        Shift amyShift1 = new Shift("a1", mon9, mon17, "Loc", "Skill", amy);
        Shift bethShift1 = new Shift("b1", mon9, mon17, "Loc", "Skill", beth);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::minShiftsTogetherPerWeekMedium)
                .given(amy, beth, mw, amyShift1, bethShift1)
                .penalizesBy(2);
    }

    @Test
    void maxWeeklyHoursMedium_penalizesOverflow() {
        Employee employee = new Employee("Amy", Set.of("Skill"), null, null, null);
        ConstraintConfiguration configuration = new ConstraintConfiguration();
        configuration.setMaxWeeklyMinutes(60);
        configuration.setMaxWeeklySeverity(ConstraintConfiguration.Severity.MEDIUM);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::maxWeeklyHoursMedium)
                .given(employee, configuration,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee))
                .penalizesBy((int) Duration.ofHours(7).toMinutes());
    }

    @Test
    void maxWeeklyHoursMedium_proratesCrossWeekShiftMinutes() {
        Employee employee = new Employee("Amy", Set.of("Skill"), null, null, null);
        ConstraintConfiguration configuration = new ConstraintConfiguration();
        configuration.setMaxWeeklyMinutes(100);
        configuration.setMaxWeeklySeverity(ConstraintConfiguration.Severity.MEDIUM);
        LocalDateTime sunday23 = LocalDate.of(2021, 2, 7).atTime(23, 0);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::maxWeeklyHoursMedium)
                .given(employee, configuration,
                        new Shift("cross-week", sunday23, sunday23.plusHours(3), "Location", "Skill", employee))
                .penalizesBy(20);
    }

    @Test
    void goalShiftsPerWeekPerEmployeeMedium_penalizesDeviation() {
        Employee employee = new Employee("Amy", Set.of("Skill"), null, null, null);
        employee.setTargetShiftsPerWeek(2);
        employee.setTargetShiftsPerWeekSeverity("MEDIUM");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::goalShiftsPerWeekPerEmployeeMedium)
                .given(employee, new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee))
                .penalizesBy(1);
    }

    @Test
    void goalShiftsPerWeekHard_countsCrossWeekShiftInEachWeek() {
        Employee employee = new Employee("Amy", Set.of("Skill"), null, null, null);
        ConstraintConfiguration configuration = new ConstraintConfiguration();
        configuration.setTargetShiftsPerWeek(2);
        configuration.setTargetShiftsPerWeekSeverity(ConstraintConfiguration.Severity.HARD);
        LocalDateTime sunday23 = LocalDate.of(2021, 2, 7).atTime(23, 0);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::goalShiftsPerWeekHard)
                .given(employee, configuration,
                        new Shift("cross-week", sunday23, sunday23.plusHours(2), "Location", "Skill", employee))
                .penalizesBy(2);
    }

    @Test
    void goalShiftsPerWeekHardZero_penalizesEmptyScheduledWeeks() {
        Employee amy = new Employee("Amy", Set.of("Skill"), null, null, null);
        Employee beth = new Employee("Beth", Set.of("Skill"), null, null, null);
        ConstraintConfiguration configuration = new ConstraintConfiguration();
        configuration.setTargetShiftsPerWeek(1);
        configuration.setTargetShiftsPerWeekSeverity(ConstraintConfiguration.Severity.HARD);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::goalShiftsPerWeekHardZero)
                .given(amy, beth, configuration,
                        new Shift("a", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", amy),
                        new Shift("b", DAY_START_TIME.plusWeeks(1), DAY_END_TIME.plusWeeks(1), "Location", "Skill", beth))
                .penalizesBy(2);
    }

    @Test
    void goalShiftsPerWeekHardZero_treatsCrossWeekShiftAsAssignedInFollowingWeek() {
        Employee amy = new Employee("Amy", Set.of("Skill"), null, null, null);
        Employee beth = new Employee("Beth", Set.of("Skill"), null, null, null);
        ConstraintConfiguration configuration = new ConstraintConfiguration();
        configuration.setTargetShiftsPerWeek(1);
        configuration.setTargetShiftsPerWeekSeverity(ConstraintConfiguration.Severity.HARD);
        LocalDateTime sunday23 = LocalDate.of(2021, 2, 7).atTime(23, 0);
        LocalDateTime monday9 = LocalDate.of(2021, 2, 8).atTime(9, 0);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::goalShiftsPerWeekHardZero)
                .given(amy, beth, configuration,
                        new Shift("amy-cross-week", sunday23, sunday23.plusHours(2), "Location", "Skill", amy),
                        new Shift("beth-following-week", monday9, monday9.plusHours(8), "Location", "Skill", beth))
                .penalizesBy(1);
    }

    @Test
    void goalShiftsPerWeekHardZero_penalizesWeeksWithOnlyUnassignedShifts() {
        Employee amy = new Employee("Amy", Set.of("Skill"), null, null, null);
        Employee beth = new Employee("Beth", Set.of("Skill"), null, null, null);
        ConstraintConfiguration configuration = new ConstraintConfiguration();
        configuration.setTargetShiftsPerWeek(1);
        configuration.setTargetShiftsPerWeekSeverity(ConstraintConfiguration.Severity.HARD);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::goalShiftsPerWeekHardZero)
                .given(amy, beth, configuration,
                        new Shift("unassigned", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", null))
                .penalizesBy(2);
    }

    @Test
    void goalMinutesPerWeekHard_proratesCrossWeekShiftMinutes() {
        Employee employee = new Employee("Amy", Set.of("Skill"), null, null, null);
        ConstraintConfiguration configuration = new ConstraintConfiguration();
        configuration.setTargetMinutesPerWeek(60);
        configuration.setTargetMinutesPerWeekSeverity(ConstraintConfiguration.Severity.HARD);
        LocalDateTime sunday23 = LocalDate.of(2021, 2, 7).atTime(23, 0);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::goalMinutesPerWeekHard)
                .given(employee, configuration,
                        new Shift("cross-week", sunday23, sunday23.plusHours(3), "Location", "Skill", employee))
                .penalizesBy(60);
    }

    @Test
    void maxWeeklyHoursMedium_doesNotCombineWeeksFromDifferentYears() {
        Employee employee = new Employee("Amy", Set.of("Skill"), null, null, null);
        ConstraintConfiguration configuration = new ConstraintConfiguration();
        configuration.setMaxWeeklyMinutes(60);
        configuration.setMaxWeeklySeverity(ConstraintConfiguration.Severity.MEDIUM);
        LocalDateTime firstWeek = LocalDate.of(2024, 1, 1).atTime(9, 0);
        LocalDateTime secondWeek = LocalDate.of(2025, 1, 1).atTime(9, 0);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::maxWeeklyHoursMedium)
                .given(employee, configuration,
                        new Shift("first", firstWeek, firstWeek.plusHours(1), "Location", "Skill", employee),
                        new Shift("second", secondWeek, secondWeek.plusHours(1), "Location", "Skill", employee))
                .penalizes(0);
    }

    @Test
    void minConcurrentSkillMedium_penalizesShortfall() {
        Employee employee = new Employee("Amy", Set.of("Skill"), null, null, null);
        ConcurrentSkillRequirement requirement = new ConcurrentSkillRequirement(
                "req-1", "Skill", DAY_START_TIME, DAY_END_TIME, 2, -1, "MEDIUM");

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::minConcurrentSkillMedium)
                .given(employee, requirement,
                        new Shift("1", DAY_START_TIME, DAY_END_TIME, "Location", "Skill", employee))
                .penalizesBy(1);
    }

    @Test
    void minShiftsTogetherPerWeekHard_disabledWhenZero() {
        Employee amy = new Employee("Amy", null, null, null, null);
        Employee beth = new Employee("Beth", null, null, null, null);

        // minShiftsTogetherPerWeek = 0 → constraint disabled
        MustWorkTogether mw = new MustWorkTogether(amy, beth);
        mw.setMinShiftsTogetherPerWeek(0);
        mw.setMinShiftsTogetherPerWeekSeverity("HARD");

        LocalDateTime mon9 = LocalDate.of(2021, 2, 1).atTime(9, 0);
        LocalDateTime mon17 = LocalDate.of(2021, 2, 1).atTime(17, 0);
        Shift amyShift1 = new Shift("a1", mon9, mon17, "Loc", "Skill", amy);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::minShiftsTogetherPerWeekHard)
                .given(amy, beth, mw, amyShift1)
                .penalizes(0);
    }

    @Test
    void minShiftsTogetherPerWeekHard_countsCrossWeekOverlapInEachWeek() {
        Employee amy = new Employee("Amy", null, null, null, null);
        Employee beth = new Employee("Beth", null, null, null, null);
        MustWorkTogether requirement = new MustWorkTogether(amy, beth);
        requirement.setMinShiftsTogetherPerWeek(2);
        requirement.setMinShiftsTogetherPerWeekSeverity("HARD");
        LocalDateTime sunday23 = LocalDate.of(2021, 2, 7).atTime(23, 0);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::minShiftsTogetherPerWeekHard)
                .given(amy, beth, requirement,
                        new Shift("amy-cross-week", sunday23, sunday23.plusHours(2), "Location", "Skill", amy),
                        new Shift("beth-cross-week", sunday23, sunday23.plusHours(2), "Location", "Skill", beth))
                .penalizesBy(2);
    }

    @Test
    void minShiftsTogetherPerWeekHardZero_penalizesEachWeekOfCrossWeekShiftWithoutPartner() {
        Employee amy = new Employee("Amy", null, null, null, null);
        Employee beth = new Employee("Beth", null, null, null, null);
        MustWorkTogether requirement = new MustWorkTogether(amy, beth);
        requirement.setMinShiftsTogetherPerWeek(1);
        requirement.setMinShiftsTogetherPerWeekSeverity("HARD");
        LocalDateTime sunday23 = LocalDate.of(2021, 2, 7).atTime(23, 0);

        constraintVerifier.verifyThat(EmployeeSchedulingConstraintProvider::minShiftsTogetherPerWeekHardZero)
                .given(amy, beth, requirement,
                        new Shift("amy-cross-week", sunday23, sunday23.plusHours(2), "Location", "Skill", amy))
                .penalizesBy(2);
    }
}
