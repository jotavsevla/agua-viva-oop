package com.aguaviva.solver;

import java.io.IOException;

public interface SolverGateway {

    SolverResponse solve(SolverRequest request) throws IOException, InterruptedException;

    void cancelBestEffort(String jobId);
}
