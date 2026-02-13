package com.aguaviva.solver;

public final class SolverJobResult {

    private final String jobId;
    private final String status;
    private final SolverResponse response;
    private final String erro;

    public SolverJobResult(String jobId, String status, SolverResponse response, String erro) {
        this.jobId = jobId;
        this.status = status;
        this.response = response;
        this.erro = erro;
    }

    public String getJobId() {
        return jobId;
    }

    public String getStatus() {
        return status;
    }

    public SolverResponse getResponse() {
        return response;
    }

    public String getErro() {
        return erro;
    }
}
