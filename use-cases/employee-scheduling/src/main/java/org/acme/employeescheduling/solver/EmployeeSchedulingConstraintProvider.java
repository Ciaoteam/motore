package org.acme.employeescheduling.solver;

import org.acme.common.ConstraintIdSanitizer;
import static ai.timefold.solver.core.api.score.stream.Joiners.equal;
import static ai.timefold.solver.core.api.score.stream.Joiners.filtering;
import static ai.timefold.solver.core.api.score.stream.Joiners.lessThanOrEqual;
import static ai.timefold.solver.core.api.score.stream.Joiners.overlapping;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import ai.timefold.solver.core.api.score.HardMediumSoftBigDecimalScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.common.LoadBalance;
import ai.timefold.solver.core.api.score.stream.bi.BiConstraintStream;
import ai.timefold.solver.core.api.score.stream.tri.TriConstraintStream;
import ai.timefold.solver.core.api.score.stream.uni.UniConstraintStream;

import org.acme.employeescheduling.domain.Employee;
import org.acme.employeescheduling.domain.Shift;
import org.acme.employeescheduling.domain.MustWorkTogether;
import org.acme.employeescheduling.domain.ConstraintConfiguration;
import org.acme.employeescheduling.domain.ConcurrentSkillRequirement;

public class EmployeeSchedulingConstraintProvider implements ConstraintProvider {

    private static final int MAX_MINUTES_PER_WEEK = 40 * 60;
    private static final int MAX_MINUTES_PER_MONTH = 160 * 60;

    // A fractional soft weight used so that varyShiftStartTimesForEmployee only acts as a low-priority
    // tie-breaker: its total impact stays far smaller than the smallest meaningful penalty/reward of any
    // other (whole-number, minute-based) soft constraint, so it never outweighs them.
    private static final HardMediumSoftBigDecimalScore VARY_SHIFT_START_TIMES_WEIGHT =
            HardMediumSoftBigDecimalScore.of(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.0001"));

    private record ShiftWeekOverlap(Shift shift, LocalDate weekStart, LocalDateTime overlapStart, LocalDateTime overlapEnd,
            int overlapMinutes) {

        Employee employee() {
            return shift.getEmployee();
        }

        boolean overlaps(Shift otherShift) {
            return getMinuteOverlap(overlapStart, overlapEnd, otherShift.getStart(), otherShift.getEnd()) > 0;
        }
    }

    private static boolean hasSeverity(String actualSeverity, String expectedSeverity) {
        return expectedSeverity.equalsIgnoreCase(actualSeverity);
    }

    private static int getMinuteOverlap(LocalDateTime firstStart, LocalDateTime firstEnd,
            LocalDateTime secondStart, LocalDateTime secondEnd) {
        LocalDateTime overlapStart = firstStart.isAfter(secondStart) ? firstStart : secondStart;
        LocalDateTime overlapEnd = firstEnd.isBefore(secondEnd) ? firstEnd : secondEnd;
        if (!overlapStart.isBefore(overlapEnd)) {
            return 0;
        }
        return (int) Duration.between(overlapStart, overlapEnd).toMinutes();
    }

    private static int getMinuteOverlap(Shift shift1, Shift shift2) {
        return getMinuteOverlap(shift1.getStart(), shift1.getEnd(), shift2.getStart(), shift2.getEnd());
    }

    private static LocalDate getWeekStart(LocalDateTime dateTime) {
        return dateTime.toLocalDate().with(WeekFields.ISO.dayOfWeek(), 1);
    }

    private static LocalDate getWeekStart(Shift shift) {
        return getWeekStart(shift.getStart());
    }

    private static boolean overlapsWeek(Shift shift, LocalDate weekStart) {
        return getWeekOverlapMinutes(shift, weekStart) > 0;
    }

    private static int getWeekOverlapMinutes(Shift shift, LocalDate weekStart) {
        LocalDateTime weekStartDateTime = weekStart.atStartOfDay();
        LocalDateTime nextWeekStartDateTime = weekStart.plusWeeks(1).atStartOfDay();
        return getMinuteOverlap(shift.getStart(), shift.getEnd(), weekStartDateTime, nextWeekStartDateTime);
    }

    private static Iterable<ShiftWeekOverlap> getWeekOverlaps(Shift shift) {
        if (!shift.getStart().isBefore(shift.getEnd())) {
            return List.of();
        }
        List<ShiftWeekOverlap> weekOverlaps = new ArrayList<>();
        LocalDate weekStart = getWeekStart(shift);
        LocalDate lastWeekStart = getWeekStart(shift.getEnd().minusNanos(1));
        while (!weekStart.isAfter(lastWeekStart)) {
            LocalDateTime weekStartDateTime = weekStart.atStartOfDay();
            LocalDateTime nextWeekStartDateTime = weekStart.plusWeeks(1).atStartOfDay();
            LocalDateTime overlapStart = shift.getStart().isAfter(weekStartDateTime) ? shift.getStart() : weekStartDateTime;
            LocalDateTime overlapEnd = shift.getEnd().isBefore(nextWeekStartDateTime) ? shift.getEnd() : nextWeekStartDateTime;
            int overlapMinutes = getMinuteOverlap(shift.getStart(), shift.getEnd(), weekStartDateTime, nextWeekStartDateTime);
            if (overlapMinutes > 0) {
                weekOverlaps.add(new ShiftWeekOverlap(shift, weekStart, overlapStart, overlapEnd, overlapMinutes));
            }
            weekStart = weekStart.plusWeeks(1);
        }
        return weekOverlaps;
    }

    private static UniConstraintStream<ShiftWeekOverlap> shiftWeekOverlaps(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .flattenLast(EmployeeSchedulingConstraintProvider::getWeekOverlaps);
    }

    private static UniConstraintStream<ShiftWeekOverlap> shiftWeekOverlapsIncludingUnassigned(
            ConstraintFactory constraintFactory) {
        return constraintFactory.forEachIncludingUnassigned(Shift.class)
                .flattenLast(EmployeeSchedulingConstraintProvider::getWeekOverlaps);
    }

    private static TriConstraintStream<Employee, LocalDate, Long> weeklyAssignedShiftCounts(
            ConstraintFactory constraintFactory) {
        return shiftWeekOverlaps(constraintFactory)
                .groupBy(ShiftWeekOverlap::employee, ShiftWeekOverlap::weekStart, ConstraintCollectors.count());
    }

    private static TriConstraintStream<Employee, LocalDate, Long> weeklyAssignedMinutes(ConstraintFactory constraintFactory) {
        return shiftWeekOverlaps(constraintFactory)
                .groupBy(ShiftWeekOverlap::employee, ShiftWeekOverlap::weekStart,
                        ConstraintCollectors.sum(weekOverlap -> Long.valueOf(weekOverlap.overlapMinutes())));
    }

    private static TriConstraintStream<MustWorkTogether, LocalDate, Long> sharedShiftCountsPerWeek(
            ConstraintFactory constraintFactory, String severity) {
        return shiftWeekOverlaps(constraintFactory)
                .join(MustWorkTogether.class, equal(ShiftWeekOverlap::employee, MustWorkTogether::getEmployeeA))
                .filter((shiftWeekOverlap, mustWorkTogether) -> mustWorkTogether.getMinShiftsTogetherPerWeek() > 0
                        && hasSeverity(mustWorkTogether.getMinShiftsTogetherPerWeekSeverity(), severity))
                .join(Shift.class,
                        equal((shiftWeekOverlap, mustWorkTogether) -> mustWorkTogether.getEmployeeB(), Shift::getEmployee),
                        filtering((shiftWeekOverlap, mustWorkTogether, shiftB) -> overlapsWeek(shiftB,
                                shiftWeekOverlap.weekStart())))
                .groupBy(
                        (shiftWeekOverlap, mustWorkTogether, shiftB) -> mustWorkTogether,
                        (shiftWeekOverlap, mustWorkTogether, shiftB) -> shiftWeekOverlap.weekStart(),
                        ConstraintCollectors.conditionally(
                                (shiftWeekOverlap, mustWorkTogether, shiftB) -> shiftWeekOverlap.overlaps(shiftB),
                                ConstraintCollectors.countTri()));
    }

    private static BiConstraintStream<MustWorkTogether, LocalDate> weeksWithoutPartnerShift(
            ConstraintFactory constraintFactory, String severity) {
        return shiftWeekOverlaps(constraintFactory)
                .join(MustWorkTogether.class, equal(ShiftWeekOverlap::employee, MustWorkTogether::getEmployeeA))
                .filter((shiftWeekOverlap, mustWorkTogether) -> mustWorkTogether.getMinShiftsTogetherPerWeek() > 0
                        && hasSeverity(mustWorkTogether.getMinShiftsTogetherPerWeekSeverity(), severity))
                .ifNotExists(Shift.class,
                        equal((shiftWeekOverlap, mustWorkTogether) -> mustWorkTogether.getEmployeeB(),
                                Shift::getEmployee),
                        filtering((shiftWeekOverlap, mustWorkTogether, shiftB) -> overlapsWeek(shiftB,
                                shiftWeekOverlap.weekStart())))
                .groupBy((shiftWeekOverlap, mustWorkTogether) -> mustWorkTogether,
                        (shiftWeekOverlap, mustWorkTogether) -> shiftWeekOverlap.weekStart());
    }

    private static BiConstraintStream<Employee, LocalDate> employeeWeeks(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Employee.class)
                .join(shiftWeekOverlapsIncludingUnassigned(constraintFactory)
                        .groupBy(ShiftWeekOverlap::weekStart));
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
                mustWorkTogetherMedium(constraintFactory),
                mustWorkTogetherSoft(constraintFactory),
                minShiftsTogetherPerWeekHard(constraintFactory),
                minShiftsTogetherPerWeekHardZero(constraintFactory),
                minShiftsTogetherPerWeekMedium(constraintFactory),
                minShiftsTogetherPerWeekMediumZero(constraintFactory),
                minShiftsTogetherPerWeekSoft(constraintFactory),
                minShiftsTogetherPerWeekSoftZero(constraintFactory),
                maxWeeklyHoursHard(constraintFactory),
                maxWeeklyHoursMedium(constraintFactory),
                maxWeeklyHoursSoft(constraintFactory),
                maxMonthlyHoursHard(constraintFactory),
                maxMonthlyHoursMedium(constraintFactory),
                maxMonthlyHoursSoft(constraintFactory),
                // Goal constraints (HARD variants will only apply if configured as HARD)
                goalShiftsPerWeekHard(constraintFactory),
                goalShiftsPerWeekHardZero(constraintFactory),
                goalShiftsPerWeekMedium(constraintFactory),
                goalShiftsPerWeekMediumZero(constraintFactory),
                goalMinutesPerWeekHard(constraintFactory),
                goalMinutesPerWeekHardZero(constraintFactory),
                goalMinutesPerWeekMedium(constraintFactory),
                goalMinutesPerWeekMediumZero(constraintFactory),

                // Soft constraints
                undesiredDayForEmployee(constraintFactory),
                desiredDayForEmployee(constraintFactory),
                balanceEmployeeShiftAssignments(constraintFactory),
                varyShiftStartTimesForEmployee(constraintFactory),
                // Goal constraints (SOFT variants)
                goalShiftsPerWeekSoft(constraintFactory),
                goalShiftsPerWeekSoftZero(constraintFactory),
                goalMinutesPerWeekSoft(constraintFactory),
                goalMinutesPerWeekSoftZero(constraintFactory),
                // Min weekly hours per employee
                minWeeklyHoursHard(constraintFactory),
                minWeeklyHoursHardZero(constraintFactory),
                minWeeklyHoursMedium(constraintFactory),
                minWeeklyHoursMediumZero(constraintFactory),
                minWeeklyHoursSoft(constraintFactory),
                minWeeklyHoursSoftZero(constraintFactory),
                // Per-employee target shifts per week
                goalShiftsPerWeekPerEmployeeHard(constraintFactory),
                goalShiftsPerWeekPerEmployeeHardZero(constraintFactory),
                goalShiftsPerWeekPerEmployeeMedium(constraintFactory),
                goalShiftsPerWeekPerEmployeeMediumZero(constraintFactory),
                goalShiftsPerWeekPerEmployeeSoft(constraintFactory),
                goalShiftsPerWeekPerEmployeeSoftZero(constraintFactory),
                // Concurrent skill headcount
                minConcurrentSkillHard(constraintFactory),
                minConcurrentSkillZeroHard(constraintFactory),
                minConcurrentSkillMedium(constraintFactory),
                minConcurrentSkillZeroMedium(constraintFactory),
                minConcurrentSkillSoft(constraintFactory),
                minConcurrentSkillZeroSoft(constraintFactory),
                maxConcurrentSkillHard(constraintFactory),
                maxConcurrentSkillMedium(constraintFactory),
                maxConcurrentSkillSoft(constraintFactory)
        };
    }

    Constraint requiredSkill(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .filter(shift -> !shift.getEmployee().getSkills().contains(shift.getRequiredSkill()))
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD)
                .asConstraint(ConstraintIdSanitizer.sanitize("Missing required skill"));
    }

    Constraint noOverlappingShifts(ConstraintFactory constraintFactory) {
        return constraintFactory.forEachUniquePair(Shift.class, equal(Shift::getEmployee),
                overlapping(Shift::getStart, Shift::getEnd))
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD,
                        EmployeeSchedulingConstraintProvider::getMinuteOverlap)
                .asConstraint(ConstraintIdSanitizer.sanitize("Overlapping shift"));
    }

    Constraint atLeast10HoursBetweenTwoShifts(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Shift.class, equal(Shift::getEmployee), lessThanOrEqual(Shift::getEnd, Shift::getStart))
                .join(ConstraintConfiguration.class)
                .filter((firstShift,
                        secondShift, cfg) -> Duration.between(firstShift.getEnd(), secondShift.getStart()).toMinutes()
                                < cfg.getMinimumRestMinutes())
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD,
                        (firstShift, secondShift, cfg) -> {
                            int breakLength = (int) Duration.between(firstShift.getEnd(), secondShift.getStart()).toMinutes();
                            return cfg.getMinimumRestMinutes() - breakLength;
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Minimum rest between 2 shifts"));
    }

    Constraint oneShiftPerDay(ConstraintFactory constraintFactory) {
        return constraintFactory.forEachUniquePair(Shift.class, equal(Shift::getEmployee),
                equal(shift -> shift.getStart().toLocalDate()))
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD)
                .asConstraint(ConstraintIdSanitizer.sanitize("Max one shift per day"));
    }

    Constraint unavailableEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Employee.class, equal(Shift::getEmployee, Function.identity()))
                .flattenLast(Employee::getUnavailableDates)
                .filter(Shift::isOverlappingWithDate)
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD, Shift::getOverlappingDurationInMinutes)
                .asConstraint(ConstraintIdSanitizer.sanitize("Unavailable employee"));
    }

    Constraint undesiredDayForEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Employee.class, equal(Shift::getEmployee, Function.identity()))
                .flattenLast(Employee::getUndesiredDates)
                .filter(Shift::isOverlappingWithDate)
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT, Shift::getOverlappingDurationInMinutes)
                .asConstraint(ConstraintIdSanitizer.sanitize("Undesired day for employee"));
    }

    Constraint desiredDayForEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(Employee.class, equal(Shift::getEmployee, Function.identity()))
                .flattenLast(Employee::getDesiredDates)
                .filter(Shift::isOverlappingWithDate)
                .reward(HardMediumSoftBigDecimalScore.ONE_SOFT, Shift::getOverlappingDurationInMinutes)
                .asConstraint(ConstraintIdSanitizer.sanitize("Desired day for employee"));
    }

    Constraint balanceEmployeeShiftAssignments(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(Shift::getEmployee, ConstraintCollectors.count())
                .complement(Employee.class, e -> 0L) // Include all employees which are not assigned to any shift.
                .groupBy(ConstraintCollectors.loadBalance((employee, shiftCount) -> employee,
                        (employee, shiftCount) -> shiftCount))
                .penalizeBigDecimal(HardMediumSoftBigDecimalScore.ONE_SOFT, LoadBalance::unfairness)
                .asConstraint(ConstraintIdSanitizer.sanitize("Balance employee shift assignments"));
    }

    // Encourages variety: penalizes an employee being repeatedly assigned shifts that start at the
    // same clock time within the same week (e.g. always mornings, or always evenings). Every pair of
    // same-employee, same-week, same-start-time shifts adds a penalty, so the more repetitive an
    // employee's week is, the higher the penalty; a varied schedule scores lowest.
    // Uses a fractional weight (see VARY_SHIFT_START_TIMES_WEIGHT) so this preference is only ever
    // applied as a low-priority tie-breaker and cannot outweigh or interfere with the other soft
    // constraints, which all operate on whole-number (e.g. minute-based) magnitudes.
    Constraint varyShiftStartTimesForEmployee(ConstraintFactory constraintFactory) {
        return constraintFactory.forEachUniquePair(Shift.class,
                equal(Shift::getEmployee),
                equal(EmployeeSchedulingConstraintProvider::getWeekStart),
                equal(Shift::getStartTimeOfDay))
                .penalize(VARY_SHIFT_START_TIMES_WEIGHT)
                .asConstraint(ConstraintIdSanitizer.sanitize("Vary shift start times for employee"));
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
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD)
                .asConstraint(ConstraintIdSanitizer.sanitize("Must work together - partner missing (A assigned - B missing) (HARD)"));
    }

    // Must work together - partner missing (A assigned - B missing) (MEDIUM)
    Constraint mustWorkTogetherMedium(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .join(MustWorkTogether.class,
                      equal(Shift::getEmployee, MustWorkTogether::getEmployeeA))
                .join(ConstraintConfiguration.class)
                .filter((shiftA, mw, cfg) -> cfg.getMustWorkTogetherSeverity() == ConstraintConfiguration.Severity.MEDIUM)
                .ifNotExists(Shift.class,
                        equal((shiftA, mw, cfg) -> mw.getEmployeeB(), Shift::getEmployee),
                        overlapping((shiftA, mw, cfg) -> shiftA.getStart(), (shiftA, mw, cfg) -> shiftA.getEnd(),
                                    Shift::getStart, Shift::getEnd))
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM)
                .asConstraint(ConstraintIdSanitizer.sanitize("Must work together - partner missing (A assigned - B missing) (MEDIUM)"));
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
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT)
                .asConstraint(ConstraintIdSanitizer.sanitize("Must work together - partner missing (A assigned - B missing) (SOFT)"));
    }

    // Min overlapping shifts together per week - shortfall (HARD)
    Constraint minShiftsTogetherPerWeekHard(ConstraintFactory constraintFactory) {
        return sharedShiftCountsPerWeek(constraintFactory, "HARD")
                .filter((mw, week, count) -> count < mw.getMinShiftsTogetherPerWeek())
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD,
                        (mw, week, count) -> mw.getMinShiftsTogetherPerWeek() - count.intValue())
                .asConstraint(ConstraintIdSanitizer.sanitize("Min shifts together per week - shortfall (HARD)"));
    }

    // Min overlapping shifts together per week - shortfall (MEDIUM)
    Constraint minShiftsTogetherPerWeekMedium(ConstraintFactory constraintFactory) {
        return sharedShiftCountsPerWeek(constraintFactory, "MEDIUM")
                .filter((mw, week, count) -> count < mw.getMinShiftsTogetherPerWeek())
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM,
                        (mw, week, count) -> mw.getMinShiftsTogetherPerWeek() - count.intValue())
                .asConstraint(ConstraintIdSanitizer.sanitize("Min shifts together per week - shortfall (MEDIUM)"));
    }

    // Min overlapping shifts together per week - shortfall (SOFT)
    Constraint minShiftsTogetherPerWeekSoft(ConstraintFactory constraintFactory) {
        return sharedShiftCountsPerWeek(constraintFactory, "SOFT")
                .filter((mw, week, count) -> count < mw.getMinShiftsTogetherPerWeek())
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT,
                        (mw, week, count) -> mw.getMinShiftsTogetherPerWeek() - count.intValue())
                .asConstraint(ConstraintIdSanitizer.sanitize("Min shifts together per week - shortfall (SOFT)"));
    }

    Constraint minShiftsTogetherPerWeekHardZero(ConstraintFactory constraintFactory) {
        return weeksWithoutPartnerShift(constraintFactory, "HARD")
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD, (mw, week) -> mw.getMinShiftsTogetherPerWeek())
                .asConstraint(ConstraintIdSanitizer.sanitize("Min shifts together per week - zero (HARD)"));
    }

    Constraint minShiftsTogetherPerWeekMediumZero(ConstraintFactory constraintFactory) {
        return weeksWithoutPartnerShift(constraintFactory, "MEDIUM")
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM, (mw, week) -> mw.getMinShiftsTogetherPerWeek())
                .asConstraint(ConstraintIdSanitizer.sanitize("Min shifts together per week - zero (MEDIUM)"));
    }

    Constraint minShiftsTogetherPerWeekSoftZero(ConstraintFactory constraintFactory) {
        return weeksWithoutPartnerShift(constraintFactory, "SOFT")
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT, (mw, week) -> mw.getMinShiftsTogetherPerWeek())
                .asConstraint(ConstraintIdSanitizer.sanitize("Min shifts together per week - zero (SOFT)"));
    }

    Constraint maxWeeklyHoursHard(ConstraintFactory constraintFactory) {
        return weeklyAssignedMinutes(constraintFactory)
                .join(ConstraintConfiguration.class)
                .filter((employee, week, totalMinutes, cfg) -> {
                    long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                    return tot > cfg.getMaxWeeklyMinutes() && cfg.getMaxWeeklySeverity() == ConstraintConfiguration.Severity.HARD;
                })
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD,
                        (employee, week, totalMinutes, cfg) -> {
                            long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                            return (int) (tot - cfg.getMaxWeeklyMinutes());
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Max weekly hours per employee (HARD)"));
    }

    Constraint maxWeeklyHoursMedium(ConstraintFactory constraintFactory) {
        return weeklyAssignedMinutes(constraintFactory)
                .join(ConstraintConfiguration.class)
                .filter((employee, week, totalMinutes, cfg) -> {
                    long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                    return tot > cfg.getMaxWeeklyMinutes() && cfg.getMaxWeeklySeverity() == ConstraintConfiguration.Severity.MEDIUM;
                })
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM,
                        (employee, week, totalMinutes, cfg) -> {
                            long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                            return (int) (tot - cfg.getMaxWeeklyMinutes());
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Max weekly hours per employee (MEDIUM)"));
    }

    Constraint maxWeeklyHoursSoft(ConstraintFactory constraintFactory) {
        return weeklyAssignedMinutes(constraintFactory)
                .join(ConstraintConfiguration.class)
                .filter((employee, week, totalMinutes, cfg) -> {
                    long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                    return tot > cfg.getMaxWeeklyMinutes() && cfg.getMaxWeeklySeverity() == ConstraintConfiguration.Severity.SOFT;
                })
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT,
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
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD,
                        (employee, month, totalMinutes, cfg) -> {
                            long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                            return (int) (tot - cfg.getMaxMonthlyMinutes());
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Max monthly hours per employee (HARD)"));
    }

    Constraint maxMonthlyHoursMedium(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(Shift.class)
                .groupBy(
                        Shift::getEmployee,
                        shift -> YearMonth.from(shift.getStart()),
                        ConstraintCollectors.sum(shift -> Long.valueOf(Duration.between(shift.getStart(), shift.getEnd()).toMinutes())))
                .join(ConstraintConfiguration.class)
                .filter((employee, month, totalMinutes, cfg) -> {
                    long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                    return tot > cfg.getMaxMonthlyMinutes() && cfg.getMaxMonthlySeverity() == ConstraintConfiguration.Severity.MEDIUM;
                })
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM,
                        (employee, month, totalMinutes, cfg) -> {
                            long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                            return (int) (tot - cfg.getMaxMonthlyMinutes());
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Max monthly hours per employee (MEDIUM)"));
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
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT,
                        (employee, month, totalMinutes, cfg) -> {
                            long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                            return (int) (tot - cfg.getMaxMonthlyMinutes());
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Max monthly hours per employee (SOFT)"));
    }

    // Goal: number of shifts per week (HARD)
    Constraint goalShiftsPerWeekHard(ConstraintFactory constraintFactory) {
        return weeklyAssignedShiftCounts(constraintFactory)
                .join(ConstraintConfiguration.class)
                .filter((employee, week, shiftCount, cfg) -> cfg.getTargetShiftsPerWeek() > 0
                        && cfg.getTargetShiftsPerWeekSeverity() == ConstraintConfiguration.Severity.HARD)
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD,
                        (employee, week, shiftCount, cfg) -> (int) Math.abs(shiftCount.longValue() - cfg.getTargetShiftsPerWeek()))
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week (HARD)"));
    }

    // Goal: number of shifts per week (MEDIUM)
    Constraint goalShiftsPerWeekMedium(ConstraintFactory constraintFactory) {
        return weeklyAssignedShiftCounts(constraintFactory)
                .join(ConstraintConfiguration.class)
                .filter((employee, week, shiftCount, cfg) -> cfg.getTargetShiftsPerWeek() > 0
                        && cfg.getTargetShiftsPerWeekSeverity() == ConstraintConfiguration.Severity.MEDIUM)
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM,
                        (employee, week, shiftCount, cfg) -> (int) Math.abs(shiftCount.longValue() - cfg.getTargetShiftsPerWeek()))
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week (MEDIUM)"));
    }

    // Goal: number of shifts per week (SOFT)
    Constraint goalShiftsPerWeekSoft(ConstraintFactory constraintFactory) {
        return weeklyAssignedShiftCounts(constraintFactory)
                .join(ConstraintConfiguration.class)
                .filter((employee, week, shiftCount, cfg) -> cfg.getTargetShiftsPerWeek() > 0
                        && cfg.getTargetShiftsPerWeekSeverity() == ConstraintConfiguration.Severity.SOFT)
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT,
                        (employee, week, shiftCount, cfg) -> (int) Math.abs(shiftCount.longValue() - cfg.getTargetShiftsPerWeek()))
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week (SOFT)"));
    }

    // Goal: minutes per week (HARD)
    Constraint goalMinutesPerWeekHard(ConstraintFactory constraintFactory) {
        return weeklyAssignedMinutes(constraintFactory)
                .join(ConstraintConfiguration.class)
                .filter((employee, week, totalMinutes, cfg) -> {
                    long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                    return cfg.getTargetMinutesPerWeek() > 0
                            && cfg.getTargetMinutesPerWeekSeverity() == ConstraintConfiguration.Severity.HARD;
                })
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD,
                        (employee, week, totalMinutes, cfg) -> {
                            long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                            return (int) Math.abs(tot - cfg.getTargetMinutesPerWeek());
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target minutes per employee per week (HARD)"));
    }

    // Goal: minutes per week (MEDIUM)
    Constraint goalMinutesPerWeekMedium(ConstraintFactory constraintFactory) {
        return weeklyAssignedMinutes(constraintFactory)
                .join(ConstraintConfiguration.class)
                .filter((employee, week, totalMinutes, cfg) -> cfg.getTargetMinutesPerWeek() > 0
                        && cfg.getTargetMinutesPerWeekSeverity() == ConstraintConfiguration.Severity.MEDIUM)
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM,
                        (employee, week, totalMinutes, cfg) -> {
                            long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                            return (int) Math.abs(tot - cfg.getTargetMinutesPerWeek());
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target minutes per employee per week (MEDIUM)"));
    }

    // Goal: minutes per week (SOFT)
    Constraint goalMinutesPerWeekSoft(ConstraintFactory constraintFactory) {
        return weeklyAssignedMinutes(constraintFactory)
                .join(ConstraintConfiguration.class)
                .filter((employee, week, totalMinutes, cfg) -> {
                    long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                    return cfg.getTargetMinutesPerWeek() > 0
                            && cfg.getTargetMinutesPerWeekSeverity() == ConstraintConfiguration.Severity.SOFT;
                })
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT,
                        (employee, week, totalMinutes, cfg) -> {
                            long tot = totalMinutes == null ? 0L : totalMinutes.longValue();
                            return (int) Math.abs(tot - cfg.getTargetMinutesPerWeek());
                        })
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target minutes per employee per week (SOFT)"));
    }

    // Min weekly hours per employee (HARD)
    Constraint minWeeklyHoursHard(ConstraintFactory constraintFactory) {
        return weeklyAssignedMinutes(constraintFactory)
                .filter((employee, week, totalMinutes) -> employee.getMinWeeklyMinutes() != null
                        && employee.getMinWeeklyMinutes() > 0
                        && "HARD".equalsIgnoreCase(employee.getMinWeeklySeverity()))
                .filter((employee, week, totalMinutes) -> totalMinutes < employee.getMinWeeklyMinutes())
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD,
                        (employee, week, totalMinutes) -> employee.getMinWeeklyMinutes() - totalMinutes.intValue())
                .asConstraint(ConstraintIdSanitizer.sanitize("Min weekly hours per employee (HARD)"));
    }

    // Min weekly hours per employee (MEDIUM)
    Constraint minWeeklyHoursMedium(ConstraintFactory constraintFactory) {
        return weeklyAssignedMinutes(constraintFactory)
                .filter((employee, week, totalMinutes) -> employee.getMinWeeklyMinutes() != null
                        && employee.getMinWeeklyMinutes() > 0
                        && hasSeverity(employee.getMinWeeklySeverity(), "MEDIUM"))
                .filter((employee, week, totalMinutes) -> totalMinutes < employee.getMinWeeklyMinutes())
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM,
                        (employee, week, totalMinutes) -> employee.getMinWeeklyMinutes() - totalMinutes.intValue())
                .asConstraint(ConstraintIdSanitizer.sanitize("Min weekly hours per employee (MEDIUM)"));
    }

    // Min weekly hours per employee (SOFT)
    Constraint minWeeklyHoursSoft(ConstraintFactory constraintFactory) {
        return weeklyAssignedMinutes(constraintFactory)
                .filter((employee, week, totalMinutes) -> employee.getMinWeeklyMinutes() != null
                        && employee.getMinWeeklyMinutes() > 0
                        && hasSeverity(employee.getMinWeeklySeverity(), "SOFT"))
                .filter((employee, week, totalMinutes) -> totalMinutes < employee.getMinWeeklyMinutes())
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT,
                        (employee, week, totalMinutes) -> employee.getMinWeeklyMinutes() - totalMinutes.intValue())
                .asConstraint(ConstraintIdSanitizer.sanitize("Min weekly hours per employee (SOFT)"));
    }

    // Goal: target shifts per week per individual employee (HARD)
    Constraint goalShiftsPerWeekPerEmployeeHard(ConstraintFactory constraintFactory) {
        return weeklyAssignedShiftCounts(constraintFactory)
                .filter((employee, week, shiftCount) -> employee.getTargetShiftsPerWeek() != null
                        && employee.getTargetShiftsPerWeek() > 0
                        && "HARD".equalsIgnoreCase(employee.getTargetShiftsPerWeekSeverity()))
                .filter((employee, week, shiftCount) ->
                        shiftCount.intValue() != employee.getTargetShiftsPerWeek().intValue())
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD,
                        (employee, week, shiftCount) ->
                                Math.abs(shiftCount.intValue() - employee.getTargetShiftsPerWeek().intValue()))
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week - individual (HARD)"));
    }

    // Goal: target shifts per week per individual employee (MEDIUM)
    Constraint goalShiftsPerWeekPerEmployeeMedium(ConstraintFactory constraintFactory) {
        return weeklyAssignedShiftCounts(constraintFactory)
                .filter((employee, week, shiftCount) -> employee.getTargetShiftsPerWeek() != null
                        && employee.getTargetShiftsPerWeek() > 0
                        && hasSeverity(employee.getTargetShiftsPerWeekSeverity(), "MEDIUM"))
                .filter((employee, week, shiftCount) ->
                        shiftCount.intValue() != employee.getTargetShiftsPerWeek().intValue())
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM,
                        (employee, week, shiftCount) ->
                                Math.abs(shiftCount.intValue() - employee.getTargetShiftsPerWeek().intValue()))
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week - individual (MEDIUM)"));
    }

    // Goal: target shifts per week per individual employee (SOFT)
    Constraint goalShiftsPerWeekPerEmployeeSoft(ConstraintFactory constraintFactory) {
        return weeklyAssignedShiftCounts(constraintFactory)
                .filter((employee, week, shiftCount) -> employee.getTargetShiftsPerWeek() != null
                        && employee.getTargetShiftsPerWeek() > 0
                        && "SOFT".equalsIgnoreCase(employee.getTargetShiftsPerWeekSeverity()))
                .filter((employee, week, shiftCount) ->
                        shiftCount.intValue() != employee.getTargetShiftsPerWeek().intValue())
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT,
                        (employee, week, shiftCount) ->
                                Math.abs(shiftCount.intValue() - employee.getTargetShiftsPerWeek().intValue()))
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week - individual (SOFT)"));
    }

    // Zero-shift companions: employees with no assignments in a scheduled week

    Constraint goalShiftsPerWeekPerEmployeeHardZero(ConstraintFactory constraintFactory) {
        return employeeWeeks(constraintFactory)
                .filter((e, week) -> e.getTargetShiftsPerWeek() != null
                        && e.getTargetShiftsPerWeek() > 0
                        && "HARD".equalsIgnoreCase(e.getTargetShiftsPerWeekSeverity()))
                .ifNotExists(Shift.class,
                        equal((employee, week) -> employee, Shift::getEmployee),
                        filtering((employee, week, shift) -> overlapsWeek(shift, week)))
                .map((employee, week) -> employee)
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD, Employee::getTargetShiftsPerWeek)
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week - individual zero (HARD)"));
    }

    Constraint goalShiftsPerWeekPerEmployeeMediumZero(ConstraintFactory constraintFactory) {
        return employeeWeeks(constraintFactory)
                .filter((e, week) -> e.getTargetShiftsPerWeek() != null
                        && e.getTargetShiftsPerWeek() > 0
                        && hasSeverity(e.getTargetShiftsPerWeekSeverity(), "MEDIUM"))
                .ifNotExists(Shift.class,
                        equal((employee, week) -> employee, Shift::getEmployee),
                        filtering((employee, week, shift) -> overlapsWeek(shift, week)))
                .map((employee, week) -> employee)
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM, Employee::getTargetShiftsPerWeek)
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week - individual zero (MEDIUM)"));
    }

    Constraint goalShiftsPerWeekPerEmployeeSoftZero(ConstraintFactory constraintFactory) {
        return employeeWeeks(constraintFactory)
                .filter((e, week) -> e.getTargetShiftsPerWeek() != null
                        && e.getTargetShiftsPerWeek() > 0
                        && hasSeverity(e.getTargetShiftsPerWeekSeverity(), "SOFT"))
                .ifNotExists(Shift.class,
                        equal((employee, week) -> employee, Shift::getEmployee),
                        filtering((employee, week, shift) -> overlapsWeek(shift, week)))
                .map((employee, week) -> employee)
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT, Employee::getTargetShiftsPerWeek)
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week - individual zero (SOFT)"));
    }

    Constraint minWeeklyHoursHardZero(ConstraintFactory constraintFactory) {
        return employeeWeeks(constraintFactory)
                .filter((e, week) -> e.getMinWeeklyMinutes() != null
                        && e.getMinWeeklyMinutes() > 0
                        && "HARD".equalsIgnoreCase(e.getMinWeeklySeverity()))
                .ifNotExists(Shift.class,
                        equal((employee, week) -> employee, Shift::getEmployee),
                        filtering((employee, week, shift) -> overlapsWeek(shift, week)))
                .map((employee, week) -> employee)
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD, Employee::getMinWeeklyMinutes)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min weekly hours per employee zero (HARD)"));
    }

    Constraint minWeeklyHoursMediumZero(ConstraintFactory constraintFactory) {
        return employeeWeeks(constraintFactory)
                .filter((e, week) -> e.getMinWeeklyMinutes() != null
                        && e.getMinWeeklyMinutes() > 0
                        && hasSeverity(e.getMinWeeklySeverity(), "MEDIUM"))
                .ifNotExists(Shift.class,
                        equal((employee, week) -> employee, Shift::getEmployee),
                        filtering((employee, week, shift) -> overlapsWeek(shift, week)))
                .map((employee, week) -> employee)
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM, Employee::getMinWeeklyMinutes)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min weekly hours per employee zero (MEDIUM)"));
    }

    Constraint minWeeklyHoursSoftZero(ConstraintFactory constraintFactory) {
        return employeeWeeks(constraintFactory)
                .filter((e, week) -> e.getMinWeeklyMinutes() != null
                        && e.getMinWeeklyMinutes() > 0
                        && hasSeverity(e.getMinWeeklySeverity(), "SOFT"))
                .ifNotExists(Shift.class,
                        equal((employee, week) -> employee, Shift::getEmployee),
                        filtering((employee, week, shift) -> overlapsWeek(shift, week)))
                .map((employee, week) -> employee)
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT, Employee::getMinWeeklyMinutes)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min weekly hours per employee zero (SOFT)"));
    }

    Constraint goalShiftsPerWeekHardZero(ConstraintFactory constraintFactory) {
        return employeeWeeks(constraintFactory)
                .ifNotExists(Shift.class,
                        equal((employee, week) -> employee, Shift::getEmployee),
                        filtering((employee, week, shift) -> overlapsWeek(shift, week)))
                .map((employee, week) -> employee)
                .join(ConstraintConfiguration.class)
                .filter((employee, cfg) -> cfg.getTargetShiftsPerWeek() > 0
                        && cfg.getTargetShiftsPerWeekSeverity() == ConstraintConfiguration.Severity.HARD)
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD,
                        (employee, cfg) -> cfg.getTargetShiftsPerWeek())
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week zero (HARD)"));
    }

    Constraint goalShiftsPerWeekMediumZero(ConstraintFactory constraintFactory) {
        return employeeWeeks(constraintFactory)
                .ifNotExists(Shift.class,
                        equal((employee, week) -> employee, Shift::getEmployee),
                        filtering((employee, week, shift) -> overlapsWeek(shift, week)))
                .map((employee, week) -> employee)
                .join(ConstraintConfiguration.class)
                .filter((employee, cfg) -> cfg.getTargetShiftsPerWeek() > 0
                        && cfg.getTargetShiftsPerWeekSeverity() == ConstraintConfiguration.Severity.MEDIUM)
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM,
                        (employee, cfg) -> cfg.getTargetShiftsPerWeek())
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week zero (MEDIUM)"));
    }

    Constraint goalShiftsPerWeekSoftZero(ConstraintFactory constraintFactory) {
        return employeeWeeks(constraintFactory)
                .ifNotExists(Shift.class,
                        equal((employee, week) -> employee, Shift::getEmployee),
                        filtering((employee, week, shift) -> overlapsWeek(shift, week)))
                .map((employee, week) -> employee)
                .join(ConstraintConfiguration.class)
                .filter((employee, cfg) -> cfg.getTargetShiftsPerWeek() > 0
                        && cfg.getTargetShiftsPerWeekSeverity() == ConstraintConfiguration.Severity.SOFT)
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT,
                        (employee, cfg) -> cfg.getTargetShiftsPerWeek())
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week zero (SOFT)"));
    }

    Constraint goalMinutesPerWeekHardZero(ConstraintFactory constraintFactory) {
        return employeeWeeks(constraintFactory)
                .ifNotExists(Shift.class,
                        equal((employee, week) -> employee, Shift::getEmployee),
                        filtering((employee, week, shift) -> overlapsWeek(shift, week)))
                .map((employee, week) -> employee)
                .join(ConstraintConfiguration.class)
                .filter((employee, cfg) -> cfg.getTargetMinutesPerWeek() > 0
                        && cfg.getTargetMinutesPerWeekSeverity() == ConstraintConfiguration.Severity.HARD)
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD,
                        (employee, cfg) -> cfg.getTargetMinutesPerWeek())
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target minutes per employee per week zero (HARD)"));
    }

    Constraint goalMinutesPerWeekMediumZero(ConstraintFactory constraintFactory) {
        return employeeWeeks(constraintFactory)
                .ifNotExists(Shift.class,
                        equal((employee, week) -> employee, Shift::getEmployee),
                        filtering((employee, week, shift) -> overlapsWeek(shift, week)))
                .map((employee, week) -> employee)
                .join(ConstraintConfiguration.class)
                .filter((employee, cfg) -> cfg.getTargetMinutesPerWeek() > 0
                        && cfg.getTargetMinutesPerWeekSeverity() == ConstraintConfiguration.Severity.MEDIUM)
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM,
                        (employee, cfg) -> cfg.getTargetMinutesPerWeek())
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target minutes per employee per week zero (MEDIUM)"));
    }

    Constraint goalMinutesPerWeekSoftZero(ConstraintFactory constraintFactory) {
        return employeeWeeks(constraintFactory)
                .ifNotExists(Shift.class,
                        equal((employee, week) -> employee, Shift::getEmployee),
                        filtering((employee, week, shift) -> overlapsWeek(shift, week)))
                .map((employee, week) -> employee)
                .join(ConstraintConfiguration.class)
                .filter((employee, cfg) -> cfg.getTargetMinutesPerWeek() > 0
                        && cfg.getTargetMinutesPerWeekSeverity() == ConstraintConfiguration.Severity.SOFT)
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT,
                        (employee, cfg) -> cfg.getTargetMinutesPerWeek())
                .asConstraint(ConstraintIdSanitizer.sanitize("Goal: target minutes per employee per week zero (SOFT)"));
    }

    // Min concurrent employees with a given skill (HARD)
    // Case A: at least one qualified shift exists but count is below the minimum.
    Constraint minConcurrentSkillHard(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(ConcurrentSkillRequirement.class)
                .filter(req -> req.getMinCount() > 0 && "HARD".equalsIgnoreCase(req.getSeverity()))
                .join(Shift.class,
                        overlapping(ConcurrentSkillRequirement::getWindowStart, ConcurrentSkillRequirement::getWindowEnd,
                                Shift::getStart, Shift::getEnd))
                .filter((req, shift) -> shift.getEmployee() != null
                        && shift.getEmployee().getSkills().contains(req.getSkill()))
                .groupBy((req, shift) -> req, ConstraintCollectors.countBi())
                .filter((req, count) -> count < req.getMinCount())
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD,
                        (req, count) -> req.getMinCount() - count)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min concurrent skill (HARD)"));
    }

    // Min concurrent employees with a given skill – zero qualified shifts (HARD)
    // Case B: no qualified shift overlaps the window at all — full shortfall = minCount.
    Constraint minConcurrentSkillZeroHard(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(ConcurrentSkillRequirement.class)
                .filter(req -> req.getMinCount() > 0 && "HARD".equalsIgnoreCase(req.getSeverity()))
                .ifNotExists(Shift.class,
                        overlapping(ConcurrentSkillRequirement::getWindowStart, ConcurrentSkillRequirement::getWindowEnd,
                                Shift::getStart, Shift::getEnd),
                        filtering((req, shift) -> shift.getEmployee() != null
                                && shift.getEmployee().getSkills().contains(req.getSkill())))
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD,
                        ConcurrentSkillRequirement::getMinCount)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min concurrent skill zero (HARD)"));
    }

    // Min concurrent employees with a given skill (MEDIUM)
    // Case A: at least one qualified shift exists but count is below the minimum.
    Constraint minConcurrentSkillMedium(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(ConcurrentSkillRequirement.class)
                .filter(req -> req.getMinCount() > 0 && hasSeverity(req.getSeverity(), "MEDIUM"))
                .join(Shift.class,
                        overlapping(ConcurrentSkillRequirement::getWindowStart, ConcurrentSkillRequirement::getWindowEnd,
                                Shift::getStart, Shift::getEnd))
                .filter((req, shift) -> shift.getEmployee() != null
                        && shift.getEmployee().getSkills().contains(req.getSkill()))
                .groupBy((req, shift) -> req, ConstraintCollectors.countBi())
                .filter((req, count) -> count < req.getMinCount())
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM,
                        (req, count) -> req.getMinCount() - count)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min concurrent skill (MEDIUM)"));
    }

    // Min concurrent employees with a given skill – zero qualified shifts (MEDIUM)
    // Case B: no qualified shift overlaps the window at all — full shortfall = minCount.
    Constraint minConcurrentSkillZeroMedium(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(ConcurrentSkillRequirement.class)
                .filter(req -> req.getMinCount() > 0 && hasSeverity(req.getSeverity(), "MEDIUM"))
                .ifNotExists(Shift.class,
                        overlapping(ConcurrentSkillRequirement::getWindowStart, ConcurrentSkillRequirement::getWindowEnd,
                                Shift::getStart, Shift::getEnd),
                        filtering((req, shift) -> shift.getEmployee() != null
                                && shift.getEmployee().getSkills().contains(req.getSkill())))
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM,
                        ConcurrentSkillRequirement::getMinCount)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min concurrent skill zero (MEDIUM)"));
    }

    // Min concurrent employees with a given skill (SOFT)
    // Case A: at least one qualified shift exists but count is below the minimum.
    Constraint minConcurrentSkillSoft(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(ConcurrentSkillRequirement.class)
                .filter(req -> req.getMinCount() > 0 && hasSeverity(req.getSeverity(), "SOFT"))
                .join(Shift.class,
                        overlapping(ConcurrentSkillRequirement::getWindowStart, ConcurrentSkillRequirement::getWindowEnd,
                                Shift::getStart, Shift::getEnd))
                .filter((req, shift) -> shift.getEmployee() != null
                        && shift.getEmployee().getSkills().contains(req.getSkill()))
                .groupBy((req, shift) -> req, ConstraintCollectors.countBi())
                .filter((req, count) -> count < req.getMinCount())
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT,
                        (req, count) -> req.getMinCount() - count)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min concurrent skill (SOFT)"));
    }

    // Min concurrent employees with a given skill – zero qualified shifts (SOFT)
    // Case B: no qualified shift overlaps the window at all — full shortfall = minCount.
    Constraint minConcurrentSkillZeroSoft(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(ConcurrentSkillRequirement.class)
                .filter(req -> req.getMinCount() > 0 && "SOFT".equalsIgnoreCase(req.getSeverity()))
                .ifNotExists(Shift.class,
                        overlapping(ConcurrentSkillRequirement::getWindowStart, ConcurrentSkillRequirement::getWindowEnd,
                                Shift::getStart, Shift::getEnd),
                        filtering((req, shift) -> shift.getEmployee() != null
                                && shift.getEmployee().getSkills().contains(req.getSkill())))
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT,
                        ConcurrentSkillRequirement::getMinCount)
                .asConstraint(ConstraintIdSanitizer.sanitize("Min concurrent skill zero (SOFT)"));
    }

    // Max concurrent employees with a given skill (HARD)
    Constraint maxConcurrentSkillHard(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(ConcurrentSkillRequirement.class)
                .filter(req -> req.getMaxCount() >= 0 && "HARD".equalsIgnoreCase(req.getSeverity()))
                .join(Shift.class,
                        overlapping(ConcurrentSkillRequirement::getWindowStart, ConcurrentSkillRequirement::getWindowEnd,
                                Shift::getStart, Shift::getEnd))
                .filter((req, shift) -> shift.getEmployee() != null
                        && shift.getEmployee().getSkills().contains(req.getSkill()))
                .groupBy((req, shift) -> req, ConstraintCollectors.countBi())
                .filter((req, count) -> count > req.getMaxCount())
                .penalize(HardMediumSoftBigDecimalScore.ONE_HARD,
                        (req, count) -> count - req.getMaxCount())
                .asConstraint(ConstraintIdSanitizer.sanitize("Max concurrent skill (HARD)"));
    }

    // Max concurrent employees with a given skill (SOFT)
    Constraint maxConcurrentSkillSoft(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(ConcurrentSkillRequirement.class)
                .filter(req -> req.getMaxCount() >= 0 && hasSeverity(req.getSeverity(), "SOFT"))
                .join(Shift.class,
                        overlapping(ConcurrentSkillRequirement::getWindowStart, ConcurrentSkillRequirement::getWindowEnd,
                                Shift::getStart, Shift::getEnd))
                .filter((req, shift) -> shift.getEmployee() != null
                        && shift.getEmployee().getSkills().contains(req.getSkill()))
                .groupBy((req, shift) -> req, ConstraintCollectors.countBi())
                .filter((req, count) -> count > req.getMaxCount())
                .penalize(HardMediumSoftBigDecimalScore.ONE_SOFT,
                        (req, count) -> count - req.getMaxCount())
                .asConstraint(ConstraintIdSanitizer.sanitize("Max concurrent skill (SOFT)"));
    }

    // Max concurrent employees with a given skill (MEDIUM)
    Constraint maxConcurrentSkillMedium(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(ConcurrentSkillRequirement.class)
                .filter(req -> req.getMaxCount() >= 0 && hasSeverity(req.getSeverity(), "MEDIUM"))
                .join(Shift.class,
                        overlapping(ConcurrentSkillRequirement::getWindowStart, ConcurrentSkillRequirement::getWindowEnd,
                                Shift::getStart, Shift::getEnd))
                .filter((req, shift) -> shift.getEmployee() != null
                        && shift.getEmployee().getSkills().contains(req.getSkill()))
                .groupBy((req, shift) -> req, ConstraintCollectors.countBi())
                .filter((req, count) -> count > req.getMaxCount())
                .penalize(HardMediumSoftBigDecimalScore.ONE_MEDIUM,
                        (req, count) -> count - req.getMaxCount())
                .asConstraint(ConstraintIdSanitizer.sanitize("Max concurrent skill (MEDIUM)"));
    }

}
