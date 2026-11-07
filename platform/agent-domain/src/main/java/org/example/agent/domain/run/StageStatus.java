package org.example.agent.domain.run;

/** Stage 生命周期（契约见《规约：流水线执行器 v1.0》§1.1）。 */
public enum StageStatus {
    PENDING,
    PREPARING,
    RUNNING,
    COLLECTING,
    SUCCEEDED,
    FAILED,
    TIMEOUT,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == TIMEOUT || this == CANCELLED;
    }
}
