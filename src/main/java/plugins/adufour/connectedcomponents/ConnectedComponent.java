/*
 * Copyright (c) 2010-2023. Institut Pasteur.
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

import icy.image.IcyBufferedImage;
import icy.roi.ROI;
import icy.sequence.Sequence;
import icy.sequence.SequenceDataIterator;
import icy.type.DataIteratorUtil;
import icy.type.DataType;
import icy.type.collection.array.Array1DUtil;
import plugins.kernel.roi.roi2d.ROI2DArea;
import plugins.kernel.roi.roi3d.ROI3DArea;
import plugins.nchenouard.spot.Detection;

import javax.vecmath.Point3d;
import javax.vecmath.Point3i;
import javax.vecmath.Vector3d;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

public class ConnectedComponent extends Detection implements Iterable<Point3i> {
    private int c = -1;

    /**
     * True if the component is on the image edge along X
     */
    boolean onEdgeX;

    /**
     * True if the component is on the image edge along Y
     */
    boolean onEdgeY;

    /**
     * True if the component is on the image edge along Z
     */
    boolean onEdgeZ;

    private boolean coordsDirty;

    /**
     * the array of points in this object
     */
    private final ArrayList<Point3i> points;

    /**
     * the array of this object contour points
     */
    private Point3i[] contourPoints;

    /**
     * Sum of all points coordinates. The mass center is given by the ratio of each component by the
     * total component size
     */
    private final Point3d coordsSum;

    private final ConnectedComponentDescriptor shapeDescriptor = new ConnectedComponentDescriptor();

    /**
     * Creates a new connected component with given initial capacity
     *
     * @param initialCapacity int
     */
    public ConnectedComponent(final int initialCapacity) {
        super(0, 0, 0, 0);
        this.points = new ArrayList<>(initialCapacity);
        coordsSum = new Point3d();
    }

    void addPointInternal(final Point3i point) {
        points.add(point);

        // accumulate coordinates to compute the mass center
        coordsSum.x += point.x;
        coordsSum.y += point.y;
        coordsSum.z += point.z;

        coordsDirty = true;
    }

    /**
     * Adds a point to this component and updates the mass center
     *
     * @param point the point to add
     */
    public void addPoint(final Point3i point) {
        addPointInternal(point);
        updateDetectionCoords();
    }

    /**
     * Adds a point to this component. This method also accumulates coordinates to optimize the mass
     * center computation
     *
     * @param point the point to add
     */
    void removePointInternal(final Point3i point) {
        if (!points.remove(point))
            return;

        // accumulate coordinates to compute the mass center
        coordsSum.x -= point.x;
        coordsSum.y -= point.y;
        coordsSum.z -= point.z;

        coordsDirty = true;
    }

    public void removeAllPoints() {
        points.clear();
        coordsSum.set(0, 0, 0);
        x = 0;
        y = 0;
        z = 0;
    }

    /**
     * @return true, if the object touches the image edge along X
     */
    public boolean isOnEdgeX() {
        return onEdgeX;
    }

    /**
     * @return true, if the object touches the image edge along Y
     */
    public boolean isOnEdgeY() {
        return onEdgeY;
    }

    /**
     * @return true, if the object touches the image edge along Z (always true for 2D images)
     */
    public boolean isOnEdgeZ() {
        return onEdgeZ;
    }

    /**
     * Computes the average intensity of the component on each channel of the given sequence, at the
     * time point where this component was found
     *
     * @param sequence Sequence
     * @return an array containing the average intensity of the component in each band
     */
    public double[] computeMeanIntensity(final Sequence sequence) {
        return computeMeanIntensity(sequence, t);
    }

    /**
     * Computes the average intensity of the component on each channel of the given sequence and the
     * specified time point
     *
     * @param sequence Sequence
     * @param t        int
     * @return an array containing the average intensity of the component in each band
     */
    public double[] computeMeanIntensity(final Sequence sequence, final int t) {
        final double[] intensitySum = new double[sequence.getSizeC()];

        for (final Point3i point : points) {
            final int offsetXY = point.x + point.y * sequence.getSizeX();

            final Object dataCXY = sequence.getDataXYC(t, point.z);

            for (int c = 0; c < intensitySum.length; c++)
                intensitySum[c] += Array1DUtil.getValue(((Object[]) dataCXY)[c], offsetXY,
                        sequence.getDataType_().isSigned());
        }

        for (int i = 0; i < intensitySum.length; i++)
            intensitySum[i] /= points.size();
        return intensitySum;
    }

    /**
     * Computes the minimum intensity of the component on each channel of the given sequence, at the
     * time point where this component was found
     *
     * @param sequence Sequence
     * @return an array containing the average intensity of the component in each band
     */
    public double[] computeMinIntensity(final Sequence sequence) {
        return computeMinIntensity(sequence, t);
    }

    /**
     * Computes the minimum intensity of the component on each channel of the given sequence and the
     * specified time point
     *
     * @param sequence Sequence
     * @param t        int
     * @return an array containing the average intensity of the component in each band
     */
    public double[] computeMinIntensity(final Sequence sequence, final int t) {
        final double[] minIntensity = new double[sequence.getSizeC()];
        Arrays.fill(minIntensity, Double.MAX_VALUE);

        for (final Point3i point : points) {
            final int offsetXY = point.x + point.y * sequence.getSizeX();

            final Object dataCXY = sequence.getImage(t, point.z).getDataXYC();

            for (int c = 0; c < minIntensity.length; c++) {
                final double val = Array1DUtil.getValue(((Object[]) dataCXY)[c], offsetXY,
                        sequence.getDataType_().isSigned());
                if (val < minIntensity[c])
                    minIntensity[c] = val;
            }
        }
        return minIntensity;
    }

    /**
     * Computes the maximum intensity of the component on each channel of the given sequence, at the
     * time point where this component was found
     *
     * @param sequence Sequence
     * @return an array containing the average intensity of the component in each band
     */
    public double[] computeMaxIntensity(final Sequence sequence) {
        return computeMaxIntensity(sequence, t);
    }

    /**
     * Computes the maximum intensity pixel of the component on each channel of the given sequence
     * and the specified time point
     *
     * @param sequence Sequence
     * @param t        int
     * @return an array containing the average intensity of the component in each band
     */
    public double[] computeMaxIntensity(final Sequence sequence, final int t) {
        final double[] maxIntensity = new double[sequence.getSizeC()];

        for (final Point3i point : points) {
            final int offsetXY = point.x + point.y * sequence.getSizeX();

            final Object dataCXY = sequence.getImage(t, point.z).getDataXYC();

            for (int c = 0; c < maxIntensity.length; c++) {
                final double val = Array1DUtil.getValue(((Object[]) dataCXY)[c], offsetXY,
                        sequence.getDataType_().isSigned());
                if (val > maxIntensity[c])
                    maxIntensity[c] = val;
            }
        }
        return maxIntensity;
    }

    /**
     * Computes the closest Euclidean distance between any two points of this component and the
     * given component
     *
     * @param component the component to compute the distance to
     * @return double
     */
    public double distanceTo(final ConnectedComponent component) {
        double distance = Double.MAX_VALUE;
        for (final Point3i srcPt : this) {
            final Vector3d srcV = new Vector3d(srcPt.x, srcPt.y, srcPt.z);
            for (final Point3i dstPt : component) {
                final Vector3d dstV = new Vector3d(dstPt.x, dstPt.y, dstPt.z);
                dstV.sub(srcV);
                final double length = dstV.length();
                if (length < distance)
                    distance = length;
            }
        }
        return distance;
    }

    /**
     * Returns an iterator over the internal points array. Useful to browse all points without
     * duplicating the array
     *
     * @return Point3i Iterator
     */
    public Iterable<Point3i> getIterablePoints() {
        return points;
    }

    /**
     * Computes the major axis of the object, which is the vector between the two most distant
     * points of this component. Note that the result vector orientation is meaningless if the
     * component is spherical.<br>
     * NOTE: this is a potentially expensive operation: O(N log N)
     *
     * @return 3D Vector
     */
    public Vector3d getMajorAxis() {
        double maxDist = 0;

        final Vector3d vector = new Vector3d();
        final Point3d pid = new Point3d();
        final Point3d pjd = new Point3d();

        final int n = points.size();
        for (int i = 0; i < n - 1; i++) {
            final Point3i pi = points.get(i);
            pid.set(pi.x, pi.y, pi.z);

            for (int j = i + 1; j < n; j++) {
                final Point3i pj = points.get(j);
                pjd.set(pj.x, pj.y, pj.z);

                // compare the squared distance to save the square root operation
                final double dist = pid.distanceSquared(pjd);

                if (dist > maxDist) {
                    maxDist = dist;
                    vector.sub(pjd, pid);
                }
            }
        }

        return vector;
    }

    void updateDetectionCoords() {
        final double factor = 1.0 / getSize();
        x = coordsSum.x * factor;
        y = coordsSum.y * factor;
        z = coordsSum.z * factor;
        coordsDirty = false;
    }

    public int getC() {
        return c;
    }

    @Override
    public double getX() {
        if (coordsDirty)
            updateDetectionCoords();
        return x;
    }

    @Override
    public double getY() {
        if (coordsDirty)
            updateDetectionCoords();
        return y;
    }

    @Override
    public double getZ() {
        if (coordsDirty)
            updateDetectionCoords();
        return z;
    }

    /**
     * Computes the mass center of this component
     *
     * @return 3D Point
     */
    public Point3d getMassCenter() {
        if (coordsDirty)
            updateDetectionCoords();
        return new Point3d(x, y, z);
        // Point3d massCenter = new Point3d(coordsSum);
        // massCenter.scale(1.0 / getSize());
        // return massCenter;
    }

    /**
     * Returns the maximum distance between any point of this component and the specified point
     *
     * @param point 3D Point
     * @return double
     */
    public double getMaxDistanceTo(final Point3d point) {
        double maxDist = 0;

        for (final Point3i p : this) {
            final double dist = point.distance(new Point3d(p.x, p.y, p.z));
            if (dist > maxDist)
                maxDist = dist;
        }

        return maxDist;
    }

    /**
     * The size in pixels (or voxels) of this component
     *
     * @return The size in pixels (or voxels) of this component
     */
    public int getSize() {
        return points.size();
    }

    /**
     * @return An array containing all the pixels of this component
     */
    public Point3i[] getPoints() {
        return points.toArray(new Point3i[getSize()]);
    }

    /**
     * @param outputSequence (set to null if not wanted) an output sequence to receive the extracted contour
     * @return An array containing all the contour pixels of this component
     */
    public Point3i[] getContourPoints(final Sequence outputSequence) {
        if (contourPoints == null) {
            final ArrayList<Point3i> list = new ArrayList<>(getSize() / 2);

            final Point3i min = new Point3i();
            final Point3i max = new Point3i();
            shapeDescriptor.computeBoundingBox(this, min, max);

            final Sequence mask = toSequence();
            final boolean outputVolatile;
            final int w = mask.getSizeX();
            final int h = mask.getSizeY();
            final int d = mask.getSizeZ();

            byte[][] outputMask = null;

            if (outputSequence != null) {
                outputSequence.removeAllImages();
                for (int i = 0; i < d; i++)
                    outputSequence.setImage(0, i, new IcyBufferedImage(w, h, 1, DataType.UBYTE));

                // easier to temporary put in no volatile
                outputVolatile = outputSequence.isVolatile();
                outputSequence.setVolatile(false);

                outputMask = outputSequence.getDataXYZAsByte(0, 0);
            }
            else
                outputVolatile = false;

            final byte[][] mask_z_xy = mask.getDataXYZAsByte(0, 0);

            final Point3i localP = new Point3i();

            if (min.z != max.z) {
                mainLoop:
                for (final Point3i p : points) {
                    localP.sub(p, min);

                    final int xy = localP.y * w + localP.x;

                    if (localP.x == 0 || localP.y == 0 || localP.x == w - 1 || localP.y == h - 1 || localP.z == 0
                            || localP.z == d - 1) {
                        list.add(p);
                        if (outputMask != null)
                            outputMask[localP.z][xy] = (byte) 1;
                        continue;
                    }

                    for (final byte[] z : new byte[][]{mask_z_xy[localP.z - 1], mask_z_xy[localP.z],
                            mask_z_xy[localP.z + 1]})
                        if (z[xy - w] == 0 || z[xy - 1] == 0 || z[xy + 1] == 0 || z[xy + w] == 0 || z[xy - w - 1] == 0
                                || z[xy - w + 1] == 0 || z[xy + w - 1] == 0 || z[xy + w + 1] == 0) {
                            list.add(p);
                            if (outputMask != null)
                                outputMask[localP.z][xy] = (byte) 1;
                            continue mainLoop;
                        }

                    // the top and bottom neighbors were forgotten in the previous loop
                    if (mask_z_xy[localP.z - 1][xy] == 0 || mask_z_xy[localP.z + 1][xy] == 0) {
                        list.add(p);
                    }
                }
            }
            else {
                for (final Point3i p : points) {
                    localP.sub(p, min);

                    final int xy = localP.y * w + localP.x;

                    if (localP.x == 0 || localP.y == 0 || localP.x == w - 1 || localP.y == h - 1) {
                        list.add(p);
                        if (outputMask != null)
                            outputMask[localP.z][xy] = (byte) 1;
                        continue;
                    }

                    final byte[] z = mask_z_xy[localP.z];

                    if (z[xy - w] == 0 || z[xy - 1] == 0 || z[xy + 1] == 0 || z[xy + w] == 0)
                    // || z[xy - w - 1] == 0
                    // || z[xy - w + 1] == 0
                    // || z[xy + w - 1] == 0
                    // || z[xy + w + 1] == 0)
                    {
                        list.add(p);
                        if (outputMask != null)
                            outputMask[localP.z][xy] = (byte) 1;
                    }
                }
            }

            // restore back volatile state
            if ((outputSequence != null) && outputVolatile)
                outputSequence.setVolatile(true);

            contourPoints = list.toArray(new Point3i[0]);
        }

        return contourPoints;
    }

    /**
     * @param component ConnectedComponent
     * @return true if this component intersects (e.g. has at least one voxel overlapping with) the
     * specified component
     */
    public boolean intersects(final ConnectedComponent component) {
        final int thisSize = getSize();
        final int componentSize = component.getSize();

        final Point3i thisPt = new Point3i();
        final Point3i componentPt = new Point3i();

        for (int i = 0; i < thisSize; i++) {
            thisPt.set(points.get(i));
            for (int j = 0; j < componentSize; j++) {
                componentPt.set(component.points.get(j));
                if (thisPt.equals(componentPt))
                    return true;
            }
        }

        return false;
    }

    /**
     * Creates a new component with the intersection between the current and specified components
     *
     * @param component ConnectedComponent
     * @return ConnectedComponent
     */
    public ConnectedComponent intersection(final ConnectedComponent component) {
        final ConnectedComponent intersection = new ConnectedComponent(0);

        // construct the intersection
        for (final Point3i srcPt : this)
            for (final Point3i dstPt : component) {
                if (srcPt.equals(dstPt)) {
                    intersection.addPointInternal(srcPt);
                    break;
                }
            }

        return intersection;
    }

    @Override
    public Iterator<Point3i> iterator() {
        return points.iterator();
    }

    /**
     * Paints this component onto the given sequence with the specified value
     *
     * @param s     Sequence
     * @param t     int
     * @param c     int
     * @param value double
     */
    public void paintOnSequence(final Sequence s, final int t, final int c, final double value) throws InterruptedException {
        DataIteratorUtil.set(new SequenceDataIterator(s, toROI(), true, -1, t, c), value);
    }

    public void setC(final int c) {
        this.c = c;
    }

    /**
     * @return a ROI of type area representing this connected component
     */
    public ROI toROI() {
        int z = 0;

        final ROI3DArea area3D = new ROI3DArea();
        area3D.beginUpdate();
        for (final Point3i pt : this) {
            z = pt.z;
            area3D.addPoint(pt.x, pt.y, pt.z);
        }
        area3D.setT(getT());
        area3D.endUpdate();

        if (area3D.getSizeZ() > 1)
            return area3D;

        // if there is only a single slice, z will tell us
        final ROI2DArea area2D = area3D.getSlice(z);
        area2D.setZ(z);

        return area2D;
    }

    /**
     * Creates a new byte sequence filled with this component. The sequence has a unique time point,
     * and the image size is equal to the bounding box of this component.
     *
     * @return Sequence
     */
    public Sequence toSequence() {
        final Sequence seq = new Sequence();

        // get the bounding box first

        final Point3i start = new Point3i();
        final Point3i end = new Point3i();

        shapeDescriptor.computeBoundingBox(this, start, end);

        // initialize the sequence

        final int depth = 1 + end.z - start.z;
        final int height = 1 + end.y - start.y;
        final int width = 1 + end.x - start.x;

        for (int z = 0; z < depth; z++)
            seq.setImage(0, z, new IcyBufferedImage(width, height, 1, DataType.UBYTE));

        // force non volatile
        final boolean v = seq.isVolatile();
        seq.setVolatile(false);
        try {
            // fill up the created sequence
            final byte[][] z_xy = seq.getDataXYZAsByte(0, 0);

            for (final Point3i point : points)
                z_xy[point.z - start.z][(point.y - start.y) * width + (point.x - start.x)] = (byte) 1;
        }
        finally {
            // restore volatile state
            seq.setVolatile(v);
        }

        return seq;
    }

    @Override
    public String toString() {
        return "[" + getX() + ", " + getY() + ", " + getZ() + "], " + getSize() + " points";
    }
}
