package org.mycompany.requisition.docapproval;

/**
 * A decision that cannot be carried out, phrased so it can be shown to the
 * approver as it is.
 * <p>
 * Kept separate from a database exception because the usual cause is not a
 * database problem: a rejection without a message, a forward to oneself, a
 * document that another approver has already completed.
 *
 * @author Shaharan Hossain
 */
public class ApprovalException extends Exception {

	private static final long serialVersionUID = 1L;

	public ApprovalException(String message) {
		super(message);
	}

	public ApprovalException(String message, Throwable cause) {
		super(message, cause);
	}
}