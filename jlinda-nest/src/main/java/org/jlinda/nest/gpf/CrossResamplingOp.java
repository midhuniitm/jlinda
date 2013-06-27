package org.jlinda.nest.gpf;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.bc.ceres.core.ProgressMonitor;
import org.apache.commons.lang.ArrayUtils;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.MetadataElement;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.nest.datamodel.AbstractMetadata;
import org.esa.nest.datamodel.Unit;
import org.esa.nest.gpf.OperatorUtils;
import org.jlinda.core.SLCImage;
import org.jlinda.core.coregistration.LUT;
import org.jlinda.core.coregistration.cross.CrossGeometry;
import org.jlinda.nest.dat.CrossResamplingOpUI;
import org.slf4j.LoggerFactory;

import javax.media.jai.*;
import java.awt.*;
import java.awt.image.DataBuffer;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.ParameterBlock;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Image resampling for Cross Interferometry
 */
@OperatorMetadata(alias = "CrossResampling",
        category = "InSAR\\Tools",
        authors = "Petar Marinkovic",
        copyright = "Copyright (C) 2013 by PPO.labs",
        description = "Estimate Resampling Polynomial using SAR Image Geometry, and Resample Input Images")
public class CrossResamplingOp extends Operator {

    private static final Logger logger = (Logger) LoggerFactory.getLogger(CrossResamplingOp.class);

    @SourceProduct
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The order of polynomial function", valueSet = {"1", "2", "3"}, defaultValue = "2",
            label = "Interpolation Polynomial Order")
    private int warpPolynomialOrder = 2;

    // only complex data accepted
    @Parameter(valueSet = {LUT.CC4P, LUT.CC6P, LUT.TS6P, LUT.TS8P, LUT.TS16P}, defaultValue = LUT.CC6P, label = "Interpolation Method")
    private String interpolationMethod = LUT.CC6P;

    // only complex data accepted
    @Parameter(valueSet = {"ERS", "Envisat ASAR"}, defaultValue = "ERS", label = "Target Geometry")
    private String targetGeometry = "ERS";

    private InterpolationTable interpTable = null;

    private Band masterBand = null;
    private Band masterBand2 = null;
    private boolean warpDataAvailable = true;

    // Processing Variables
    // target
    private double targetPRF;
    private double targetRSR;
    // source
    private double sourcePRF;
    private double sourceRSR;

    private SLCImage slcMetadata = null;

    private WarpPolynomial warpPolynomial;

    // PARAMETERS FOR JAI INTERPOLATION KERNEL
    private final static int SUBSAMPLE_BITS = 7;
    private final static int PRECISION_BITS = 32;

    // ERS NOMINAL PRF and RSR
    private final static double ERS_PRF_NOMINAL = 1679.902; // [Hz]
    private final static double ERS_RSR_NOMINAL = 18.962468 * 1000; // [Hz]

    // ASAR NOMINAL PRF and RSR
    private final static double ASAR_PRF_NOMINAL = 1652.4156494140625;       // [Hz]
    private final static double ASAR_RSR_NOMINAL = 19.20768 * 1000;  // [Hz]

    private final Map<Band, Band> sourceRasterMap = new HashMap<Band, Band>(10);
    private final Map<Band, Band> complexSrcMap = new HashMap<Band, Band>(10);

    private String processedSlaveBand;
    private String[] masterBandNames = null;

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public CrossResamplingOp() {
    }

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link org.esa.beam.framework.datamodel.Product} annotated with the
     * {@link org.esa.beam.framework.gpf.annotations.TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws org.esa.beam.framework.gpf.OperatorException
     *          If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */

    @Override
    public void initialize() throws OperatorException {

        logger.setLevel(Level.TRACE);

        try {

            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            slcMetadata = new SLCImage(absRoot);

            final String mission = slcMetadata.getMission();

            // arrange bands
            masterBand = sourceProduct.getBandAt(0);
            if (masterBand.getUnit() != null && masterBand.getUnit().equals(Unit.REAL) && sourceProduct.getNumBands() > 1) {
                masterBand2 = sourceProduct.getBandAt(1);
            }

            if (!mission.equals("ERS") || !mission.equals("ASAR")) {
                throw new OperatorException("The Cross Interferometry operator is for ERS 1/2 and Envisat ASAR products only");
            }

            if (mission.equals(targetGeometry)) {
                throw new OperatorException("Some smart warning / exception message here");
            }

            // declare source
            sourcePRF = slcMetadata.getPRF();
            sourceRSR = slcMetadata.getRsr2x();

            // declare target conditionally
            if (mission.equals("ERS")) {
                targetPRF = ASAR_PRF_NOMINAL;
                targetRSR = ASAR_RSR_NOMINAL;
            } else if (mission.equals("ASAR")) {
                targetPRF = ERS_PRF_NOMINAL;
                targetRSR = ERS_RSR_NOMINAL;
            }

            constructPolynomial();
            constructInterpolationTable(interpolationMethod);
            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void constructPolynomial() {

        CrossGeometry crossGeometry = new CrossGeometry();

        crossGeometry.setPrfOriginal(sourcePRF);
        crossGeometry.setRsrOriginal(sourceRSR);
        crossGeometry.setPrfTarget(targetPRF);
        crossGeometry.setRsrTarget(targetRSR);
        crossGeometry.setNormalizeFlag(false);

        // use grids for computing polynomial using JAI Warp
        crossGeometry.computeCoeffsFromCoords();

        // cast coefficients to floats
        double[] xCoeffsDouble = crossGeometry.getCoeffsRg();
        double[] yCoeffsDouble = crossGeometry.getCoeffsAz();
        float[] xCoeffsFloat = new float[xCoeffsDouble.length];
        float[] yCoeffsFloat = new float[yCoeffsDouble.length];
        for (int i = 0; i < xCoeffsFloat.length; i++) {
            yCoeffsFloat[i] = (float) yCoeffsDouble[i];
            xCoeffsFloat[i] = (float) xCoeffsDouble[i];
        }
        // show polynomials
        logger.debug("coeffsY : {}", ArrayUtils.toString(yCoeffsDouble));
        logger.debug("coeffsX : {}", ArrayUtils.toString(xCoeffsDouble));

        // construct polynomial
        warpPolynomial = new WarpGeneralPolynomial(xCoeffsFloat, yCoeffsFloat);

    }

    private void constructInterpolationTable(String interpolationMethod) {

        // construct interpolation LUT
        int kernelLength = extractNumber(interpolationMethod);
        LUT lut = new LUT(interpolationMethod, kernelLength);
        lut.constructLUT();

        // get lut and cast to float
        double[] lutArrayDoubles = lut.getKernel().toArray();
        float lutArrayFloats[] = new float[lutArrayDoubles.length];
        int i = 0;
        for (double lutElement : lutArrayDoubles) {
            lutArrayFloats[i++] = (float) lutElement;
        }

        // construct interpolation table for JAI resampling
        int padding = kernelLength / 2 - 1;
        interpTable = new InterpolationTable(padding, kernelLength, SUBSAMPLE_BITS, PRECISION_BITS, lutArrayFloats);


    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        // coregistrated image should have the same geo-coding as the master image
        OperatorUtils.copyProductNodes(sourceProduct, targetProduct);
        updateTargetProductMetadata();

    }

    /**
     * Update metadata in the target product.
     */
    private void updateTargetProductMetadata() {
        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.coregistered_stack, 1);

        // TODO - here goes update of metadata
        // 1. RSR of source replaced with RSR of target
        // 2. PRF of source replaced with PRF of target
        // 3. resolution cell sampling
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetBand The target band.
     * @param targetTile The current tile associated with the target band to be computed.
     * @param pm         A progress monitor which should be used to determine computation cancelation requests.
     * @throws org.esa.beam.framework.gpf.OperatorException
     *          If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        final Rectangle targetRectangle = targetTile.getRectangle();
        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int w = targetRectangle.width;
        final int h = targetRectangle.height;
        //System.out.println("CrossResamplingOperator: x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

        try {

            final Band srcBand = sourceRasterMap.get(targetBand);
            if (srcBand == null)
                return;
            Band realSrcBand = complexSrcMap.get(srcBand);
            if (realSrcBand == null)
                realSrcBand = srcBand;

            // create source image
            final Tile sourceRaster = getSourceTile(srcBand, targetRectangle);

            if (pm.isCanceled())
                return;

            // get source image
            final RenderedImage srcImage = sourceRaster.getRasterDataNode().getSourceImage();

            // get warped image
            final RenderedOp warpedImage = createWarpImage(warpPolynomial, srcImage);

            // copy warped image data to target
            final float[] dataArray = warpedImage.getData(targetRectangle).getSamples(x0, y0, w, h, 0, (float[]) null);

            // set samples in target
            targetTile.setRawSamples(ProductData.createInstance(dataArray));

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    /**
     * Create warped image.
     *
     * @param warp     The WARP polynomial.
     * @param srcImage The source image.
     * @return The warped image.
     */
    private RenderedOp createWarpImage(WarpPolynomial warp, final RenderedImage srcImage) {

        // reformat source image by casting pixel values from ushort to float
        final ParameterBlock pb1 = new ParameterBlock();
        pb1.addSource(srcImage);
        pb1.add(DataBuffer.TYPE_FLOAT);
        final RenderedImage srcImageFloat = JAI.create("format", pb1);

        // get warped image
        final ParameterBlock pb2 = new ParameterBlock();

        pb2.addSource(srcImageFloat);
        pb2.add(warp);
        pb2.add(interpTable);

        RenderedOp warpOutput = JAI.create("warp", pb2);

        return warpOutput;
    }

    private int extractNumber(String line) {
        String numbers = new String();

        Pattern p = Pattern.compile("\\d+");
        Matcher m = p.matcher(line);
        while (m.find()) {
            numbers = numbers + m.group();
        }

        return Integer.parseInt(numbers);
    }



    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.beam.framework.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see org.esa.beam.framework.gpf.OperatorSpi#createOperator()
     * @see org.esa.beam.framework.gpf.OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CrossResamplingOp.class);
            super.setOperatorUI(CrossResamplingOpUI.class);
        }
    }

}