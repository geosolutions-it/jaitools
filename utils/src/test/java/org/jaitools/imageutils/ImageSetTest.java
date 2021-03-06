/* 
 *  Copyright (c) 2011, Michael Bedward. All rights reserved. 
 *   
 *  Redistribution and use in source and binary forms, with or without modification, 
 *  are permitted provided that the following conditions are met: 
 *   
 *  - Redistributions of source code must retain the above copyright notice, this  
 *    list of conditions and the following disclaimer. 
 *   
 *  - Redistributions in binary form must reproduce the above copyright notice, this 
 *    list of conditions and the following disclaimer in the documentation and/or 
 *    other materials provided with the distribution.   
 *   
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND 
 *  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED 
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE 
 *  DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR 
 *  ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES 
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; 
 *  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON 
 *  ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT 
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS 
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. 
 */   

package org.jaitools.imageutils;

import java.awt.image.RenderedImage;
import java.util.Map;

import javax.media.jai.TiledImage;

import org.jaitools.imageutils.ImageSet.Iterator;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author michael
 */
public class ImageSetTest extends TestBase {

    private static final int WIDTH = 17;
    private static final int HEIGHT = 19;
    private static final int NUM_BANDS = 3;
    private static final Integer OUTSIDE = Integer.valueOf(-1);

    private static final String[] NAMES = {"foo", "bar", "baz"};
    private final RenderedImage[] images = new RenderedImage[NAMES.length];
    private ImageSet<String> theSet;

    @Before
    public void setup() {
        theSet = new ImageSet<String>();
        for (int i = 0; i < NAMES.length; i++) {
            images[i] = createSequentialImage(WIDTH, HEIGHT, NUM_BANDS, i * 10);
            theSet.add(NAMES[i], images[i], OUTSIDE);
        }
    }

    @Test
    public void getNumImages() {
        assertEquals(NAMES.length, theSet.size());
    }
    
    @Test
    public void getImageByKey() {
        for (int i = 0; i < NAMES.length; i++) {
            // note: just testing identity here
            assertTrue(images[i] == theSet.get(NAMES[i]));
        }
    }

    @Test
    public void getIterSample() {
        ImageSet.Iterator<String> iterator = theSet.getIterator();

        int x = 0;
        int y = 0;
        do {
            Map<String, Number> sample = iterator.getSample();
            assertSample(sample, x, y, 0);

            x = (x + 1) % WIDTH;
            if (x == 0) {
                y++ ;
            }
        } while (iterator.next());
    }

    @Test
    public void getIterSampleByBand() {
        Iterator<String> iterator = theSet.getIterator();

        int x = 0;
        int y = 0;
        do {
            for (int band = 0; band < NUM_BANDS; band++) {
                Map<String, Number> sample = iterator.getSample(band);
                assertSample(sample, x, y, band);
            }

            x = (x + 1) % WIDTH;
            if (x == 0) {
                y++ ;
            }
        } while (iterator.next());
    }

    @Test
    public void copySet() {
        ImageSet<String> copy = ImageSet.copy(theSet);
        assertNotNull(copy);
        assertEquals(theSet.size(), copy.size());
        
        for (String key : copy.keySet()) {
            assertTrue(theSet.containsKey(key));
            assertTrue(theSet.get(key) == copy.get(key));
            assertTrue(theSet.getOutsideValue(key) == copy.getOutsideValue(key));
        }
    }

    private void assertSample(Map<String, ? extends Number> sample, int x, int y, int band) {
        assertEquals(NAMES.length, sample.size());
        for (int i = 0; i < NAMES.length; i++) {
            Number value = sample.get(NAMES[i]);
            assertNotNull(value);

            int imgValue = getImageValue(i, x, y, band);
            assertEquals(imgValue, value.intValue());
        }
    }

    private int getImageValue(int index, int x, int y, int band) {
        TiledImage timg = (TiledImage) images[index];
        return timg.getSample(x, y, band);
    }

}
