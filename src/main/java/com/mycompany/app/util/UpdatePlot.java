package com.mycompany.app.util;

import com.mycompany.app.PerformanceTest;

import java.io.IOException;
import java.nio.file.Paths;

public class UpdatePlot {
    public static void main(String[] args) throws IOException {
        PerformanceTest.updatePlot(Paths.get(".\\target\\results\\plot"));
    }
}
