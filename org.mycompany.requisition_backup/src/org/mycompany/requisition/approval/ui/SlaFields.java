package org.mycompany.requisition.approval.ui;

/**
 * The hour fields shared by section 04 and section 07.
 * <p>
 * Both sections write the same SLA columns of the same level row, so the rule for
 * what an empty or negative hour means is stated once. Two copies of it would be
 * two chances for the two screens to disagree about what "0" means, and the SLA
 * monitor would then have to cope with both readings.
 *
 * <p>
 * The rule is: empty is not set, zero is not set, and a negative value is a
 * mistake the user has to see. Nothing is silently coerced.
 */
final class SlaFields {

	private SlaFields() {
		// helper class
	}

	/**
	 * Reads an hour box.
	 *
	 * @param box   the field
	 * @param label the field name, used in the error message
	 * @return the hours, or null when the field is empty or zero
	 * @throws IllegalArgumentException when the value is negative or not a number
	 */
	static Integer hoursOrNull(org.adempiere.webui.component.Textbox box, String label) {
		if (box == null)
			return null;
		String text = box.getValue();
		if (text == null || text.trim().length() == 0)
			return null;
		int hours;
		try {
			hours = Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(label + " must be a whole number of hours.");
		}
		if (hours < 0)
			throw new IllegalArgumentException(label + " cannot be negative.");
		if (hours == 0)
			return null;
		return Integer.valueOf(hours);
	}
}
