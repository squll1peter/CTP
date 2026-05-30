/*---------------------------------------------------------------
 *  Copyright 2026 by the Radiological Society of North America
 *
 *  This source software is released under the terms of the
 *  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
 *----------------------------------------------------------------*/

package org.rsna.ctp.plugin;

import org.rsna.ctp.objects.DicomObject;

/**
 * Contract for plugins that receive stability notifications from
 * StabilityMonitorProcessor.
 */
public interface StabilityNotificationPlugin {

	/**
	 * Notify the plugin that a stability group is ready.
	 * @param representative the representative DicomObject for the group; may be null
	 * @return true if the notification was accepted; false otherwise
	 */
	public boolean notify(DicomObject representative);
}
