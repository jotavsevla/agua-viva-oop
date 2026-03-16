package com.aguaviva.solver;

public record SolverJobResult(String jobId, String status, SolverResponse response, String erro) {}
