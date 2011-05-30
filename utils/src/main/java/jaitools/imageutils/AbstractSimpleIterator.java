/*
 * Copyright 2011 Michael Bedward
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

package jaitools.imageutils;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.DataBuffer;
import java.awt.image.RenderedImage;
import java.lang.ref.WeakReference;

import javax.media.jai.iterator.RectIter;


/**
 * Base class for image iterators with row-column (line-pixel) movement.
 *
 * @author michael
 */
public abstract class AbstractSimpleIterator {

    /**
     * This is implemented by sub-classes to pass a method back to
     * this class to create the delegate iterator. This allows the
     * delegate to be a final field.
     */
    protected static interface DelegateHelper {
        RectIter create(RenderedImage image, Rectangle bounds);
    }
    
    protected final WeakReference<RenderedImage> imageRef;
    protected final int imageDataType;
    protected final Rectangle imageBounds;
    protected final int numImageBands;
    
    protected final Rectangle iterBounds;
    protected final Point mainPos;
    protected final Point lastPos;
    protected final Number outsideValue;

    protected final RectIter delegateIter;
    protected final Rectangle delegateBounds;
    protected final Point delegatePos;

    
    /**
     * Creates a new instance. The helper object is provided by a sub-class 
     * to create the delegate iterator that will then be held by this class as
     * a final field. The iterator bounds are allowed to extend beyond the 
     * target image bounds. When the iterator is positioned outside the target
     * image area it returns the specified outside value.
     * 
     * @param helper a helper provided by sub-class to create the delegate iterator
     * @param image the target image
     * @param bounds the bounds for this iterator; {@code null} means to use the
     *     target image bounds
     * @param outsideValue value to return when positioned outside the bounds of
     *     the target image
     * 
     * @throws IllegalArgumentException if the image argument is {@code null}
     */
    public AbstractSimpleIterator(DelegateHelper helper, RenderedImage image, 
            Rectangle bounds, Number outsideValue) {
        
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        
        imageRef = new WeakReference<RenderedImage>(image);
        imageDataType = image.getSampleModel().getDataType();
        numImageBands = image.getSampleModel().getNumBands();
        
        imageBounds = new Rectangle(image.getMinX(), image.getMinY(),
                image.getWidth(), image.getHeight());
        
        if (bounds == null) {
            iterBounds = imageBounds;
        } else {
            iterBounds = new Rectangle(bounds);
        }

        delegateBounds = imageBounds.intersection(iterBounds);
        if (delegateBounds.isEmpty()) {
            delegatePos = null;
            delegateIter = null;
            
        } else {
            delegatePos = new Point(delegateBounds.x, delegateBounds.y);
            delegateIter = helper.create(image, delegateBounds);
        }
        
        mainPos = new Point(iterBounds.x, iterBounds.y);
        
        lastPos = new Point(
                iterBounds.x + iterBounds.width - 1, 
                iterBounds.y + iterBounds.height - 1);
        
        this.outsideValue = outsideValue;
    }

    /**
     * Gets the bounds of this iterator. Note that these may extend
     * beyond the bounds of the target image.
     * 
     * @return the iterator bounds
     */
    public Rectangle getBounds() {
        return new Rectangle(iterBounds);
    }

    /**
     * Tests if this iterator can be advanced further, ie. if a call to 
     * {@link #next()} would return {@code true}.
     * 
     * @return {@code true} if the iterator can be advanced; 
     *     {@code false} if it is at the end of its bounds
     */
    public boolean hasNext() {
        return (delegateIter != null && mainPos.x < lastPos.x || mainPos.y < lastPos.y);
    }

    /**
     * Advances the iterator to the next position. The iterator moves by
     * column (pixel), then row (line). It is always safe to call this
     * method speculatively.
     * 
     * @return {@code true} if the iterator was successfully advanced;
     *     {@code false} if it was already at the end of its bounds
     */
    public boolean next() {
        if (hasNext()) {
            mainPos.x++ ;
            if (mainPos.x > lastPos.x) {
                mainPos.x = iterBounds.x;
                mainPos.y++;
            }

            setDelegatePosition();
            return true;
        }

        return false;
    }

    /**
     * Resets the iterator to its first position.
     */
    public void reset() {
        mainPos.x = iterBounds.x;
        mainPos.y = iterBounds.y;
        setDelegatePosition();
    }

    /**
     * Gets the current iterator position. It is always safe to call
     * this method.
     * 
     * @return current position
     */
    public Point getPos() {
        return new Point(mainPos);
    }

    /**
     * Sets the iterator to a new position. Note that a return value of
     * {@code true} indicates that the new position was valid. If the new position
     * is equal to the iterator's current position this method will still
     * return {@code true} even though the iterator has not moved.
     * <p>
     * If the new position is outside this iterator's bounds, the position remains
     * unchanged and {@code false} is returned.
     * 
     * @param pos the new position
     * @return {@code true} if the new position was valid; {@code false}
     *     otherwise
     * 
     * @throws IllegalArgumentException if {@code pos} is {@code null}
     */
    public boolean setPos(Point pos) {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        return setPos(pos.x, pos.y);
    }

    /**
     * Sets the iterator to a new position. Note that a return value of
     * {@code true} indicates that the new position was valid. If the new position
     * is equal to the iterator's current position this method will still
     * return {@code true} even though the iterator has not moved.
     * <p>
     * If the new position is outside this iterator's bounds, the position remains
     * unchanged and {@code false} is returned.
     * 
     * @param x image X position
     * @param y image Y position
     * @return {@code true} if the new position was valid; {@code false}
     *     otherwise
     */
    public boolean setPos(int x, int y) {
        if (iterBounds.contains(x, y)) {
            mainPos.setLocation(x, y);
            setDelegatePosition();
            return true;
        }
        
        return false;
    }

    /**
     * Returns the value from the first band of the image at the current position,
     * or the outside value if the iterator is positioned beyond the image bounds.
     * 
     * @return image or outside value
     */
    public Number getSample() {
        return getSample(0);
    }

    /**
     * Returns the value from the first band of the image at the specified position,
     * If the position is within the iterator's bounds, but outside the target
     * image bounds, the outside value is returned. After calling this method, the
     * iterator will be set to the specified position.
     * <p>
     * If the position is outside the iterator's bounds, {@code null} is returned
     * and the iterator's position will remain unchanged.
     * 
     * @param pos the position to sample
     * @return image, outside value or {@code null}
     * 
     * @throws IllegalArgumentException if {@code pos} is {@code null}
     */
    public Number getSample(Point pos) {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        return getSample(pos.x, pos.y);
    }

    /**
     * Returns the value from the first band of the image at the specified position,
     * If the position is within the iterator's bounds, but outside the target
     * image bounds, the outside value is returned. After calling this method, the
     * iterator will be set to the specified position.
     * <p>
     * If the position is outside the iterator's bounds, {@code null} is returned
     * and the iterator's position will remain unchanged.
     * 
     * @param x sampling position X-ordinate
     * @param y sampling position Y-ordinate
     * @return image, outside value or {@code null}
     */
    public Number getSample(int x, int y) {
        if (setPos(x, y)) {
            return getSample();
        } else {
            return null;
        }
    }

    /**
     * Returns the value from the specified band of the image at the current position,
     * or the outside value if the iterator is positioned beyond the image bounds.
     * 
     * @param band image band
     * @return image or outside value
     * @throws IllegalArgumentException if {@code band} is out of range for the the
     *     target image
     */
    public Number getSample(int band) {
        RenderedImage image = imageRef.get();
        if (image == null) {
            throw new IllegalStateException("Target image has been deleted");
        }

        if (delegateBounds.contains(mainPos)) {
            switch (imageDataType) {
                case DataBuffer.TYPE_DOUBLE:
                    return new Double(delegateIter.getSampleDouble(band));

                case DataBuffer.TYPE_FLOAT:
                    return new Float(delegateIter.getSampleFloat(band));

                default:
                    return Integer.valueOf(delegateIter.getSample(band));
            }

        } else {
            return outsideValue;
        }
    }

    /**
     * Returns the value from the specified band of the image at the specified position,
     * If the position is within the iterator's bounds, but outside the target
     * image bounds, the outside value is returned. After calling this method, the
     * iterator will be set to the specified position.
     * <p>
     * If the position is outside the iterator's bounds, {@code null} is returned
     * and the iterator's position will remain unchanged.
     * 
     * @param pos the position to sample
     * @param band image band
     * @return image, outside value or {@code null}
     * 
     * @throws IllegalArgumentException if {@code pos} is {@code null} or
     *     {@code band} is out of range
     */
    public Number getSample(Point pos, int band) {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        return getSample(pos.x, pos.y, band);
    }

    /**
     * Returns the value from the specified band of the image at the specified position,
     * If the position is within the iterator's bounds, but outside the target
     * image bounds, the outside value is returned. After calling this method, the
     * iterator will be set to the specified position.
     * <p>
     * If the position is outside the iterator's bounds, {@code null} is returned
     * and the iterator's position will remain unchanged.
     * 
     * @param x sampling position X-ordinate
     * @param y sampling position Y-ordinate
     * @param band image band
     * @return image, outside value or {@code null}
     * 
     * @throws IllegalArgumentException if {@code band} is out of range
     */
    public Number getSample(int x, int y, int band) {
        if (setPos(x, y)) {
            return getSample(band);
        } else {
            return null;
        }
    }

    /**
     * Sets the delegate iterator position. If {@code newPos} is outside
     * the target image bounds, the delegate iterator does not move.
     */
    protected void setDelegatePosition() {
        boolean inside = delegateBounds.contains(mainPos);
        if (inside) {
            int dy = mainPos.y - delegatePos.y;
            if (dy < 0) {
                delegateIter.startLines();
                delegatePos.y = delegateBounds.y;
                dy = mainPos.y - delegateBounds.y;
            }

            while (dy > 0) {
                delegateIter.nextLineDone();
                delegatePos.y++ ;
                dy--;
            }

            int dx = mainPos.x - delegatePos.x;
            if (dx < 0) {
                delegateIter.startPixels();
                delegatePos.x = delegateBounds.x;
                dx = mainPos.x - delegateBounds.x;
            }

            while (dx > 0) {
                delegateIter.nextPixelDone();
                delegatePos.x++ ;
                dx--;
            }
        }
    }

    /**
     * Helper method to check that a band value is valid.
     * 
     * @param band band value
     */
    protected void checkBandArg(int band) {
        if (band < 0 || band >= numImageBands) {
            throw new IllegalArgumentException( String.format(
                    "band argument (%d) is out of range: number of image bands is %d",
                    band, numImageBands) );
}
    }
}
