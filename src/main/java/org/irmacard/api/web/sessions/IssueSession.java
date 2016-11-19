package org.irmacard.api.web.sessions;

import org.irmacard.api.common.issuing.IdentityProviderRequest;
import org.irmacard.api.common.issuing.IssuingRequest;
import org.irmacard.credentials.idemix.messages.IssueCommitmentMessage;

public class IssueSession extends IrmaSession<IdentityProviderRequest, IssuingRequest> {
	private IssueCommitmentMessage commitments;

	public IssueSession() {
		super();
	}

	public IssueSession(IdentityProviderRequest ipRequest) {
		super(ipRequest);
	}

	public IssueCommitmentMessage getCommitments() {
		return commitments;
	}

	public void setCommitments(IssueCommitmentMessage commitments) {
		this.commitments = commitments;
	}
}
