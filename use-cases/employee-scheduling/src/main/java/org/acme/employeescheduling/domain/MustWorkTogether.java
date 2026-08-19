package org.acme.employeescheduling.domain;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Problem fact: when employeeA works a shift, employeeB must also work that same shift.
 * Optionally, a minimum number of overlapping shifts per week can be required via
 * {@code minShiftsTogetherPerWeek} (0 = disabled) and {@code minShiftsTogetherPerWeekSeverity}
 * ("HARD", "MEDIUM", or "SOFT", defaults to "SOFT").
 */
public class MustWorkTogether {

    private Employee employeeA;
    private Employee employeeB;

    /**
     * Minimum number of shifts that A and B must share (overlap) in the same ISO week.
     * 0 disables this goal. Configure severity via {@code minShiftsTogetherPerWeekSeverity}.
     */
    private int minShiftsTogetherPerWeek = 0;

    /**
     * Severity for the minimum-shifts-together-per-week goal: "HARD", "MEDIUM", or "SOFT" (default).
     */
    private String minShiftsTogetherPerWeekSeverity = "SOFT";

    public MustWorkTogether() {
        // No-arg constructor for JSON deserialization
    }

    @JsonCreator
    public MustWorkTogether(@JsonProperty("employeeA") Employee employeeA,
                            @JsonProperty("employeeB") Employee employeeB) {
        this.employeeA = Objects.requireNonNull(employeeA, "employeeA");
        this.employeeB = Objects.requireNonNull(employeeB, "employeeB");
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

    public int getMinShiftsTogetherPerWeek() {
        return minShiftsTogetherPerWeek;
    }

    public void setMinShiftsTogetherPerWeek(int minShiftsTogetherPerWeek) {
        this.minShiftsTogetherPerWeek = minShiftsTogetherPerWeek;
    }

    public String getMinShiftsTogetherPerWeekSeverity() {
        return minShiftsTogetherPerWeekSeverity;
    }

    public void setMinShiftsTogetherPerWeekSeverity(String minShiftsTogetherPerWeekSeverity) {
        this.minShiftsTogetherPerWeekSeverity = minShiftsTogetherPerWeekSeverity;
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
        return "MustWorkTogether{" + employeeA + " <-> " + employeeB + '}';
    }
}
