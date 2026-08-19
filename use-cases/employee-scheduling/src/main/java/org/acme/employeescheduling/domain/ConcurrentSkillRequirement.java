package org.acme.employeescheduling.domain;

import java.time.LocalDateTime;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Problem fact that constrains how many employees with a given skill may be
 * working concurrently (i.e. their assigned shifts overlap with the time window
 * [windowStart, windowEnd]).
 *
 * <ul>
 *   <li>{@code minCount} – minimum number of concurrent employees with the skill.
 *       Set to {@code 0} to disable the minimum.</li>
 *   <li>{@code maxCount} – maximum number of concurrent employees with the skill.
 *       Set to {@code -1} to disable the maximum.</li>
 *   <li>{@code severity} – {@code "HARD"}, {@code "MEDIUM"}, or {@code "SOFT"}.</li>
 * </ul>
 */
public class ConcurrentSkillRequirement {

    private String id;

    /** The skill to count (must match values in {@link Employee#getSkills()}). */
    private String skill;

    /** Start of the time window to evaluate. */
    private LocalDateTime windowStart;

    /** End of the time window to evaluate. */
    private LocalDateTime windowEnd;

    /** Minimum concurrent employees with the skill. 0 = disabled. */
    private int minCount = 0;

    /** Maximum concurrent employees with the skill. -1 = disabled. */
    private int maxCount = -1;

    /** "HARD", "MEDIUM", or "SOFT". */
    private String severity = "HARD";

    public ConcurrentSkillRequirement() {
    }

    @JsonCreator
    public ConcurrentSkillRequirement(
            @JsonProperty("id") String id,
            @JsonProperty("skill") String skill,
            @JsonProperty("windowStart") LocalDateTime windowStart,
            @JsonProperty("windowEnd") LocalDateTime windowEnd,
            @JsonProperty("minCount") int minCount,
            @JsonProperty("maxCount") int maxCount,
            @JsonProperty("severity") String severity) {
        this.id = Objects.requireNonNull(id, "id");
        this.skill = Objects.requireNonNull(skill, "skill");
        this.windowStart = Objects.requireNonNull(windowStart, "windowStart");
        this.windowEnd = Objects.requireNonNull(windowEnd, "windowEnd");
        this.minCount = minCount;
        this.maxCount = maxCount;
        this.severity = severity != null ? severity : "HARD";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSkill() {
        return skill;
    }

    public void setSkill(String skill) {
        this.skill = skill;
    }

    public LocalDateTime getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(LocalDateTime windowStart) {
        this.windowStart = windowStart;
    }

    public LocalDateTime getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(LocalDateTime windowEnd) {
        this.windowEnd = windowEnd;
    }

    public int getMinCount() {
        return minCount;
    }

    public void setMinCount(int minCount) {
        this.minCount = minCount;
    }

    public int getMaxCount() {
        return maxCount;
    }

    public void setMaxCount(int maxCount) {
        this.maxCount = maxCount;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConcurrentSkillRequirement)) return false;
        ConcurrentSkillRequirement that = (ConcurrentSkillRequirement) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ConcurrentSkillRequirement{id='" + id + "', skill='" + skill
                + "', window=" + windowStart + "-" + windowEnd
                + ", min=" + minCount + ", max=" + maxCount
                + ", severity='" + severity + "'}";
    }
}
