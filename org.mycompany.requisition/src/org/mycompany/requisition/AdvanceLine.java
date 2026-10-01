package org.mycompany.requisition;

import java.math.BigDecimal;

/** One line of the advance list (Segment, Charge, Remark, Amount). */
public class AdvanceLine {
	public final int segmentId;
	public final String segmentName;
	public final int chargeId;
	public final String chargeName;
	public final String remark;
	public final BigDecimal amount;

	public AdvanceLine(int segmentId, String segmentName, int chargeId, String chargeName,
			String remark, BigDecimal amount) {
		this.segmentId = segmentId;
		this.segmentName = segmentName;
		this.chargeId = chargeId;
		this.chargeName = chargeName;
		this.remark = remark;
		this.amount = amount;
	}
}
