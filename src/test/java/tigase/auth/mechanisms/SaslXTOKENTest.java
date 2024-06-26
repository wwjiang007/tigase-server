/*
 * Tigase XMPP Server - The instant messaging server
 * Copyright (C) 2004 Tigase, Inc. (office@tigase.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 */
package tigase.auth.mechanisms;

import junit.framework.TestCase;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import tigase.TestLogger;
import tigase.auth.callbacks.AuthorizationIdCallback;
import tigase.auth.callbacks.ReplaceServerKeyCallback;
import tigase.auth.callbacks.ServerKeyCallback;
import tigase.auth.callbacks.SharedSecretKeyCallback;
import tigase.util.Base64;
import tigase.xmpp.jid.BareJID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.SaslException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

@RunWith(Parameterized.class)
public class SaslXTOKENTest extends TestCase {

	private static final Logger log = TestLogger.getLogger(SaslSCRAMTest.class);
	private byte[] serverKey;
	private byte[] newServerKey;
	private final SecureRandom random = new SecureRandom();
	private SaslXTOKEN sasl;
	private final byte[] sharedKey;

	private final boolean printTestVectors = false;

	public SaslXTOKENTest(byte[] sharedKey) {
		this.sharedKey = sharedKey;
	}

	@Parameterized.Parameters
	public static Collection<Object[]> data() {
		byte[] sharedKey = new byte[32];
		SecureRandom random = new SecureRandom();
		random.nextBytes(sharedKey);
		return Arrays.asList(new Object[][] {
				{ null },
				{ sharedKey }
		});
	}

	@Override
	@Before
	public void setUp() {
		serverKey = new byte[32];
		random.nextBytes(serverKey);
		Map<? super String, ?> props = new HashMap<String, Object>();
		CallbackHandler callbackHandler = new CallbackHandler() {

			private String username;

			@Override
			public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
				for (Callback callback : callbacks) {
					if (callback instanceof NameCallback) {
						BareJID jid = BareJID.bareJIDInstanceNS(((NameCallback) callback).getDefaultName());
						if (jid.getLocalpart() == null || !"domain.com".equalsIgnoreCase(jid.getDomain())) {
							jid = BareJID.bareJIDInstanceNS(((NameCallback) callback).getDefaultName(), "domain.com");
						}
						username = jid.toString();
						((NameCallback) callback).setName(username);
					} else if (callback instanceof ServerKeyCallback) {
						((ServerKeyCallback) callback).setServerKey(serverKey);
					} else if (callback instanceof AuthorizationIdCallback) {
						// there is nothing to do..
					} else if (callback instanceof AuthorizeCallback) {
						boolean a = ((AuthorizeCallback) callback).getAuthorizationID()
								.equals(((AuthorizeCallback) callback).getAuthenticationID());
						((AuthorizeCallback) callback).setAuthorized(a);
						if (a) {
							((AuthorizeCallback) callback).setAuthorizedID(
									((AuthorizeCallback) callback).getAuthorizationID());
						}
					} else if (callback instanceof ReplaceServerKeyCallback) {
						((ReplaceServerKeyCallback) callback).setNewServerKey(newServerKey);
					} else if (callback instanceof SharedSecretKeyCallback) {
						((SharedSecretKeyCallback) callback).setSecret(sharedKey);
					} else {
						throw new UnsupportedCallbackException(callback);
					}
				}
			}
		};
		sasl = new SaslXTOKEN(props, callbackHandler);
	}

	@Test
	public void testEmptyToken() {
		try {
			sasl.evaluateResponse(new byte[0]);
			fail("Exception must be thrown");
		} catch (SaslException e) {
			// should be thrown
		}
		assertFalse(sasl.isComplete());
	}

	@Test
	public void testValidToken() throws InvalidKeyException, NoSuchAlgorithmException {
		newServerKey = null;
		byte[] data = new byte[32];
		random.nextBytes(data);

		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(serverKey, "SHA-256"));
		mac.update(data);
		if (sharedKey != null) {
			mac.update(sharedKey);
		}
		byte[] token = mac.doFinal();
		
		try {
			byte[] jid = "test@domain.com".getBytes(StandardCharsets.UTF_8);
			byte[] response = new byte[data.length + 1 + token.length + 1 + jid.length];
			System.arraycopy(data, 0, response,0 , 32);
			System.arraycopy(token, 0, response,33 , 32);
			System.arraycopy(jid, 0, response,66 , jid.length);
			byte[] finish = sasl.evaluateResponse(response);
			Assert.assertArrayEquals(jid,finish);
		} catch (SaslException e) {
			// shouldn't be thrown
			e.printStackTrace();
			fail("Exception must not be thrown");
		}
		assertTrue(sasl.isComplete());
	}

	@Test
	public void testValidTokenWithNewKey() throws InvalidKeyException, NoSuchAlgorithmException {
		newServerKey = new byte[32];
		random.nextBytes(newServerKey);
		byte[] data = new byte[32];
		random.nextBytes(data);

		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(serverKey, "SHA-256"));
		mac.update(data);
		if (sharedKey != null) {
			mac.update(sharedKey);
		}
		byte[] token = mac.doFinal();

		try {
			byte[] jid = "test@domain.com".getBytes(StandardCharsets.UTF_8);
			byte[] response = new byte[data.length + 1 + token.length + 1 + jid.length];
			System.arraycopy(data, 0, response,0 , 32);
			System.arraycopy(token, 0, response,33 , 32);
			System.arraycopy(jid, 0, response,66 , jid.length);
			byte[] finish = sasl.evaluateResponse(response);
			byte[] exp = new byte[jid.length + 1 + newServerKey.length];
			System.arraycopy(jid, 0, exp, 0, jid.length);
			System.arraycopy(newServerKey, 0, exp, jid.length + 1, newServerKey.length);
			Assert.assertArrayEquals(exp,finish);
			print(jid, serverKey, response, finish, newServerKey);

			serverKey = newServerKey;
			newServerKey = null;
			mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(serverKey, "SHA-256"));
			mac.update(data);
			if (sharedKey != null) {
				mac.update(sharedKey);
			}
			token = mac.doFinal();
			
			response = new byte[data.length + 1 + token.length + 1 + jid.length];
			System.arraycopy(data, 0, response,0 , 32);
			System.arraycopy(token, 0, response,33 , 32);
			System.arraycopy(jid, 0, response,66 , jid.length);
			finish = sasl.evaluateResponse(response);
			Assert.assertArrayEquals(jid,finish);
			print(jid, serverKey, response, finish, newServerKey);
		} catch (SaslException e) {
			// shouldn't be thrown
			e.printStackTrace();
			fail("Exception must not be thrown");
		}
		assertTrue(sasl.isComplete());
	}

	private void print(byte[] jid, byte[] serverKey, byte[] clientSaslRequest, byte[] serverSaslResponse, byte[] newServerKey) {
		if (!printTestVectors) {
			return;
		}
		System.out.println("Authentication" + (newServerKey == null ? "" : " with key replace"));
		System.out.println("JID: " + new String(jid, StandardCharsets.UTF_8));
		System.out.println("Server key: " + Base64.encode(serverKey));
		byte[] data = new byte[serverKey.length + 1 + jid.length];
		System.arraycopy(serverKey, 0, data, 0, serverKey.length);
		System.arraycopy(jid, 0, data, serverKey.length + 1, jid.length);
		System.out.println("QR code: " + Base64.encode(data));
		System.out.println("SASL payload sent by client: " + Base64.encode(clientSaslRequest));
		System.out.println("SASL payload sent by server: " + Base64.encode(serverSaslResponse));
		if (newServerKey != null) {
			System.out.println("Server key (new): " + Base64.encode(newServerKey));
		}
	}
}
