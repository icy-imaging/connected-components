/*
 * Copyright (c) 2010-2025. Institut Pasteur.
 *
 * This file is part of Icy.
 * Icy is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Icy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Icy. If not, see <https://www.gnu.org/licenses/>.
 */

package plugins.adufour.connectedcomponents;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.bioimageanalysis.extension.kernel.roi.roi2d.ROI2DArea;
import org.bioimageanalysis.extension.kernel.roi.roi3d.ROI3DArea;
import org.bioimageanalysis.icy.Icy;
import org.bioimageanalysis.icy.common.collection.array.Array1DUtil;
import org.bioimageanalysis.icy.common.string.StringUtil;
import org.bioimageanalysis.icy.common.type.DataIteratorUtil;
import org.bioimageanalysis.icy.common.type.DataType;
import org.bioimageanalysis.icy.extension.plugin.annotation_.IcyPluginIcon;
import org.bioimageanalysis.icy.extension.plugin.annotation_.IcyPluginName;
import org.bioimageanalysis.icy.gui.frame.progress.FailedAnnounceFrame;
import org.bioimageanalysis.icy.io.FileUtil;
import org.bioimageanalysis.icy.io.xls.XLSXUtil;
import org.bioimageanalysis.icy.model.colormap.FireColorMap;
import org.bioimageanalysis.icy.model.image.IcyBufferedImage;
import org.bioimageanalysis.icy.model.roi.ROI;
import org.bioimageanalysis.icy.model.sequence.Sequence;
import org.bioimageanalysis.icy.model.sequence.SequenceDataIterator;
import org.bioimageanalysis.icy.model.sequence.VolumetricImage;
import org.bioimageanalysis.icy.model.swimmingPool.SwimmingObject;
import org.bioimageanalysis.icy.system.IcyHandledException;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import plugins.adufour.blocks.lang.Block;
import plugins.adufour.blocks.util.VarList;
import plugins.adufour.ezplug.*;
import plugins.adufour.vars.lang.VarGenericArray;
import plugins.adufour.vars.lang.VarROIArray;
import plugins.adufour.vars.lang.VarSequence;
import plugins.adufour.vars.util.VarException;
import plugins.nchenouard.spot.DetectionResult;
import plugins.nchenouard.spot.Spot;

import javax.vecmath.Point3d;
import javax.vecmath.Point3i;
import javax.vecmath.Point4d;
import java.io.File;
import java.io.IOException;
import java.util.*;

@IcyPluginName("Connected Components")
@IcyPluginIcon(path = "/plugins/adufour/connectedcomponents/Connected_Components.png")
public class ConnectedComponents extends EzPlug implements Block {
    /**
     * List of extraction methods suitable for the Connected Components plugin
     *
     * @author Alexandre Dufour
     */
    public enum ExtractionType {
        /**
         * The user defined value is considered as the background, and all components with a
         * different intensity should be extracted (regardless of intensity variations)
         */
        BACKGROUND,
        /**
         * The user defined value is considered as the background, and all components with a
         * different intensity should be extracted, accounting for intensity variations to
         * distinguish touching components
         */
        BACKGROUND_LABELED,
        /**
         * Extracts components with pixel intensities matching the user defined value
         */
        VALUE,
        /**
         * Extracts ROI from the input sequence
         */
        ROI
    }

    public enum Sorting {
        /**
         * Components are not sorted
         */
        ARBITRARY(null),
        /**
         * Components are sorted by ascending depth value
         */
        DEPTH_ASC((o1, o2) -> (int) Math.signum(o1.getZ() - o2.getZ())),
        /**
         * Components are sorted by descending depth value
         */
        DEPTH_DESC((o1, o2) -> (int) Math.signum(o2.getZ() - o1.getZ()));

        /**
         * The comparator which can be used to sort an array of connected components
         */
        public final Comparator<ConnectedComponent> comparator;

        Sorting(final Comparator<ConnectedComponent> comparator) {
            this.comparator = comparator;
        }
    }

    protected EzVarSequence input = new EzVarSequence("Input");

    protected EzVarEnum<ExtractionType> extractionMethod = new EzVarEnum<>("Extraction mode", ExtractionType.values());

    protected EzLabel extractionMethodDetail = new EzLabel("Description");

    protected EzVarInteger background = new EzVarInteger("Value", 0, Integer.MIN_VALUE, Integer.MAX_VALUE, 1);

    protected EzVarBoolean discardEdgesX = new EzVarBoolean("Remove border objects (X)", true);
    protected EzVarBoolean discardEdgesY = new EzVarBoolean("Remove border objects (Y)", true);
    protected EzVarBoolean discardEdgesZ = new EzVarBoolean("Remove border objects (Z)", true);

    protected EzVarBoolean boundSize = new EzVarBoolean("Filter objects by size", false);

    protected EzVarInteger minSize = new EzVarInteger("Min. size", 1, 1, Integer.MAX_VALUE, 1);
    protected EzVarInteger maxSize = new EzVarInteger("Max. size", 10000, 1, Integer.MAX_VALUE, 1);

    protected EzVarBoolean labeledExtraction;

    protected EzLabel objectCount;

    protected EzVarBoolean exportSequence = new EzVarBoolean("Labeled sequence", true);

    protected EzVarEnum<Sorting> labelSorting = new EzVarEnum<>("Sort components", Sorting.values(), Sorting.ARBITRARY);

    protected EzVarBoolean exportSwPool = new EzVarBoolean("Swimming pool", false);
    protected EzVarBoolean exportROI = new EzVarBoolean("ROI", false);
    protected EzVarBoolean exportExcel = new EzVarBoolean("Export to Excel", false);
    protected EzVarFile exportExcelFile = new EzVarFile("Excel file", "");

    protected VarSequence outputSequence = new VarSequence("output", null);
    protected VarGenericArray<ConnectedComponent[]> outputCCs = new VarGenericArray<>("components", ConnectedComponent[].class, null);
    protected VarROIArray outputROIs = new VarROIArray("list of ROI");

    @Override
    protected void initialize() {
        addEzComponent(input);
        input.addVarChangeListener((source, newValue) -> {
            if (newValue != null) {
                final boolean doZ = newValue.getSizeZ() > 1;
                discardEdgesZ.setValue(doZ);
                discardEdgesZ.setVisible(doZ);
            }
        });

        addEzComponent(extractionMethod);
        extractionMethod.addVarChangeListener((source, newValue) -> {
            switch (newValue) {
                case BACKGROUND:
                    extractionMethodDetail.setText(
                            "Standard mode: extracts all pixels different than the given background value, regardless of their intensity");
                    break;
                case BACKGROUND_LABELED:
                    extractionMethodDetail.setText(
                            "Standard labeled mode: extracts all pixels different than the background, however different intensities are seen as different objects");
                    break;
                case VALUE:
                    extractionMethodDetail
                            .setText("Value-extraction mode: extracts all pixels with the specified value");
                    break;
                case ROI:
                    extractionMethodDetail.setText("ROI mode: extracts all ROI in the input sequence");
                    break;
            }
        });

        addEzComponent(extractionMethodDetail);
        extractionMethod.addVisibilityTriggerTo(background, ExtractionType.BACKGROUND,
                ExtractionType.BACKGROUND_LABELED, ExtractionType.VALUE);
        addEzComponent(background);

        addEzComponent(new EzGroup("Discard edges", discardEdgesX, discardEdgesY, discardEdgesZ));

        addEzComponent(boundSize);
        addEzComponent(minSize);
        addEzComponent(maxSize);
        boundSize.addVisibilityTriggerTo(minSize, true);
        boundSize.addVisibilityTriggerTo(maxSize, true);

        addEzComponent(new EzGroup("Export", exportSequence, labelSorting, exportSwPool, exportROI, exportExcel,
                exportExcelFile));
        exportSequence.addVisibilityTriggerTo(labelSorting, true);
        exportExcel.addVisibilityTriggerTo(exportExcelFile, true);

        addEzComponent(objectCount = new EzLabel(""));

        setTimeDisplay(true);
    }

    @Override
    public void declareInput(final VarList inputMap) {
        inputMap.add("input", input.getVariable());
        inputMap.add("method", extractionMethod.getVariable());
        inputMap.add("value", background.getVariable());
        inputMap.add("no edge (X)", discardEdgesX.getVariable());
        inputMap.add("no edge (Y)", discardEdgesY.getVariable());
        inputMap.add("no edge (Z)", discardEdgesZ.getVariable());
        inputMap.add("size filter", boundSize.getVariable());
        inputMap.add("min. size", minSize.getVariable());
        inputMap.add("max. size", maxSize.getVariable());
        inputMap.add("Export to Excel", exportExcel.getVariable());
        inputMap.add("Excel file", exportExcelFile.getVariable());
    }

    @Override
    public void declareOutput(final VarList outputMap) {
        outputMap.add("labeled sequence", outputSequence);
        outputMap.add("objects", outputCCs);
        outputMap.add("list of extracted ROI", outputROIs);
    }

    @Override
    protected void execute() {
        final ArrayList<ConnectedComponent> componentsList = new ArrayList<>();
        final Map<Integer, List<ConnectedComponent>> componentsMap;

        final int min = boundSize.getValue() ? minSize.getValue() : 0;
        final int max = boundSize.getValue() ? maxSize.getValue() : Integer.MAX_VALUE;

        final Sequence output = new Sequence();

        final Sequence inputSequence = input.getValue(true);
        if (inputSequence.getSizeT() == 0)
            throw new VarException(input.getVariable(), "Cannot extract connected components from an emtpy sequence !");

        // never remove objects touching the Z edge in 2D (that would remove... everything!)
        final boolean discardEdgesAlongZ = (inputSequence.getSizeZ() > 1 && discardEdgesZ.getValue());

        try {
            if (extractionMethod.getValue() == ExtractionType.ROI) {
                final int width = inputSequence.getWidth();
                final int height = inputSequence.getHeight();

                //Sequence labeledSequence = new Sequence(inputSequence.getMetadata());
                final Sequence labeledSequence = new Sequence(inputSequence.getOMEXMLMetadata());
                for (int t = 0; t < inputSequence.getSizeT(); t++)
                    for (int z = 0; z < inputSequence.getSizeZ(); z++)
                        labeledSequence.setImage(t, z, new IcyBufferedImage(width, height, 1, DataType.USHORT));

                short cpt = 1;
                for (final ROI roi : inputSequence.getROIs())
                    DataIteratorUtil.set(new SequenceDataIterator(labeledSequence, roi, true), cpt++ & 0xffff);

                componentsMap = extractConnectedComponents(labeledSequence, 0, ExtractionType.BACKGROUND_LABELED,
                        discardEdgesX.getValue(), discardEdgesY.getValue(), discardEdgesAlongZ, min, max, output);
            }
            else {
                componentsMap = extractConnectedComponents(inputSequence, background.getValue(),
                        extractionMethod.getValue(), discardEdgesX.getValue(), discardEdgesY.getValue(),
                        discardEdgesAlongZ, min, max, output);
            }
        }
        catch (final InterruptedException e) {
            new FailedAnnounceFrame(e.getMessage());
            return;
        }

        outputSequence.setValue(output);

        int nbObjects = 0;
        for (final List<ConnectedComponent> ccs : componentsMap.values()) {
            nbObjects += ccs.size();
            componentsList.addAll(ccs);
        }

        outputCCs.setValue(componentsList.toArray(new ConnectedComponent[nbObjects]));

        if (getUI() != null) {
            objectCount.setText("Total: " + nbObjects + " components");

            if (exportSequence.getValue()) {
                createLabeledSequence(output, componentsMap, labelSorting.getValue().comparator);

                addSequence(output);
            }
        }

        if (exportSwPool.getValue()) {
            final DetectionResult result = convertToDetectionResult(componentsMap, input.getValue());
            final SwimmingObject object = new SwimmingObject(result, "Set of " + nbObjects + " connected components");
            Icy.getMainInterface().getSwimmingPool().add(object);
        }

        if (exportROI.getValue() || outputROIs.isReferenced()) {
            if (output.getSizeZ() > 1) {
                final ArrayList<ROI3DArea> rois = new ArrayList<>(componentsMap.size());

                int ccID = 1;

                for (final List<ConnectedComponent> ccs : componentsMap.values())
                    for (final ConnectedComponent cc : ccs) {
                        final ROI3DArea area = new ROI3DArea();
                        area.setName("Object #" + ccID++);
                        area.beginUpdate();
                        for (final Point3i pt : cc)
                            area.addPoint(pt.x, pt.y, pt.z);
                        area.setT(cc.getT());
                        area.endUpdate();
                        rois.add(area);
                    }

                outputROIs.setValue(rois.toArray(new ROI3DArea[0]));
            }
            else {
                final ArrayList<ROI2DArea> rois = new ArrayList<>(componentsMap.size());

                for (final List<ConnectedComponent> ccs : componentsMap.values())
                    for (final ConnectedComponent cc : ccs) {
                        final ROI2DArea area = new ROI2DArea();
                        area.beginUpdate();
                        for (final Point3i pt : cc)
                            area.addPoint(pt.x, pt.y);
                        area.setT(cc.getT());
                        area.endUpdate();
                        rois.add(area);
                    }

                outputROIs.setValue(rois.toArray(new ROI2DArea[0]));
            }

            if (exportROI.getValue()) {
                // replace all ROI on the input by the new ones
                final Sequence in = input.getValue();

                in.beginUpdate();

                for (final ROI roi : input.getValue().getROIs())
                    in.removeROI(roi);

                for (final ROI roi : outputROIs.getValue())
                    in.addROI(roi);

                in.endUpdate();
            }
        }

        if (exportExcel.getValue()) {
            int page = 1;

            Sheet sheet;
            final Workbook workbook;

            File f = exportExcelFile.getValue(true);
            if (!FileUtil.getFileExtension(f.getPath(), false).equalsIgnoreCase("xlsx"))
                f = new File(f.getPath() + ".xlsx");

            try {
                workbook = XLSXUtil.createWorkbook();
                sheet = XLSXUtil.createNewPage(workbook, "Page " + page);
            }
            catch (final IOException e) {
                throw new IcyHandledException(e.getMessage());
            }

            final Sequence s = input.getValue();
            final Point4d resolution = new Point4d(s.getPixelSizeX(), s.getPixelSizeY(), s.getPixelSizeZ(),
                    s.getTimeInterval());
            final double voxelSize = resolution.x * resolution.y * resolution.z;
            String res = "Sequence resolution:";
            res += " X=" + StringUtil.toStringEx(resolution.x, 5);
            res += ", Y=" + StringUtil.toStringEx(resolution.y, 5);
            res += ", Z=" + StringUtil.toStringEx(resolution.z, 5);
            res += ", T=" + StringUtil.toStringEx(resolution.w, 5);

            XLSXUtil.setCellString(sheet, 0, 0, s.getName());
            XLSXUtil.setCellString(sheet, 6, 0, res);
            XLSXUtil.setCellString(sheet, 0, 1, "#");
            XLSXUtil.setCellString(sheet, 1, 1, "t");
            XLSXUtil.setCellString(sheet, 2, 1, "x");
            XLSXUtil.setCellString(sheet, 3, 1, "y");
            XLSXUtil.setCellString(sheet, 4, 1, "z");
            XLSXUtil.setCellString(sheet, 5, 1, "perimeter");
            XLSXUtil.setCellString(sheet, 6, 1, "area");
            XLSXUtil.setCellString(sheet, 7, 1, "sphericity");
            XLSXUtil.setCellString(sheet, 8, 1, "major axis");
            XLSXUtil.setCellString(sheet, 9, 1, "minor axis");
            XLSXUtil.setCellString(sheet, 10, 1, "minor Z axis");
            XLSXUtil.setCellString(sheet, 11, 1, "eccentricity");
            XLSXUtil.setCellString(sheet, 12, 1, "hull fill ratio");
            XLSXUtil.setCellString(sheet, 13, 1, "M100");
            XLSXUtil.setCellString(sheet, 14, 1, "M010");
            XLSXUtil.setCellString(sheet, 15, 1, "M001");
            XLSXUtil.setCellString(sheet, 16, 1, "M110");
            XLSXUtil.setCellString(sheet, 17, 1, "M101");
            XLSXUtil.setCellString(sheet, 18, 1, "M011");
            XLSXUtil.setCellString(sheet, 19, 1, "M111");
            XLSXUtil.setCellString(sheet, 20, 1, "M200");
            XLSXUtil.setCellString(sheet, 21, 1, "M020");
            XLSXUtil.setCellString(sheet, 22, 1, "M002");
            XLSXUtil.setCellString(sheet, 23, 1, "M220");
            XLSXUtil.setCellString(sheet, 24, 1, "M202");
            XLSXUtil.setCellString(sheet, 25, 1, "M022");
            XLSXUtil.setCellString(sheet, 26, 1, "M222");
            XLSXUtil.setCellString(sheet, 27, 1, "convex perimeter");
            XLSXUtil.setCellString(sheet, 28, 1, "convex volume");

            final ConnectedComponentDescriptor shapeDescriptor = new ConnectedComponentDescriptor();
            int cpt = 2;
            for (final Integer time : componentsMap.keySet())
                for (final ConnectedComponent cc : componentsMap.get(time)) {
                    final boolean is2D = shapeDescriptor.is2D(cc);
                    final Point3d center = cc.getMassCenter();
                    center.x *= resolution.x;
                    center.y *= resolution.y;
                    center.z *= resolution.z;
                    XLSXUtil.setCellNumber(sheet, 0, cpt, (double) (cpt - 1));
                    XLSXUtil.setCellNumber(sheet, 1, cpt, time * resolution.w);
                    XLSXUtil.setCellNumber(sheet, 2, cpt, center.x);
                    XLSXUtil.setCellNumber(sheet, 3, cpt, center.y);
                    XLSXUtil.setCellNumber(sheet, 4, cpt, center.z);
                    XLSXUtil.setCellNumber(sheet, 5, cpt, shapeDescriptor.computePerimeter(cc, null, null));
                    XLSXUtil.setCellNumber(sheet, 6, cpt, cc.getSize() * voxelSize);
                    XLSXUtil.setCellNumber(sheet, 7, cpt, shapeDescriptor.computeSphericity(cc));
                    final double[] radiuses = shapeDescriptor.computeEllipseDimensions(cc);
                    XLSXUtil.setCellNumber(sheet, 8, cpt, radiuses[0]);
                    XLSXUtil.setCellNumber(sheet, 9, cpt, radiuses[1]);
                    XLSXUtil.setCellNumber(sheet, 10, cpt, radiuses[2]);
                    XLSXUtil.setCellNumber(sheet, 11, cpt, shapeDescriptor.computeEccentricity(cc));

                    final double[] contour_area = shapeDescriptor.computeConvexAreaAndVolume(cc);
                    XLSXUtil.setCellNumber(sheet, 12, cpt, contour_area[1] == 0.0 ? 0.0 : Math.min(1.0, cc.getSize() / contour_area[1]));
                    XLSXUtil.setCellNumber(sheet, 13, cpt, shapeDescriptor.computeGeometricMoment(cc, 1, 0, 0));
                    XLSXUtil.setCellNumber(sheet, 14, cpt, shapeDescriptor.computeGeometricMoment(cc, 0, 1, 0));

                    if (!is2D)
                        XLSXUtil.setCellNumber(sheet, 15, cpt, shapeDescriptor.computeGeometricMoment(cc, 0, 0, 1));

                    XLSXUtil.setCellNumber(sheet, 16, cpt, shapeDescriptor.computeGeometricMoment(cc, 1, 1, 0));

                    if (!is2D)
                        XLSXUtil.setCellNumber(sheet, 17, cpt, shapeDescriptor.computeGeometricMoment(cc, 1, 0, 1));

                    if (!is2D)
                        XLSXUtil.setCellNumber(sheet, 18, cpt, shapeDescriptor.computeGeometricMoment(cc, 0, 1, 1));

                    if (!is2D)
                        XLSXUtil.setCellNumber(sheet, 19, cpt, shapeDescriptor.computeGeometricMoment(cc, 1, 1, 1));

                    XLSXUtil.setCellNumber(sheet, 20, cpt, shapeDescriptor.computeGeometricMoment(cc, 2, 0, 0));
                    XLSXUtil.setCellNumber(sheet, 21, cpt, shapeDescriptor.computeGeometricMoment(cc, 0, 2, 0));

                    if (!is2D)
                        XLSXUtil.setCellNumber(sheet, 22, cpt, shapeDescriptor.computeGeometricMoment(cc, 0, 0, 2));

                    XLSXUtil.setCellNumber(sheet, 23, cpt, shapeDescriptor.computeGeometricMoment(cc, 2, 2, 0));

                    if (!is2D)
                        XLSXUtil.setCellNumber(sheet, 24, cpt, shapeDescriptor.computeGeometricMoment(cc, 2, 0, 2));

                    if (!is2D)
                        XLSXUtil.setCellNumber(sheet, 25, cpt, shapeDescriptor.computeGeometricMoment(cc, 0, 2, 2));

                    if (!is2D)
                        XLSXUtil.setCellNumber(sheet, 26, cpt, shapeDescriptor.computeGeometricMoment(cc, 2, 2, 2));

                    XLSXUtil.setCellNumber(sheet, 27, cpt, contour_area[0]);
                    XLSXUtil.setCellNumber(sheet, 28, cpt, contour_area[1]);
                    cpt++;
                    if (cpt == Short.MAX_VALUE) {
                        page++;
                        sheet = XLSXUtil.createNewPage(workbook, "Page " + page);
                        cpt = 1;
                    }
                }

            try {
                XLSXUtil.saveAndClose(workbook, f);
            }
            catch (final Exception e) {
                throw new IcyHandledException(e.getMessage());
            }
        }
    }

    @Override
    public void clean() {
    }

    private static class Label {
        final double imageValue;

        /**
         * final label that should replace the current label if fusion is needed
         */
        int targetLabelValue;

        /**
         * if non-null, indicates the parent object with which the current object should be fused
         */
        Label targetLabel;

        int size;

        private boolean onEdgeX;

        private boolean onEdgeY;

        private boolean onEdgeZ;

        /**
         * Creates a new label with the given value. If no parent is set to this label, the given
         * value will be the final one
         *
         * @param value the pixel value
         * @param label the label value
         */
        @Contract(pure = true)
        Label(final double value, final int label) {
            this.imageValue = value;
            this.targetLabelValue = label;
        }

        /**
         * Retrieves the final object label (recursively)
         */
        @Contract(pure = true)
        int getFinalLabelValue() {
            return targetLabel == null ? targetLabelValue : targetLabel.getFinalLabelValue();
        }
    }

    /**
     * Extracts the connected components in the given sequence with specified size bounds. Note that
     * the method works on both binary gray-scale images. If the input is not binary, any value
     * other than 0 will be extracted as a component.
     *
     * @param inputSequence   A binary or gray-scale sequence. If the sequence is gray-scale, any value other
     *                        than 0 will be extracted as a component
     * @param labeledSequence a single-band integer sequence that will be filled with the labeled components
     * @return a map with extracted components, indexed by the sequence time points
     * @see ExtractionType
     * @see ConnectedComponent
     */
    @Contract("null, _ -> fail")
    public static @NotNull Map<Integer, List<ConnectedComponent>> extractConnectedComponents(
            final Sequence inputSequence,
            final Sequence labeledSequence
    ) {
        return extractConnectedComponents(inputSequence, false, 0, Integer.MAX_VALUE, labeledSequence);
    }

    /**
     * Extracts the connected components in the given sequence with specified size bounds. Note that
     * the method works on both binary gray-scale images. If the input is not binary, any value
     * other than 0 will be extracted as a component.
     *
     * @param inputSequence   A binary or gray-scale sequence. If the sequence is gray-scale, any value other
     *                        than 0 will be extracted as a component
     * @param minSize         the minimum size of the objects to extract
     * @param maxSize         the maximum size of the objects to extract
     * @param labeledSequence a single-band integer sequence that will be filled with the labeled components
     * @return a map with extracted components, indexed by the sequence time points
     * @see ExtractionType
     * @see ConnectedComponent
     */
    @Contract("null, _, _, _ -> fail")
    public static @NotNull Map<Integer, List<ConnectedComponent>> extractConnectedComponents(
            final Sequence inputSequence,
            final int minSize,
            final int maxSize,
            final Sequence labeledSequence
    ) {
        return extractConnectedComponents(inputSequence, false, minSize, maxSize, labeledSequence);
    }

    /**
     * Extracts the connected components in the given sequence with specified size bounds. Note that
     * the method works on both binary gray-scale images. If the input is not binary, an option can
     * be set to distinguish touching objects which have a different intensity value, or to extract
     * a specific intensity value
     *
     * @param inputSequence   A binary or gray-scale sequence.
     * @param isInputLabeled  if true, touching components with different pixel values are extracted separately
     * @param minSize         the minimum size of the objects to extract
     * @param maxSize         the maximum size of the objects to extract
     * @param labeledSequence a single-band integer sequence that will be filled with the labeled components
     * @return a map with extracted components, indexed by the sequence time points
     * @see ExtractionType
     * @see ConnectedComponent
     */
    @Contract("null, _, _, _, _ -> fail")
    public static @NotNull Map<Integer, List<ConnectedComponent>> extractConnectedComponents(
            final Sequence inputSequence,
            final boolean isInputLabeled,
            final int minSize,
            final int maxSize,
            final Sequence labeledSequence
    ) {
        return extractConnectedComponents(
                inputSequence,
                0,
                isInputLabeled ? ExtractionType.BACKGROUND_LABELED : ExtractionType.BACKGROUND,
                minSize,
                maxSize,
                labeledSequence
        );
    }

    /**
     * Extracts the connected components in the given sequence with specified size bounds. Note that
     * the method works on both binary gray-scale images. If the input is not binary, an option can
     * be set to distinguish touching objects which have a different intensity value, or to extract
     * a specific intensity value
     *
     * @param inputSequence   A binary or gray-scale sequence.
     * @param value           the user value to be interpreted depending on the type parameter
     * @param type            the extraction method (or how to interpret the value parameter)
     * @param minSize         the minimum size of the objects to extract
     * @param maxSize         the maximum size of the objects to extract
     * @param labeledSequence a single-band integer sequence that will be filled with the labeled components
     * @return a map with extracted components, indexed by the sequence time points
     * @see ExtractionType
     * @see ConnectedComponent
     */
    @Contract("null, _, _, _, _, _ -> fail")
    public static @NotNull Map<Integer, List<ConnectedComponent>> extractConnectedComponents(
            final Sequence inputSequence,
            final double value,
            final ExtractionType type,
            final int minSize,
            final int maxSize,
            final Sequence labeledSequence
    ) {
        return extractConnectedComponents(
                inputSequence,
                value,
                type,
                false,
                false,
                false,
                minSize,
                maxSize,
                labeledSequence
        );
    }

    /**
     * Extracts the connected components in the given sequence with specified size bounds. Note that
     * the method works on both binary gray-scale images. If the input is not binary, an option can
     * be set to distinguish touching objects which have a different intensity value, or to extract
     * a specific intensity value
     *
     * @param inputSequence   A binary or gray-scale sequence.
     * @param value           the user value to be interpreted depending on the type parameter
     * @param type            the extraction type (or how to interpret the value parameter)
     * @param noEdgeX         set to true if components touching the image edge along X should be discarded
     *                        during the extraction
     * @param noEdgeY         set to true if components touching the image edge along Y should be discarded
     *                        during the extraction
     * @param noEdgeZ         set to true if components touching the image edge along Z should be discarded
     *                        during the extraction
     * @param minSize         the minimum size of the objects to extract
     * @param maxSize         the maximum size of the objects to extract
     * @param labeledSequence a single-band integer sequence that will be filled with the labeled components
     * @return a map with extracted components, indexed by the sequence time points
     * @see ExtractionType
     * @see ConnectedComponent
     */
    @Contract("null, _, _, _, _, _, _, _, _ -> fail")
    public static @NotNull Map<Integer, List<ConnectedComponent>> extractConnectedComponents(
            final Sequence inputSequence,
            final double value,
            final ExtractionType type,
            final boolean noEdgeX,
            final boolean noEdgeY,
            final boolean noEdgeZ,
            final int minSize,
            final int maxSize,
            Sequence labeledSequence
    ) {
        if (inputSequence == null || inputSequence.getSizeT() == 0)
            throw new IllegalArgumentException("Cannot extract connected components from an empty sequence !");

        final int width = inputSequence.getSizeX();
        final int height = inputSequence.getSizeY();

        if (labeledSequence == null)
            labeledSequence = new Sequence();

        final Map<Integer, List<ConnectedComponent>> componentsMap = new TreeMap<>();

        for (int t = 0; t < inputSequence.getSizeT(); t++) {
            for (int z = 0; z < inputSequence.getSizeZ(); z++)
                labeledSequence.setImage(t, z, new IcyBufferedImage(width, height, 1, DataType.UINT));

            final VolumetricImage volIN = inputSequence.getVolumetricImage(t);
            final VolumetricImage volOUT = labeledSequence.getVolumetricImage(t);

            final List<ConnectedComponent> components = extractConnectedComponents(volIN, value, type, noEdgeX, noEdgeY,
                    noEdgeZ, minSize, maxSize, volOUT);

            // int cpt=1;
            for (final ConnectedComponent cc : components) {
                cc.setT(t);
                // System.out.println(t + "\t" + cpt++ + "\t" + (int)cc.getMassCenter().x + "\t" +
                // (int)cc.getMassCenter().y);
            }

            componentsMap.put(t, components);
        }

        labeledSequence.updateChannelsBounds(true);
        labeledSequence.getColorModel().setColorMap(0, new FireColorMap(), false);

        return componentsMap;
    }

    /**
     * Extracts the connected components in the given volumetric image with specified size bounds.
     * The method works on both binary gray-scale images. If the input is not binary, an option
     * can be set to distinguish touching objects which have a different intensity value, or to
     * extract a specific intensity value
     *
     * @param stack   A binary or gray-scale volumetric image.
     * @param value   the user value to be interpreted depending on the type parameter
     * @param type    the extraction type (or how to interpret the value parameter)
     * @param noEdgeX set to true if components touching the image edge along X should be discarded
     *                during the extraction
     * @param noEdgeY set to true if components touching the image edge along Y should be discarded
     *                during the extraction
     * @param noEdgeZ set to true if components touching the image edge along Z should be discarded
     *                during the extraction
     * @param minSize the minimum size of the objects to extract
     * @param maxSize the maximum size of the objects to extract
     * @return a collection containing all components extracted.
     * @see ExtractionType
     * @see ConnectedComponent
     */
    @Contract("_, _, _, _, _, _, _, _ -> new")
    public static @NotNull List<ConnectedComponent> extractConnectedComponents(
            final VolumetricImage stack,
            final double value,
            final ExtractionType type,
            final boolean noEdgeX,
            final boolean noEdgeY,
            final boolean noEdgeZ,
            final int minSize,
            final int maxSize
    ) {
        return extractConnectedComponents(
                stack,
                value,
                type,
                noEdgeX,
                noEdgeY,
                noEdgeZ,
                minSize,
                maxSize,
                new VolumetricImage()
        );
    }

    /**
     * Extracts the connected components in the given volumetric image with specified size bounds.
     * The the method works on both binary gray-scale images. If the input is not binary, an option
     * can be set to distinguish touching objects which have a different intensity value, or to
     * extract a specific intensity value
     *
     * @param stack        A binary or gray-scale volumetric image.
     * @param value        the user value to be interpreted depending on the type parameter
     * @param type         the extraction type (or how to interpret the value parameter)
     * @param noEdgeX      set to true if components touching the image edge along X should be discarded
     *                     during the extraction
     * @param noEdgeY      set to true if components touching the image edge along Y should be discarded
     *                     during the extraction
     * @param noEdgeZ      set to true if components touching the image edge along Z should be discarded
     *                     during the extraction
     * @param minSize      the minimum size of the objects to extract
     * @param maxSize      the maximum size of the objects to extract
     * @param labeledStack a volumetric image where labeled information will be stored during the extraction.
     *                     Note that this volumetric image should be non-null and filled with zero-values (no
     *                     cleaning is performed to optimize recursive processes)
     * @return a collection containing all components extracted.
     * @throws NullPointerException is labeledStack is null
     * @see ExtractionType
     * @see ConnectedComponent
     */
    @Contract("_, _, _, _, _, _, _, _, _ -> new")
    public static @NotNull List<ConnectedComponent> extractConnectedComponents(
            final @NotNull VolumetricImage stack,
            final double value,
            final ExtractionType type,
            final boolean noEdgeX,
            final boolean noEdgeY,
            final boolean noEdgeZ,
            final int minSize,
            final int maxSize,
            final VolumetricImage labeledStack
    ) throws NullPointerException {
        final int width = stack.getFirstImage().getSizeX();
        final int height = stack.getFirstImage().getSizeY();
        final int depth = stack.getSize();

        final int[] neighborLabelValues = new int[13];
        int neighborhoodSize = 0;

        final boolean extractUserValue = (type == ExtractionType.VALUE);

        final Label[] labels = new Label[width * height * depth];

        // first image pass: naive labeling with simple backward neighborhood

        int highestKnownLabel = 0;

        boolean onEdgeX;
        boolean onEdgeY;
        boolean onEdgeZ;

        for (int z = 0; z < depth; z++) {
            onEdgeZ = (z == 0 || z == depth - 1);

            final IcyBufferedImage currentSliceImage = labeledStack.getImage(z);
            // retrieve the direct pointer to the current slice
            final int[] _labelsInCurrentSlice = currentSliceImage.getDataXYAsInt(0);
            // retrieve a direct pointer to the previous slice
            final int[] _labelsInUpperSlice = (z == 0) ? null : labeledStack.getImage(z - 1).getDataXYAsInt(0);

            int voxelOffset = 0;

            final Object inputData = stack.getImage(z).getDataXY(0);
            final DataType dataType = stack.getImage(z).getDataType();

            for (int y = 0; y < height; y++) {
                onEdgeY = (y == 0 || y == height - 1);

                for (int x = 0; x < width; x++, voxelOffset++) {
                    onEdgeX = (x == 0 || x == width - 1);

                    final double pixelValue = Array1DUtil.getValue(inputData, voxelOffset, dataType);

                    final boolean pixelEqualsUserValue = (pixelValue == value);

                    // do not process the current pixel if:
                    // - extractUserValue is true and pixelEqualsUserValue is false
                    // - extractUserValue is false and pixelEqualsUserValue is true

                    if (extractUserValue != pixelEqualsUserValue)
                        continue;

                    // the current pixel should be labeled

                    // -> look for existing labels in its neighborhood

                    // 1) define the neighborhood of interest here
                    // NB: this is a single pass method, so backward neighborhood is sufficient

                    // legend:
                    // e = edge
                    // x = current pixel
                    // n = valid neighbor
                    // . = other neighbor

                    if (z == 0) {
                        if (y == 0) {
                            if (x == 0) {
                                // e e e
                                // e x .
                                // e . .

                                // do nothing
                            }
                            else {
                                // e e e
                                // n x .
                                // . . .

                                neighborLabelValues[0] = _labelsInCurrentSlice[voxelOffset - 1];
                                neighborhoodSize = 1;
                            }
                        }
                        else {
                            final int north = voxelOffset - width;

                            if (x == 0) {
                                // e n n
                                // e x .
                                // e . .

                                neighborLabelValues[0] = _labelsInCurrentSlice[north];
                                neighborLabelValues[1] = _labelsInCurrentSlice[north + 1];
                                neighborhoodSize = 2;
                            }
                            else if (x == width - 1) {
                                // n n e
                                // n x e
                                // . . e

                                neighborLabelValues[0] = _labelsInCurrentSlice[north - 1];
                                neighborLabelValues[1] = _labelsInCurrentSlice[north];
                                neighborLabelValues[2] = _labelsInCurrentSlice[voxelOffset - 1];
                                neighborhoodSize = 3;
                            }
                            else {
                                // n n n
                                // n x .
                                // . . .

                                neighborLabelValues[0] = _labelsInCurrentSlice[north - 1];
                                neighborLabelValues[1] = _labelsInCurrentSlice[north];
                                neighborLabelValues[2] = _labelsInCurrentSlice[north + 1];
                                neighborLabelValues[3] = _labelsInCurrentSlice[voxelOffset - 1];
                                neighborhoodSize = 4;
                            }
                        }
                    }
                    else {
                        if (y == 0) {
                            final int south = voxelOffset + width;

                            if (x == 0) {
                                // e e e | e e e
                                // e n n | e x .
                                // e n n | e . .

                                neighborLabelValues[0] = _labelsInUpperSlice[voxelOffset];
                                neighborLabelValues[1] = _labelsInUpperSlice[voxelOffset + 1];
                                neighborLabelValues[2] = _labelsInUpperSlice[south];
                                neighborLabelValues[3] = _labelsInUpperSlice[south + 1];
                                neighborhoodSize = 4;
                            }
                            else if (x == width - 1) {
                                // e e e | e e e
                                // n n e | n x e
                                // n n e | . . e

                                neighborLabelValues[0] = _labelsInUpperSlice[voxelOffset - 1];
                                neighborLabelValues[1] = _labelsInUpperSlice[voxelOffset];
                                neighborLabelValues[2] = _labelsInUpperSlice[south - 1];
                                neighborLabelValues[3] = _labelsInUpperSlice[south];
                                neighborLabelValues[4] = _labelsInCurrentSlice[voxelOffset - 1];
                                neighborhoodSize = 5;
                            }
                            else {
                                // e e e | e e e
                                // n n n | n x .
                                // n n n | . . .

                                neighborLabelValues[0] = _labelsInUpperSlice[voxelOffset - 1];
                                neighborLabelValues[1] = _labelsInUpperSlice[voxelOffset];
                                neighborLabelValues[2] = _labelsInUpperSlice[voxelOffset + 1];
                                neighborLabelValues[3] = _labelsInUpperSlice[south - 1];
                                neighborLabelValues[4] = _labelsInUpperSlice[south];
                                neighborLabelValues[5] = _labelsInUpperSlice[south + 1];
                                neighborLabelValues[6] = _labelsInCurrentSlice[voxelOffset - 1];
                                neighborhoodSize = 7;
                            }
                        }
                        else if (y == height - 1) {
                            final int north = voxelOffset - width;

                            if (x == 0) {
                                // e n n | e n n
                                // e n n | e x .
                                // e e e | e e e

                                neighborLabelValues[0] = _labelsInUpperSlice[north];
                                neighborLabelValues[1] = _labelsInUpperSlice[north + 1];
                                neighborLabelValues[2] = _labelsInUpperSlice[voxelOffset];
                                neighborLabelValues[3] = _labelsInUpperSlice[voxelOffset + 1];
                                neighborLabelValues[4] = _labelsInCurrentSlice[north];
                                neighborLabelValues[5] = _labelsInCurrentSlice[north + 1];
                                neighborhoodSize = 6;
                            }
                            else if (x == width - 1) {
                                // n n e | n n e
                                // n n e | n x e
                                // e e e | e e e

                                neighborLabelValues[0] = _labelsInUpperSlice[north - 1];
                                neighborLabelValues[1] = _labelsInUpperSlice[north];
                                neighborLabelValues[2] = _labelsInUpperSlice[voxelOffset - 1];
                                neighborLabelValues[3] = _labelsInUpperSlice[voxelOffset];
                                neighborLabelValues[4] = _labelsInCurrentSlice[north - 1];
                                neighborLabelValues[5] = _labelsInCurrentSlice[north];
                                neighborLabelValues[6] = _labelsInCurrentSlice[voxelOffset - 1];
                                neighborhoodSize = 7;
                            }
                            else {
                                // n n n | n n n
                                // n n n | n x .
                                // e e e | e e e

                                neighborLabelValues[0] = _labelsInUpperSlice[north - 1];
                                neighborLabelValues[1] = _labelsInUpperSlice[north];
                                neighborLabelValues[2] = _labelsInUpperSlice[north + 1];
                                neighborLabelValues[3] = _labelsInUpperSlice[voxelOffset - 1];
                                neighborLabelValues[4] = _labelsInUpperSlice[voxelOffset];
                                neighborLabelValues[5] = _labelsInUpperSlice[voxelOffset + 1];
                                neighborLabelValues[6] = _labelsInCurrentSlice[north - 1];
                                neighborLabelValues[7] = _labelsInCurrentSlice[north];
                                neighborLabelValues[8] = _labelsInCurrentSlice[north + 1];
                                neighborLabelValues[9] = _labelsInCurrentSlice[voxelOffset - 1];
                                neighborhoodSize = 10;
                            }
                        }
                        else {
                            final int north = voxelOffset - width;
                            final int south = voxelOffset + width;

                            if (x == 0) {
                                // e n n | e n n
                                // e n n | e x .
                                // e n n | e . .

                                neighborLabelValues[0] = _labelsInUpperSlice[north];
                                neighborLabelValues[1] = _labelsInUpperSlice[north + 1];
                                neighborLabelValues[2] = _labelsInUpperSlice[voxelOffset];
                                neighborLabelValues[3] = _labelsInUpperSlice[voxelOffset + 1];
                                neighborLabelValues[4] = _labelsInUpperSlice[south];
                                neighborLabelValues[5] = _labelsInUpperSlice[south + 1];
                                neighborLabelValues[6] = _labelsInCurrentSlice[north];
                                neighborLabelValues[7] = _labelsInCurrentSlice[north + 1];
                                neighborhoodSize = 8;
                            }
                            else if (x == width - 1) {
                                final int northwest = north - 1;
                                final int west = voxelOffset - 1;

                                // n n e | n n e
                                // n n e | n x e
                                // n n e | . . e

                                neighborLabelValues[0] = _labelsInUpperSlice[northwest];
                                neighborLabelValues[1] = _labelsInUpperSlice[north];
                                neighborLabelValues[2] = _labelsInUpperSlice[west];
                                neighborLabelValues[3] = _labelsInUpperSlice[voxelOffset];
                                neighborLabelValues[4] = _labelsInUpperSlice[south - 1];
                                neighborLabelValues[5] = _labelsInUpperSlice[south];
                                neighborLabelValues[6] = _labelsInCurrentSlice[northwest];
                                neighborLabelValues[7] = _labelsInCurrentSlice[north];
                                neighborLabelValues[8] = _labelsInCurrentSlice[west];
                                neighborhoodSize = 9;
                            }
                            else {
                                final int northwest = north - 1;
                                final int west = voxelOffset - 1;
                                final int northeast = north + 1;
                                final int southwest = south - 1;
                                final int southeast = south + 1;

                                // n n n | n n n
                                // n n n | n x .
                                // n n n | . . .

                                neighborLabelValues[0] = _labelsInUpperSlice[northwest];
                                neighborLabelValues[1] = _labelsInUpperSlice[north];
                                neighborLabelValues[2] = _labelsInUpperSlice[northeast];
                                neighborLabelValues[3] = _labelsInUpperSlice[west];
                                neighborLabelValues[4] = _labelsInUpperSlice[voxelOffset];
                                neighborLabelValues[5] = _labelsInUpperSlice[voxelOffset + 1];
                                neighborLabelValues[6] = _labelsInUpperSlice[southwest];
                                neighborLabelValues[7] = _labelsInUpperSlice[south];
                                neighborLabelValues[8] = _labelsInUpperSlice[southeast];
                                neighborLabelValues[9] = _labelsInCurrentSlice[northwest];
                                neighborLabelValues[10] = _labelsInCurrentSlice[north];
                                neighborLabelValues[11] = _labelsInCurrentSlice[northeast];
                                neighborLabelValues[12] = _labelsInCurrentSlice[west];
                                neighborhoodSize = 13;
                            }
                        }
                    }

                    // 2) the neighborhood is ready, move to the labeling step

                    int currentVoxelLabelValue = Integer.MAX_VALUE;

                    // to avoid creating too many labels and fuse them later on,
                    // find the minimum non-zero label in the neighborhood
                    // and assign that minimum label right now

                    for (int i = 0; i < neighborhoodSize; i++) {
                        final int neighborLabelValue = neighborLabelValues[i];

                        // zero labels are not interesting...
                        if (neighborLabelValue == 0)
                            continue;

                        // neighbor labels should have the same/different ? image value
                        if (type == ExtractionType.BACKGROUND_LABELED
                                && labels[neighborLabelValue].imageValue != pixelValue)
                            continue;

                        // here, the neighbor label is valid
                        // => check if it is lower
                        if (neighborLabelValue < currentVoxelLabelValue) {
                            currentVoxelLabelValue = neighborLabelValue;
                        }
                    }

                    if (currentVoxelLabelValue == Integer.MAX_VALUE) {
                        // currentVoxelLabel didn't change
                        // => no lower neighbor value found
                        // => new label
                        highestKnownLabel++;
                        currentVoxelLabelValue = highestKnownLabel;
                        labels[currentVoxelLabelValue] = new Label(pixelValue, currentVoxelLabelValue);
                    }
                    else {
                        // currentVoxelLabelValue has been modified
                        // -> browse its neighborhood again
                        // -> find all neighbors with a higher label value
                        // -> change their value to currentVoxelLabelValue
                        // -> change their target to currentVoxelLabel

                        final Label currentVoxelLabel = labels[currentVoxelLabelValue];

                        for (int i = 0; i < neighborhoodSize; i++) {
                            final int neighborLabelValue = neighborLabelValues[i];

                            if (neighborLabelValue > currentVoxelLabelValue) {
                                final Label label = labels[neighborLabelValue];

                                if (type == ExtractionType.BACKGROUND_LABELED && label.imageValue != pixelValue)
                                    continue;

                                final int finalLabelValue = label.getFinalLabelValue();
                                final Label finalLabel = labels[finalLabelValue];

                                if (currentVoxelLabel.targetLabelValue == finalLabelValue)
                                    continue;

                                if (currentVoxelLabelValue < finalLabelValue) {
                                    finalLabel.targetLabel = currentVoxelLabel;
                                    finalLabel.targetLabelValue = currentVoxelLabelValue;
                                }
                                else if (currentVoxelLabelValue > finalLabelValue) {
                                    currentVoxelLabel.targetLabel = finalLabel;
                                    currentVoxelLabel.targetLabelValue = finalLabelValue;
                                }
                            }
                        }
                    }

                    // -> store this label in the labeled image
                    _labelsInCurrentSlice[voxelOffset] = currentVoxelLabelValue;
                    labels[currentVoxelLabelValue].size++;
                    labels[currentVoxelLabelValue].onEdgeX |= onEdgeX;
                    labels[currentVoxelLabelValue].onEdgeY |= onEdgeY;
                    labels[currentVoxelLabelValue].onEdgeZ |= onEdgeZ;
                }
            }

            // for data changed and proper cache update
            currentSliceImage.setDataXY(0, _labelsInCurrentSlice);
        }

        // end of the first pass, all pixels have a label
        // (though might not be unique within a given component)

        final HashMap<Integer, ConnectedComponent> componentsMap = new HashMap<>();

        // fusion strategy: fuse higher labels with lower ones
        // "highestKnownLabel" holds the highest known label
        // -> loop backward from there to accumulate object size recursively

        int finalLabel = 0;

        for (int labelValue = highestKnownLabel; labelValue > 0; labelValue--) {
            final Label label = labels[labelValue];

            final int targetLabelValue = label.targetLabelValue;

            if (targetLabelValue < labelValue) {
                // label should be fused to targetLabel

                final Label targetLabel = labels[targetLabelValue];

                // -> add label's size to targetLabel
                targetLabel.size += label.size;

                // -> mark targetLabel as onEdge if label is
                targetLabel.onEdgeX |= label.onEdgeX;
                targetLabel.onEdgeY |= label.onEdgeY;
                targetLabel.onEdgeZ |= label.onEdgeZ;

                // -> mark label to fuse with targetLabel
                label.targetLabel = labels[targetLabelValue];
            }
            else {
                // label has same labelValue and targetLabelValue
                // -> it cannot be fused to anything
                // -> this is a terminal label

                // -> check if it obeys to user constraints

                if (label.size < minSize || label.size > maxSize) {
                    // the component size is out of the given range
                    // -> mark the object for deletion
                    label.targetLabelValue = 0;
                }
                else if ((noEdgeX && label.onEdgeX) || (noEdgeY && label.onEdgeY) || (noEdgeZ && label.onEdgeZ)) {
                    // the component size is on an edge to discard
                    // -> mark the object for deletion
                    label.targetLabelValue = 0;
                }
                else {
                    // the label is clean and user-valid
                    // -> assign its final labelValue (for the final image labeling pass)
                    finalLabel++;
                    label.targetLabelValue = finalLabel;

                    // -> add this label to the list of valid labels

                    final ConnectedComponent component = new ConnectedComponent(label.size);
                    component.onEdgeX = label.onEdgeX;
                    component.onEdgeY = label.onEdgeY;
                    component.onEdgeZ = label.onEdgeZ;
                    componentsMap.put(finalLabel, component);
                }
            }
        }

        // 3) second image pass: replace all labels by their final values

        for (int z = 0; z < depth; z++) {
            final IcyBufferedImage sliceImage = labeledStack.getImage(z);
            final int[] _outputSlice = sliceImage.getDataXYAsInt(0);

            int pixelOffset = 0;

            for (int j = 0; j < height; j++) {
                for (int i = 0; i < width; i++, pixelOffset++) {
                    int targetLabelValue = _outputSlice[pixelOffset];

                    if (targetLabelValue == 0)
                        continue;

                    // if a fusion was indicated, retrieve the final label value
                    targetLabelValue = labels[targetLabelValue].getFinalLabelValue();

                    // assign the final label in the output image
                    _outputSlice[pixelOffset] = targetLabelValue;

                    if (targetLabelValue == 0)
                        continue;

                    // store the current pixel in the component
                    componentsMap.get(targetLabelValue).addPointInternal(new Point3i(i, j, z));
                }
            }

            // data changed and proper cache update
            sliceImage.setDataXY(0, _outputSlice);
        }

        return new ArrayList<>(componentsMap.values());
    }

    public static @NotNull DetectionResult convertToDetectionResult(
            final @NotNull Map<Integer, List<ConnectedComponent>> detections,
            final Sequence sequence
    ) {
        final DetectionResult detectionResult = new DetectionResult();
        for (final Integer t : detections.keySet())
            for (final ConnectedComponent cc : detections.get(t)) {
                // TODO: add points information
                final Spot spot = new Spot(cc.getMassCenter().x, cc.getMassCenter().y, cc.getMassCenter().z);
                detectionResult.addDetection(t, spot);
            }

        detectionResult.setSequence(sequence);

        return detectionResult;
    }

    /**
     * Fill the channel 0 of the given sequence with the list of components sorted using the given
     * comparator. The method does nothing if the given comparator is null
     *
     * @param output     a sequence of type INT
     * @param components Map of List of ConnectedComponent
     * @param comparator Comparator of ConnectedComponents
     */
    public static void createLabeledSequence(
            final Sequence output,
            final Map<Integer, List<ConnectedComponent>> components,
            final Comparator<ConnectedComponent> comparator
    ) {
        if (comparator == null)
            return;

        final int width = output.getSizeX();
        final int[] ids = new int[output.getSizeC()];
        Arrays.fill(ids, 1);

        for (final Integer t : components.keySet()) {
            for (int c = 0; c < output.getSizeC(); c++) {
                // retrieve all objects in that channel
                final ArrayList<ConnectedComponent> cComponents = new ArrayList<>();
                for (final ConnectedComponent cc : components.get(t)) {
                    if (cc.getC() == -1 || cc.getC() == c)
                        cComponents.add(cc);
                }

                final ConnectedComponent[] ccs = cComponents.toArray(new ConnectedComponent[0]);
                Arrays.sort(ccs, comparator);

                final boolean outputVolatile = output.isVolatile();
                try {
                    final int[][] data = output.getDataXYZAsInt(t, c);

                    for (final ConnectedComponent cc : ccs) {
                        for (final Point3i pt : cc) {
                            data[pt.z][pt.y * width + pt.x] = ids[c];
                        }
                        ids[c]++;
                    }
                }
                finally {
                    // restore back volatile state
                    output.setVolatile(outputVolatile);
                }
            }
        }
    }
}
