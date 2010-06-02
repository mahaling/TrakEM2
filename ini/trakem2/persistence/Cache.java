package ini.trakem2.persistence;

import ij.ImagePlus;
import ini.trakem2.utils.Utils;

import java.awt.Image;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;

/** Access is not synchronized, that is your duty.
 * 
 *  The current setup depends on calls to removeAndFlushSome to clean up empty slots;
 *  otherwise these slots are never cleaned up to avoid O(n) overhead (worst case)
 *  when removing a Pyramid for a given id, or O(1) cost of checking whether the first interval
 *  is empty and removing it. Granted, the latter could be done in all calls to @method append,
 *  but in the current setup this overhead is just not necessary.
 *  
 *  This Cache self-regulates the size to stay always at or below max_bytes.
 *  If the smallest image added is larger than max_bytes, then that image will be the only
 *  one in the cache, and will be thrown out when adding a new image.
 *  That is, the max_bytes is an indication for a desired maximum. The usual is that
 *  the cache will stay below max_bytes, unless when a single image is larger than max_bytes.
 *  Also, momentarily when adding an image, max_bytes may be overflown by maximum the
 *  size of the newly added image. Take that into account when choosing a value for max_bytes.
 *  
 *  When an image is removed, either directly or out of house-keeping to stay under max_bytes,
 *  that image is flushed. ImagePlus instances are not flushed, but if they point to an Image,
 *  then that image is flushed.
 */
public class Cache {
	
	
	// TODO: keep internal count of the total number of bytes in the Cache
	// and on put, ensure that it's not beyond a given maximum.
	
	
	private final class Pyramid {
		private final Image[] images;
		private HashMap<Long,Pyramid> interval = null;
		private final long id;
		private ImagePlus imp;
		private int n_images; // counts non-null instances in images array

		Pyramid(final long id, final Image image, final int level) {
			this.id = id;
			this.images = new Image[maxLevel(image, level)];
			this.images[level] = image;
			this.n_images = 1;
		}
		
		/** *@param maxdim is the max(width, height) of the Patch that wraps @param imp,
		 *  i.e. the dimensions of the mipmap images. */
		Pyramid(final long id, final ImagePlus imp, final int maxdim) {
			this.id = id;
			this.imp = imp;
			this.images = new Image[maxLevel(maxdim)];
			this.n_images = 0;
		}

		/** Accepts a null @param img.
		 *  Returns number of bytes freed, assuming old and new have the same dimensions.
		 *  If it was null here and img is not null, returns zero: no bytes to free. */
		final long replace(final Image img, final int level) {
			long b = 0;
			try{
			if (null == images[level]) {
				if (null != img) {
					// A: only old is null
					images[level] = img;
					n_images++;
				}
				// else, B: both are null
				return 0; // no bytes were freed (rather, some were used)
			} else {
				//long b = 0;
				if (null == img) {
					// C: old is not null, and new is null: must return freed bytes
					n_images--;
					b = Cache.size(images[level]); // some bytes to free
					images[level].flush();
					images[level] = null;
				} else if (img != images[level]) {
					// D: both are not null, and are not the same instance:
					b = Cache.size(images[level]); // some bytes to free
					images[level].flush();
					images[level] = img;
				}
				return b;
			}
			} finally {
				Utils.log2("replace: null == images[" + level + "] : " + (null == images[level]) + ", null == img : " + (null == img) + ", return: " + b);
			}
		}

		/** Returns the number of bytes freed, assuming old and new have the same dimensions. */
		final long replace(final ImagePlus imp) {
			if (this.imp == imp) return 0; // assumes ImageProcessor has not changed in size
			long b = 0;
			if (null != this.imp) {
				if (null == imp) b = Cache.size(this.imp);
				this.imp.flush();
			}
			this.imp = imp;
			return b;
			// no need to do anything else. If the dimensions of the mipmaps had changed, it would have been decached first.
		}
	}
	
	static private final int[] PIXEL_SIZE = new int[]{1, 2, 4, 1, 4}; // GRAY0, GRAY16, GRAY32, COLOR_256 and COLOR_RGB
	static private final int OVERHEAD = 1024; // in bytes: what a LUT would take (256 * 3) plus some extra
	
	static final long size(final ImagePlus imp) {
		return imp.getWidth() * imp.getHeight() * imp.getNSlices() * PIXEL_SIZE[imp.getType()] + OVERHEAD;
	}
	
	static final long size(final Image img) {
		return img.getWidth(null) * img.getHeight(null) * 4 + OVERHEAD; // assume int[] image
 	}
	
	static private final int computeLevel(final int i) {
		return (int)(0.5 + ((Math.log(i) - Math.log(32)) / Math.log(2))) + 1;
	}

	/** The position in the array is the Math.max(width, height) of an image. */
	private final static int[] max_levels = new int[50000]; // don't change to smaller than 33. Here 50000 is the maximum width or height for which precomputed mipmap levels will exist.
	static {
		// from 0 to 31 all zeros
		for (int i=32; i<max_levels.length; i++) {
			max_levels[i] = computeLevel(i);
		}
	}
	
	static private int maxLevel(final int maxdim) {
		return maxdim < max_levels.length ? max_levels[maxdim] : computeLevel(maxdim);
	}
	
	private final int maxLevel(final Image image, final int starting_level) {
		/*
		final int w = image.getWidth(null);
		final int h = image.getHeight(null);
		int max_level = starting_level;

		while (w > 32 || h > 32) {
			w /= 2;
			h /= 2;
			max_level++;
		}
		return max_level;
		*/

		final int max = Math.max(image.getWidth(null), image.getHeight(null));
		return starting_level + (max < max_levels.length ? max_levels[max] : computeLevel(max));

		/*
		if (max >= max_levels.length) {
			return starting_level + computeLevel(max);
		} else {
			return starting_level + max_levels[max];
		}
		*/
	}
	
	///////////////
	
	private final HashMap<Long,Pyramid> pyramids = new HashMap<Long,Pyramid>();
	private final LinkedList<HashMap<Long,Pyramid>> intervals = new LinkedList<HashMap<Long,Pyramid>>();
	private int count = 0;
	private long bytes = 0,
				 max_bytes = 0; // negative values are ok
	
	public Cache(final long max_bytes) {
		this.max_bytes = max_bytes;
	}
	
	public void setMaxBytes(final long max_bytes) {
		if (max_bytes < this.max_bytes) {
			removeAndFlushSome(this.max_bytes - max_bytes);
		}
		this.max_bytes = max_bytes;
	}
	
	/** Remove and flush the minimal amount of images to ensure there are at least min_free_bytes free. */
	public final long ensureFree(final long min_free_bytes) {
		if (bytes + min_free_bytes > max_bytes) {
			// remove the difference (or a bit more):
			return removeAndFlushSome(bytes + min_free_bytes - max_bytes);
		}
		return 0;
	}
	
	/** Maximum desired space for this cache. */
	public long getMaxBytes() { return max_bytes; }
	
	/** Current estimated space occupied by the images in this cache. */
	public long getBytes() { return bytes; }
	
	public final boolean contains(final long id) {
		return pyramids.containsKey(id);
	}

	public final boolean contains(final long id, final int level) {
		final Pyramid p = pyramids.get(id);
		return null != p && null != p.images[level];
	}

	public final Image get(final long id, final int level) {
		final Pyramid p = pyramids.get(id);
		if (null == p) return null;
		if (null == p || null == p.images[level]) return null;
		
		update(p);
		
		return p.images[level];
	}
	
	public final ImagePlus get(final long id) {
		final Pyramid p = pyramids.get(id);
		if (null == p) return null;
		if (null == p.imp) return null;
		
		update(p);
		
		return p.imp;
	}

	public final Map<Integer,Image> getAll(final long id) {
		final Pyramid p = pyramids.get(id);
		final HashMap<Integer,Image> m = new HashMap<Integer,Image>();
		if (null == p) return m;
		for (int i=0; i<p.images.length; i++) {
			if (null != p.images[i]) m.put(i, p.images[i]);
		}
		update(p);
		return m;
	}

	public final Image getClosestAbove(final long id, final int level) {
		final Pyramid p = pyramids.get(id);
		if (null == p) return null;
		for (int i=Math.min(level, p.images.length-1); i>-1; i--) {
			if (null == p.images[i]) continue;
			update(p);
			return p.images[i];
		}
		return null;
	}

	// Below or equal
	public final Image getClosestBelow(final long id, final int level) {
		final Pyramid p = pyramids.get(id);
		if (null == p) return null;
		for (int i=level; i<p.images.length; i++) {
			if (null == p.images[i]) continue;
			update(p);
			return p.images[i];
		}
		return null;
	}

	static private final int MAX_INTERVAL_SIZE = 20;
	private HashMap<Long,Pyramid> last_interval = new HashMap<Long,Pyramid>(MAX_INTERVAL_SIZE);
	{
		intervals.add(last_interval);
	}
	
	private final void reset() {
		pyramids.clear();
		intervals.clear();
		count = 0;
		bytes = 0;
		last_interval = new HashMap<Long, Pyramid>(MAX_INTERVAL_SIZE);
		intervals.add(last_interval);
	}
	
	private final void update(final Pyramid p) {
		// Last-access -based priority queue:
		// Remove from current interval and append to last interval
		if (last_interval != p.interval) {
			p.interval.remove(p.id);
			append(p);
		}
	}

	/** Append the key to the last interval, creating a new interval if the last is full.
	 *  Then set that interval as the key's interval. */
	private final void append(final Pyramid p) {
		// May have been removed:
		if (0 == intervals.size()) intervals.add(last_interval);
		// Push an new interval if the last one is full:
		if (last_interval.size() >= MAX_INTERVAL_SIZE) {
			last_interval = new HashMap<Long,Pyramid>(MAX_INTERVAL_SIZE);
			intervals.add(last_interval);
		}

		last_interval.put(p.id, p);
		// Reflection: tell the Pyramid instance where it is
		p.interval = last_interval;
	}

	/** Makes up space to fit b, and also drops empty intervals from the head. */
	private final void fit(final long b) {
		Utils.log2("bytes before: " + bytes + " and adding " + b + " : " + (bytes + b));
		bytes += b;
		if (bytes > max_bytes) {
			removeAndFlushSome(bytes - max_bytes);
			Utils.log2("-- after removeAndFlushSome(" + (max_bytes - bytes) + "): " + bytes);
		}
	}
	
	// If already there, move to latest interval
	// If the image is different, flush the old image
	public final void put(final long id, final Image image, final int level) {
		Pyramid p = pyramids.get(id);
		if (null == p) {
			p = new Pyramid(id, image, level);
			pyramids.put(id, p);
			append(p);
			fit(Cache.size(image)); // AFTER adding it
		} else {
			update(p);
			if (0 == p.replace(image, level)) {
				fit(Cache.size(image)); // AFTER adding the image to the empty level
			}
		}
		count++;
	}
	
	/** @param maxdim is max(width, height) of the Patch wrapping @param imp;
	 *  that is, the dimensions of the mipmap image. */
	public final void put(final long id, final ImagePlus imp, final int maxdim) {
		Pyramid p = pyramids.get(id);
		if (null == p) {
			p = new Pyramid(id, imp, maxdim);
			pyramids.put(id, p);
			append(p);
			fit(Cache.size(imp)); // AFTER adding it
		} else {
			update(p);
			if (0 == p.replace(imp)) {
				fit(Cache.size(imp));
			}
		}
		count++;
	}


	// WARNING: an empty interval may be left behind. Will be cleaned up by removeAndFlushSome.
	/** Remove one mipmap level, if there. */
	public final Image remove(final long id, final int level) {
		final Pyramid p = pyramids.get(id);
		if (null == p) return null;
		final Image im = p.images[level];
		bytes -= p.replace(null, level);
		count--;
		// If at least one level is still not null, keep the pyramid; otherwise drop it
		if (0 == p.n_images) {
			p.interval.remove(id);
			pyramids.remove(id);
		}
		return im;
	}
	
	/** Remove only the ImagePlus, if there. */
	public final ImagePlus removeImagePlus(final long id) {
		final Pyramid p = pyramids.get(id);
		if (null == p.imp) return null;
		final ImagePlus imp = p.imp;
		bytes -= p.replace(null);
		count--;
		if (0 == p.n_images) {
			p.interval.remove(id);
			pyramids.remove(id);
		}
		return imp;
	}
	
	public final void remove(final long id) {
		final Pyramid p = pyramids.remove(id);
		if (null == p) return;
		bytes -= p.replace(null); // the imp may need cleanup
		for (int i=0; i<p.images.length; i++) {
			bytes -= p.replace(null, i);
		}
		p.interval.remove(id);
	}
	
	/** Flush all mipmaps and imps. */
	public final void removeAndFlushAll() {
		for (final Pyramid p : pyramids.values()) {
			p.replace(null); // the imp may need cleanup
			for (int i=0; i<p.images.length; i++) {
				if (null == p.images[i]) continue;
				p.images[i].flush();
			}
		}
		reset();
	}

	// WARNING: an empty interval may be left behind. Will be cleaned up by removeAndFlushSome.
	/** Does not alter the ImagePlus. */
	public final void removeAndFlushPyramid(final long id) {
		final Pyramid p = pyramids.get(id);
		if (null == p) return;
		bytes -= p.replace(null); // the imp may need cleanup
		for (int i=0; i<p.images.length; i++) {
			bytes -= p.replace(null, i);
		}
		if (null == p.imp) {
			pyramids.remove(id);
			p.interval.remove(id);
		}
	}
	
	/** Returns the number of released bytes. */
	public final long removeAndFlushSome(final long min_bytes) {
		long size = 0;
		try {
			Utils.log2("removeAndFlushSome: now: " + this.bytes + ", want to remove: " + min_bytes);


		while (intervals.size() > 0) {
			final HashMap<Long,Pyramid> interval = intervals.getFirst();
			for (final Iterator<Pyramid> it = interval.values().iterator(); it.hasNext(); ) {
				final Pyramid p = it.next();
				if (null != p.imp) {
					final long s = p.replace(null); // the imp may need cleanup
					size += s;
					this.bytes -= s;
					count--;
					if (size >= min_bytes) {
						if (0 == p.n_images) {
							pyramids.remove(p.id);
							it.remove();
							if (interval.isEmpty()) intervals.removeFirst();
						}
						return size;
					}
				}
				for (int i=0; i<p.images.length && p.n_images > 0; i++) {
					if (null == p.images[i]) continue;
					final long s = p.replace(null, i);
					size += s;
					this.bytes -= s;
					Utils.log2("     this.bytes = " + this.bytes  + "   s: " + s);
					count--;
					if (size >= min_bytes) {
						if (0 == p.n_images) {
							pyramids.remove(p.id);
							it.remove();
							if (interval.isEmpty()) intervals.removeFirst();
						}
						return size;
					}
				}
				pyramids.remove(p.id);
				it.remove(); // from the interval
			}
			intervals.removeFirst();
		}
		return size;
		
		} finally {
			Utils.log2("  == bytes removed: " + size + ", remaining: " + this.bytes);
		}
	}

	public final long removeAndFlushSome(int n) {
		long size = 0;
		while (intervals.size() > 0) {
			final HashMap<Long,Pyramid> interval = intervals.getFirst();
			for (final Iterator<Pyramid> it = interval.values().iterator(); it.hasNext(); ) {
				final Pyramid p = it.next();
				if (null != p.imp) {
					final long s = p.replace(null);
					size += s;
					this.bytes -= s;
					p.replace(null); // the imp may need cleanup
					n--;
					count--;
					if (0 == n) {
						if (0 == p.n_images) {
							pyramids.remove(p.id);
							it.remove();
							if (interval.isEmpty()) intervals.removeFirst();
						}
						return size;
					}
				}
				for (int i=0; i<p.images.length; i++) {
					if (null == p.images[i]) continue;
					final long s = p.replace(null, i);
					size += s;
					this.bytes -= s;
					n--;
					count--;
					if (0 == n) {
						if (0 == p.n_images) {
							pyramids.remove(p.id);
							it.remove();
							if (interval.isEmpty()) intervals.removeFirst();
						}
						return size;
					}
				}
				pyramids.remove(p.id);
				it.remove(); // from the interval
			}
			intervals.removeFirst();
		}
		return size;
	}

	public final int size() {
		return count;
	}
	
	public void debug() {
		Utils.log2("@@@@@@@@@@ START");
		Utils.log2("pyramids: " + pyramids.size());
		for (Map.Entry<Long,Pyramid> e : new TreeMap<Long,Pyramid>(pyramids).entrySet()) {
			Utils.log2("p id:" + e.getKey() + " value: " + e.getValue().images.length + " imp: " + e.getValue().imp);
		}
		Utils.log2("----");
		int i = 0;
		for (HashMap<Long,Pyramid> m : intervals) {
			Utils.log2("interval " + (++i));
			for (Map.Entry<Long,Pyramid> e : new TreeMap<Long,Pyramid>(m).entrySet()) {
				Utils.log2("p id:" + e.getKey() + " value: " + e.getValue().images.length + " imp: " + e.getValue().imp);
			}
		}
		Utils.log2("----");
		// Analytics
		Utils.log2("count is: " + count + ", size is: " + bytes + " / " + max_bytes + ", intervals.size = " + intervals.size() + ", pyr.size = " + pyramids.size());
		HashMap<Integer,Integer> s = new HashMap<Integer,Integer>();
		for (HashMap<Long,Pyramid> m : intervals) {
			int l = m.size();
			Integer in = s.get(l);
			if (null == in) s.put(l, 1);
			else s.put(l, in.intValue() + 1);
		}
		Utils.log2("interval size distribution: ", s);
	}
	
	public final long seqFindId(final ImagePlus imp) {
		for (final Pyramid p : pyramids.values()) {
			if (p.imp == imp) return p.id;
		}
		return Long.MIN_VALUE;
	}
}