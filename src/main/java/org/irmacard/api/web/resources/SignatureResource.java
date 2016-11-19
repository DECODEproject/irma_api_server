/*
 * SignatureResource.java
 *
 * Copyright (c) 2016, Koen van Ingen, Radboud University
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the IRMA project nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.irmacard.api.web.resources;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.SigningKeyResolverAdapter;
import org.irmacard.api.common.*;
import org.irmacard.api.common.exceptions.ApiError;
import org.irmacard.api.common.exceptions.ApiException;
import org.irmacard.api.common.signatures.SignatureClientRequest;
import org.irmacard.api.common.signatures.SignatureProofRequest;
import org.irmacard.api.common.signatures.SignatureProofResult;
import org.irmacard.api.web.ApiConfiguration;
import org.irmacard.api.web.sessions.IrmaSession.Status;
import org.irmacard.api.web.sessions.Sessions;
import org.irmacard.api.web.sessions.SignatureSession;
import org.irmacard.credentials.idemix.proofs.ProofList;
import org.irmacard.credentials.info.AttributeIdentifier;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.credentials.info.KeyException;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.security.Key;

// TODO: make generic and merge with VerificationResource into BaseResource or something like that?

@Path("signature")
public class SignatureResource {
	private Sessions<SignatureSession> sessions = Sessions.getSignatureSessions();

	private static final int DEFAULT_TOKEN_VALIDITY = 60 * 60; // 1 hour

	@Inject
	public SignatureResource() {}

	@POST
	@Consumes({MediaType.TEXT_PLAIN,MediaType.APPLICATION_JSON})
	@Produces(MediaType.APPLICATION_JSON)
	public ClientQr newSession(String jwt) {
		if (ApiConfiguration.getInstance().isHotReloadEnabled())
			ApiConfiguration.load();

		JwtParser<SignatureClientRequest> parser = new JwtParser<>(SignatureClientRequest.class,
				ApiConfiguration.getInstance().allowUnsignedSignatureRequests(),
				ApiConfiguration.getInstance().getMaxJwtAge());

		parser.setKeyResolver(new SigningKeyResolverAdapter() {
			@Override public Key resolveSigningKey(JwsHeader header, Claims claims) {
				String keyId = (String) header.get("kid");
				if (keyId == null)
					keyId = claims.getIssuer();
				return ApiConfiguration.getInstance().getClientPublicKey("sigclients", keyId);
			}
		});

		SignatureClientRequest request = parser.parseJwt(jwt).getPayload();
		return create(request, parser.getJwtIssuer(), jwt);
	}

	private ClientQr create(SignatureClientRequest clientRequest, String verifier, String jwt) {
		SignatureProofRequest request = clientRequest.getRequest();
		if (request == null || request.getContent() == null ||
				request.getContent().size() == 0 || request.getMessage() == null)
			throw new ApiException(ApiError.MALFORMED_SIGNATURE_REQUEST);

		// Check if the requested attributes match the DescriptionStore
		if (!request.attributesMatchStore())
			throw new ApiException(ApiError.ATTRIBUTES_WRONG);

		// Check if this client is authorized to verify these attributes
		for (AttributeDisjunction disjunction : request.getContent())
			for (AttributeIdentifier identifier : disjunction)
				if (!ApiConfiguration.getInstance().canRequestSignatureWithAttribute(verifier, identifier))
					throw new ApiException(ApiError.UNAUTHORIZED, identifier.toString());

		if (clientRequest.getValidity() == 0)
			clientRequest.setValidity(DEFAULT_TOKEN_VALIDITY);
		if (clientRequest.getTimeout() == 0)
			clientRequest.setTimeout(ApiConfiguration.getInstance().getTokenGetTimeout());

		request.setNonceAndContext();

		SignatureSession session = new SignatureSession(clientRequest);
		session.setJwt(jwt);
		String token = session.getSessionToken();
		sessions.addSession(session);

		System.out.println("Received session, token: " + token);
		System.out.println(request.toString());

		return new ClientQr("2.0", "2.1", token);
	}

	@GET
	@Path("/{sessiontoken}")
	@Produces(MediaType.APPLICATION_JSON)
	public SignatureProofRequest get(@PathParam("sessiontoken") String sessiontoken) {
		System.out.println("Received get, token: " + sessiontoken);
		SignatureSession session = sessions.getNonNullSession(sessiontoken);
		session.setStatusConnected();

		return session.getRequest();
	}

	@GET
	@Path("/{sessiontoken}/jwt")
	@Produces(MediaType.APPLICATION_JSON)
	public JwtSessionRequest getJwt(@PathParam("sessiontoken") String sessiontoken) {
		System.out.println("Received get, token: " + sessiontoken);
		SignatureSession session = sessions.getNonNullSession(sessiontoken);
		session.setStatusConnected();

		SignatureProofRequest request = session.getRequest();

		return new JwtSessionRequest(session.getJwt(), request.getSignatureNonce(), request.getContext());
	}

	@GET
	@Path("/{sessiontoken}/status")
	@Produces(MediaType.APPLICATION_JSON)
	public Status getStatus(
			@PathParam("sessiontoken") String sessiontoken) {
		SignatureSession session = sessions.getNonNullSession(sessiontoken);
		Status status = session.getStatus();

		// Remove the session if this session is cancelled
		if (status == Status.CANCELLED) {
			session.close();
		}

		return status;
	}

	@POST
	@Path("/{sessiontoken}/proofs")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public SignatureProofResult.Status proofs(ProofList proofs, @PathParam("sessiontoken") String sessiontoken)
			throws InfoException {
		SignatureSession session = sessions.getNonNullSession(sessiontoken);

		SignatureProofResult result;
		try {
			proofs.populatePublicKeyArray();
			result = session.getRequest().verify(proofs);
		} catch (Exception e) {
			// Everything in the verification has to be exactly right; if not, we don't accept the proofs as valid
			e.printStackTrace();
			result = new SignatureProofResult();
			result.setStatus(SignatureProofResult.Status.INVALID);
		}
		session.setResult(result);

		System.out.println("Received proofs, token: " + sessiontoken);

		return result.getStatus();
	}

	@GET
	@Path("/{sessiontoken}/getunsignedproof")
	@Produces(MediaType.APPLICATION_JSON)
	public SignatureProofResult getproof(@PathParam("sessiontoken") String sessiontoken) {
		SignatureSession session = sessions.getNonNullSession(sessiontoken);
		SignatureProofResult result = session.getResult();

		if (result == null) {
			result = new SignatureProofResult();
			result.setStatus(SignatureProofResult.Status.WAITING);
		} else {
			session.close();
		}

		result.setServiceProviderData(session.getClientRequest().getData());
		return result;
	}

	/**
	 * Checks if an IRMA signature if valid, can be used by the SP to check a certain signature
	 * TODO: this is unsigned yet, how are we going to sign this?
	 * @param result
	 * @return
	 */
	@POST
	@Path("/checksignature")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public SignatureProofResult.Status checkSignature(SignatureProofResult result) {
		try {
			AttributeBasedSignature signature = result.getSignature();
			if (result.getMessageType() != SignatureProofRequest.MessageType.STRING || result.getMessage() == null
					|| signature == null || signature.getNonce() == null || signature.getContext() == null) {
				System.out.println("Error in signature verification request");
				return SignatureProofResult.Status.INVALID;
			}

			return result.getSignature().verify(result.getMessage()).getStatus();
		} catch (ClassCastException | InfoException | KeyException e ) {
			System.out.println("Error verifying proof: ");
			e.printStackTrace();;
			return SignatureProofResult.Status.INVALID;
		}
	}

	@DELETE
	@Path("/{sessiontoken}")
	public void delete(@PathParam("sessiontoken") String sessiontoken) {
		SignatureSession session = sessions.getNonNullSession(sessiontoken);

		System.out.println("Received delete, token: " + sessiontoken);
		if (session.getStatus() == Status.CONNECTED) {
			// We have connected clients, we need to inform listeners of cancel
			session.setStatusCancelled();

			// If status socket is still active then the update has been sent, so we
			// can remove the session immediately. Otherwise we wait until the
			// status has been polled.
			if (session.isStatusSocketConnected()) {
				session.close();
			}
		} else {
			// In all other cases INITIALIZED, CANCELLED, DONE all parties
			// are already informed, we can close the session
			session.close();
		}
	}
}