package com.aguaviva.solver;

public final class SolverAsyncAccepted {

    private final String jobId;
    private final String status;

    public SolverAsyncAccepted(String jobId, String status) {
        this.jobId = jobId;
        this.status = status;
    }

    public String getJobId() {
        return jobId;
    }

    public String getStatus() {
        return status;
    }
}
