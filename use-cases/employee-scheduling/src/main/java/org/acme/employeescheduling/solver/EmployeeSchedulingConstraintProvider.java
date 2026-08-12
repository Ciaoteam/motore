package org.acme.employeescheduling.solver;

import org.acme.common.ConstraintIdSanitizer;
import static ai.timefold.solver.core.api.score.stream.Joiners.equal;
import static ai.timefold.solver.core.api.score.stream.Joiners.lessThanOrEqual;
import static ai.timefold.solver.core.api.score.stream.Joiners.overlapping;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.WeekFields;
import java.util.function.Function;

import ai.timefold.solver.core.api.score.HardSoftBigDecimalScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.common.LoadBalance;

import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Shift;
import org.acme.employeescheduling.domain.MustWorkTogether;
import org.acme.employeescheduling.domain.ConstraintConfiguration;

public class EmployeeSchedulingConstraintProvider implements ConstraintProvider {

    private static final int MAX_MINUTES_PER_WEEK = 40 * 60;
    private static final int MAX_MINUTES_PER_MONTH = 160 * 60;

    private static int getMinuteOverlap(Shift shift1, Shift shift2) {
        LocalDateTime shift1Start = shift1.getStart();
        LocalDateTime shift1End = shift1.getEnd();
        LocalDateTime shift2Start = shift2.getStart();
        LocalDateTime shift2End = shift2.getEnd();
        return (int) Duration.between((shift1Start.isAfter(shift2Start)) ? shift1Start : shift2Start,
                (shift1End.isBefore(shift2End)) ? shift1End : shift2End).toMinutes();
    }

    @Override
    public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                // Hard constraints
                requiredSkill(constraintFactory),
                noOverlappingShifts(constraintFactory),
                atLeast10HoursBetweenTwoShifts(constraintFactory),
                oneShiftPerDay(constraintFactory),
                unavailableEmployee(constraintFactory),
                mustWorkTogetherHard(constraintFactory),
                mustWorkTogetherSoft(constraintFactory),
                minShiftsTogetherPerWeekHard(constraintFactory),
                minShiftsTogetherPerWeekSoft(constraintFactory),
                maxWeeklyHoursHard(constraintFactory),
                maxWeeklyHoursSoft(constraintFactory),
                maxMonthlyHoursHard(constraintFactory),
                maxMonthlyHoursSoft(constraintFactory),
                // Goal constraints (HARD variants will only apply if configured as HARD)
                goalShiftsPerWeekHard(constraintFactory),
                goalShiftsPerWeekHardZero(constraintFactory),
                goalMinutesPerWeekHard(constraintFactory),
                goalMinutesPerWeekHardZero(constraintFactory),

                // Soft constraints
                undesiredDayForEmployee(constraintFactory),
                desiredDayForEmployee(constraintFactory),
                balanceEmployeeShiftAssignments(constraintFactory),
                // Goal constraints (SOFT variants)
                goalShiftsPerWeekSoft(constraintFactory),
                goalShiftsPerWeekSoftZero(constraintFactory),
                goalMinutesPerWeekSoft(constraintFactory),
                goalMinutesPerWeekSoftZero(constraintFactory),
                // Min weekly hours per employee
                minWeeklyHoursHard(constraintFactory),
                minWeeklyHoursHardZero(constraintFactory),
                minWeeklyHoursSoft(constraintFactory),
                minWeeklyHoursSoftZero(constraintFactory),
                // Per-employee target shifts per week
                goalShiftsPerWeekPerEmployeeHard(constraintFactory),
                goalShiftsPerWeekPerEmployeeHardZero(constraintFactory),
                goalShiftsPerWeekPerEmployeeSoft(constraintFactory),
                goalShiftsPerWeekPerEmployeeSoftZero(constraintFactory)
        };
    }

    Constraint requiredSkill(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> !shift.getEmployee().getSkills().contains(shift.getRequiredSkill()))
                .penalize(HardSoftBigDecimalScore.ONE_HARD)
                .asConstraint(ConstraintIdSanitizer.sanitize("Missing required skill"));
    }

    Constraint noOverlappingShifts(ConstraintFactory constraintFactory) {
        return constraintFactory.forEachUniquePair(Shift.class, equal(Shift::getEmployee),
                overlapping(Shift::getStart, Shift::getEnd))
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        EmployeeSchedulingConstraintProvider::getMinuteOverlap)
                .asConstraint(ConstraintIdSanitizer.sanitize("Overlapping shift"));
    }

    Constraint atLeast10HoursBetweenTwoShifts(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Shift.class, equal(Shift::getEmployee), lessThanOrEqual(Shift::getEnd, Shift::getStart))
                .filter((firstShift,
                        secondShift) -> Duration.between(firstShift.getEnd(), secondShift.getStart()).toHours() < 10)
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        (firstShift, secondShift) -> {
                            int breakLength = (int) Duration.between(firstShift.getEnd(), secondShift.getStart()).toMinutes();
                            return (10 * 60) - breakLength;
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("At least 10 hours between 2 shifts"));
    }

    Constraint oneShiftPerDay(ConstraintFactory constraintFactory) {
        return constraintFactory.forEachUniquePair(Shift.class, equal(Shift::getEmployee),
                equal(shift -> shift.getStart().toLocalDate()))
                .penalize(HardSoftBigDecimalScore.ONE_HARD)
                .asConstraint(ConstraintIdSanitizer.sanitize("Max one shift per day"));
    }

    Constraint unavailableEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Employee.class, equal(Shift::getEmployee, Function.identity()))
                .flattenLast(Employee::getUnavailableDates)
                .filter(Shift::isOverlappingWithDate)
                .penalize(HardSoftBigDecimalScore.ONE_HARD, Shift::getOverlappingDurationInMinutes)
                .asConstraint(ConstraintIdSanitizer.sanitize("Unavailable employee"));
    }

    Constraint undesiredDayForEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Employee.class, equal(Shift::getEmployee, Function.identity()))
                .flattenLast(Employee::getUndesiredDates)
                .filter(Shift::isOverlappingWithDate)
                .penalize(HardSoftBigDecimalScore.ONE_SOFT, Shift::getOverlappingDurationInMinutes)
                .asConstraint(ConstraintIdSanitizer.sanitize("Undesired day for employee"));
    }

    Constraint desiredDayForEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Employee.class, equal(Shift::getEmployee, Function.identity()))
                .flattenLast(Employee::getDesiredDates)
                .filter(Shift::isOverlappingWithDate)
                .reward(HardSoftBigDecimalScore.ONE_SOFT, Shift::getOverlappingDurationInMinutes)
                .asConstraint(ConstraintIdSanitizer.sanitize("Desired day for employee"));
    }

    Constraint balanceEmployeeShiftAssignments(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(Shift::getEmployee, ConstraintCollectors.count())
                .complement(Employee.class, e -> 0L) // Include all employees which are not assigned to any shift.
                .groupBy(ConstraintCollectors.loadBalance((employee, shiftCount) -> employee,
                        (employee, shiftCount) -> shiftCount))
                .penalizeBigDecimal(HardSoftBigDecimalScore.ONE_SOFT, LoadBalance::unfairness)
                .asConstraint(ConstraintIdSanitizer.sanitize("Balance employee shift assignments"));
    }

    // Must work together - partner missing (A assigned - B missing) (HARD)
    Constraint mustWorkTogetherHard(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(MustWorkTogether.class,
                      equal(Shift::getEmployee, MustWorkTogether::getEmployeeA))
                .join(ConstraintConfiguration.class)
                .filter((shiftA, mw, cfg) -> cfg.getMustWorkTogetherSeverity() == ConstraintConfiguration.Severity.HARD)
                // Look for any Shift for employeeB that overlaps in time with shiftA.
                .ifNotExists(Shift.class,
                        // employeeB must match Shift.employee
                        equal((shiftA, mw, cfg) -> mw.getEmployeeB(), Shift::getEmployee),
                        // and the times must overlap
                        overlapping((shiftA, mw, cfg) -> shiftA.getStart(), (shiftA, mw, cfg) -> shiftA.getEnd(),
                                    Shift::getStart, Shift::getEnd))
                .penalize(HardSoftBigDecimalScore.ONE_HARD)
                .asConstraint(ConstraintIdSanitizer.sanitize("Must work together - partner missing (A assigned - B missing) (HARD)"));
    }

    // Must work together - partner missing (A assigned - B missing) (SOFT)
    Constraint mustWorkTogetherSoft(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(MustWorkTogether.class,
                      equal(Shift::getEmployee, MustWorkTogether::getEmployeeA))
                .join(ConstraintConfiguration.class)
                .filter((shiftA, mw, cfg) -> cfg.getMustWorkTogetherSeverity() == ConstraintConfiguration.Severity.SOFT)
                .ifNotExists(Shift.class,
                        equal((shiftA, mw, cfg) -> mw.getEmployeeB(), Shift::getEmployee),
                        overlapping((shiftA, mw, cfg) -> shiftA.getStart(), (shiftA, mw, cfg) -> shiftA.getEnd(),
                                    Shift::getStart, Shift::getEnd))
                .penalize(HardSoftBigDecimalScore.ONE_SOFT)
                .asConstraint(ConstraintIdSanitizer.sanitize("Must work together - partner missing (A assigned - B missing) (SOFT)"));
    }

    // Min overlapping shifts together per week - shortfall (HARD)
    Constraint minShiftsTogetherPerWeekHard(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(MustWorkTogether.class,
                        equal(Shift::getEmployee, MustWorkTogether::getEmployeeA))
                .filter((shiftA, mw) -> mw.getMinShiftsTogetherPerWeek() > 0
                        && "HARD".equalsIgnoreCase(mw.getMinShiftsTogetherPerWeekSeverity()))
                .join(Shift.class,
                        equal((shiftA, mw) -> mw.getEmployeeB(), Shift::getEmployee),
                        overlapping((shiftA, mw) -> shiftA.getStart(), (shiftA, mw) -> shiftA.getEnd(),
                                Shift::getStart, Shift::getEnd))
                .groupBy(
                        (shiftA, mw, shiftB) -> mw,
                        (shiftA, mw, shiftB) -> shiftA.getStart().get(WeekFields.ISO.weekOfWeekBasedYear()),
                        ConstraintCollectors.countTri())
                .filter((mw, week, count) -> count < mw.getMinShiftsTogetherPerWeek())
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        (mw, week, count) -> mw.getMinShiftsTogetherPerWeek() - count)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min shifts together per week - shortfall (HARD)"));
    }

    // Min overlapping shifts together per week - shortfall (SOFT)
    Constraint minShiftsTogetherPerWeekSoft(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(MustWorkTogether.class,
                        equal(Shift::getEmployee, MustWorkTogether::getEmployeeA))
                .filter((shiftA, mw) -> mw.getMinShiftsTogetherPerWeek() > 0
                        && !"HARD".equalsIgnoreCase(mw.getMinShiftsTogetherPerWeekSeverity()))
                .join(Shift.class,
                        equal((shiftA, mw) -> mw.getEmployeeB(), Shift::getEmployee),
                        overlapping((shiftA, mw) -> shiftA.getStart(), (shiftA, mw) -> shiftA.getEnd(),
                                Shift::getStart, Shift::getEnd))
                .groupBy(
                        (shiftA, mw, shiftB) -> mw,
                        (shiftA, mw, shiftB) -> shiftA.getStart().get(WeekFields.ISO.weekOfWeekBasedYear()),
                        ConstraintCollectors.countTri())
                .filter((mw, week, count) -> count < mw.getMinShiftsTogetherPerWeek())
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,
                        (mw, week, count) -> mw.getMinShiftsTogetherPerWeek() - count)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min shifts together per week - shortfall (SOFT)"));
    }

    Constraint maxWeeklyHoursHard(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(
                        Shift::getEmployee,
                        shift -> shift.getStart().get(WeekFields.ISO.weekOfWeekBasedYear()),
                        ConstraintCollectors.sum(shift -> Long.valueOf(Duration.between(shift.getStart(), shift.getEnd()).toMinutes())))
                .join(ConstraintConfiguration.class)
                .filter((employee, week, totalMinutes, cfg) -> {
                    long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                    return tot > cfg.getMaxWeeklyMinutes() && cfg.getMaxWeeklySeverity() == ConstraintConfiguration.Severity.HARD;
                })
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        (employee, week, totalMinutes, cfg) -> {
                            long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                            return (int) (tot - cfg.getMaxWeeklyMinutes());
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Max weekly hours per employee (HARD)"));
    }

    Constraint maxWeeklyHoursSoft(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(
                        Shift::getEmployee,
                        shift -> shift.getStart().get(WeekFields.ISO.weekOfWeekBasedYear()),
                        ConstraintCollectors.sum(shift -> Long.valueOf(Duration.between(shift.getStart(), shift.getEnd()).toMinutes())))
                .join(ConstraintConfiguration.class)
                .filter((employee, week, totalMinutes, cfg) -> {
                    long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                    return tot > cfg.getMaxWeeklyMinutes() && cfg.getMaxWeeklySeverity() == ConstraintConfiguration.Severity.SOFT;
                })
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,
                        (employee, week, totalMinutes, cfg) -> {
                            long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                            return (int) (tot - cfg.getMaxWeeklyMinutes());
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Max weekly hours per employee (SOFT)"));
    }

    Constraint maxMonthlyHoursHard(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(
                        Shift::getEmployee,
                        shift -> YearMonth.from(shift.getStart()),
                        ConstraintCollectors.sum(shift -> Long.valueOf(Duration.between(shift.getStart(), shift.getEnd()).toMinutes())))
                .join(ConstraintConfiguration.class)
                .filter((employee, month, totalMinutes, cfg) -> {
                    long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                    return tot > cfg.getMaxMonthlyMinutes() && cfg.getMaxMonthlySeverity() == ConstraintConfiguration.Severity.HARD;
                })
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        (employee, month, totalMinutes, cfg) -> {
                            long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                            return (int) (tot - cfg.getMaxMonthlyMinutes());
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Max monthly hours per employee (HARD)"));
    }

    Constraint maxMonthlyHoursSoft(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(
                        Shift::getEmployee,
                        shift -> YearMonth.from(shift.getStart()),
                        ConstraintCollectors.sum(shift -> Long.valueOf(Duration.between(shift.getStart(), shift.getEnd()).toMinutes())))
                .join(ConstraintConfiguration.class)
                .filter((employee, month, totalMinutes, cfg) -> {
                    long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                    return tot > cfg.getMaxMonthlyMinutes() && cfg.getMaxMonthlySeverity() == ConstraintConfiguration.Severity.SOFT;
                })
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,
                        (employee, month, totalMinutes, cfg) -> {
                            long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                            return (int) (tot - cfg.getMaxMonthlyMinutes());
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Max monthly hours per employee (SOFT)"));
    }

    // Goal: number of shifts per week (HARD)
    Constraint goalShiftsPerWeekHard(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(
                        Shift::getEmployee,
                        shift -> shift.getStart().get(WeekFields.ISO.weekOfWeekBasedYear()),
                        ConstraintCollectors.count())
                .join(ConstraintConfiguration.class)
                .filter((employee, week, shiftCount, cfg) -> cfg.getTargetShiftsPerWeek() > 0
                        && cfg.getTargetShiftsPerWeekSeverity() == ConstraintConfiguration.Severity.HARD)
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        (employee, week, shiftCount, cfg) -> (int) Math.abs(shiftCount - cfg.getTargetShiftsPerWeek()))
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week (HARD)"));
    }

    // Goal: number of shifts per week (SOFT)
    Constraint goalShiftsPerWeekSoft(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(
                        Shift::getEmployee,
                        shift -> shift.getStart().get(WeekFields.ISO.weekOfWeekBasedYear()),
                        ConstraintCollectors.count())
                .join(ConstraintConfiguration.class)
                .filter((employee, week, shiftCount, cfg) -> cfg.getTargetShiftsPerWeek() > 0
                        && cfg.getTargetShiftsPerWeekSeverity() == ConstraintConfiguration.Severity.SOFT)
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,
                        (employee, week, shiftCount, cfg) -> (int) Math.abs(shiftCount - cfg.getTargetShiftsPerWeek()))
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week (SOFT)"));
    }

    // Goal: minutes per week (HARD)
    Constraint goalMinutesPerWeekHard(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(
                        Shift::getEmployee,
                        shift -> shift.getStart().get(WeekFields.ISO.weekOfWeekBasedYear()),
                        ConstraintCollectors.sum(shift -> Long.valueOf(Duration.between(shift.getStart(), shift.getEnd()).toMinutes())))
                .join(ConstraintConfiguration.class)
                .filter((employee, week, totalMinutes, cfg) -> {
                    long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                    return cfg.getTargetMinutesPerWeek() > 0
                            && cfg.getTargetMinutesPerWeekSeverity() == ConstraintConfiguration.Severity.HARD;
                })
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        (employee, week, totalMinutes, cfg) -> {
                            long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                            return (int) Math.abs(tot - cfg.getTargetMinutesPerWeek());
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target minutes per employee per week (HARD)"));
    }

    // Goal: minutes per week (SOFT)
    Constraint goalMinutesPerWeekSoft(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(
                        Shift::getEmployee,
                        shift -> shift.getStart().get(WeekFields.ISO.weekOfWeekBasedYear()),
                        ConstraintCollectors.sum(shift -> Long.valueOf(Duration.between(shift.getStart(), shift.getEnd()).toMinutes())))
                .join(ConstraintConfiguration.class)
                .filter((employee, week, totalMinutes, cfg) -> {
                    long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                    return cfg.getTargetMinutesPerWeek() > 0
                            && cfg.getTargetMinutesPerWeekSeverity() == ConstraintConfiguration.Severity.SOFT;
                })
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,
                        (employee, week, totalMinutes, cfg) -> {
                            long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                            return (int) Math.abs(tot - cfg.getTargetMinutesPerWeek());
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target minutes per employee per week (SOFT)"));
    }

    // Min weekly hours per employee (HARD)
    Constraint minWeeklyHoursHard(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> shift.getEmployee() != null)
                .filter(shift -> shift.getEmployee().getMinWeeklyMinutes() != null
                        && shift.getEmployee().getMinWeeklyMinutes() > 0)
                .filter(shift -> "HARD".equalsIgnoreCase(shift.getEmployee().getMinWeeklySeverity()))
                .groupBy(Shift::getEmployee,
                        shift -> shift.getStart().get(WeekFields.ISO.weekOfWeekBasedYear()),
                        ConstraintCollectors.sum(Shift::getDurationInMinutes))
                .filter((employee, week, totalMinutes) -> totalMinutes < employee.getMinWeeklyMinutes())
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        (employee, week, totalMinutes) -> employee.getMinWeeklyMinutes() - totalMinutes)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min weekly hours per employee (HARD)"));
    }

    // Min weekly hours per employee (SOFT)
    Constraint minWeeklyHoursSoft(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> shift.getEmployee() != null)
                .filter(shift -> shift.getEmployee().getMinWeeklyMinutes() != null
                        && shift.getEmployee().getMinWeeklyMinutes() > 0)
                .filter(shift -> !"HARD".equalsIgnoreCase(shift.getEmployee().getMinWeeklySeverity()))
                .groupBy(Shift::getEmployee,
                        shift -> shift.getStart().get(WeekFields.ISO.weekOfWeekBasedYear()),
                        ConstraintCollectors.sum(Shift::getDurationInMinutes))
                .filter((employee, week, totalMinutes) -> totalMinutes < employee.getMinWeeklyMinutes())
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,
                        (employee, week, totalMinutes) -> employee.getMinWeeklyMinutes() - totalMinutes)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min weekly hours per employee (SOFT)"));
    }

    // Goal: target shifts per week per individual employee (HARD)
    Constraint goalShiftsPerWeekPerEmployeeHard(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> {
                    Employee e = shift.getEmployee();
                    return e != null && e.getTargetShiftsPerWeek() != null
                            && e.getTargetShiftsPerWeek() > 0
                            && "HARD".equalsIgnoreCase(e.getTargetShiftsPerWeekSeverity());
                })
                .groupBy(Shift::getEmployee,
                        shift -> shift.getStart().get(WeekFields.ISO.weekOfWeekBasedYear()),
                        ConstraintCollectors.count())
                .filter((employee, week, shiftCount) ->
                        shiftCount != employee.getTargetShiftsPerWeek().intValue())
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        (employee, week, shiftCount) ->
                                Math.abs(shiftCount - employee.getTargetShiftsPerWeek().intValue()))
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week - individual (HARD)"));
    }

    // Goal: target shifts per week per individual employee (SOFT)
    Constraint goalShiftsPerWeekPerEmployeeSoft(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> {
                    Employee e = shift.getEmployee();
                    return e != null && e.getTargetShiftsPerWeek() != null
                            && e.getTargetShiftsPerWeek() > 0
                            && "SOFT".equalsIgnoreCase(e.getTargetShiftsPerWeekSeverity());
                })
                .groupBy(Shift::getEmployee,
                        shift -> shift.getStart().get(WeekFields.ISO.weekOfWeekBasedYear()),
                        ConstraintCollectors.count())
                .filter((employee, week, shiftCount) ->
                        shiftCount != employee.getTargetShiftsPerWeek().intValue())
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,
                        (employee, week, shiftCount) ->
                                Math.abs(shiftCount - employee.getTargetShiftsPerWeek().intValue()))
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week - individual (SOFT)"));
    }

    // Zero-shift companions: employees with a target but NO assigned shifts at all

    Constraint goalShiftsPerWeekPerEmployeeHardZero(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Employee.class)
                .filter(e -> e.getTargetShiftsPerWeek() != null
                        && e.getTargetShiftsPerWeek() > 0
                        && "HARD".equalsIgnoreCase(e.getTargetShiftsPerWeekSeverity()))
                .ifNotExists(Shift.class, equal(Function.identity(), Shift::getEmployee))
                .penalize(HardSoftBigDecimalScore.ONE_HARD, Employee::getTargetShiftsPerWeek)
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week - individual zero (HARD)"));
    }

    Constraint goalShiftsPerWeekPerEmployeeSoftZero(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Employee.class)
                .filter(e -> e.getTargetShiftsPerWeek() != null
                        && e.getTargetShiftsPerWeek() > 0
                        && "SOFT".equalsIgnoreCase(e.getTargetShiftsPerWeekSeverity()))
                .ifNotExists(Shift.class, equal(Function.identity(), Shift::getEmployee))
                .penalize(HardSoftBigDecimalScore.ONE_SOFT, Employee::getTargetShiftsPerWeek)
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week - individual zero (SOFT)"));
    }

    Constraint minWeeklyHoursHardZero(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Employee.class)
                .filter(e -> e.getMinWeeklyMinutes() != null
                        && e.getMinWeeklyMinutes() > 0
                        && "HARD".equalsIgnoreCase(e.getMinWeeklySeverity()))
                .ifNotExists(Shift.class, equal(Function.identity(), Shift::getEmployee))
                .penalize(HardSoftBigDecimalScore.ONE_HARD, Employee::getMinWeeklyMinutes)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min weekly hours per employee zero (HARD)"));
    }

    Constraint minWeeklyHoursSoftZero(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Employee.class)
                .filter(e -> e.getMinWeeklyMinutes() != null
                        && e.getMinWeeklyMinutes() > 0
                        && !"HARD".equalsIgnoreCase(e.getMinWeeklySeverity()))
                .ifNotExists(Shift.class, equal(Function.identity(), Shift::getEmployee))
                .penalize(HardSoftBigDecimalScore.ONE_SOFT, Employee::getMinWeeklyMinutes)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min weekly hours per employee zero (SOFT)"));
    }

    Constraint goalShiftsPerWeekHardZero(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Employee.class)
                .ifNotExists(Shift.class, equal(Function.identity(), Shift::getEmployee))
                .join(ConstraintConfiguration.class)
                .filter((employee, cfg) -> cfg.getTargetShiftsPerWeek() > 0
                        && cfg.getTargetShiftsPerWeekSeverity() == ConstraintConfiguration.Severity.HARD)
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        (employee, cfg) -> cfg.getTargetShiftsPerWeek())
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week zero (HARD)"));
    }

    Constraint goalShiftsPerWeekSoftZero(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Employee.class)
                .ifNotExists(Shift.class, equal(Function.identity(), Shift::getEmployee))
                .join(ConstraintConfiguration.class)
                .filter((employee, cfg) -> cfg.getTargetShiftsPerWeek() > 0
                        && cfg.getTargetShiftsPerWeekSeverity() == ConstraintConfiguration.Severity.SOFT)
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,
                        (employee, cfg) -> cfg.getTargetShiftsPerWeek())
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week zero (SOFT)"));
    }

    Constraint goalMinutesPerWeekHardZero(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Employee.class)
                .ifNotExists(Shift.class, equal(Function.identity(), Shift::getEmployee))
                .join(ConstraintConfiguration.class)
                .filter((employee, cfg) -> cfg.getTargetMinutesPerWeek() > 0
                        && cfg.getTargetMinutesPerWeekSeverity() == ConstraintConfiguration.Severity.HARD)
                .penalize(HardSoftBigDecimalScore.ONE_HARD,
                        (employee, cfg) -> cfg.getTargetMinutesPerWeek())
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target minutes per employee per week zero (HARD)"));
    }

    Constraint goalMinutesPerWeekSoftZero(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Employee.class)
                .ifNotExists(Shift.class, equal(Function.identity(), Shift::getEmployee))
                .join(ConstraintConfiguration.class)
                .filter((employee, cfg) -> cfg.getTargetMinutesPerWeek() > 0
                        && cfg.getTargetMinutesPerWeekSeverity() == ConstraintConfiguration.Severity.SOFT)
                .penalize(HardSoftBigDecimalScore.ONE_SOFT,
                        (employee, cfg) -> cfg.getTargetMinutesPerWeek())
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target minutes per employee per week zero (SOFT)"));
    }

}
