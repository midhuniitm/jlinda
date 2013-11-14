package org.jlinda.core.unwrapping.mcf;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.winvector.linalg.DenseVec;
import com.winvector.linalg.LinalgFactory;
import com.winvector.linalg.Matrix;
import com.winvector.linalg.colt.ColtMatrix;
import com.winvector.linalg.colt.NativeMatrix;
import com.winvector.linalg.jblas.JBlasMatrix;
import com.winvector.lp.LPEQProb;
import com.winvector.lp.LPException;
import com.winvector.lp.LPSoln;
import com.winvector.lp.impl.RevisedSimplexSolver;
import org.jblas.DoubleMatrix;
import org.jlinda.core.Constants;
import org.jlinda.core.unwrapping.mcf.utils.SimulateData;
import org.jlinda.core.unwrapping.mcf.utils.UnwrapUtils;
import org.perf4j.StopWatch;
import org.slf4j.LoggerFactory;

import static org.jblas.DoubleMatrix.concatHorizontally;
import static org.jlinda.core.unwrapping.mcf.utils.JblasUtils.*;

/**
 * Description: Implementation of MCF ~ Linear Programming Unwrapping. Based on work of Costantini.
 */
public class Unwrapper {

    private static final Logger logger = (Logger) LoggerFactory.getLogger(Unwrapper.class);

    // Parameters for Linear Programming estimation/solution
    final double tol = 1.0e-4;
    final int maxRounds = 10000;

    private DoubleMatrix wrappedPhase;
    private DoubleMatrix unwrappedPhase;

    private String factoryName = "test";
    private LinalgFactory<?> factory;

    public Unwrapper(DoubleMatrix wrappedPhase) {
        logger.setLevel(Level.TRACE);

        this.wrappedPhase = wrappedPhase;

        switch (factoryName) {
            case ("jblas"):
                factory = JBlasMatrix.factory;
                break;
            case ("colt"):
                factory = ColtMatrix.factory;
                break;
            case ("native"):
                factory = NativeMatrix.factory;
                break;
            default:
                factory = JBlasMatrix.factory;
        }
    }

    public void setWrappedPhase(DoubleMatrix wrappedPhase) {
        this.wrappedPhase = wrappedPhase;
    }

    public DoubleMatrix getUnwrappedPhase() {
        return unwrappedPhase;
    }

    // for now only one method - this should be facade like call
    public void unwrap() {
        try {
            costantiniUnwrap();
        } catch (LPException ex) {
            ex.printStackTrace();
        }
    }

    private void costantiniUnwrap() throws LPException {

        final int ny = wrappedPhase.rows - 1; // start from Zero!
        final int nx = wrappedPhase.columns - 1; // start from Zero!

        if (wrappedPhase.isVector()) throw new IllegalArgumentException("Input must be 2D array");
        if (wrappedPhase.rows < 2 || wrappedPhase.columns < 2)
            throw new IllegalArgumentException("Size of input must be larger than 2");

        // Default weight
        DoubleMatrix w1 = DoubleMatrix.ones(ny + 1, 1);
        w1.put(0, 0.5);
        w1.put(w1.length - 1, 0.5);
        DoubleMatrix w2 = DoubleMatrix.ones(1, nx + 1);
        w2.put(0, 0.5);
        w2.put(w2.length - 1, 0.5);
        DoubleMatrix weight = w1.mmul(w2);

        // Compute partial derivative Psi1, eqt (1,3)
        DoubleMatrix i, j, I_J, IP1_J, I_JP1;
        DoubleMatrix Psi1, Psi2;
        DoubleMatrix[] ROWS;

        i = intRangeDoubleMatrix(0, ny - 1);
        j = intRangeDoubleMatrix(0, nx);
        ROWS = grid2D(i, j);
        I_J = sub2ind(wrappedPhase.rows, ROWS[0], ROWS[1]);
        IP1_J = sub2ind(wrappedPhase.rows, ROWS[0].add(1), ROWS[1]);
        Psi1 = getMatrixFromIdx(wrappedPhase, IP1_J).sub(getMatrixFromIdx(wrappedPhase, I_J));
        Psi1 = UnwrapUtils.wrapDoubleMatrix(Psi1);

        // Compute partial derivative Psi2, eqt (2,4)
        i = intRangeDoubleMatrix(0, ny);
        j = intRangeDoubleMatrix(0, nx - 1);
        ROWS = grid2D(i, j);
        I_J = sub2ind(wrappedPhase.rows, ROWS[0], ROWS[1]);
        I_JP1 = sub2ind(wrappedPhase.rows, ROWS[0], ROWS[1].add(1));
        Psi2 = getMatrixFromIdx(wrappedPhase, I_JP1).sub(getMatrixFromIdx(wrappedPhase, I_J));
        Psi2 = UnwrapUtils.wrapDoubleMatrix(Psi2);

        // Compute beq
        DoubleMatrix beq = DoubleMatrix.zeros(ny, nx);
        i = intRangeDoubleMatrix(0, ny - 1);
        j = intRangeDoubleMatrix(0, nx - 1);
        ROWS = grid2D(i, j);
        I_J = sub2ind(Psi1.rows, ROWS[0], ROWS[1]);
        I_JP1 = sub2ind(Psi1.rows, ROWS[0], ROWS[1].add(1));
        beq.addi(getMatrixFromIdx(Psi1, I_JP1).sub(getMatrixFromIdx(Psi1, I_J)));
        I_J = sub2ind(Psi2.rows, ROWS[0], ROWS[1]);
        I_JP1 = sub2ind(Psi2.rows, ROWS[0].add(1), ROWS[1]);
        beq.subi(getMatrixFromIdx(Psi2, I_JP1).sub(getMatrixFromIdx(Psi2, I_J)));
        beq.muli(-1 / (2 * Constants._PI));
        for (int k = 0; k < beq.length; k++) {
            beq.put(k, Math.round(beq.get(k)));
        }
        beq.reshape(beq.length, 1);

        logger.debug("Constraint matrix");
        i = intRangeDoubleMatrix(0, ny - 1);
        j = intRangeDoubleMatrix(0, nx - 1);
        ROWS = grid2D(i, j);
        DoubleMatrix ROW_I_J = sub2ind(i.length, ROWS[0], ROWS[1]);
        int nS0 = nx * ny;

        // Use by S1p, S1m
        DoubleMatrix[] COLS;
        COLS = grid2D(i, j);
        DoubleMatrix COL_IJ_1 = sub2ind(i.length, COLS[0], COLS[1]);
        COLS = grid2D(i, j.add(1));
        DoubleMatrix COL_I_JP1 = sub2ind(i.length, COLS[0], COLS[1]);
        int nS1 = (nx + 1) * (ny);

        // SOAPBinding.Use by S2p, S2m
        COLS = grid2D(i, j);
        DoubleMatrix COL_IJ_2 = sub2ind(i.length + 1, COLS[0], COLS[1]);
        COLS = grid2D(i.add(1), j);
        DoubleMatrix COL_IP1_J = sub2ind(i.length + 1, COLS[0], COLS[1]);
        int nS2 = nx * (ny + 1);

        // Equality constraint matrix (Aeq)
        /*
            S1p = + sparse(ROW_I_J, COL_I_JP1,1,nS0,nS1) ...
                  - sparse(ROW_I_J, COL_IJ_1,1,nS0,nS1);
            S1m = -S1p;

            S2p = - sparse(ROW_I_J, COL_IP1_J,1,nS0,nS2) ...
                  + sparse(ROW_I_J, COL_IJ_2,1,nS0,nS2);
            S2m = -S2p;
        */

        // ToDo: Aeq matrix should be sparse from it's initialization, look into JblasMatrix factory for howto
        // ...otherwise even a data set of eg 40x40 pixels will exhaust heap:
        // ...    dimension of Aeq (equality constraints) matrix for 30x30 input is 1521x6240 matrix
        // ...    dimension of Aeq (                    ) matrix for 50x50 input is 2401x9800
        // ...    dimension of Aeq (                    ) matrix for 512x512 input is 261121x1046528
        DoubleMatrix S1p = setUpMatrixFromIdx(nS0, nS1, ROW_I_J, COL_I_JP1).sub(setUpMatrixFromIdx(nS0, nS1, ROW_I_J, COL_IJ_1));
        DoubleMatrix S1m = S1p.neg();

        DoubleMatrix S2p = setUpMatrixFromIdx(nS0, nS2, ROW_I_J, COL_IP1_J).neg().add(setUpMatrixFromIdx(nS0, nS2, ROW_I_J, COL_IJ_2));
        DoubleMatrix S2m = S2p.neg();

        DoubleMatrix Aeq = concatHorizontally(concatHorizontally(S1p, S1m), concatHorizontally(S2p, S2m));

        final int nObs = Aeq.columns;
        final int nUnkn = Aeq.rows;

        DoubleMatrix c1 = getMatrixFromRange(0, ny, 0, weight.columns, weight);
        DoubleMatrix c2 = getMatrixFromRange(0, weight.rows, 0, nx, weight);

        c1.reshape(c1.length, 1);
        c2.reshape(c2.length, 1);

        DoubleMatrix cost = DoubleMatrix.concatVertically(DoubleMatrix.concatVertically(c1, c1), DoubleMatrix.concatVertically(c2, c2));

        logger.debug("Minimum network flow resolution");

        StopWatch clockProb = new StopWatch();
        final Matrix<?> m = factory.newMatrix(nUnkn, nObs, true);

        // repackage matrix from Jblas to Colt Sparse
        for (int k = 0; k < nUnkn; k++) {
            for (int l = 0; l < nObs; l++) {
                if (Aeq.get(k, l) != 0) {
                    m.set(k, l, Aeq.get(k, l));
                }
            }
        }

        final LPEQProb prob = new LPEQProb(m.columnMatrix(), beq.data, new DenseVec(cost.data));
//        prob.printCPLEX(System.out);
        clockProb.stop();
        logger.debug("Total setup time: {} [sec]", (double) (clockProb.getElapsedTime()) / 1000);

        try {
            final RevisedSimplexSolver solver = new RevisedSimplexSolver();
            final LPSoln soln = solver.solve(prob, null, tol, maxRounds, factory);
            logger.debug("Total unwrapping time: {} [sec]", (double) (soln.reportedRunTimeMS) / 1000);
        } catch (LPException.LPTooManyStepsException e) {
            e.printStackTrace();
        }

    }

    public static void main(String[] args) throws LPException {

        final int rows = 16;
        final int cols = rows;

        logger.trace("Start Unwrapping");
        logger.info("Simulate Data");
        SimulateData simulateData = new SimulateData(rows, cols);
        simulateData.peaks();

        DoubleMatrix Phi = simulateData.getSimulatedData();
        DoubleMatrix Psi = UnwrapUtils.wrapDoubleMatrix(Phi);

        StopWatch clockFull = new StopWatch();
        clockFull.start();

        Unwrapper unwrapper = new Unwrapper(Psi);
        unwrapper.unwrap();

        clockFull.stop();
        logger.info("Total processing time {} [sec]", (double) (clockFull.getElapsedTime()) / 1000);
    }

}