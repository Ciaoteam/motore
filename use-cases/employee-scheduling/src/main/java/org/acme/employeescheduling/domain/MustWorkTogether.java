package org.acme.employeescheduling.domain;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.acme.employeescheduling.domain.ConstraintConfiguration.Severity;

/**
 * Problem fact: captures a co-scheduling relationship between two employees.
 *
 * <ul>
 *   <li>The existing hard/soft "work same shift" constraint is controlled via
 *       {@link ConstraintConfiguration#getMustWorkTogetherSeverity()} and applies to every
 *       overlapping shift the pair shares.</li>
 *   <li>The new <em>weekly target</em> fields ({@code targetShiftsPerWeek} and
 *       {@code weeklyTargetSeverity}) express how many shifts per week this specific pair should
 *       work together, and whether missing that target is a hard or soft violation.
 *       Set {@code targetShiftsPerWeek} to {@code 0} to disable the weekly goal for this pair.</li>
 * </ul>
 */
public class MustWorkTogether {

    private Employee employeeA;
    private Employee employeeB;

    /**
     * How many shifts per week employeeA and employeeB should work together.
     * 0 means no weekly target is set for this pair.
     */
    private int targetShiftsPerWeek = 0;

    /**
     * Whether missing the weekly co-shift target is HARD, SOFT, or NONE.
     * Ignored when {@code targetShiftsPerWeek} is 0.
     */
    private Severity weeklyTargetSeverity = Severity.SOFT;

    public MustWorkTogether() {
        // No-arg constructor for JSON deserialization
    }

    @JsonCreator
    public MustWorkTogether(@JsonProperty("employeeA") Employee employeeA,
                            @JsonProperty("employeeB") Employee employeeB,
                            @JsonProperty("targetShiftsPerWeek") int targetShiftsPerWeek,
                            @JsonProperty("weeklyTargetSeverity") Severity weeklyTargetSeverity) {
        this.employeeA = Objects.requireNonNull(employeeA, "employeeA");
        this.employeeB = Objects.requireNonNull(employeeB, "employeeB");
        this.targetShiftsPerWeek = targetShiftsPerWeek;
        this.weeklyTargetSeverity = weeklyTargetSeverity != null ? weeklyTargetSeverity : Severity.SOFT;
    }

    public Employee getEmployeeA() {
        return employeeA;
    }

    public void setEmployeeA(Employee employeeA) {
        this.employeeA = employeeA;
    }

    public Employee getEmployeeB() {
        return employeeB;
    }

    public void setEmployeeB(Employee employeeB) {
        this.employeeB = employeeB;
    }

    public int getTargetShiftsPerWeek() {
        return targetShiftsPerWeek;
    }

    public void setTargetShiftsPerWeek(int targetShiftsPerWeek) {
        this.targetShiftsPerWeek = targetShiftsPerWeek;
    }

    public Severity getWeeklyTargetSeverity() {
        return weeklyTargetSeverity;
    }

    public void setWeeklyTargetSeverity(Severity weeklyTargetSeverity) {
        this.weeklyTargetSeverity = weeklyTargetSeverity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MustWorkTogether)) return false;
        MustWorkTogether that = (MustWorkTogether) o;
        return Objects.equals(employeeA, that.employeeA) && Objects.equals(employeeB, that.employeeB);
    }

    @Override
    public int hashCode() {
        return Objects.hash(employeeA, employeeB);
    }

    @Override
    public String toString() {
        return "MustWorkTogether{" + employeeA + " <-> " + employeeB
                + ", targetShiftsPerWeek=" + targetShiftsPerWeek
                + ", weeklyTargetSeverity=" + weeklyTargetSeverity + '}';
    }
}
