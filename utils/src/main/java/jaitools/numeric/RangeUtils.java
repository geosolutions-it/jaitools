/*
 * Copyright 2010 Michael Bedward
 *
 * This file is part of jai-tools.
 *
 * jai-tools is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * jai-tools is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with jai-tools.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package jaitools.numeric;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Provides static helper methods to transform, sort and merge {@code Range} objects.
 *
 * @author Michael Bedward
 * @since 1.1
 * @version $Id$
 */
public class RangeUtils {

    private static class RangeSortComparator<T extends Number & Comparable> implements Comparator<Range<T>> {
        private RangeComparator<T> rc;

        public RangeSortComparator(RangeComparator<T> rc) {
            this.rc = rc;
        }

        public int compare(Range<T> r1, Range<T> r2) {
            RangeComparator.Result result = rc.compare(r1, r2);
            switch (result.getAt(RangeComparator.MIN_MIN)) {
                case RangeComparator.LT:
                    return -1;

                case RangeComparator.GT:
                    return 1;

                default:
                    switch (result.getAt(RangeComparator.MAX_MAX)) {
                        case RangeComparator.LT:
                            return -1;

                        case RangeComparator.GT:
                            return 1;

                        default:
                            return 0;
                    }
            }
        }

    }


    public static <T extends Number & Comparable> List<Range<T>> createComplement(Range<T> range) {
        List<Range<T>> complements = new ArrayList<Range<T>>();

        // special case: point range
        if (range.isPoint()) {
            if (range.isMinInf() || range.isMinNegInf()) {
                complements.add(Range.create((T)null, true, (T)null, true));
                
            } else {  // finite point
                complements.add(Range.create(null, true, range.getMin(), false));
                complements.add(Range.create(range.getMin(), false, null, true));
            }

        } else {  // interval range
            if (range.isMinClosed()) {
                complements.add(Range.create(null, true, range.getMin(), !range.isMinIncluded()));
            }
            if (range.isMaxClosed()) {
                complements.add(Range.create(range.getMax(), !range.isMaxIncluded(), null, true));
            }
            // BIG LETTERS
        }

        return complements;
    }

    /**
     * Sort a collection of {@code Ranges} in ascending order of min value, then max value.
     *
     * @param ranges the {@code Ranges} to sort
     *
     * @return sorted {@code Ranges}
     */
    public static <T extends Number & Comparable> List<Range<T>> sort(Collection<Range<T>> ranges) {
        List<Range<T>> inputs = new ArrayList<Range<T>>(ranges);
        Collections.sort(inputs, new RangeSortComparator(new RangeComparator<T>()));
        return inputs;
    }

    /**
     * Takes a collection of ranges and returns a simplified collection by merging ranges
     * that overlap.
     *
     * @param ranges input ranges to simplify
     *
     * @return simplified ranges sorted by min, then max end-points
     */
    public static <T extends Number & Comparable> List<Range<T>> simplify(Collection<Range<T>> ranges) {
        List<Range<T>> inputs = new ArrayList<Range<T>>(ranges);
        List<Range<T>> simplified = new ArrayList<Range<T>>();
        RangeComparator<T> comparator = new RangeComparator<T>();

        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < inputs.size()-1 && !changed; i++) {
                Range<T> r1 = inputs.get(i);
                for (int j = i+1; j < inputs.size() && !changed; j++) {
                    Range<T> r2 = inputs.get(j);
                    RangeComparator.Result result = comparator.compare(r1, r2);
                    if (RangeComparator.isIntersection(result)) {
                        switch (result) {
                            case EEEE:  // r1 and r2 are equal points
                            case EEGG:  // r2 is a point at min of r1
                            case LEEG:  // equal intervals
                            case LEGG:  // r1 contains r2
                            case LLEE:  // r2 is a point at max of r1
                            case LLEG:  // r1 contains r2
                            case LLGG:  // r1 contains r2
                                inputs.remove(j);
                                break;

                            case EGEG:  // r1 is a point at max of r2
                            case LELE:  // r1 is a point at min of r2
                            case LELG:  // r1 is contained in r2
                            case LGEG:  // r1 is contained in r2
                            case LGLG:  // r1 is contained in r2
                                inputs.remove(i);
                                break;

                            case EGGG:  // r1 extends from max of r2
                            case LGGG:  // r1 starts within and extends beyond r2
                                inputs.remove(j);
                                inputs.remove(i);
                                inputs.add(0, new Range<T>(r2.getMin(), r2.isMinIncluded(), r1.getMax(), r1.isMaxIncluded()));
                                break;

                            case LLLE:  // r1 extends to min of r2
                            case LLLG:  // r1 extends into r2
                                inputs.remove(j);
                                inputs.remove(i);
                                inputs.add(0, new Range<T>(r1.getMin(), r1.isMinIncluded(), r2.getMax(), r2.isMaxIncluded()));
                                break;
                        }
                        changed = true;
                    }
                }
            }

        } while (changed);

        /*
         * Next, look for any pairs of the form [A, B) [B, C] that can be joined as [A, C]
         */
        Collections.sort(inputs, new RangeSortComparator(comparator));
        do {
            changed = false;
            for (int i = 0; i < inputs.size() - 1 && !changed; i++) {
                Range<T> r1 = inputs.get(i);
                if (r1.isMaxClosed()) {
                    for (int j = i + 1; j < inputs.size() && !changed; j++) {
                        Range<T> r2 = inputs.get(j);
                        if (r2.isMinClosed()) {
                            if (r1.getMax().compareTo(r2.getMin()) == 0) {
                                inputs.remove(j);
                                inputs.remove(i);
                                inputs.add(i, new Range<T>(r1.getMin(), r1.isMinIncluded(), r2.getMax(), r2.isMaxIncluded()));
                                changed = true;
                            }
                        }
                    }
                }
            }
        } while (changed);

        return inputs;
    }

    /**
     * Return the intersection of the two ranges.
     *
     * @param r1 first range
     * @param r2 second range
     *
     * @return a new {@code Range} representing the intersection or null if the inputs
     *         do not intersect
     */
    public static <T extends Number & Comparable> Range<T> intersection(Range<T> r1, Range<T> r2) {
        RangeComparator<T> rc = new RangeComparator<T>();
        RangeComparator.Result result = rc.compare(r1, r2);
        if (RangeComparator.isIntersection(result)) {
            T min;
            boolean minIncluded;
            switch (result.getAt(RangeComparator.MIN_MIN)) {
                case RangeComparator.LT:
                    min = r2.getMin();
                    minIncluded = r2.isMinIncluded();
                    break;

                case RangeComparator.GT:
                    min = r1.getMin();
                    minIncluded = r1.isMinIncluded();
                    break;

                default:
                    min = r1.getMin();
                    minIncluded = r1.isMinIncluded() || r2.isMinIncluded();
                    break;
            }

            T max;
            boolean maxIncluded;
            switch (result.getAt(RangeComparator.MAX_MAX)) {
                case RangeComparator.LT:
                    max = r1.getMax();
                    maxIncluded = r1.isMaxIncluded();
                    break;

                case RangeComparator.GT:
                    max = r2.getMax();
                    maxIncluded = r2.isMaxIncluded();
                    break;

                default:
                    max = r1.getMax();
                    maxIncluded = r1.isMaxIncluded() || r2.isMaxIncluded();
                    break;
            }

            return new Range<T>(min, minIncluded, max, maxIncluded);
        }

        return null;
    }

    /**
     * Subtract the first {@code Range} from the second. If the two inputs do not intersect
     * the result will be equal to the second. If the two inputs are equal, or the first
     * input encloses the second, the result will be an empty list. If the first input
     * is strictly contained within the second the result will be two {@code Ranges}.
     * 
     * @param r1 the first range
     * @param r2 the second range
     * 
     * @return 0, 1 or 2 {@code Ranges} representing the result of {@code r2 - r1}
     */
    public static <T extends Number & Comparable> List<Range<T>> subtact(Range<T> r1, Range<T> r2) {
        List<Range<T>> difference = new ArrayList<Range<T>>();
        /*
         * Check for equality between inputs
         */
        if (r1.equals(r2)) {
            return difference;  // empty list
        }

        Range<T> common = intersection(r1, r2);
        
        /*
         * Check if r1 enclosed r2
         */
        if (common.equals(r2)) {
            return difference;  // empty list
        }

        /*
         * Check for no overlap between inputs
         */
        if (common == null) {
            difference.add( new Range<T>(r2) );
            return difference;
        }

        RangeComparator<T> rc = new RangeComparator<T>();
        RangeComparator.Result result = rc.compare(common, r2);

        int minComp = result.getAt(RangeComparator.MIN_MIN);
        int maxComp = result.getAt(RangeComparator.MAX_MAX);

        if (minComp == RangeComparator.EQ) {
            difference.add(new Range<T>(common.getMax(), !common.isMaxIncluded(), r2.getMax(), r2.isMaxIncluded()));
        } else {  // minComp == GT
            if (maxComp == RangeComparator.EQ) {
                difference.add(new Range<T>(r2.getMin(), r2.isMinIncluded(), common.getMin(), !common.isMinIncluded()));
            } else {
                // common lies within r2
                difference.add(new Range<T>(r2.getMin(), r2.isMinIncluded(), common.getMin(), !common.isMinIncluded()));
                difference.add(new Range<T>(common.getMax(), !common.isMaxIncluded(), r2.getMax(), r2.isMaxIncluded()));
            }
        }

        return difference;
    }
}

