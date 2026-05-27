/*---------------------------------------------------------------
 *  Copyright 2016 by the Radiological Society of North America
 *
 *  This source software is released under the terms of the
 *  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
 *----------------------------------------------------------------*/

package org.rsna.ctp.objects;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Encapsulate the index of private element names listed in an anonymizer script.
 */
public class PrivateNameIndex {

	ConcurrentHashMap<String,Integer> index;
	
	public PrivateNameIndex() {
		index = new ConcurrentHashMap<String,Integer>();
	}
	
	public void putTag(String name, int tag) {
		index.put(name.toLowerCase(), Integer.valueOf(tag));
	}
	
	public int getTag(String name) {
		int tag = 0;
		Integer tagInteger = index.get(name.toLowerCase());
		if (tagInteger != null) tag = tagInteger.intValue();
		return tag;
	}
}
			
