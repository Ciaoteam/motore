package org.acme.employeescheduling.domain;

import java.util.List;
import java.util.ArrayList;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.ProblemFactProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.HardMediumSoftBigDecimalScore;
import ai.timefold.solver.core.api.solver.SolverStatus;

@PlanningSolution
public class EmployeeSchedule {

    @ProblemFactCollectionProperty
    @ValueRangeProvider
    private List<Employee> employees;

    @PlanningEntityCollectionProperty
    private List<Shift> shifts;

    @ProblemFactCollectionProperty
    private List<MustWorkTogether> mustWorkTogetherList = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<ConcurrentSkillRequirement> concurrentSkillRequirements = new ArrayList<>();

    @ProblemFactProperty
    private ConstraintConfiguration constraintConfiguration = new ConstraintConfiguration();

    @PlanningScore
    private HardMediumSoftBigDecimalScore score;

    private SolverStatus solverStatus;

    // No-arg constructor required for Timefold
    public EmployeeSchedule() {}

    public EmployeeSchedule(List<Employee> employees, List<Shift> shifts) {
        this.employees = employees;
        this.shifts = shifts;
    }

    public EmployeeSchedule(HardMediumSoftBigDecimalScore score, SolverStatus solverStatus) {
        this.score = score;
        this.solverStatus = solverStatus;
    }

    public List<Employee> getEmployees() {
        return employees;
    }

    public void setEmployees(List<Employee> employees) {
        this.employees = employees;
    }

    public List<Shift> getShifts() {
        return shifts;
    }

    public void setShifts(List<Shift> shifts) {
        this.shifts = shifts;
    }

    public List<MustWorkTogether> getMustWorkTogetherList() {
        return mustWorkTogetherList;
    }

    public void setMustWorkTogetherList(List<MustWorkTogether> mustWorkTogetherList) {
        this.mustWorkTogetherList = mustWorkTogetherList;
    }

    public List<ConcurrentSkillRequirement> getConcurrentSkillRequirements() {
        return concurrentSkillRequirements;
    }

    public void setConcurrentSkillRequirements(List<ConcurrentSkillRequirement> concurrentSkillRequirements) {
        this.concurrentSkillRequirements = concurrentSkillRequirements;
    }

    public ConstraintConfiguration getConstraintConfiguration() {
        return constraintConfiguration;
    }

    public void setConstraintConfiguration(ConstraintConfiguration constraintConfiguration) {
        this.constraintConfiguration = constraintConfiguration;
    }

    public HardMediumSoftBigDecimalScore getScore() {
        return score;
    }

    public void setScore(HardMediumSoftBigDecimalScore score) {
        this.score = score;
    }

    public SolverStatus getSolverStatus() {
        return solverStatus;
    }

    public void setSolverStatus(SolverStatus solverStatus) {
        this.solverStatus = solverStatus;
    }
}
